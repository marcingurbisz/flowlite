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
 * Action with stage change. Combines a business action with a resulting stage transition.
 *
 * @param T the type of context object the action operates on (must be immutable)
 */
class ActionWithStatus<T>(
    val action: (item: T) -> T, // Action now returns the modified state
    val resultStage: (item: T) -> Stage,
    val retry: RetryStrategy? = null,
) {
    /** Alternative constructor that uses a fixed result stage. */
    constructor(
        action: (item: T) -> T, // Action now returns the modified state
        resultStage: Stage,
        retry: RetryStrategy? = null,
    ) : this(action = action, resultStage = { resultStage }, retry = retry)
}

/** Retry configuration for actions. Using a value class for better performance as this is mainly a data carrier. */
@JvmInline
value class RetryStrategy(private val config: RetryConfig) {
    constructor(
        maxAttempts: Int,
        delayMs: Long = 0,
        exponentialBackoff: Boolean = false,
        retryOn: Set<KClass<out Throwable>> = setOf(Exception::class),
    ) : this(RetryConfig(maxAttempts, delayMs, exponentialBackoff, retryOn))

    val maxAttempts: Int
        get() = config.maxAttempts

    val delayMs: Long
        get() = config.delayMs

    val exponentialBackoff: Boolean
        get() = config.exponentialBackoff

    val retryOn: Set<KClass<out Throwable>>
        get() = config.retryOn
}

/** Data class to hold retry configuration properties */
data class RetryConfig(
    val maxAttempts: Int,
    val delayMs: Long = 0,
    val exponentialBackoff: Boolean = false,
    val retryOn: Set<KClass<out Throwable>> = setOf(Exception::class),
)

/**
 * Process definition for a workflow.
 *
 * @param T the type of context object the flow operates on
 */
data class ProcessDefinition<T : Any>(
    val id: String,
    val stateClass: KClass<*>, // Keep track of the class for potential serialization/reflection
    val transitions: Map<Stage, Map<Event?, ActionWithStatus<T>>>, // Simplified representation of transitions
    // Add other necessary properties like statePersister associated with this definition?
)

/**
 * Builder for defining a workflow.
 *
 * @param T the type of context object the flow operates on
 * @param startStage stage from which flow starts when new flow instance is created. May be null for subflows.
 */
class FlowBuilder<T : Any>(startStage: Stage?) {
    private val transitions = mutableMapOf<Stage, MutableMap<Event?, ActionWithStatus<T>>>()

    /** Define an action with a stage change. Using context receivers for better integration with the flow context. */
    fun doAction(action: (item: T) -> T, stage: Stage, retry: RetryStrategy? = null): FlowBuilder<T> = this

    /** Handle an event that can trigger this flow. */
    fun onEvent(event: Event): EventBuilder<T> = EventBuilder(this)

    /**
     * Join a flow segment that transitions to the specified stage. This allows reusing a flow segment by its target
     * stage without repeating the code.
     *
     * @param targetStage The stage to join from
     * @return This FlowBuilder instance for method chaining
     * @throws IllegalArgumentException if no action transitioning to the specified stage is found
     */
    fun joinActionWithStatus(targetStage: Stage): FlowBuilder<T> {
        // Find an existing transition to the specified stage
        val stageEntry = findActionForTargetStage(targetStage)

        if (stageEntry == null) {
            throw IllegalArgumentException("No action found that transitions to stage '$targetStage'")
        }

        // Apply the found action with stage to the current flow
        val (sourceStage, eventMap) = stageEntry
        val eventEntry = eventMap.entries.first()

        // Add the transition to the current flow
        // In a real implementation, we'd need to handle the entire subsequent flow
        // For now, we just add the direct transition
        transitions.getOrPut(sourceStage) { mutableMapOf() }[eventEntry.key] = eventEntry.value

        return this
    }

    /**
     * Find an action that transitions to the specified stage.
     *
     * @param targetStage The stage to find an action for
     * @return The source stage and its event map if found, null otherwise
     */
    private fun findActionForTargetStage(targetStage: Stage): Pair<Stage, Map<Event?, ActionWithStatus<T>>>? {
        for ((sourceStage, eventMap) in transitions) {
            for (actionWithStatus in eventMap.values) {
                // Check if this action transitions to the target stage
                // For simplicity, we're only checking fixed result stages
                if (
                    actionWithStatus.resultStage is Function0<*> &&
                        (actionWithStatus.resultStage as Function0<Stage>).invoke() == targetStage
                ) {
                    return Pair(sourceStage, eventMap)
                }
            }
        }
        return null
    }

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
    fun end(): FlowBuilder<T> = this

    /** Builds the transitions for the process definition. This is intended to be used internally by the FlowEngine. */
    fun buildTransitions(): Map<Stage, Map<Event?, ActionWithStatus<T>>> {
        // Return an immutable copy of the transitions
        return transitions.mapValues { it.value.toMap() }
    }
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
    fun doAction(action: (item: T) -> T, stage: Stage): FlowBuilder<T> = flowBuilder

    /**
     * Join a flow segment that transitions to the specified stage. This allows reusing flow segments by stage within
     * event handlers.
     *
     * @param targetStage The stage to join from
     * @return The parent FlowBuilder instance for method chaining
     */
    fun join(targetStage: Stage): FlowBuilder<T> {
        return flowBuilder.joinActionWithStatus(targetStage)
    }

    /** Transition to a specific stage without performing any action. */
    fun transitionTo(stage: Stage): FlowBuilder<T> = flowBuilder
}

/** Type-erased process definition wrapper to allow storing different process types in the same engine. */
class ProcessDefinitionWrapper(
    val id: String,
    val stateClass: KClass<*>,
    val statePersister: StatePersister<*>,
    private val processDefinition: Any, // The actual ProcessDefinition<T>
) {
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getTypedDefinition(): ProcessDefinition<T> = processDefinition as ProcessDefinition<T>
}

/** Engine for executing flows. This engine can manage processes of different types. */
class FlowEngine(private val processDefinitions: MutableMap<String, ProcessDefinitionWrapper> = mutableMapOf()) {
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
    ) {
        val processDefinition =
            ProcessDefinition(id = flowId, stateClass = stateClass, transitions = flowBuilder.buildTransitions())

        registerProcessDefinition(flowId, stateClass, processDefinition, statePersister)
    }

    private fun <T : Any> registerProcessDefinition(
        flowId: String,
        stateClass: KClass<T>,
        processDefinition: ProcessDefinition<T>,
        statePersister: StatePersister<T>,
    ) {
        if (processDefinitions.containsKey(flowId)) {
            throw IllegalArgumentException("Process definition with ID '$flowId' already registered.")
        }

        val wrapper =
            ProcessDefinitionWrapper(
                id = flowId,
                stateClass = stateClass,
                statePersister = statePersister,
                processDefinition = processDefinition,
            )

        processDefinitions[flowId] = wrapper
    }

    /**
     * Starts a new process instance.
     *
     * @param flowId The ID of the registered ProcessDefinition.
     * @param initialState The initial state object (must include initial stage).
     * @return The generated unique process instance ID.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> startProcess(flowId: String, initialState: T): String {
        val wrapper =
            processDefinitions[flowId] ?: throw IllegalArgumentException("No process definition found for ID '$flowId'")

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
     *
     * @param processId The ID of the process instance.
     * @param flowId The ID of the flow this process belongs to.
     * @param event The event to trigger.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> triggerEvent(processId: String, flowId: String, event: Event) {
        val wrapper =
            processDefinitions[flowId] ?: throw IllegalArgumentException("No process definition found for ID '$flowId'")

        val statePersister = wrapper.statePersister as StatePersister<T>
        val definition = wrapper.getTypedDefinition<T>()

        val currentState =
            statePersister.load(processId)
                ?: throw IllegalArgumentException(
                    "No process instance found for ID '$processId' or failed to load state."
                )

        // TODO: Determine current stage from the currentState object (needs reflection or an interface)
        val currentStage: Stage = TODO("Implement logic to get stage from currentState")

        // Find the action associated with the current stage and event
        val actionWithStatus =
            definition.transitions[currentStage]?.get(event)
                ?: throw IllegalStateException(
                    "No transition defined for stage '$currentStage' and event '$event' in flow '${definition.id}'"
                )

        // Execute the action
        println("Executing action for process '$processId' due to event '$event'...")
        val newState =
            try {
                // TODO: Implement retry logic using actionWithStatus.retry if present
                actionWithStatus.action(currentState)
            } catch (e: Throwable) {
                // TODO: Handle execution errors, potentially based on retry strategy
                println("Error executing action for process '$processId': ${e.message}")
                throw e // Re-throw or handle differently
            }

        // TODO: Update the stage in the newState object based on actionWithStatus.resultStage
        val finalStateWithNewStage = TODO("Implement logic to update stage in newState")

        // Persist the new state
        statePersister.save(processId, finalStateWithNewStage)
        println("Process '$processId' transitioned to new state: $finalStateWithNewStage")
    }
}
