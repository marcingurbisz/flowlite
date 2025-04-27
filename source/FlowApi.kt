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
class FlowBuilder<T : Any>() {

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
        val stageDefinition = StageDefinition(stage, action)
        stages[stage] = stageDefinition
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
        val stageDefinition = StageDefinition<T>(stage)
        stages[stage] = stageDefinition
        return StageBuilder(this, stageDefinition)
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
    val action: ((item: T) -> T)? = null,
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
    private val flowBuilder: FlowBuilder<T>,
    private val stageDefinition: StageDefinition<T>,
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
    fun onEvent(event: Event): EventBuilder<T> = EventBuilder(this)

    /**
     * Conditional branching with trailing lambda syntax for the true branch. Enables a DSL-style syntax with the
     * onFalse infix function.
     *
     * @param predicate Function that evaluates the condition based on the process state
     * @param trueBranch Builder function with receiver to define the flow when the condition is true
     * @return A ConditionBuilder to be used with the onFalse infix function
     */
    fun condition(predicate: (item: T) -> Boolean, trueBranch: FlowBuilder<T>.() -> Unit): ConditionBuilder<T> =
        ConditionBuilder(flowBuilder, this, predicate, trueBranch)

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
    fun join(targetStage: Stage): FlowBuilder<T> = flowBuilder
}

/**
 * Builder for event-based transitions in a flow. This acts as a specialized builder for when an event is being handled.
 *
 * @param T the type of context object the flow operates on
 */
class EventBuilder<T : Any>(private val stageBuilder: StageBuilder<T>) {
    /** Define an action with a stage change for this event. */
    fun stage(stage: Stage, action: (item: T) -> T): StageBuilder<T> = stageBuilder

    /** Transition to a specific stage without performing any action. */
    fun stage(stage: Stage): StageBuilder<T> = stageBuilder

    /** Join another stage in the flow. */
    fun join(targetStage: Stage): StageBuilder<T> = stageBuilder
}

/**
 * Builder for the false branch of a condition. This class enables the DSL-style syntax with trailing lambdas and the
 * onFalse infix function.
 */
class ConditionBuilder<T : Any>(
    private val flowBuilder: FlowBuilder<T>,
    private val stageBuilder: StageBuilder<T>,
    private val predicate: (item: T) -> Boolean,
    private val trueBranch: FlowBuilder<T>.() -> Unit,
) {
    /**
     * Define the actions to take when the condition is false.
     *
     * @param falseBranch A function with receiver to define the flow when the condition is false
     * @return The parent FlowBuilder for method chaining
     */
    infix fun onFalse(falseBranch: FlowBuilder<T>.() -> Unit): StageBuilder<T> {
        // In a real implementation, we would apply the branches based on the predicate
        // For this stub, we just apply both to the flowBuilder
        return stageBuilder
    }
}

/** Engine for executing flows */
class FlowEngine() {
    /**
     * Register a flow with the engine.
     *
     * @param statePersister The persister for this specific flow type
     */
    fun <T : Any> registerFlow(
        flowId: String,
        stateClass: KClass<T>,
        flowBuilder: FlowBuilder<T>,
        statePersister: StatePersister<T>,
    ) {}

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
