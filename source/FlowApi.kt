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
 * Represents a single instance of a process with its current state.
 * Each process instance has a unique ID and a current status.
 */
class ProcessInstance<T>(
    val processId: String,
    val data: T,
    var currentStatus: Status
) {
    /**
     * Timestamp when the process was created.
     */
    val createdAt: Long = System.currentTimeMillis()
    
    /**
     * Timestamp when the process was last updated.
     */
    var updatedAt: Long = createdAt
    
    /**
     * Optional map for storing additional metadata about the process.
     */
    val metadata: MutableMap<String, Any> = mutableMapOf()
    
    /**
     * Updates the current status of the process and the updated timestamp.
     */
    fun updateStatus(newStatus: Status) {
        currentStatus = newStatus
        updatedAt = System.currentTimeMillis()
    }
}

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
 * Interface for storing and retrieving process instances.
 * Implementations will provide concrete storage mechanisms (database, in-memory, etc.).
 */
interface ProcessStorage<T> {
    /**
     * Saves a process instance.
     */
    fun saveProcess(process: ProcessInstance<T>)
    
    /**
     * Retrieves a process instance by its ID.
     */
    fun getProcess(processId: String): ProcessInstance<T>?

}

/**
 * Simple in-memory implementation of ProcessStorage.
 * Useful for testing or simple applications.
 */
class InMemoryProcessStorage<T> : ProcessStorage<T> {
    private val processes = mutableMapOf<String, ProcessInstance<T>>()
    private val flowIdToProcessIds = mutableMapOf<String, MutableSet<String>>()
    private val statusToProcessIds = mutableMapOf<Status, MutableSet<String>>()
    
    override fun saveProcess(process: ProcessInstance<T>) {
        val oldProcess = processes[process.processId]
        if (oldProcess != null) {
            // If status changed, update the status index
            if (oldProcess.currentStatus != process.currentStatus) {
                statusToProcessIds[oldProcess.currentStatus]?.remove(process.processId)
                statusToProcessIds.getOrPut(process.currentStatus) { mutableSetOf() }.add(process.processId)
            }
        }
        processes[process.processId] = process
    }
    
    override fun getProcess(processId: String): ProcessInstance<T>? {
        return processes[processId]
    }
    
    /**
     * Associate a process with a flow ID.
     */
    fun associateProcessWithFlow(processId: String, flowId: String) {
        flowIdToProcessIds.getOrPut(flowId) { mutableSetOf() }.add(processId)
    }
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
    
    /**
     * Get the initial status of this flow.
     */
    fun getInitialStatus(): Status = initialStatus
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
class FlowEngine<T>(private val processStorage: ProcessStorage<T> = InMemoryProcessStorage()) {
    private val flows = mutableMapOf<String, FlowBuilder<T>>()
    private val idGenerator = IdGenerator()
    
    /**
     * Register a flow with the engine.
     */
    fun registerFlow(id: String, flow: FlowBuilder<T>) {
        flows[id] = flow
    }
    
    /**
     * Start a new process instance with the specified flow.
     * 
     * @param flowId The ID of the flow to start
     * @param data The data object for the process
     * @param processId Optional custom process ID (if not provided, one will be generated)
     * @return The created process instance
     */
    fun startProcess(flowId: String, data: T, processId: String? = null): ProcessInstance<T> {
        val flow = flows[flowId] ?: throw IllegalArgumentException("No flow registered with ID: $flowId")
        val actualProcessId = processId ?: idGenerator.nextId()
        
        val process = ProcessInstance(
            processId = actualProcessId,
            data = data,
            currentStatus = flow.getInitialStatus()
        )
        
        // Store the process
        processStorage.saveProcess(process)
        
        // If using InMemoryProcessStorage, associate the process with the flow
        if (processStorage is InMemoryProcessStorage) {
            processStorage.associateProcessWithFlow(actualProcessId, flowId)
        }
        
        return process
    }
    
    /**
     * Send an event to a specific process instance.
     * 
     * @param processId The ID of the process to send the event to
     * @param event The event to send
     * @return The updated process instance
     */
    fun sendEvent(processId: String, event: Event): ProcessInstance<T> {
        val process = processStorage.getProcess(processId)
            ?: throw IllegalArgumentException("No process found with ID: $processId")
        
        // In a real implementation, this would handle the event based on the flow definition
        // For now, we just return the process
        return process
    }
    
    /**
     * Get a process instance by its ID.
     */
    fun getProcess(processId: String): ProcessInstance<T>? {
        return processStorage.getProcess(processId)
    }
}

/**
 * Simple ID generator for process instances.
 */
class IdGenerator {
    private var counter = 0L
    
    /**
     * Generate a new unique ID.
     */
    fun nextId(): String {
        return "proc-${System.currentTimeMillis()}-${counter++}"
    }
}