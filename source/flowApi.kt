package io.flowlite.api

import java.util.UUID
import kotlin.reflect.KClass

/**
 * Represents a stage within a workflow. Implementations should be enums to provide a finite set of possible stages.
 */
interface Stage

/**
 * Represents an event that can trigger state transitions in a workflow. Implementations should be enums to provide a
 * finite set of possible events.
 */
interface Event

/**
 * Exception thrown when a duplicate stage or event is added to a flow.
 */
class FlowDefinitionException(message: String) : RuntimeException(message)

/**
 * Interface for persisting the state of a workflow instance.
 *
 * @param T The type of the state object.
 */
interface StatePersister<T : Any> {
    fun save(processId: String, state: T)

    fun load(processId: String): T?
}

data class Flow<T : Any>(
    val initialStage: Stage?,
    val initialCondition: ConditionHandler<T>?,
    val stages: Map<Stage, StageDefinition<T>>,
) {
    init {
        require((initialStage != null) xor (initialCondition != null)) {
            "Flow must have either an initial stage or an initial condition, but not both"
        }
    }
}

class FlowBuilder<T : Any> {

    internal val stages = mutableMapOf<Stage, StageDefinition<T>>()
    internal var initialStage: Stage? = null
    internal var initialCondition: ConditionHandler<T>? = null

    fun stage(stage: Stage, action: ((item: T) -> T)? = null): StageBuilder<T> {
        return internalStage(stage, action)
    }

    fun join(targetStage: Stage) { initialStage = targetStage }

    internal fun internalStage(stage: Stage, action: ((item: T) -> T)? = null): StageBuilder<T> {
        if (initialStage == null && initialCondition == null) {
            initialStage = stage
        }
        
        val stageDefinition = StageDefinition<T>(stage, action)
        addStage(stage, stageDefinition)
        return StageBuilder(this, stageDefinition)
    }
    
    fun condition(
        predicate: (item: T) -> Boolean,
        onTrue: FlowBuilder<T>.() -> Unit,
        onFalse: FlowBuilder<T>.() -> Unit,
        description: String
    ): FlowBuilder<T> {
        initialCondition = createConditionHandler(predicate, onTrue, onFalse, description)
        return this
    }

    internal fun addStage(stage: Stage, definition: StageDefinition<T>) {
        if (stage in stages) {
            throw FlowDefinitionException("Stage $stage already defined - each stage should be defined only once")
        }
        stages[stage] = definition
    }

    internal fun createConditionHandler(
        predicate: (item: T) -> Boolean,
        onTrue: FlowBuilder<T>.() -> Unit,
        onFalse: FlowBuilder<T>.() -> Unit,
        description: String
    ): ConditionHandler<T> {
        // Create both branch builders
        val trueBranch = FlowBuilder<T>().apply(onTrue)
        val falseBranch = FlowBuilder<T>().apply(onFalse)
        
        // Collect all stage definitions from both branches
        trueBranch.stages.forEach { (stage, definition) ->
            addStage(stage, definition)
        }
        falseBranch.stages.forEach { (stage, definition) ->
            addStage(stage, definition)
        }
        
        return ConditionHandler(
            predicate,
            trueBranch.initialStage,
            trueBranch.initialCondition,
            falseBranch.initialStage,
            falseBranch.initialCondition,
            description
        )
    }

    /**
     * Builds and returns an immutable flow.
     */
    fun build(): Flow<T> {
        return Flow(initialStage, initialCondition, stages.toMap())
    }

}

/**
 * Types of transitions between stages.
 */
enum class TransitionType {
    DIRECT,    // Direct stage-to-stage transition
    EVENT,     // Event-based transition
    CONDITION  // Conditional branching
}

data class StageDefinition<T : Any>(
    val stage: Stage,
    val action: ((item: T) -> T)? = null,
) {
    val eventHandlers = mutableMapOf<Event, EventHandler<T>>()
    var conditionHandler: ConditionHandler<T>? = null
    var nextStage: Stage? = null
    
    fun hasConflictingTransitions(newTransitionType: TransitionType): Boolean {
        return when (newTransitionType) {
            TransitionType.DIRECT -> eventHandlers.isNotEmpty() || conditionHandler != null
            TransitionType.EVENT -> nextStage != null || conditionHandler != null
            TransitionType.CONDITION -> nextStage != null || eventHandlers.isNotEmpty()
        }
    }

    fun getExistingTransitions(): String {
        val transitions = mutableListOf<String>()
        if (nextStage != null) transitions.add("nextStage")
        if (eventHandlers.isNotEmpty()) transitions.add("eventHandlers")
        if (conditionHandler != null) transitions.add("conditionHandler")
        return transitions.joinToString(", ")
    }
}

data class ConditionHandler<T : Any>(
    val predicate: (item: T) -> Boolean,
    val trueStage: Stage?,
    val trueCondition: ConditionHandler<T>?,
    val falseStage: Stage?,
    val falseCondition: ConditionHandler<T>?,
    val description: String,
)

data class EventHandler<T : Any>(
    val event: Event,
    val targetStage: Stage?,
    val targetCondition: ConditionHandler<T>?,
)

class StageBuilder<T : Any>(
    val flowBuilder: FlowBuilder<T>,
    val stageDefinition: StageDefinition<T>,
) {
    fun stage(stage: Stage, action: ((item: T) -> T)? = null): StageBuilder<T> {
        if (stageDefinition.hasConflictingTransitions(TransitionType.DIRECT)) {
            throw FlowDefinitionException("Stage ${stageDefinition.stage} already has transitions defined: ${stageDefinition.getExistingTransitions()}. Use only one of: stage(), onEvent(), or condition().")
        }
        stageDefinition.nextStage = stage
        return flowBuilder.internalStage(stage, action)
    }

    fun waitFor(event: Event, condition: ((item: T) -> Boolean)? = null): EventBuilder<T> {
        return EventBuilder(this, event)
    }

    fun condition(
        predicate: (item: T) -> Boolean,
        onTrue: FlowBuilder<T>.() -> Unit,
        onFalse: FlowBuilder<T>.() -> Unit,
        description: String
    ): FlowBuilder<T> {
        if (stageDefinition.hasConflictingTransitions(TransitionType.CONDITION)) {
            throw FlowDefinitionException("Stage ${stageDefinition.stage} already has transitions defined: ${stageDefinition.getExistingTransitions()}. Use only one of: stage(), onEvent(), or condition().")
        }
        stageDefinition.conditionHandler = flowBuilder.createConditionHandler(predicate, onTrue, onFalse, description)

        return flowBuilder
    }
    
    fun join(targetStage: Stage): FlowBuilder<T> {
        if (stageDefinition.hasConflictingTransitions(TransitionType.DIRECT)) {
            throw FlowDefinitionException("Stage ${stageDefinition.stage} already has transitions defined: ${stageDefinition.getExistingTransitions()}. Use only one of: stage(), onEvent(), or condition().")
        }
        stageDefinition.nextStage = targetStage
        return flowBuilder
    }

    fun end(): FlowBuilder<T> = flowBuilder
}

class EventBuilder<T : Any>(
    private val stageBuilder: StageBuilder<T>,
    private val event: Event
) {
    fun stage(stage: Stage, action: ((item: T) -> T)? = null): StageBuilder<T> {
        if (stageBuilder.stageDefinition.hasConflictingTransitions(TransitionType.EVENT)) {
            throw FlowDefinitionException("Stage ${stageBuilder.stageDefinition.stage} already has transitions defined: ${stageBuilder.stageDefinition.getExistingTransitions()}. Use only one of: stage(), onEvent(), or condition().")
        }

        val targetStageBuilder = stageBuilder.flowBuilder.internalStage(stage, action)
        stageBuilder.stageDefinition.eventHandlers[event] = EventHandler(event, stage, null)
        
        return targetStageBuilder
    }

    fun join(targetStage: Stage) {
        stageBuilder.stageDefinition.eventHandlers[event] = EventHandler(event, targetStage, null)
    }

    fun condition(
        predicate: (item: T) -> Boolean,
        onTrue: FlowBuilder<T>.() -> Unit,
        onFalse: FlowBuilder<T>.() -> Unit,
        description: String
    ): FlowBuilder<T> {
        stageBuilder.stageDefinition.eventHandlers[event] = EventHandler(event, null,
            stageBuilder.flowBuilder.createConditionHandler(predicate, onTrue, onFalse, description))
        return stageBuilder.flowBuilder
    }

}

class FlowEngine {
    private val flows = mutableMapOf<String, Any>()
    private val persisters = mutableMapOf<String, Any>()
    private val stateClasses = mutableMapOf<String, KClass<*>>()

    /**
     * Register a flow with the engine.
     *
     * @param statePersister The persister for this specific flow type
     */
    fun <T : Any> registerFlow(
        flowId: String,
        stateClass: KClass<T>,
        flow: Flow<T>,
        statePersister: StatePersister<T>,
    ) {
        flows[flowId] = flow
        persisters[flowId] = statePersister
        stateClasses[flowId] = stateClass
    }

    fun <T : Any> startProcess(flowId: String, initialState: T): String {
        val processId = UUID.randomUUID().toString()
        return processId
    }
}
