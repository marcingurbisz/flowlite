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
        } else if (stageDefinition.action != null && action != stageDefinition.action) {
            //TODO find a better way to detect that stage was added by join before actual definition
            //maybe separate join stages list that is precessed at the end?
            throw FlowDefinitionException("Stage $stage already defined with a different action")
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
        //TODO: detect when we add already defined stage
        if (initialStage == null) {
            initialStage = stage
        }
        var stageDefinition = stages[stage]
        if (stageDefinition == null) {
            stageDefinition = StageDefinition(stage)
            stages[stage] = stageDefinition
        }
        return StageBuilder(this, stageDefinition)
    }
    
    /**
     * Internal method to check if a stage exists in the flow
     */
    //TODO: Check if really needed
    internal fun hasStage(stage: Stage): Boolean {
        return stages.containsKey(stage)
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
     * If the stage already exists, merge the definitions.
     * TODO: Merging definitions looks to me as not necessary
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
            } else if (definition.action != null && definition.action != existingDef.action) {
                throw FlowDefinitionException("Cannot add stage $stage with a different action than the one already defined")
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
            } else if (definition.conditionHandler != null && 
                      (definition.conditionHandler?.trueStage != existingDef.conditionHandler?.trueStage || 
                       definition.conditionHandler?.falseStage != existingDef.conditionHandler?.falseStage)) {
                throw FlowDefinitionException("Cannot add stage $stage with a different condition than the one already defined")
            }
            
            // Copy event handlers
            definition.eventHandlers.forEach { (event, handler) ->
                val existingHandler = existingDef.eventHandlers[event]
                if (existingHandler != null && existingHandler.targetStage != handler.targetStage) {
                    throw FlowDefinitionException("Cannot add event $event to stage $stage with a different target stage than the one already defined")
                }
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
        stageDefinition.conditionHandler = conditionHandler
        
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
