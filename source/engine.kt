package io.flowlite.api

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID

class FlowEngine(
    private val eventStore: EventStore,
    private val tickScheduler: TickScheduler,
) {
    init {
        tickScheduler.setTickHandler(::processTick)
    }
    private val flows = mutableMapOf<String, Flow<Any, Stage, Event>>()
    private val persisters = mutableMapOf<String, StatePersister<Any, Stage>>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any, S : Stage, E : Event> registerFlow(
        flowId: String,
        flow: Flow<T, S, E>,
        statePersister: StatePersister<T, S>,
    ) {
        log.info { "registerFlow(flowId=$flowId)" }
        flows[flowId] = flow as Flow<Any, Stage, Event>
        persisters[flowId] = statePersister as StatePersister<Any, Stage>
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> startInstance(flowId: String, initialState: T): UUID {
        val flowInstanceId = UUID.randomUUID()
        val flow = requireNotNull(flows[flowId]) { "Flow '$flowId' not registered" }
        val persister = requireNotNull(persisters[flowId]) { "Persister for flow '$flowId' not registered" }
        val initialStage = resolveInitialStage(flow, initialState)
        log.info { "startInstance(flowId=$flowId, flowInstanceId=$flowInstanceId, initialStage=$initialStage)" }
        val data = InstanceData(
            flowInstanceId = flowInstanceId,
            state = initialState as Any,
            stage = initialStage,
            stageStatus = StageStatus.PENDING,
        )
        persister.save(data)
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

    private fun resolveInitialStage(flow: Flow<Any, Stage, Event>, state: Any): Stage {
        flow.initialStage?.let { return it }
        val cond = requireNotNull(flow.initialCondition) { "Flow must have initial stage or condition" }
        return resolveConditionInitialStage(cond, state)
            ?: error("Initial condition did not resolve to a stage")
    }

    private fun resolveConditionInitialStage(condition: ConditionHandler<Any, Stage>, state: Any): Stage? {
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

    private fun processTickLoop(
        flowId: String,
        flow: Flow<Any, Stage, Event>,
        persister: StatePersister<Any, Stage>,
        initial: InstanceData<Any, Stage>,
    ) {
        require(initial.stageStatus == StageStatus.RUNNING) {
            "processTickLoop expects RUNNING but was ${initial.stageStatus}"
        }

        var data = initial
        val flowInstanceId = data.flowInstanceId

        while (true) {
            log.debug { "Processing loop for $flowId/$flowInstanceId at stage ${data.stage}" }
            val def = flow.stages[data.stage]
                ?: error("No definition for stage ${data.stage}")

            try {
                if (def.eventHandlers.isNotEmpty()) {
                    val next = tryConsumeEventAndAdvance(flowId, def, data, persister, flowInstanceId)
                    if (next != null) {
                        log.debug { "Event consumed for ${data.stage}; advancing" }
                        data = next
                        continue
                    }
                    // No matching event; release the RUNNING claim.
                    persister.save(data.copy(stageStatus = StageStatus.PENDING))
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
                        persister.save(data.copy(state = newState, stageStatus = StageStatus.COMPLETED))
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
                    data = persister.save(data.copy(state = newState, stage = nextStage))
                    log.debug { "Action advanced $from -> $nextStage ($flowId/$flowInstanceId)" }
                    continue
                }

                def.conditionHandler?.let { cond ->
                    val from = data.stage
                    val target = resolveConditionInitialStage(cond, data.state)
                        ?: error("Condition did not resolve to a stage from ${data.stage}")
                    data = persister.save(data.copy(stage = target))
                    log.debug { "Condition transition $from -> $target ($flowId/$flowInstanceId)" }
                    continue
                }

                def.nextStage?.let { ns ->
                    val from = data.stage
                    data = persister.save(data.copy(stage = ns))
                    log.debug { "Automatic transition $from -> $ns ($flowId/$flowInstanceId)" }
                    continue
                }

                if (def.isTerminal()) {
                    persister.save(data.copy(stageStatus = StageStatus.COMPLETED))
                    log.info { "Stage ${data.stage} marked COMPLETED ($flowId/$flowInstanceId)" }
                    return
                }

                error("Stage ${data.stage} has no transitions but is not terminal")
            } catch (ex: Exception) {
                log.error(ex) { "Failure in $flowId/$flowInstanceId at stage ${data.stage}" }
                persister.save(data.copy(stageStatus = StageStatus.ERROR))
                throw ex
            }
        }
    }

    private fun tryConsumeEventAndAdvance(
        flowId: String,
        def: StageDefinition<Any, Stage, Event>,
        data: InstanceData<Any, Stage>,
        persister: StatePersister<Any, Stage>,
        flowInstanceId: UUID,
    ): InstanceData<Any, Stage>? {
        val stored = eventStore.peek(flowId, flowInstanceId, def.eventHandlers.keys) ?: return null
        val handler = def.eventHandlers[stored.event] ?: return null
        val targetStage = handler.targetStage ?: handler.targetCondition?.let { ch ->
            resolveConditionInitialStage(ch, data.state)
        }
            ?: error("Event handler did not resolve to a stage from ${data.stage}")
        val next = data.copy(stage = targetStage)
        val saved = persister.save(next)
        eventStore.delete(stored.id)
        return saved
    }

    private fun StageDefinition<*, *, *>.isTerminal(): Boolean =
        nextStage == null && conditionHandler == null && eventHandlers.isEmpty()
}

private val log = KotlinLogging.logger {}
