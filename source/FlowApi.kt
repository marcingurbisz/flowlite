package io.flowlite.api

import kotlin.reflect.KClass
import java.util.UUID

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
 * Interface for persisting the state of a workflow instance.
 * @param T The type of the state object.
 */
interface StatePersister<T : Any> {
    fun save(processId: String, state: T)
    fun load(processId: String): T?
}

/**
 * Action with status change. Combines a business action with a resulting status transition.
 * @param T the type of context object the action operates on (must be immutable)
 */
class ActionWithStatus<T>(
    val action: (item: T) -> T, // Action now returns the modified state
    val resultStatus: (item: T) -> Status,
    val retry: RetryStrategy? = null
) {
    /**
     * Alternative constructor that uses a fixed result status.
     */
    constructor(
        action: (item: T) -> T, // Action now returns the modified state
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
 * Process definition for a workflow.
 * @param T the type of context object the flow operates on
 */
data class ProcessDefinition<T : Any>(
    val id: String,
    val stateClass: KClass<*>, // Keep track of the class for potential serialization/reflection
    val transitions: Map<Status, Map<Event?, ActionWithStatus<T>>> // Simplified representation of transitions
    // Add other necessary properties like statePersister associated with this definition?
)

/**
 * Builder for defining a workflow.
 * @param T the type of context object the flow operates on
 */
class FlowBuilder<T : Any> {
    private val transitions = mutableMapOf<Status, MutableMap<Event?, ActionWithStatus<T>>>()

    /**
     * Define an action with a status change.
     * Using context receivers for better integration with the flow context.
     */
    fun doAction(
        action: (item: T) -> T,
        status: Status,
        retry: RetryStrategy? = null
    ): FlowBuilder<T> = this

    /**
     * Handle an event that can trigger this flow.
     */
    fun onEvent(event: Event): EventBuilder<T> = EventBuilder(this)

    /**
     * Transition to a specific status without performing any action.
     */
    fun transitionTo(status: Status): FlowBuilder<T> = this

    /**
     * Join a flow segment that transitions to the specified status.
     * This allows reusing a flow segment by its target status without repeating the code.
     * 
     * @param targetStatus The status to join from
     * @return This FlowBuilder instance for method chaining
     * @throws IllegalArgumentException if no action transitioning to the specified status is found
     */
    fun joinActionWithStatus(targetStatus: Status): FlowBuilder<T> {
        // Find an existing transition to the specified status
        val statusEntry = findActionForTargetStatus(targetStatus)
        
        if (statusEntry == null) {
            throw IllegalArgumentException("No action found that transitions to status '$targetStatus'")
        }
        
        // Apply the found action with status to the current flow
        val (sourceStatus, eventMap) = statusEntry
        val eventEntry = eventMap.entries.first()
        
        // Add the transition to the current flow
        // In a real implementation, we'd need to handle the entire subsequent flow
        // For now, we just add the direct transition
        transitions.getOrPut(sourceStatus) { mutableMapOf() }[eventEntry.key] = eventEntry.value
        
        return this
    }
    
    /**
     * Find an action that transitions to the specified status.
     * @param targetStatus The status to find an action for
     * @return The source status and its event map if found, null otherwise
     */
    private fun findActionForTargetStatus(targetStatus: Status): Pair<Status, Map<Event?, ActionWithStatus<T>>>? {
        for ((sourceStatus, eventMap) in transitions) {
            for (actionWithStatus in eventMap.values) {
                // Check if this action transitions to the target status
                // For simplicity, we're only checking fixed result statuses
                if (actionWithStatus.resultStatus is Function0<*> && 
                    (actionWithStatus.resultStatus as Function0<Status>).invoke() == targetStatus) {
                    return Pair(sourceStatus, eventMap)
                }
            }
        }
        return null
    }

    /**
     * Conditional branching based on the state of the process.
     * @param predicate Function that evaluates the condition based on the process state
     * @param onTrue Builder function to define the flow when the condition is true
     * @param onFalse Optional builder function to define the flow when the condition is false
     * @return This FlowBuilder instance for method chaining
     */
    fun condition(
        predicate: (item: T) -> Boolean,
        onTrue: (FlowBuilder<T>) -> FlowBuilder<T>,
        onFalse: ((FlowBuilder<T>) -> FlowBuilder<T>)? = null
    ): FlowBuilder<T> = this

    /**
     * Use another flow as a subflow within this flow.
     */
    fun subFlow(flow: FlowBuilder<T>): FlowBuilder<T> = this

    /**
     * End the flow.
     */
    fun end(): FlowBuilder<T> = this

    /**
     * Builds the transitions for the process definition.
     * This is intended to be used internally by the FlowEngine.
     */
    fun buildTransitions(): Map<Status, Map<Event?, ActionWithStatus<T>>> {
        // Return an immutable copy of the transitions
        return transitions.mapValues { it.value.toMap() }
    }
}

/**
 * Builder for event-based transitions in a flow.
 * This acts as a specialized builder for when an event is being handled.
 * @param T the type of context object the flow operates on
 */
class EventBuilder<T : Any>(private val flowBuilder: FlowBuilder<T>) {
    /**
     * Define an action with a status change for this event.
     */
    fun doAction(
        action: (item: T) -> T,
        status: Status
    ): FlowBuilder<T> = flowBuilder
    
    /**
     * Join a flow segment that transitions to the specified status.
     * This allows reusing flow segments by status within event handlers.
     * 
     * @param targetStatus The status to join from
     * @return The parent FlowBuilder instance for method chaining
     */
    fun joinActionWithStatus(targetStatus: Status): FlowBuilder<T> {
        return flowBuilder.joinActionWithStatus(targetStatus)
    }

    /**
     * Use another flow as a subflow within this event handler.
     */
    fun subFlow(flow: FlowBuilder<T>): FlowBuilder<T> = flowBuilder

    /**
     * Transition to a specific status without performing any action.
     */
    fun transitionTo(status: Status): FlowBuilder<T> = flowBuilder
}

/**
 * Type-erased process definition wrapper to allow storing different process types
 * in the same engine.
 */
class ProcessDefinitionWrapper(
    val id: String,
    val stateClass: KClass<*>,
    val statePersister: StatePersister<*>,
    private val processDefinition: Any // The actual ProcessDefinition<T>
) {
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getTypedDefinition(): ProcessDefinition<T> = 
        processDefinition as ProcessDefinition<T>
}

/**
 * Engine for executing flows.
 * This engine can manage processes of different types.
 */
class FlowEngine(
    private val processDefinitions: MutableMap<String, ProcessDefinitionWrapper> = mutableMapOf()
) {
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
        statePersister: StatePersister<T>
    ) {
        val processDefinition = ProcessDefinition(
            id = flowId,
            stateClass = stateClass,
            transitions = flowBuilder.buildTransitions()
        )
        
        registerProcessDefinition(flowId, stateClass, processDefinition, statePersister)
    }

    private fun <T : Any> registerProcessDefinition(
        flowId: String,
        stateClass: KClass<T>,
        processDefinition: ProcessDefinition<T>,
        statePersister: StatePersister<T>
    ) {
        if (processDefinitions.containsKey(flowId)) {
            throw IllegalArgumentException("Process definition with ID '$flowId' already registered.")
        }
        
        val wrapper = ProcessDefinitionWrapper(
            id = flowId,
            stateClass = stateClass,
            statePersister = statePersister,
            processDefinition = processDefinition
        )
        
        processDefinitions[flowId] = wrapper
    }

    /**
     * Starts a new process instance.
     * @param flowId The ID of the registered ProcessDefinition.
     * @param initialState The initial state object (must include initial status).
     * @return The generated unique process instance ID.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> startProcess(flowId: String, initialState: T): String {
        val wrapper = processDefinitions[flowId]
            ?: throw IllegalArgumentException("No process definition found for ID '$flowId'")
        
        // Validate that the initialState is of the correct type
        if (initialState::class != wrapper.stateClass) {
            throw IllegalArgumentException(
                "Initial state type ${initialState::class} does not match the expected type ${wrapper.stateClass}"
            )
        }
        
        val statePersister = wrapper.statePersister as StatePersister<T>
        
        val processId = UUID.randomUUID().toString()
        statePersister.save(processId, initialState) // Persist initial state
        println("Started process '$processId' for flow '$flowId' with initial state: $initialState") // Add logging
        return processId
    }

    /**
     * Triggers an event for a specific process instance.
     * @param processId The ID of the process instance.
     * @param flowId The ID of the flow this process belongs to.
     * @param event The event to trigger.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> triggerEvent(processId: String, flowId: String, event: Event) {
        val wrapper = processDefinitions[flowId]
            ?: throw IllegalArgumentException("No process definition found for ID '$flowId'")
        
        val statePersister = wrapper.statePersister as StatePersister<T>
        val definition = wrapper.getTypedDefinition<T>()
        
        val currentState = statePersister.load(processId)
            ?: throw IllegalArgumentException("No process instance found for ID '$processId' or failed to load state.")
        
        // TODO: Determine current status from the currentState object (needs reflection or an interface)
        val currentStatus: Status = TODO("Implement logic to get status from currentState")
        
        // Find the action associated with the current status and event
        val actionWithStatus = definition.transitions[currentStatus]?.get(event)
            ?: throw IllegalStateException("No transition defined for status '$currentStatus' and event '$event' in flow '${definition.id}'")
        
        // Execute the action
        println("Executing action for process '$processId' due to event '$event'...")
        val newState = try {
            // TODO: Implement retry logic using actionWithStatus.retry if present
            actionWithStatus.action(currentState)
        } catch (e: Throwable) {
            // TODO: Handle execution errors, potentially based on retry strategy
            println("Error executing action for process '$processId': ${e.message}")
            throw e // Re-throw or handle differently
        }
        
        // TODO: Update the status in the newState object based on actionWithStatus.resultStatus
        val finalStateWithNewStatus = TODO("Implement logic to update status in newState")
        
        // Persist the new state
        statePersister.save(processId, finalStateWithNewStatus)
        println("Process '$processId' transitioned to new state: $finalStateWithNewStatus")
    }
}