package io.flowlite

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID

class FlowEngine(
    private val eventStore: EventStore,
    private val tickScheduler: TickScheduler,
    private val historyStore: HistoryStore = NoopHistoryStore,
) {
    private companion object {
        private val log = KotlinLogging.logger {}
    }

    init {
        tickScheduler.setTickHandler(::processTick)
    }

    private val flows = mutableMapOf<String, Flow<Any, Stage, Event>>()
    private val persisters = mutableMapOf<String, StatePersister<Any>>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any, S : Stage, E : Event> registerFlow(
        flowId: String,
        flow: Flow<T, S, E>,
        statePersister: StatePersister<T>,
    ) {
        log.info { "registerFlow(flowId=$flowId)" }
        flows[flowId] = flow as Flow<Any, Stage, Event>
        persisters[flowId] = statePersister as StatePersister<Any>
    }

    fun registeredFlows(): Map<String, Flow<Any, Stage, Event>> = flows.toMap()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> startInstance(flowId: String, initialState: T): UUID {
        val flowInstanceId = UUID.randomUUID()
        val flow = requireNotNull(flows[flowId]) { "Flow '$flowId' not registered" }
        val persister = requireNotNull(persisters[flowId]) { "Persister for flow '$flowId' not registered" }
        val initialStage = resolveInitialStage(flow as Flow<T, Stage, Event>, initialState)
        log.info { "startInstance(flowId=$flowId, flowInstanceId=$flowInstanceId, initialStage=$initialStage)" }
        val data = InstanceData(
            flowInstanceId = flowInstanceId,
            state = initialState,
            stage = initialStage,
            stageStatus = StageStatus.Pending,
        )
        persister.save(data as InstanceData<Any>)
        historyStore.recordStarted(flowId, data as InstanceData<Any>)
        enqueueTick(flowId, flowInstanceId)
        return flowInstanceId
    }

    fun startInstance(flowId: String, flowInstanceId: UUID): UUID {
        requireNotNull(flows[flowId]) { "Flow '$flowId' not registered" }
        val persister = requireNotNull(persisters[flowId]) { "Persister for flow '$flowId' not registered" }
        val current = persister.load(flowInstanceId)
        log.info {
            "startInstance(flowId=$flowId, flowInstanceId=$flowInstanceId) currentStatus=${current.stageStatus} currentStage=${current.stage}"
        }
        if (current.stageStatus == StageStatus.Completed || current.stageStatus == StageStatus.Cancelled) return flowInstanceId
        enqueueTick(flowId, flowInstanceId)
        return flowInstanceId
    }

    fun sendEvent(flowId: String, flowInstanceId: UUID, event: Event) {
        requireNotNull(flows[flowId]) { "Flow '$flowId' not registered" }
        requireNotNull(persisters[flowId]) { "Persister for flow '$flowId' not registered" }
        log.info { "sendEvent(flowId=$flowId, flowInstanceId=$flowInstanceId, event=$event)" }
        eventStore.append(flowId, flowInstanceId, event)
        historyStore.recordEventAppended(flowId, flowInstanceId, event)
        enqueueTick(flowId, flowInstanceId)
    }

    fun retry(flowId: String, flowInstanceId: UUID) {
        val persister = requireNotNull(persisters[flowId]) { "Persister for flow '$flowId' not registered" }
        val current = persister.load(flowInstanceId)
        log.info { "retry(flowId=$flowId, flowInstanceId=$flowInstanceId) currentStatus=${current.stageStatus} currentStage=${current.stage}" }
        if (current.stageStatus != StageStatus.Error) {
            error("Cannot retry $flowId/$flowInstanceId because status is ${current.stageStatus}")
        }
        val reset = current.copy(stageStatus = StageStatus.Pending)
        persister.save(reset)
        historyStore.recordStatusChanged(flowId, current, from = StageStatus.Error, to = StageStatus.Pending)
        enqueueTick(flowId, flowInstanceId)
    }

    fun cancel(flowId: String, flowInstanceId: UUID) {
        requireNotNull(flows[flowId]) { "Flow '$flowId' not registered" }
        val persister = requireNotNull(persisters[flowId]) { "Persister for flow '$flowId' not registered" }
        val current = persister.load(flowInstanceId)
        log.info { "cancel(flowId=$flowId, flowInstanceId=$flowInstanceId) currentStatus=${current.stageStatus} currentStage=${current.stage}" }
        if (current.stageStatus == StageStatus.Completed || current.stageStatus == StageStatus.Cancelled) return

        val cancelled = current.copy(stageStatus = StageStatus.Cancelled)
        persister.save(cancelled)
        historyStore.recordCancelled(flowId, current, from = current.stageStatus)
    }

    fun changeStage(flowId: String, flowInstanceId: UUID, targetStage: String) {
        val flow = requireNotNull(flows[flowId]) { "Flow '$flowId' not registered" }
        val persister = requireNotNull(persisters[flowId]) { "Persister for flow '$flowId' not registered" }

        val resolvedTarget = flow.stages.keys.firstOrNull { historyValueOf(it) == targetStage }
            ?: error("Stage '$targetStage' not found in flow '$flowId'")

        var current = persister.load(flowInstanceId)
        log.info {
            "changeStage(flowId=$flowId, flowInstanceId=$flowInstanceId, targetStage=$targetStage) " +
                "currentStatus=${current.stageStatus} currentStage=${current.stage}"
        }

        if (current.stage != resolvedTarget) {
            val before = current
            current = persister.save(current.copy(stage = resolvedTarget))
            historyStore.recordStageChanged(flowId, before, from = before.stage, to = resolvedTarget)
        }

        if (current.stageStatus != StageStatus.Pending) {
            val before = current
            current = persister.save(current.copy(stageStatus = StageStatus.Pending))
            historyStore.recordStatusChanged(flowId, before, from = before.stageStatus, to = StageStatus.Pending)
        }

        enqueueTick(flowId, flowInstanceId)
    }

    fun getStatus(flowId: String, flowInstanceId: UUID): Pair<Stage, StageStatus> {
        requireNotNull(flows[flowId]) { "Flow '$flowId' not registered" }
        val persister = requireNotNull(persisters[flowId]) { "Persister for flow '$flowId' not registered" }
        val pd = persister.load(flowInstanceId)
        log.debug { "getStatus(flowId=$flowId, flowInstanceId=$flowInstanceId) -> (${pd.stage}, ${pd.stageStatus})" }
        return pd.stage to pd.stageStatus
    }

    // --- Internal processing ---

    private fun <T : Any> resolveInitialStage(flow: Flow<T, Stage, Event>, state: T): Stage {
        flow.initialStage?.let { return it }
        val cond = requireNotNull(flow.initialCondition) { "Flow must have initial stage or condition" }
        return resolveConditionInitialStage(cond, state)
            ?: error("Initial condition did not resolve to a stage")
    }

    private fun <T : Any> resolveConditionInitialStage(condition: ConditionHandler<T, Stage>, state: T): Stage? {
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
            StageStatus.Error -> {
                log.info { "Tick when $flowId/$flowInstanceId is in ERROR at stage ${loaded.stage}; awaiting retry" }
                return
            }
            StageStatus.Completed -> {
                log.info { "Tick when $flowId/$flowInstanceId already COMPLETED" }
                return
            }
            StageStatus.Cancelled -> {
                log.info { "Tick when $flowId/$flowInstanceId already CANCELLED" }
                return
            }
            StageStatus.Running -> {
                // Tick delivered while another worker owns the RUNNING claim.
                // This can happen, e.g. when an event arrives while the flow instance is already running and enqueues a tick.
                log.info { "Tick when $flowId/$flowInstanceId is RUNNING at stage ${loaded.stage}; ignoring" }
                return
            }
            StageStatus.Pending -> {
                val claimed = persister.tryTransitionStageStatus(
                    flowInstanceId = flowInstanceId,
                    expectedStage = loaded.stage,
                    expectedStageStatus = StageStatus.Pending,
                    newStageStatus = StageStatus.Running,
                )
                if (!claimed) {
                    // Someone else advanced/claimed; tick is a duplicate.
                    return
                }
                historyStore.recordStatusChanged(flowId, loaded, from = StageStatus.Pending, to = StageStatus.Running)
                val running = persister.load(flowInstanceId)
                processTickLoop(flowId, flow, persister, running)
            }
        }
    }

    private fun processTickLoop(flowId: String, flow: Flow<Any, Stage, Event>, persister: StatePersister<Any>, initial: InstanceData<Any>) {
        require(initial.stageStatus == StageStatus.Running) {
            "processTickLoop expects RUNNING but was ${initial.stageStatus}"
        }

        var data = initial
        val flowInstanceId = data.flowInstanceId

        while (true) {
            log.debug { "Processing loop for $flowId/$flowInstanceId at stage ${data.stage}" }
            try {
                val def = flow.stages[data.stage]
                    ?: error("No definition for stage ${data.stage}")

                if (def.eventHandlers.isNotEmpty()) {
                    val next = tryConsumeEventAndAdvance(flowId, def, data, persister, flowInstanceId)
                    if (next != null) {
                        log.debug { "Event consumed for ${data.stage}; advancing" }
                        data = next
                        continue
                    }
                    // No matching event; release the RUNNING claim.
                    persister.save(data.copy(stageStatus = StageStatus.Pending))
                    historyStore.recordStatusChanged(flowId, data, from = StageStatus.Running, to = StageStatus.Pending)
                    // If an event arrived while we were RUNNING, its tick might have been delivered and ignored.
                    // Check the store and enqueue a tick in case event is there
                    if (eventStore.peek(flowId, flowInstanceId, def.eventHandlers.keys) != null) {
                        enqueueTick(flowId, flowInstanceId)
                    }
                    return
                }

                if (def.action != null) {
                    val result = def.action(data.state)
                    val newState = result ?: data.state

                    if (def.isTerminal()) {
                        persister.save(data.copy(state = newState, stageStatus = StageStatus.Completed))
                        historyStore.recordStatusChanged(flowId, data, from = StageStatus.Running, to = StageStatus.Completed)
                        log.info { "Stage ${def.stage} completed after action ($flowId/$flowInstanceId)" }
                        return
                    }

                    val nextStage: Stage = def.conditionHandler
                        ?.let { cond ->
                            resolveConditionInitialStage(cond, newState)
                                ?: error("Condition did not resolve to a stage from ${data.stage}")
                        }
                        ?: def.nextStage
                        ?: error("Non-terminal stage ${data.stage} has an action but no nextStage/condition")

                    val from = data.stage
                    val before = data
                    data = persister.save(data.copy(state = newState, stage = nextStage))
                    historyStore.recordStageChanged(flowId, before, from = from, to = nextStage)
                    log.debug { "Action advanced $from -> $nextStage ($flowId/$flowInstanceId)" }
                    continue
                }

                def.conditionHandler?.let { cond ->
                    val from = data.stage
                    val target = resolveConditionInitialStage(cond, data.state)
                        ?: error("Condition did not resolve to a stage from ${data.stage}")
                    val before = data
                    data = persister.save(data.copy(stage = target))
                    historyStore.recordStageChanged(flowId, before, from = from, to = target)
                    log.debug { "Condition transition $from -> $target ($flowId/$flowInstanceId)" }
                    continue
                }

                def.nextStage?.let { ns ->
                    val from = data.stage
                    val before = data
                    data = persister.save(data.copy(stage = ns))
                    historyStore.recordStageChanged(flowId, before, from = from, to = ns)
                    log.debug { "Automatic transition $from -> $ns ($flowId/$flowInstanceId)" }
                    continue
                }

                if (def.isTerminal()) {
                    persister.save(data.copy(stageStatus = StageStatus.Completed))
                    historyStore.recordStatusChanged(flowId, data, from = StageStatus.Running, to = StageStatus.Completed)
                    log.info { "Stage ${data.stage} marked COMPLETED ($flowId/$flowInstanceId)" }
                    return
                }

                error("Stage ${data.stage} has no transitions but is not terminal")
            } catch (ex: Exception) {
                log.error(ex) { "Failure in $flowId/$flowInstanceId at stage ${data.stage}" }
                persister.save(data.copy(stageStatus = StageStatus.Error))
                historyStore.recordError(flowId, data, ex)
                throw ex
            }
        }
    }

    private fun tryConsumeEventAndAdvance(
        flowId: String,
        def: StageDefinition<Any, Stage, Event>,
        data: InstanceData<Any>,
        persister: StatePersister<Any>,
        flowInstanceId: UUID,
    ): InstanceData<Any>? {
        val stored = eventStore.peek(flowId, flowInstanceId, def.eventHandlers.keys) ?: return null
        val handler = def.eventHandlers[stored.event] ?: return null
        val targetStage = handler.targetStage ?: handler.targetCondition?.let { ch ->
            resolveConditionInitialStage(ch, data.state)
        }
            ?: error("Event handler did not resolve to a stage from ${data.stage}")
        val from = data.stage
        val next = data.copy(stage = targetStage)
        val saved = persister.save(next)
        eventStore.delete(stored.id)
        historyStore.recordStageChanged(flowId, data, from = from, to = targetStage, event = stored.event)
        return saved
    }

    private fun StageDefinition<*, *, *>.isTerminal(): Boolean =
        nextStage == null && conditionHandler == null && eventHandlers.isEmpty()
}
