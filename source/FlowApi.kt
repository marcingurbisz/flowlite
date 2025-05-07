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

    private val stages = mutableMapOf<Stage, StageDefinition<T>>()
    private var initialStage: Stage? = null

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
        if (stage in stages) {
            throw FlowDefinitionException("Stage $stage already defined")
        }
        val stageDefinition = StageDefinition<T>(stage, action).also { stages[stage] = it }
        return StageBuilder(this, stageDefinition)
    }
    
    /**
     * Internal method to ensure a stage exists, creating it if needed
     */
    //TODO: Check if really needed
    internal fun ensureStage(stage: Stage): StageDefinition<T> {
        return stages.getOrPut(stage) { StageDefinition<T>(stage) }
    }
    
    /**
     * Add a stage and its definition to the flow.
     * If the stage already exists, throw an exception.
     */
    internal fun addStage(stage: Stage, definition: StageDefinition<T>) {
        if (stage in stages) {
            throw FlowDefinitionException("Stage $stage already exists in the flow")
        }
        
        // Add the stage
        stages[stage] = definition
    }

    /**
     * Builds and returns an immutable flow.
     */
    fun build(): Flow<T> = Flow(initialStage!!, stages.toMap())
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
}

/**
 * Handler for conditional branching.
 */
class ConditionHandler<T : Any>(
    val predicate: (item: T) -> Boolean,
    val trueStage: Stage,
    val falseStage: Stage,
)

/**
 * Handler for events.
 */
class EventHandler<T : Any>(
    val event: Event,
    val targetStage: Stage,
    val action: ((item: T) -> T)? = null,
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
        return flowBuilder.stage(stage, action)
    }

    /**
     * Define a new stage without an action.
     */
    fun stage(stage: Stage): StageBuilder<T> {
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
        // Create and build both branch flows
        val trueFlow = FlowBuilder<T>().apply(onTrue).build()
        val falseFlow = FlowBuilder<T>().apply(onFalse).build()
        
        // Create and add condition handler to the current stage
        stageDefinition.conditionHandler = ConditionHandler(
            predicate, 
            trueFlow.initialStage, 
            falseFlow.initialStage
        )
        
        // Add all stages from both branches to the main flow
        trueFlow.stages.forEach { (stage, definition) -> flowBuilder.addStage(stage, definition) }
        falseFlow.stages.forEach { (stage, definition) -> flowBuilder.addStage(stage, definition) }

        return flowBuilder
    }

    /**
     * End the current stage definition and return to the flow builder.
     */
    fun end(): FlowBuilder<T> = flowBuilder

    /**
     * Join another stage in the flow.
     *
     * @param targetStage The stage to join
     * @return The parent FlowBuilder instance for method chaining
     */
    fun join(targetStage: Stage): FlowBuilder<T> {
        // TODO: Find stage and create it as a next stage for current stage
        return flowBuilder
    }
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
        // Create an event handler for this event
        val eventHandler = EventHandler(event, stage, action)
        
        // Register the event handler with the stage
        stageBuilder.stageDefinition.eventHandlers[event] = eventHandler
        
        // Create a new StageBuilder for the target stage to continue building the flow
        return stageBuilder.flowBuilder.stage(stage)
    }

    /**
     * Transition to a specific stage without performing any action.
     */
    fun stage(stage: Stage): StageBuilder<T> {
        // Create an event handler for this event without an action
        val eventHandler = EventHandler<T>(event, stage, null)
        
        // Register the event handler with the stage
        stageBuilder.stageDefinition.eventHandlers[event] = eventHandler
        
        // Create a new StageBuilder for the target stage to continue building the flow
        return stageBuilder.flowBuilder.stage(stage)
    }

    /**
     * Join another stage in the flow.
     */
    fun join(targetStage: Stage): StageBuilder<T> {
        // Create a placeholder stage definition if it doesn't exist yet
        stageBuilder.flowBuilder.ensureStage(targetStage)
        
        // Create an event handler that transitions to the target stage
        val eventHandler = EventHandler<T>(event, targetStage, null)
        
        // Register the event handler with the stage
        stageBuilder.stageDefinition.eventHandlers[event] = eventHandler
        
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
