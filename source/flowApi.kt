package io.flowlite.api

import java.util.UUID
import io.github.oshai.kotlinlogging.KotlinLogging

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
    *
    * This method is called frequently for stage/status transitions.
    * Implementations should make a best-effort attempt to persist the change, e.g.:
    * - retry on optimistic-locking conflicts;
    * - merge engine-owned fields (stage, stageStatus) with a freshly loaded domain snapshot,
    *   to avoid losing concurrent updates made by external writers.
    *
    * Returns refreshed data on success.
     */
    fun save(processData: ProcessData<T>): ProcessData<T>

    /** Load current process data; throws if the process does not exist. */
    fun load(flowInstanceId: UUID): ProcessData<T>

    /**
     * Attempt to transition stage status atomically (compare-and-set).
     *
     * Implementations must ensure the update is applied only if both `expectedStage` and `expectedStageStatus` match
     * the current persisted values. Returns `true` if the transition was applied, otherwise `false`.
     *
     * This is used by the engine primarily to claim single-flight processing (`PENDING -> RUNNING`).
     */
    fun tryTransitionStageStatus(
        flowInstanceId: UUID,
        expectedStage: Stage,
        expectedStageStatus: StageStatus,
        newStageStatus: StageStatus,
    ): Boolean
}

/**
 * Pluggable store for pending events. Default implementation is in-memory; applications can provide
 * persistent implementations (e.g., Spring Data JDBC) without changing the engine.
 */
interface EventStore {
    fun append(flowId: String, flowInstanceId: UUID, event: Event)
    fun peek(flowId: String, flowInstanceId: UUID, candidates: Collection<Event>): StoredEvent?
    fun delete(eventId: UUID): Boolean
}

data class StoredEvent(
    val id: UUID,
    val event: Event,
)

interface TickScheduler {
    fun setTickHandler(handler: (String, UUID) -> Unit)
    fun scheduleTick(flowId: String, flowInstanceId: UUID)
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
        validateDefinitions()
        return Flow(initialStage, initialCondition, stages.toMap())
    }

    private fun validateDefinitions() {
        val eventToStage = mutableMapOf<Event, Stage>()
        stages.values.forEach { def ->
            if (def.action != null && def.eventHandlers.isNotEmpty()) {
                throw FlowDefinitionException("Stage ${def.stage} cannot declare both an action and event handlers")
            }
            if (def.eventHandlers.isNotEmpty() && (def.nextStage != null || def.conditionHandler != null)) {
                throw FlowDefinitionException("Stage ${def.stage} cannot mix event handlers with direct or conditional transitions")
            }

            def.eventHandlers.keys.forEach { event ->
                val existing = eventToStage.putIfAbsent(event, def.stage)
                if (existing != null && existing != def.stage) {
                    throw FlowDefinitionException(
                        "Event $event is used in multiple waitFor declarations ($existing and ${def.stage}). " +
                            "Reusing the same event type in different parts of a flow is not supported; " +
                            "model repeated occurrences as distinct event types or include event identity/deduplication in your EventStore."
                    )
                }
            }
        }
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

class FlowEngine(
    private val eventStore: EventStore,
    private val tickScheduler: TickScheduler,
) {
    init {
        tickScheduler.setTickHandler(::processTick)
    }
    private val flows = mutableMapOf<String, Flow<Any>>()
    private val persisters = mutableMapOf<String, StatePersister<Any>>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> registerFlow(
        flowId: String,
        flow: Flow<T>,
        statePersister: StatePersister<T>,
    ) {
        log.info { "registerFlow(flowId=$flowId)" }
        flows[flowId] = flow as Flow<Any>
        persisters[flowId] = statePersister as StatePersister<Any>
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> startInstance(flowId: String, initialState: T): UUID {
        val flowInstanceId = UUID.randomUUID()
        val flow = requireNotNull(flows[flowId]) { "Flow '$flowId' not registered" }
        val persister = requireNotNull(persisters[flowId]) { "Persister for flow '$flowId' not registered" }
        val initialStage = resolveInitialStage(flow as Flow<T>, initialState)
        log.info { "startInstance(flowId=$flowId, flowInstanceId=$flowInstanceId, initialStage=$initialStage)" }
        val pd = ProcessData(
            flowInstanceId = flowInstanceId,
            state = initialState,
            stage = initialStage,
            stageStatus = StageStatus.PENDING,
        )
        persister.save(pd as ProcessData<Any>)
        enqueueTick(flowId, flowInstanceId)
        return flowInstanceId
    }

    fun startInstance(flowId: String, flowInstanceId: UUID): UUID {
        requireNotNull(flows[flowId]) { "Flow '$flowId' not registered" }
        val persister = requireNotNull(persisters[flowId]) { "Persister for flow '$flowId' not registered" }
        val current = persister.load(flowInstanceId)
        log.info { "startInstance(flowId=$flowId, flowInstanceId=$flowInstanceId) currentStatus=${current.stageStatus} currentStage=${current.stage}" }
        if (current.stageStatus == StageStatus.COMPLETED) return flowInstanceId
        enqueueTick(flowId, flowInstanceId)
        return flowInstanceId
    }

    fun sendEvent(flowId: String, flowInstanceId: UUID, event: Event) {
        requireNotNull(flows[flowId]) { "Flow '$flowId' not registered" }
        requireNotNull(persisters[flowId]) { "Persister for flow '$flowId' not registered" }
        log.info { "sendEvent(flowId=$flowId, flowInstanceId=$flowInstanceId, event=$event)" }
        eventStore.append(flowId, flowInstanceId, event)
        enqueueTick(flowId, flowInstanceId)
    }

    fun retry(flowId: String, flowInstanceId: UUID) {
        val persister = requireNotNull(persisters[flowId]) { "Persister for flow '$flowId' not registered" }
        val current = persister.load(flowInstanceId)
        log.info { "retry(flowId=$flowId, flowInstanceId=$flowInstanceId) currentStatus=${current.stageStatus} currentStage=${current.stage}" }
        if (current.stageStatus != StageStatus.ERROR) {
            error("Cannot retry $flowId/$flowInstanceId because status is ${current.stageStatus}")
        }
        val reset = current.copy(stageStatus = StageStatus.PENDING)
        persister.save(reset)
        enqueueTick(flowId, flowInstanceId)
    }

    fun getStatus(flowId: String, flowInstanceId: UUID): Pair<Stage, StageStatus>? {
        val persister = persisters[flowId] ?: return null
        val pd = persister.load(flowInstanceId)
        log.debug { "getStatus(flowId=$flowId, flowInstanceId=$flowInstanceId) -> (${pd.stage}, ${pd.stageStatus})" }
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

    private fun enqueueTick(flowId: String, flowInstanceId: UUID) {
        tickScheduler.scheduleTick(flowId, flowInstanceId)
    }

    private fun processTick(flowId: String, flowInstanceId: UUID) {
        val flow = requireNotNull(flows[flowId]) { "Flow '$flowId' not registered" }
        val persister = requireNotNull(persisters[flowId]) { "Persister for flow '$flowId' not registered" }

        val loaded = persister.load(flowInstanceId)
        when (loaded.stageStatus) {
            StageStatus.ERROR -> {
                log.info { "Tick when $flowId/$flowInstanceId is in ERROR at stage ${loaded.stage}; awaiting retry" }
                return
            }
            StageStatus.COMPLETED -> {
                log.info { "Tick when $flowId/$flowInstanceId already COMPLETED" }
                return
            }
            StageStatus.RUNNING -> {
                // Tick delivered while another worker owns the RUNNING claim.
                // This can happen, e.g. when an event arrives while the process is already running and enqueues a tick.
                log.info { "Tick when $flowId/$flowInstanceId is RUNNING at stage ${loaded.stage}; ignoring" }
                return
            }
            StageStatus.PENDING -> {
                val claimed = persister.tryTransitionStageStatus(
                    flowInstanceId = flowInstanceId,
                    expectedStage = loaded.stage,
                    expectedStageStatus = StageStatus.PENDING,
                    newStageStatus = StageStatus.RUNNING,
                )
                if (!claimed) {
                    // Someone else advanced/claimed; tick is a duplicate.
                    return
                }
                val running = persister.load(flowInstanceId)
                processTickLoop(flowId, flow, persister, running)
            }
        }
    }

    private fun processTickLoop(flowId: String, flow: Flow<Any>, persister: StatePersister<Any>, initial: ProcessData<Any>) {
        require(initial.stageStatus == StageStatus.RUNNING) {
            "processTickLoop expects RUNNING but was ${initial.stageStatus}"
        }

        var pd = initial
        val flowInstanceId = pd.flowInstanceId

        while (true) {
            log.debug { "Processing loop for $flowId/$flowInstanceId at stage ${pd.stage}" }
            val def = flow.stages[pd.stage]
                ?: throw FlowDefinitionException("No definition for stage ${pd.stage}")

            try {
                if (def.eventHandlers.isNotEmpty()) {
                    val next = tryConsumeEventAndAdvance(flowId, def, pd, persister, flowInstanceId)
                    if (next != null) {
                        log.debug { "Event consumed for ${pd.stage}; advancing" }
                        pd = next
                        continue
                    }
                    // No matching event; release the RUNNING claim.
                    persister.save(pd.copy(stageStatus = StageStatus.PENDING))
                    // If an event arrived while we were RUNNING, its tick might have been delivered and ignored.
                    // Check the store and enqueue a tick in case event is there
                    if (eventStore.peek(flowId, flowInstanceId, def.eventHandlers.keys) != null) {
                        enqueueTick(flowId, flowInstanceId)
                    }
                    return
                }

                if (def.action != null) {
                    val result = def.action(pd.state)
                    val newState = result ?: pd.state

                    if (def.isTerminal()) {
                        persister.save(pd.copy(state = newState, stageStatus = StageStatus.COMPLETED))
                        log.info { "Stage ${def.stage} completed after action ($flowId/$flowInstanceId)" }
                        return
                    }

                    val nextStage: Stage = def.conditionHandler
                        ?.let { cond ->
                            resolveConditionInitialStage(cond, newState)
                                ?: throw FlowDefinitionException("Condition did not resolve to a stage from ${pd.stage}")
                        }
                        ?: def.nextStage
                        ?: error("Non-terminal stage ${pd.stage} has an action but no nextStage/condition")

                    val from = pd.stage
                    pd = persister.save(pd.copy(state = newState, stage = nextStage))
                    log.debug { "Action advanced $from -> $nextStage ($flowId/$flowInstanceId)" }
                    continue
                }

                def.conditionHandler?.let { cond ->
                    val from = pd.stage
                    val target = resolveConditionInitialStage(cond, pd.state)
                        ?: throw FlowDefinitionException("Condition did not resolve to a stage from ${pd.stage}")
                    pd = persister.save(pd.copy(stage = target))
                    log.debug { "Condition transition $from -> $target ($flowId/$flowInstanceId)" }
                    continue
                }

                def.nextStage?.let { ns ->
                    val from = pd.stage
                    pd = persister.save(pd.copy(stage = ns))
                    log.debug { "Automatic transition $from -> $ns ($flowId/$flowInstanceId)" }
                    continue
                }

                if (def.isTerminal()) {
                    persister.save(pd.copy(stageStatus = StageStatus.COMPLETED))
                    log.info { "Stage ${pd.stage} marked COMPLETED ($flowId/$flowInstanceId)" }
                    return
                }

                error("Stage ${pd.stage} has no transitions but is not terminal")
            } catch (ex: Exception) {
                log.error(ex) { "Failure in $flowId/$flowInstanceId at stage ${pd.stage}" }
                persister.save(pd.copy(stageStatus = StageStatus.ERROR))
                throw ex
            }
        }
    }

    private fun tryConsumeEventAndAdvance(
        flowId: String,
        def: StageDefinition<Any>,
        pd: ProcessData<Any>,
        persister: StatePersister<Any>,
        flowInstanceId: UUID,
    ): ProcessData<Any>? {
        val stored = eventStore.peek(flowId, flowInstanceId, def.eventHandlers.keys) ?: return null
        val handler = def.eventHandlers[stored.event] ?: return null
        val targetStage = handler.targetStage ?: handler.targetCondition?.let { ch ->
            resolveConditionInitialStage(ch, pd.state)
        }
        ?: throw FlowDefinitionException("Event handler did not resolve to a stage from ${pd.stage}")
        val next = pd.copy(stage = targetStage)
        val saved = persister.save(next)
        eventStore.delete(stored.id)
        return saved
    }

    private fun StageDefinition<*>.isTerminal(): Boolean =
        nextStage == null && conditionHandler == null && eventHandlers.isEmpty()
}

private val log = KotlinLogging.logger {}