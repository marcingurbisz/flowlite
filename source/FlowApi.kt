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
     * Defines a stage with an associated action.
     *
     * @param stage The stage to define
     * @param action The action to execute when entering this stage
     * @return A StageBuilder for continuing the flow definition from this stage
     */
    fun stage(stage: Stage, action: (item: T) -> T): StageBuilder<T> {
        if (initialStage == null) {
            initialStage = stage
        }
        var stageDefinition = stages[stage]
        if (stageDefinition == null) {
            stageDefinition = StageDefinition(stage, action)
            stages[stage] = stageDefinition
        }
        return StageBuilder(this, stageDefinition)
    }

    /**
     * Defines a stage without an associated action.
     *
     * @param stage The stage to define
     * @return A StageBuilder for continuing the flow definition from this stage
     */
    fun stage(stage: Stage): StageBuilder<T> {
        if (initialStage == null) {
            initialStage = stage
        }
        var stageDefinition = stages[stage]
        if (stageDefinition == null) {
            stageDefinition = StageDefinition<T>(stage)
            stages[stage] = stageDefinition
        }
        return StageBuilder(this, stageDefinition)
    }
    
    /**
     * Add a stage and its definition to the flow.
     * If the stage already exists, merge the definitions.
     */
    internal fun addStage(stage: Stage, definition: StageDefinition<T>) {
        val existingDef = stages[stage]
        if (existingDef == null) {
            // If this stage doesn't exist yet, add it
            stages[stage] = definition
            
            // Also add any event target stages that might be referenced
            definition.eventHandlers.forEach { (_, handler) ->
                if (!stages.containsKey(handler.targetStage)) {
                    // Create a placeholder definition for the target stage
                    val targetDef = StageDefinition<T>(handler.targetStage)
                    stages[handler.targetStage] = targetDef
                }
            }
        } else {
            // If the stage already exists, merge the definitions
            // Copy the action if not already set
            if (existingDef.action == null) {
                existingDef.action = definition.action
            }
            
            // Copy the condition handler if not already set
            if (existingDef.conditionHandler == null) {
                existingDef.conditionHandler = definition.conditionHandler
                
                // Make sure both branch target stages exist
                definition.conditionHandler?.let { handler ->
                    if (!stages.containsKey(handler.trueStage)) {
                        stages[handler.trueStage] = StageDefinition<T>(handler.trueStage)
                    }
                    if (!stages.containsKey(handler.falseStage)) {
                        stages[handler.falseStage] = StageDefinition<T>(handler.falseStage)
                    }
                }
            }
            
            // Copy event handlers
            definition.eventHandlers.forEach { (event, handler) ->
                existingDef.eventHandlers.putIfAbsent(event, handler)
                
                // Make sure the target stage exists
                if (!stages.containsKey(handler.targetStage)) {
                    stages[handler.targetStage] = StageDefinition<T>(handler.targetStage)
                }
            }
        }
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
    val prevStageDefinition: StageDefinition<T>,
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
        // Create a flow builder for the true branch
        val trueBuilder = FlowBuilder<T>()
        // Apply the true branch definition
        trueBuilder.onTrue()
        
        // Create a flow builder for the false branch
        val falseBuilder = FlowBuilder<T>()
        // Apply the false branch definition
        falseBuilder.onFalse()
        
        // Build both flows to get their structures
        val trueFlow = trueBuilder.build()
        val falseFlow = falseBuilder.build()
        
        // Get the initial stages of both branches
        val trueStage = trueFlow.initialStage
        val falseStage = falseFlow.initialStage
        
        // Create a condition handler
        val conditionHandler = ConditionHandler(predicate, trueStage, falseStage)
        // Add the condition handler to the current stage
        prevStageDefinition.conditionHandler = conditionHandler
        
        // Add all stages from the true branch to the main flow
        trueFlow.stages.forEach { (stage, definition) ->
            flowBuilder.addStage(stage, definition)
        }
        
        // Add all stages from the false branch to the main flow
        falseFlow.stages.forEach { (stage, definition) ->
            flowBuilder.addStage(stage, definition)
        }
        
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
        // Implicitly create an event handler that transitions to the target stage
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
        stageBuilder.prevStageDefinition.eventHandlers[event] = eventHandler
        
        return stageBuilder
    }

    /**
     * Transition to a specific stage without performing any action.
     */
    fun stage(stage: Stage): StageBuilder<T> {
        // Create an event handler for this event without an action
        val eventHandler = EventHandler<T>(event, stage, null)
        
        // Register the event handler with the stage
        stageBuilder.prevStageDefinition.eventHandlers[event] = eventHandler
        
        return stageBuilder
    }

    /**
     * Join another stage in the flow.
     */
    fun join(targetStage: Stage): StageBuilder<T> {
        // Create an event handler that transitions to the target stage
        val eventHandler = EventHandler<T>(event, targetStage, null)
        
        // Register the event handler with the stage
        stageBuilder.prevStageDefinition.eventHandlers[event] = eventHandler
        
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
