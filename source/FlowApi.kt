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
 * Builder for defining a workflow.
 *
 * @param T the type of context object the flow operates on
 */
class FlowBuilder<T : Any>() {

    fun stage(stage: Stage, action: (item: T) -> T): FlowBuilder<T> = this

    fun stage(stage: Stage): FlowBuilder<T> = this


    /** Handle an event that can trigger this flow. */
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
        ConditionBuilder(this, predicate, trueBranch)

    /** End the flow. */
    fun end() {}

}

/**
 * Builder for the false branch of a condition. This class enables the DSL-style syntax with trailing lambdas and the
 * onFalse infix function.
 */
class ConditionBuilder<T : Any>(
    private val flowBuilder: FlowBuilder<T>,
    private val predicate: (item: T) -> Boolean,
    private val trueBranch: FlowBuilder<T>.() -> Unit,
) {
    /**
     * Define the actions to take when the condition is false.
     *
     * @param falseBranch A function with receiver to define the flow when the condition is false
     * @return The parent FlowBuilder for method chaining
     */
    infix fun onFalse(falseBranch: FlowBuilder<T>.() -> Unit): FlowBuilder<T> {
        // In a real implementation, we would apply the branches based on the predicate
        // For this stub, we just apply both to the flowBuilder
        return flowBuilder
    }
}

/**
 * Builder for event-based transitions in a flow. This acts as a specialized builder for when an event is being handled.
 *
 * @param T the type of context object the flow operates on
 */
class EventBuilder<T : Any>(private val flowBuilder: FlowBuilder<T>) {
    /** Define an action with a stage change for this event. */
    fun stage(stage: Stage, action: (item: T) -> T): FlowBuilder<T> = flowBuilder

    /**
     * Join a flow segment that transitions to the specified stage. This allows reusing flow segments by stage within
     * event handlers.
     *
     * @param targetStage The stage to join from
     * @return The parent FlowBuilder instance for method chaining
     */
    fun join(targetStage: Stage) {}

    /** Transition to a specific stage without performing any action. */
    fun stage(stage: Stage): FlowBuilder<T> = flowBuilder
}

/** Engine for executing flows */
class FlowEngine() {
    /**
     * Register a flow with the engine.
     *
     * @param flowId The unique identifier for this flow
     * @param stateClass The class of the state object
     * @param flowBuilder The flow builder containing the flow definition
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
