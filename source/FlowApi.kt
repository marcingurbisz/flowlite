package io.flowlite.api

import kotlin.reflect.KClass

/**
 * Represents a status within a workflow.
 * Implementations should be enums to provide a finite set of possible statuses.
 */
interface Status

/**
 * Represents an event that can trigger state transitions in a workflow.
 * Implementations should be enums to provide a finite set of possible events.
 */
interface Event

/**
 * Action with status change. Combines a business action with a resulting status transition.
 * @param T the type of context object the action operates on
 */
class ActionWithStatus<T>(
    val action: (item: T) -> Unit,
    val resultStatus: (item: T) -> Status,
    val retry: RetryStrategy? = null
) {
    /**
     * Alternative constructor that uses a fixed result status.
     */
    constructor(
        action: (item: T) -> Unit,
        resultStatus: Status,
        retry: RetryStrategy? = null
    ) : this(
        action = action,
        resultStatus = { resultStatus },
        retry = retry
    )
}

/**
 * Retry configuration for actions.
 * Using a value class for better performance as this is mainly a data carrier.
 */
@JvmInline
value class RetryStrategy(private val config: RetryConfig) {
    constructor(
        maxAttempts: Int,
        delayMs: Long = 0,
        exponentialBackoff: Boolean = false,
        retryOn: Set<KClass<out Throwable>> = setOf(Exception::class)
    ) : this(RetryConfig(maxAttempts, delayMs, exponentialBackoff, retryOn))
    
    val maxAttempts: Int get() = config.maxAttempts
    val delayMs: Long get() = config.delayMs
    val exponentialBackoff: Boolean get() = config.exponentialBackoff
    val retryOn: Set<KClass<out Throwable>> get() = config.retryOn
}

/**
 * Data class to hold retry configuration properties
 */
data class RetryConfig(
    val maxAttempts: Int,
    val delayMs: Long = 0,
    val exponentialBackoff: Boolean = false,
    val retryOn: Set<KClass<out Throwable>> = setOf(Exception::class)
)

/**
 * Main class for defining workflows.
 * @param T the type of context object the flow operates on
 * @param initialStatus the starting status of the flow
 */
class FlowBuilder<T>(private val initialStatus: Status) {
    /**
     * Define an action with a status change.
     * Using context receivers for better integration with the flow context.
     */
    fun doAction(
        action: (item: T) -> Unit,
        resultStatus: Status
    ): FlowBuilder<T> = this
    
    /**
     * Overload to use a pre-defined ActionWithStatus.
     */
    fun doAction(actionWithStatus: ActionWithStatus<T>): FlowBuilder<T> = this
    
    /**
     * Define a conditional branch in the flow.
     * Use this to create conditional logic where different paths are taken based on the condition.
     * 
     * Example:
     * ```
     * flow.condition(
     *     predicate = { order -> order.type == OrderType.PREMIUM },
     *     onTrue = { it.doAction(...) },
     *     onFalse = { it.doAction(...) }
     * )
     * ```
     * 
     * @param predicate The condition to evaluate
     * @param onTrue Builder block for the true branch
     * @param onFalse Builder block for the false branch (optional)
     * @return This flow builder for method chaining
     */
    fun condition(
        predicate: (item: T) -> Boolean,
        onTrue: (FlowBuilder<T>) -> FlowBuilder<T>,
        onFalse: ((FlowBuilder<T>) -> FlowBuilder<T>)? = null
    ): FlowBuilder<T> {
        // Implementation: in a real implementation, this would store both branches
        // For this API definition, we just return this
        return this
    }
    
    /**
     * Handle an event that can trigger this flow.
     */
    fun onEvent(event: Event): EventBuilder<T> = EventBuilder(this)
    
    /**
     * Transition to a specific status without performing any action.
     */
    fun transitionTo(status: Status): FlowBuilder<T> = this
    
    /**
     * Use another flow as a subflow within this flow.
     */
    fun subFlow(flow: FlowBuilder<T>): FlowBuilder<T> = this
    
    /**
     * End the flow.
     */
    fun end(): FlowBuilder<T> = this
    
    /**
     * Jump to another flow.
     */
    fun goTo(flow: FlowBuilder<T>): FlowBuilder<T> = this
}

/**
 * Builder for handling events in a flow.
 */
class EventBuilder<T>(private val parent: FlowBuilder<T>) {
    /**
     * Define an action to perform when the event occurs.
     * Using context receivers for better integration with the flow context.
     */
    fun doAction(
        action: (item: T) -> Unit,
        resultStatus: Status
    ): FlowBuilder<T> = parent
    
    /**
     * Overload to use a pre-defined ActionWithStatus.
     */
    fun doAction(actionWithStatus: ActionWithStatus<T>): FlowBuilder<T> = parent
    
    /**
     * Use another flow as a subflow when the event occurs.
     */
    fun subFlow(flow: FlowBuilder<T>): FlowBuilder<T> = parent
    fun transitionTo(status: Status): FlowBuilder<T> = parent
}

/**
 * Engine for executing flows.
 * @param T the type of context object the flows operate on
 */
class FlowEngine<T> {
    /**
     * Register a flow with the engine.
     */
    fun registerFlow(id: String, flow: FlowBuilder<T>) {
        // Registration implementation
    }
}