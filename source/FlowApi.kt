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

/**
 * Represents an immutable flow definition.
 *
 * @param T the type of context object the flow operates on
 */
class Flow<T : Any>(
    val initialStage: Stage,
    val stages: Map<Stage, StageDefinition<T>>,
) {
    // Immutable container for flow definition
    // Additional metadata could be added here
}

/**
 * Builder for defining a workflow.
 *
 * @param T the type of context object the flow operates on
 */
class FlowBuilder<T : Any> {

    internal val stages = mutableMapOf<Stage, StageDefinition<T>>()
    internal val joinReferences = mutableListOf<JoinReference>()
    internal var initialStage: Stage? = null

    /**
     * Defines a stage with an optional associated action.
     *
     * @param stage The stage to define
     * @param action The action to execute when entering this stage (optional)
     * @return A StageBuilder for continuing the flow definition from this stage
     */
    fun stage(stage: Stage, action: ((item: T) -> T)? = null): StageBuilder<T> {
        if (initialStage == null) {
            initialStage = stage
        }
        val stageDefinition = StageDefinition<T>(stage, action)
        addStage(stage, stageDefinition)
        return StageBuilder(this, stageDefinition)
    }
    
    /**
     * Add a stage and its definition to the flow.
     * Throws exception if stage is already defined.
     */
    internal fun addStage(stage: Stage, definition: StageDefinition<T>) {
        if (stage in stages) {
            throw FlowDefinitionException("Stage $stage already defined - each stage should be defined only once")
        }
        stages[stage] = definition
    }
    
    /**
     * Add a join reference to be resolved during build()
     */
    internal fun addJoinReference(fromStage: Stage, event: Event, targetStage: Stage) {
        joinReferences.add(JoinReference(fromStage, event, targetStage))
    }

    /**
     * Builds and returns an immutable flow.
     */
    fun build(): Flow<T> {
        resolveJoinReferences(stages)
        return Flow(initialStage!!, stages.toMap())
    }

    
    /**
     * Resolve join references by adding event handlers to the appropriate stages.
     * Join references can point to stages defined in any branch of the flow.
     */
    private fun resolveJoinReferences(resolvedStages: Map<Stage, StageDefinition<T>>) {
        joinReferences.forEach { joinRef ->
            val fromStage = resolvedStages[joinRef.fromStage]
                ?: throw FlowDefinitionException("From stage ${joinRef.fromStage} not found when resolving join reference")
            
            // Target stage must exist somewhere in the resolved stages
            val targetStageExists = resolvedStages.containsKey(joinRef.targetStage)
            if (!targetStageExists) {
                throw FlowDefinitionException("Target stage ${joinRef.targetStage} not found when resolving join reference. Available stages: ${resolvedStages.keys.joinToString()}")
            }
            
            // Add event handler to the from stage
            if (joinRef.event in fromStage.eventHandlers) {
                throw FlowDefinitionException("Duplicate event handler for ${joinRef.event} in stage ${joinRef.fromStage}")
            }
            
            val targetStageDefinition = resolvedStages[joinRef.targetStage]!!
            fromStage.eventHandlers[joinRef.event] = EventHandler(joinRef.event, targetStageDefinition)
        }
    }
}

/**
 * Represents a join reference that needs to be resolved during build
 */
data class JoinReference(
    val fromStage: Stage,
    val event: Event,
    val targetStage: Stage
)

/**
 * Types of transitions between stages.
 */
enum class TransitionType {
    DIRECT,    // Direct stage-to-stage transition
    EVENT,     // Event-based transition
    CONDITION  // Conditional branching
}

/**
 * Represents a stage definition within a flow.
 */
class StageDefinition<T : Any>(
    val stage: Stage,
    var action: ((item: T) -> T)? = null,
) {
    val eventHandlers = mutableMapOf<Event, EventHandler<T>>()
    var conditionHandler: ConditionHandler<T>? = null
    var nextStage: Stage? = null
    
    /**
     * Check if this stage already has conflicting transition mechanisms.
     */
    fun hasConflictingTransitions(newTransitionType: TransitionType): Boolean {
        return when (newTransitionType) {
            TransitionType.DIRECT -> eventHandlers.isNotEmpty() || conditionHandler != null
            TransitionType.EVENT -> nextStage != null || conditionHandler != null
            TransitionType.CONDITION -> nextStage != null || eventHandlers.isNotEmpty()
        }
    }
    
    /**
     * Get a description of existing transitions for error messages.
     */
    fun getExistingTransitions(): String {
        val transitions = mutableListOf<String>()
        if (nextStage != null) transitions.add("nextStage")
        if (eventHandlers.isNotEmpty()) transitions.add("eventHandlers")
        if (conditionHandler != null) transitions.add("conditionHandler")
        return transitions.joinToString(", ")
    }
}

/**
 * Handler for conditional branching.
 */
class ConditionHandler<T : Any>(
    val predicate: (item: T) -> Boolean,
    val trueStage: Stage,
    val falseStage: Stage,
)

data class EventHandler<T : Any>(
    val event: Event,
    val targetStageDefinition: StageDefinition<T>,
)

/**
 * Builder for defining stages within a flow.
 */
class StageBuilder<T : Any>(
    val flowBuilder: FlowBuilder<T>,
    val stageDefinition: StageDefinition<T>,
) {
    /**
     * Define a new stage with an action.
     */
    fun stage(stage: Stage, action: (item: T) -> T): StageBuilder<T> {
        if (stageDefinition.hasConflictingTransitions(TransitionType.DIRECT)) {
            throw FlowDefinitionException("Stage ${stageDefinition.stage} already has transitions defined: ${stageDefinition.getExistingTransitions()}. Use only one of: stage(), onEvent(), or condition().")
        }
        stageDefinition.nextStage = stage
        return flowBuilder.stage(stage, action)
    }

    /**
     * Define a new stage without an action.
     */
    fun stage(stage: Stage): StageBuilder<T> {
        if (stageDefinition.hasConflictingTransitions(TransitionType.DIRECT)) {
            throw FlowDefinitionException("Stage ${stageDefinition.stage} already has transitions defined: ${stageDefinition.getExistingTransitions()}. Use only one of: stage(), onEvent(), or condition().")
        }
        stageDefinition.nextStage = stage
        return flowBuilder.stage(stage)
    }

    /**
     * Handle an event that can trigger this flow.
     */
    fun onEvent(event: Event): EventBuilder<T> = EventBuilder(this, event)

    /**
     * Conditional branching with both true and false branches defined directly.
     *
     * @param predicate Function that evaluates the condition based on the process state
     * @param onTrue Builder function to define the flow when the condition is true
     * @param onFalse Builder function to define the flow when the condition is false
     * @return The parent StageBuilder for method chaining
     */
    fun condition(
        predicate: (item: T) -> Boolean,
        onTrue: FlowBuilder<T>.() -> Unit,
        onFalse: FlowBuilder<T>.() -> Unit
    ): FlowBuilder<T> {
        if (stageDefinition.hasConflictingTransitions(TransitionType.CONDITION)) {
            throw FlowDefinitionException("Stage ${stageDefinition.stage} already has transitions defined: ${stageDefinition.getExistingTransitions()}. Use only one of: stage(), onEvent(), or condition().")
        }
        
        // Create both branch builders without building them yet
        val trueBranch = FlowBuilder<T>().apply(onTrue)
        val falseBranch = FlowBuilder<T>().apply(onFalse)
        
        // Create and add condition handler to the current stage
        stageDefinition.conditionHandler = ConditionHandler(
            predicate, 
            trueBranch.initialStage!!, 
            falseBranch.initialStage!!
        )
        
        // Collect all stage definitions from both branches (without resolving join references)
        trueBranch.stages.forEach { (stage, definition) ->
            flowBuilder.addStage(stage, definition)
        }
        falseBranch.stages.forEach { (stage, definition) ->
            flowBuilder.addStage(stage, definition)
        }
        
        // Collect join references from both branches
        trueBranch.joinReferences.forEach { joinRef ->
            flowBuilder.addJoinReference(joinRef.fromStage, joinRef.event, joinRef.targetStage)
        }
        falseBranch.joinReferences.forEach { joinRef ->
            flowBuilder.addJoinReference(joinRef.fromStage, joinRef.event, joinRef.targetStage)
        }

        return flowBuilder
    }

    /**
     * End the current stage definition and return to the flow builder.
     */
    fun end(): FlowBuilder<T> = flowBuilder
}

/**
 * Builder for event-based transitions in a flow.
 *
 * @param T the type of context object the flow operates on
 */
class EventBuilder<T : Any>(
    private val stageBuilder: StageBuilder<T>,
    private val event: Event
) {
    /**
     * Define an action with a stage change for this event.
     */
    fun stage(stage: Stage, action: (item: T) -> T): StageBuilder<T> {
        if (stageBuilder.stageDefinition.hasConflictingTransitions(TransitionType.EVENT)) {
            throw FlowDefinitionException("Stage ${stageBuilder.stageDefinition.stage} already has transitions defined: ${stageBuilder.stageDefinition.getExistingTransitions()}. Use only one of: stage(), onEvent(), or condition().")
        }
        
        // Create a new StageBuilder for the target stage first
        val targetStageBuilder = stageBuilder.flowBuilder.stage(stage, action)
        
        // Create an event handler for this event with the target stage definition
        val eventHandler = EventHandler(event, targetStageBuilder.stageDefinition)
        
        // Register the event handler with the current stage
        stageBuilder.stageDefinition.eventHandlers[event] = eventHandler
        
        return targetStageBuilder
    }

    /**
     * Transition to a specific stage without performing any action.
     */
    fun stage(stage: Stage): StageBuilder<T> {
        if (stageBuilder.stageDefinition.hasConflictingTransitions(TransitionType.EVENT)) {
            throw FlowDefinitionException("Stage ${stageBuilder.stageDefinition.stage} already has transitions defined: ${stageBuilder.stageDefinition.getExistingTransitions()}. Use only one of: stage(), onEvent(), or condition().")
        }
        
        // Create a new StageBuilder for the target stage first
        val targetStageBuilder = stageBuilder.flowBuilder.stage(stage)
        
        // Create an event handler for this event with the target stage definition
        val eventHandler = EventHandler<T>(event, targetStageBuilder.stageDefinition)
        
        // Register the event handler with the current stage
        stageBuilder.stageDefinition.eventHandlers[event] = eventHandler
        
        return targetStageBuilder
    }

    /**
     * Join another stage in the flow.
     */
    fun join(targetStage: Stage): StageBuilder<T> {
        // Record this as a join reference to be resolved during build
        stageBuilder.flowBuilder.addJoinReference(
            stageBuilder.stageDefinition.stage,
            event,
            targetStage
        )

        //TODO: Return flow builder since it is not allowed to do anything with
        // stage already defined elsewhere
        return stageBuilder
    }
}

/**
 * Engine for executing flows
 */
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

    /**
     * Starts a new process instance.
     *
     * @param flowId The ID of the registered ProcessDefinition.
     * @param initialState The initial state object (must include initial stage).
     * @return The generated unique process instance ID.
     */
    fun <T : Any> startProcess(flowId: String, initialState: T): String {
        val processId = UUID.randomUUID().toString()
        return processId
    }
}
