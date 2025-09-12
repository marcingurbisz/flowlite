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
 * Runtime status of a single active stage for a flow instance.
 */
enum class StageStatus {
    PENDING,
    RUNNING,
    COMPLETED, // used only for terminal stages
    ERROR,
}

/**
 * Persisted view of a flow instance.
 */
data class ProcessData<T : Any>(
    val flowInstanceId: UUID,
    val state: T,
    val stage: Stage,
    val stageStatus: StageStatus,
)

/**
 * Interface for persisting the state of a workflow instance.
 *
 * @param T The type of the state object.
 */
interface StatePersister<T : Any> {
    /**
     * Create or update the domain row and engine columns atomically.
     * Return true on success, false on optimistic conflict (no write applied).
     */
    fun save(processData: ProcessData<T>): Boolean

    /** Load current process data or null if not found. */
    fun load(flowInstanceId: UUID): ProcessData<T>?
}

data class Flow<T : Any>(
    val initialStage: Stage?,
    val initialCondition: ConditionHandler<T>?,
    val stages: Map<Stage, StageDefinition<T>>,
) {
    init {
        require((initialStage != null) xor (initialCondition != null)) {
            "Flow must have either an initial stage or an initial condition, but not both"
        }
    }
}

class FlowBuilder<T : Any> {

    internal val stages = mutableMapOf<Stage, StageDefinition<T>>()
    internal var initialStage: Stage? = null
    internal var initialCondition: ConditionHandler<T>? = null

    fun stage(stage: Stage, action: ((item: T) -> T?)? = null): StageBuilder<T> {
        return internalStage(stage, action)
    }

    fun join(targetStage: Stage) { initialStage = targetStage }

    internal fun internalStage(stage: Stage, action: ((item: T) -> T?)? = null): StageBuilder<T> {
        if (initialStage == null && initialCondition == null) {
            initialStage = stage
        }
        
        val stageDefinition = StageDefinition<T>(stage, action)
        addStage(stage, stageDefinition)
        return StageBuilder(this, stageDefinition)
    }
    
    fun condition(
        predicate: (item: T) -> Boolean,
        onTrue: FlowBuilder<T>.() -> Unit,
        onFalse: FlowBuilder<T>.() -> Unit,
        description: String
    ): FlowBuilder<T> {
        initialCondition = createConditionHandler(predicate, onTrue, onFalse, description)
        return this
    }

    internal fun addStage(stage: Stage, definition: StageDefinition<T>) {
        if (stage in stages) {
            throw FlowDefinitionException("Stage $stage already defined - each stage should be defined only once")
        }
        stages[stage] = definition
    }

    internal fun createConditionHandler(
        predicate: (item: T) -> Boolean,
        onTrue: FlowBuilder<T>.() -> Unit,
        onFalse: FlowBuilder<T>.() -> Unit,
        description: String
    ): ConditionHandler<T> {
        // Create both branch builders
        val trueBranch = FlowBuilder<T>().apply(onTrue)
        val falseBranch = FlowBuilder<T>().apply(onFalse)
        
        // Collect all stage definitions from both branches
        trueBranch.stages.forEach { (stage, definition) ->
            addStage(stage, definition)
        }
        falseBranch.stages.forEach { (stage, definition) ->
            addStage(stage, definition)
        }
        
        return ConditionHandler(
            predicate,
            trueBranch.initialStage,
            trueBranch.initialCondition,
            falseBranch.initialStage,
            falseBranch.initialCondition,
            description
        )
    }

    /**
     * Builds and returns an immutable flow.
     */
    fun build(): Flow<T> {
        return Flow(initialStage, initialCondition, stages.toMap())
    }

}

/**
 * Types of transitions between stages.
 */
enum class TransitionType {
    DIRECT,    // Direct stage-to-stage transition
    EVENT,     // Event-based transition
    CONDITION  // Conditional branching
}

data class StageDefinition<T : Any>(
    val stage: Stage,
    val action: ((item: T) -> T?)? = null,
) {
    val eventHandlers = mutableMapOf<Event, EventHandler<T>>()
    var conditionHandler: ConditionHandler<T>? = null
    var nextStage: Stage? = null
    
    fun hasConflictingTransitions(newTransitionType: TransitionType): Boolean {
        return when (newTransitionType) {
            TransitionType.DIRECT -> eventHandlers.isNotEmpty() || conditionHandler != null
            TransitionType.EVENT -> nextStage != null || conditionHandler != null
            TransitionType.CONDITION -> nextStage != null || eventHandlers.isNotEmpty()
        }
    }

    fun getExistingTransitions(): String {
        val transitions = mutableListOf<String>()
        if (nextStage != null) transitions.add("nextStage")
        if (eventHandlers.isNotEmpty()) transitions.add("eventHandlers")
        if (conditionHandler != null) transitions.add("conditionHandler")
        return transitions.joinToString(", ")
    }
}

data class ConditionHandler<T : Any>(
    val predicate: (item: T) -> Boolean,
    val trueStage: Stage?,
    val trueCondition: ConditionHandler<T>?,
    val falseStage: Stage?,
    val falseCondition: ConditionHandler<T>?,
    val description: String,
)

data class EventHandler<T : Any>(
    val event: Event,
    val targetStage: Stage?,
    val targetCondition: ConditionHandler<T>?,
)

class StageBuilder<T : Any>(
    val flowBuilder: FlowBuilder<T>,
    val stageDefinition: StageDefinition<T>,
) {
    fun stage(stage: Stage, action: ((item: T) -> T?)? = null): StageBuilder<T> {
        if (stageDefinition.hasConflictingTransitions(TransitionType.DIRECT)) {
            throw FlowDefinitionException("Stage ${stageDefinition.stage} already has transitions defined: ${stageDefinition.getExistingTransitions()}. Use only one of: stage(), onEvent(), or condition().")
        }
        stageDefinition.nextStage = stage
        return flowBuilder.internalStage(stage, action)
    }

    fun waitFor(event: Event): EventBuilder<T> = EventBuilder(this, event)

    fun condition(
        predicate: (item: T) -> Boolean,
        onTrue: FlowBuilder<T>.() -> Unit,
        onFalse: FlowBuilder<T>.() -> Unit,
        description: String
    ): FlowBuilder<T> {
        if (stageDefinition.hasConflictingTransitions(TransitionType.CONDITION)) {
            throw FlowDefinitionException("Stage ${stageDefinition.stage} already has transitions defined: ${stageDefinition.getExistingTransitions()}. Use only one of: stage(), onEvent(), or condition().")
        }
        stageDefinition.conditionHandler = flowBuilder.createConditionHandler(predicate, onTrue, onFalse, description)

        return flowBuilder
    }
    
    fun join(targetStage: Stage): FlowBuilder<T> {
        if (stageDefinition.hasConflictingTransitions(TransitionType.DIRECT)) {
            throw FlowDefinitionException("Stage ${stageDefinition.stage} already has transitions defined: ${stageDefinition.getExistingTransitions()}. Use only one of: stage(), onEvent(), or condition().")
        }
        stageDefinition.nextStage = targetStage
        return flowBuilder
    }

    fun end(): FlowBuilder<T> = flowBuilder
}

class EventBuilder<T : Any>(
    private val stageBuilder: StageBuilder<T>,
    private val event: Event
) {
    fun stage(stage: Stage, action: ((item: T) -> T?)? = null): StageBuilder<T> {
        if (stageBuilder.stageDefinition.hasConflictingTransitions(TransitionType.EVENT)) {
            throw FlowDefinitionException("Stage ${stageBuilder.stageDefinition.stage} already has transitions defined: ${stageBuilder.stageDefinition.getExistingTransitions()}. Use only one of: stage(), onEvent(), or condition().")
        }

        val targetStageBuilder = stageBuilder.flowBuilder.internalStage(stage, action)
        stageBuilder.stageDefinition.eventHandlers[event] = EventHandler(event, stage, null)
        
        return targetStageBuilder
    }

    fun join(targetStage: Stage) {
        stageBuilder.stageDefinition.eventHandlers[event] = EventHandler(event, targetStage, null)
    }

    fun condition(
        predicate: (item: T) -> Boolean,
        onTrue: FlowBuilder<T>.() -> Unit,
        onFalse: FlowBuilder<T>.() -> Unit,
        description: String
    ): FlowBuilder<T> {
        stageBuilder.stageDefinition.eventHandlers[event] = EventHandler(event, null,
            stageBuilder.flowBuilder.createConditionHandler(predicate, onTrue, onFalse, description))
        return stageBuilder.flowBuilder
    }

}

class FlowEngine {
    private val flows = mutableMapOf<String, Flow<Any>>()
    private val persisters = mutableMapOf<String, StatePersister<Any>>()
    private val stateClasses = mutableMapOf<String, KClass<*>>()

    // Instance index: which flow a given instance belongs to
    private val instanceToFlow = mutableMapOf<UUID, String>()

    // Shared pending events store (no payload)
    private val pendingEvents = mutableMapOf<UUID, MutableList<Event>>()

    fun <T : Any> registerFlow(
        flowId: String,
        stateClass: KClass<T>,
        flow: Flow<T>,
        statePersister: StatePersister<T>,
    ) {
        @Suppress("UNCHECKED_CAST")
        val anyFlow = flow as Flow<Any>
        @Suppress("UNCHECKED_CAST")
        val anyPersister = statePersister as StatePersister<Any>

        flows[flowId] = anyFlow
        persisters[flowId] = anyPersister
        stateClasses[flowId] = stateClass
    }

    fun <T : Any> startProcess(flowId: String, initialState: T): UUID {
        val flowInstanceId = UUID.randomUUID()
        return startProcess(flowId, flowInstanceId, initialState)
    }

    fun <T : Any> startProcess(flowId: String, flowInstanceId: UUID, initialState: T): UUID {
        val flow = requireNotNull(flows[flowId]) { "Flow '$flowId' not registered" }
        val persister = requireNotNull(persisters[flowId]) { "Persister for flow '$flowId' not registered" }
        val initialStage = resolveInitialStage(flow as Flow<T>, initialState)
        val pd = ProcessData(
            flowInstanceId = flowInstanceId,
            state = initialState,
            stage = initialStage,
            stageStatus = StageStatus.PENDING,
        )
        persister.save(pd as ProcessData<Any>)
        instanceToFlow[flowInstanceId] = flowId
        // Enqueue initial tick (synchronous processing in MVP)
        processTick(flowId, flowInstanceId)
        return flowInstanceId
    }

    fun sendEvent(flowInstanceId: UUID, event: Event) {
        val queue = pendingEvents.getOrPut(flowInstanceId) { mutableListOf() }
        queue.add(event)
        // Try to consume with a tick
        val flowId = instanceToFlow[flowInstanceId] ?: return
        processTick(flowId, flowInstanceId)
    }

    fun retry(flowInstanceId: UUID) {
        val flowId = instanceToFlow[flowInstanceId] ?: return
        processTick(flowId, flowInstanceId)
    }

    fun getStatus(flowInstanceId: UUID): Pair<Stage, StageStatus>? {
        val flowId = instanceToFlow[flowInstanceId] ?: return null
        val persister = persisters[flowId] ?: return null
        val pd = persister.load(flowInstanceId) ?: return null
        return pd.stage to pd.stageStatus
    }

    // --- Internal processing ---

    private fun <T : Any> resolveInitialStage(flow: Flow<T>, state: T): Stage {
        flow.initialStage?.let { return it }
        val cond = requireNotNull(flow.initialCondition) { "Flow must have initial stage or condition" }
        return resolveConditionInitialStage(cond, state)
            ?: throw FlowDefinitionException("Initial condition did not resolve to a stage")
    }

    private fun <T : Any> resolveConditionInitialStage(condition: ConditionHandler<T>, state: T): Stage? {
        val branchTrue = condition.predicate(state)
        val stage = if (branchTrue) condition.trueStage else condition.falseStage
        val nested = if (branchTrue) condition.trueCondition else condition.falseCondition
        return stage ?: nested?.let { resolveConditionInitialStage(it, state) }
    }

    private fun processTick(flowId: String, flowInstanceId: UUID) {
        val flow = flows[flowId] ?: return
        val persister = persisters[flowId] ?: return
        val stateClass = stateClasses[flowId] ?: return

        @Suppress("UNCHECKED_CAST")
        processTickTyped(flow as Flow<Any>, persister as StatePersister<Any>, flowInstanceId)
    }

    private tailrec fun processTickTyped(flow: Flow<Any>, persister: StatePersister<Any>, flowInstanceId: UUID) {
        val pd = persister.load(flowInstanceId) ?: return

        when (pd.stageStatus) {
            StageStatus.ERROR -> return // wait for manual retry
            StageStatus.RUNNING -> return // action in progress (or crashed); noop on tick
            StageStatus.COMPLETED -> return // terminal
            StageStatus.PENDING -> {
                val def = flow.stages[pd.stage]
                    ?: throw FlowDefinitionException("No definition for stage ${pd.stage}")

                // First, if waiting-for-events, try consume
                if (def.eventHandlers.isNotEmpty()) {
                    val consumed = tryConsumeEventAndAdvance(def, flow, pd, persister, flowInstanceId)
                    if (consumed) {
                        // After advancing due to event, continue processing
                        processTickTyped(flow, persister, flowInstanceId)
                        return
                    }
                    // No event: if there is an action and we haven't executed it yet for this stage, run it once
                    if (def.action != null) {
                        if (!runActionAndPersistNext(def, flow, pd, persister, flowInstanceId)) return
                        // Do NOT recurse here: remaining in same stage waiting for events. Further progress will be
                        // triggered by a future tick when an external event is sent.
                    }
                    return
                }

                // If there is a condition transition
                def.conditionHandler?.let { cond ->
                    // Optional action before branching
                    if (def.action != null) {
                        if (!runActionAndPersistNext(def, flow, pd, persister, flowInstanceId, branchOnly = true)) return
                    }
                    val target = resolveConditionInitialStage(cond as ConditionHandler<Any>, pd.state)
                        ?: throw FlowDefinitionException("Condition did not resolve to a stage from ${pd.stage}")
                    val next = pd.copy(stage = target, stageStatus = StageStatus.PENDING)
                    if (!persister.save(next)) return
                    processTickTyped(flow, persister, flowInstanceId)
                    return
                }

                // Direct next stage
                def.nextStage?.let { ns ->
                    // Optional action before moving
                    if (def.action != null) {
                        if (!runActionAndPersistNext(def, flow, pd, persister, flowInstanceId, directNext = ns)) return
                        processTickTyped(flow, persister, flowInstanceId)
                        return
                    }
                    val next = pd.copy(stage = ns, stageStatus = StageStatus.PENDING)
                    if (!persister.save(next)) return
                    processTickTyped(flow, persister, flowInstanceId)
                    return
                }

                // No transitions -> terminal stage
                if (def.action != null) {
                    // Run the terminal action then mark completed
                    if (!runActionAndPersistNext(def, flow, pd, persister, flowInstanceId, markTerminal = true)) return
                    return
                } else {
                    val completed = pd.copy(stageStatus = StageStatus.COMPLETED)
                    persister.save(completed)
                    return
                }
            }
        }
    }

    private fun tryConsumeEventAndAdvance(
        def: StageDefinition<Any>,
        flow: Flow<Any>,
        pd: ProcessData<Any>,
        persister: StatePersister<Any>,
        flowInstanceId: UUID,
    ): Boolean {
        val queue = pendingEvents[flowInstanceId] ?: return false
        val matching = def.eventHandlers.keys.firstOrNull { e -> queue.contains(e) } ?: return false
        // consume first occurrence
        queue.remove(matching)
        val handler = def.eventHandlers[matching] ?: return false
        val targetStage = handler.targetStage ?: handler.targetCondition?.let { ch ->
            resolveConditionInitialStage(ch as ConditionHandler<Any>, pd.state)
        }
        ?: throw FlowDefinitionException("Event handler did not resolve to a stage from ${pd.stage}")
        val next = pd.copy(stage = targetStage, stageStatus = StageStatus.PENDING)
        return persister.save(next)
    }

    /**
     * Execute action with RUNNING flip and persist the outcome.
     */
    private fun runActionAndPersistNext(
        def: StageDefinition<Any>,
        flow: Flow<Any>,
        pd: ProcessData<Any>,
        persister: StatePersister<Any>,
        flowInstanceId: UUID,
        branchOnly: Boolean = false,
        directNext: Stage? = null,
        markTerminal: Boolean = false,
    ): Boolean {
        // Flip to RUNNING
        val running = pd.copy(stageStatus = StageStatus.RUNNING)
        if (!persister.save(running)) return false

        val action = def.action
            ?: return advanceAfterAction(null, def, flow, pd, persister, branchOnly, directNext, markTerminal)

        val result: Any?
        try {
            result = action(pd.state)
        } catch (ex: Throwable) {
            val errored = pd.copy(stageStatus = StageStatus.ERROR)
            persister.save(errored)
            return false
        }

        return advanceAfterAction(result, def, flow, pd, persister, branchOnly, directNext, markTerminal)
    }

    private fun advanceAfterAction(
        result: Any?,
        def: StageDefinition<Any>,
        flow: Flow<Any>,
        pd: ProcessData<Any>,
        persister: StatePersister<Any>,
        branchOnly: Boolean,
        directNext: Stage?,
        markTerminal: Boolean,
    ): Boolean {
        // Determine next stage
        val nextStage: Stage? = when {
            markTerminal -> null
            directNext != null -> directNext
            def.conditionHandler != null -> {
                val target = resolveConditionInitialStage(def.conditionHandler as ConditionHandler<Any>, (result ?: pd.state))
                    ?: throw FlowDefinitionException("Condition did not resolve to a stage from ${pd.stage}")
                target
            }
            def.eventHandlers.isNotEmpty() -> null // wait for event
            def.nextStage != null -> def.nextStage
            else -> null
        }

        // Compute new state and status
        return if (markTerminal) {
            val newState = (result ?: pd.state)
            val completed = pd.copy(state = newState, stageStatus = StageStatus.COMPLETED)
            persister.save(completed)
        } else if (nextStage != null) {
            val newState = (result ?: pd.state)
            val next = pd.copy(state = newState, stage = nextStage, stageStatus = StageStatus.PENDING)
            // If result == null (stage-only), on conflict we should retry the save once with reloaded state
            val saved = persister.save(next)
            if (!saved && result == null) {
                val fresh = persister.load(pd.flowInstanceId) ?: return false
                val retried = fresh.copy(stage = nextStage, stageStatus = StageStatus.PENDING)
                return persister.save(retried)
            }
            saved
        } else {
            // Remain in same stage waiting for event
            val newState = (result ?: pd.state)
            val waiting = pd.copy(state = newState, stageStatus = StageStatus.PENDING)
            val saved = persister.save(waiting)
            // After action in a waiting stage, try to consume if event already present
            if (saved) {
                val consumed = tryConsumeEventAndAdvance(def, flow, waiting, persister, pd.flowInstanceId)
                if (consumed) return true
            }
            saved
        }
    }
}
