package io.flowlite

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.util.UUID

class Engine(
    private val eventStore: EventStore,
    private val tickScheduler: TickScheduler,
    private val historyStore: HistoryStore = NoopHistoryStore,
    private val retryStateStore: RetryStateStore = NoopRetryStateStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    private companion object {
        private val log = KotlinLogging.logger {}
        private const val AUTO_RETRY_TICK_PREFIX = "__auto_retry__:"
    }

    init {
        tickScheduler.setTickHandler(::processTick)
    }

    private val flows = mutableMapOf<String, Flow<Any, Stage, Event>>()
    private val persisters = mutableMapOf<String, StatePersister<Any>>()
    private val failureClassifiers = mutableMapOf<String, FailureClassifier<Any>?>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any, S, E : Event> registerFlow(
        flowId: String,
        flow: Flow<T, S, E>,
        statePersister: StatePersister<T>,
        failureClassifier: FailureClassifier<T>? = null,
    ) where S : Enum<S>, S : Stage {
        log.info { "registerFlow(flowId=$flowId)" }
        flows[flowId] = flow as Flow<Any, Stage, Event>
        persisters[flowId] = statePersister as StatePersister<Any>
        failureClassifiers[flowId] = failureClassifier as FailureClassifier<Any>?
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
            stageStatus = waitingStatus(flow, initialStage),
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
        retry(flowId, flowInstanceId, trigger = RetryTrigger.Cockpit)
    }

    fun externalRetry(flowId: String, flowInstanceId: UUID) {
        retry(flowId, flowInstanceId, trigger = RetryTrigger.External)
    }

    private fun retry(flowId: String, flowInstanceId: UUID, trigger: RetryTrigger) {
        val persister = requireNotNull(persisters[flowId]) { "Persister for flow '$flowId' not registered" }
        val current = persister.load(flowInstanceId)
        log.info { "$trigger retry(flowId=$flowId, flowInstanceId=$flowInstanceId) currentStatus=${current.stageStatus} currentStage=${current.stage}" }
        if (current.stageStatus != StageStatus.Error) {
            error("Cannot retry $flowId/$flowInstanceId because status is ${current.stageStatus}")
        }
        val currentRetryState = retryStateStore.find(flowInstanceId)
        if (trigger == RetryTrigger.External && currentRetryState?.externalRetryAllowed != true) {
            error("Cannot externally retry $flowId/$flowInstanceId because external retry is not allowed")
        }
        val flow = requireNotNull(flows[flowId]) { "Flow '$flowId' not registered" }
        val reset = current.copy(stageStatus = waitingStatus(flow, current.stage))
        val saved = persister.save(reset)
        currentRetryState?.let { retryState ->
            retryStateStore.save(
                retryState.copy(
                    autoRetryMaxAttempts = null,
                    nextAutoRetryAt = null,
                    updatedAt = clock.instant(),
                ),
            )
        }
        historyStore.recordRetried(flowId, saved, trigger)
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

        val targetStatus = waitingStatus(flow, resolvedTarget)
        if (current.stage != resolvedTarget || current.stageStatus != targetStatus) {
            val before = current
            current = persister.save(current.copy(stage = resolvedTarget, stageStatus = targetStatus))
            historyStore.recordManualStageChanged(
                flowId = flowId,
                data = current,
                fromStage = before.stage,
                toStage = resolvedTarget,
                fromStatus = before.stageStatus,
                toStatus = targetStatus,
            )
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

    private fun enqueueTick(
        flowId: String,
        flowInstanceId: UUID,
        notBefore: java.time.Instant = clock.instant(),
        targetStage: String? = null,
    ) {
        tickScheduler.scheduleTick(flowId, flowInstanceId, notBefore, targetStage)
    }

    private fun processTick(tick: ScheduledTick) {
        val flow = requireNotNull(flows[tick.flowId]) { "Flow '${tick.flowId}' not registered" }
        val persister = requireNotNull(persisters[tick.flowId]) { "Persister for flow '${tick.flowId}' not registered" }

        val loaded = persister.load(tick.flowInstanceId)
        if (tick.targetStage != null) {
            val currentStage = historyValueOf(loaded.stage)
            val expectedStage = decodeTickTargetStage(tick.targetStage)
            if (currentStage != expectedStage) {
                log.info {
                    "Ignoring stale timer tick for ${tick.flowId}/${tick.flowInstanceId}: currentStage=$currentStage targetStage=${tick.targetStage}"
                }
                return
            }
            if (isAutoRetryTick(tick.targetStage) && loaded.stageStatus != StageStatus.Error) {
                log.info {
                    "Ignoring stale auto retry tick for ${tick.flowId}/${tick.flowInstanceId}: currentStatus=${loaded.stageStatus} targetStage=${tick.targetStage}"
                }
                return
            }
        }

        when (loaded.stageStatus) {
            StageStatus.Error -> {
                if (isAutoRetryTick(tick.targetStage) && tryAutoRetry(tick.flowId, flow, persister, loaded)) {
                    return
                }
                log.info { "Tick when ${tick.flowId}/${tick.flowInstanceId} is in ERROR at stage ${loaded.stage}; awaiting retry" }
                return
            }
            StageStatus.Completed -> {
                log.info { "Tick when ${tick.flowId}/${tick.flowInstanceId} already COMPLETED" }
                return
            }
            StageStatus.Cancelled -> {
                log.info { "Tick when ${tick.flowId}/${tick.flowInstanceId} already CANCELLED" }
                return
            }
            StageStatus.Running -> {
                // Tick delivered while another worker owns the RUNNING claim.
                // This can happen, e.g. when an event arrives while the flow instance is already running and enqueues a tick.
                log.info { "Tick when ${tick.flowId}/${tick.flowInstanceId} is RUNNING at stage ${loaded.stage}; ignoring" }
                return
            }
            StageStatus.WaitingForTimer,
            StageStatus.WaitingForEvent,
            StageStatus.PendingEngine,
            -> {
                val claimed = persister.tryTransitionStageStatus(
                    flowInstanceId = tick.flowInstanceId,
                    expectedStage = loaded.stage,
                    expectedStageStatus = loaded.stageStatus,
                    newStageStatus = StageStatus.Running,
                )
                if (!claimed) {
                    // Someone else advanced/claimed; tick is a duplicate.
                    return
                }
                historyStore.recordStatusChanged(tick.flowId, loaded, from = loaded.stageStatus, to = StageStatus.Running)
                val running = persister.load(tick.flowInstanceId)
                processTickLoop(tick.flowId, flow, persister, running, tick)
            }
        }
    }

    private fun processTickLoop(
        flowId: String,
        flow: Flow<Any, Stage, Event>,
        persister: StatePersister<Any>,
        initial: InstanceData<Any>,
        tick: ScheduledTick,
    ) {
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

                if (def.timer != null) {
                    val stageKey = historyValueOf(data.stage)
                    val now = clock.instant()
                    val existingTick = tickScheduler.findScheduledTick(flowId, flowInstanceId, stageKey)
                    val isDueTimerTick = tick.targetStage == stageKey && !tick.notBefore.isAfter(now)

                    when {
                        isDueTimerTick -> Unit
                        existingTick != null -> {
                            clearRetryState(flowInstanceId)
                            persister.save(data.copy(stageStatus = StageStatus.WaitingForTimer))
                            historyStore.recordStatusChanged(flowId, data, from = StageStatus.Running, to = StageStatus.WaitingForTimer)
                            log.debug { "Timer stage ${data.stage} already has wake-up at ${existingTick.notBefore} ($flowId/$flowInstanceId)" }
                            return
                        }
                        else -> {
                            val wakeUpAt = def.timer(ActionContext(flowId = flowId, flowInstanceId = flowInstanceId, now = now), data.state)
                            if (wakeUpAt.isAfter(now)) {
                                enqueueTick(
                                    flowId = flowId,
                                    flowInstanceId = flowInstanceId,
                                    notBefore = wakeUpAt,
                                    targetStage = stageKey,
                                )
                                clearRetryState(flowInstanceId)
                                persister.save(data.copy(stageStatus = StageStatus.WaitingForTimer))
                                historyStore.recordStatusChanged(flowId, data, from = StageStatus.Running, to = StageStatus.WaitingForTimer)
                                log.debug { "Timer stage ${data.stage} scheduled wake-up at $wakeUpAt ($flowId/$flowInstanceId)" }
                                return
                            }
                        }
                    }

                    if (def.isTerminal()) {
                        clearRetryState(flowInstanceId)
                        persister.save(data.copy(stageStatus = StageStatus.Completed))
                        historyStore.recordStatusChanged(flowId, data, from = StageStatus.Running, to = StageStatus.Completed)
                        log.info { "Timer stage ${def.stage} completed after wake-up ($flowId/$flowInstanceId)" }
                        return
                    }

                    val nextStage: Stage = def.conditionHandler
                        ?.let { cond ->
                            resolveConditionInitialStage(cond, data.state)
                                ?: error("Condition did not resolve to a stage from ${data.stage}")
                        }
                        ?: def.nextStage
                        ?: error("Non-terminal stage ${data.stage} has a timer but no nextStage/condition")

                    val from = data.stage
                    val before = data
                    clearRetryState(flowInstanceId)
                    data = persister.save(data.copy(stage = nextStage))
                    historyStore.recordStageChanged(flowId, before, from = from, to = nextStage)
                    log.debug { "Timer advanced $from -> $nextStage ($flowId/$flowInstanceId)" }
                    continue
                }

                if (def.eventHandlers.isNotEmpty()) {
                    val next = tryConsumeEventAndAdvance(flowId, def, data, persister, flowInstanceId)
                    if (next != null) {
                        clearRetryState(flowInstanceId)
                        log.debug { "Event consumed for ${data.stage}; advancing" }
                        data = next
                        continue
                    }
                    // No matching event; release the RUNNING claim.
                    clearRetryState(flowInstanceId)
                    persister.save(data.copy(stageStatus = StageStatus.WaitingForEvent))
                    historyStore.recordStatusChanged(flowId, data, from = StageStatus.Running, to = StageStatus.WaitingForEvent)
                    // If an event arrived while we were RUNNING, its tick might have been delivered and ignored.
                    // Check the store and enqueue a tick in case event is there
                    if (eventStore.peek(flowId, flowInstanceId, def.eventHandlers.keys) != null) {
                        enqueueTick(flowId, flowInstanceId)
                    }
                    return
                }

                if (def.action != null) {
                    val result = def.action(ActionContext(flowId = flowId, flowInstanceId = flowInstanceId, now = clock.instant()), data.state)
                    val newState = result ?: data.state

                    if (def.isTerminal()) {
                        clearRetryState(flowInstanceId)
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
                    clearRetryState(flowInstanceId)
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
                    clearRetryState(flowInstanceId)
                    data = persister.save(data.copy(stage = target))
                    historyStore.recordStageChanged(flowId, before, from = from, to = target)
                    log.debug { "Condition transition $from -> $target ($flowId/$flowInstanceId)" }
                    continue
                }

                def.nextStage?.let { ns ->
                    val from = data.stage
                    val before = data
                    clearRetryState(flowInstanceId)
                    data = persister.save(data.copy(stage = ns))
                    historyStore.recordStageChanged(flowId, before, from = from, to = ns)
                    log.debug { "Automatic transition $from -> $ns ($flowId/$flowInstanceId)" }
                    continue
                }

                if (def.isTerminal()) {
                    clearRetryState(flowInstanceId)
                    persister.save(data.copy(stageStatus = StageStatus.Completed))
                    historyStore.recordStatusChanged(flowId, data, from = StageStatus.Running, to = StageStatus.Completed)
                    log.info { "Stage ${data.stage} marked COMPLETED ($flowId/$flowInstanceId)" }
                    return
                }

                error("Stage ${data.stage} has no transitions but is not terminal")
            } catch (ex: Exception) {
                val retryState = buildRetryState(flowId, data, ex)
                log.error(ex) { "Failure in $flowId/$flowInstanceId at stage ${data.stage}" }
                persister.save(data.copy(stageStatus = StageStatus.Error))
                retryStateStore.save(retryState)
                historyStore.recordError(flowId, data, ex, retryState)
                retryState.nextAutoRetryAt?.let { nextAutoRetryAt ->
                    enqueueTick(
                        flowId = flowId,
                        flowInstanceId = flowInstanceId,
                        notBefore = nextAutoRetryAt,
                        targetStage = autoRetryTickTarget(data.stage),
                    )
                    return
                }
                throw ex
            }
        }
    }

    private fun clearRetryState(flowInstanceId: UUID) {
        retryStateStore.delete(flowInstanceId)
    }

    private fun buildRetryState(flowId: String, data: InstanceData<Any>, error: Exception): RetryState {
        val stageName = historyValueOf(data.stage)
        val previous = retryStateStore.find(data.flowInstanceId)
        val failedAttemptCount = if (previous?.stage == stageName) previous.failedAttemptCount + 1 else 1
        val handling = failureClassifiers[flowId]?.classify(
            context = ActionContext(flowId = flowId, flowInstanceId = data.flowInstanceId, now = clock.instant()),
            stage = data.stage,
            state = data.state,
            error = error,
            failedAttemptCount = failedAttemptCount,
        ) ?: FailureHandling()
        val nextAutoRetryAt = handling.autoRetry
            ?.takeIf { (failedAttemptCount - 1) < it.maxAttempts }
            ?.nextDelay(failedAttemptCount)
            ?.let(clock.instant()::plus)

        return RetryState(
            flowId = flowId,
            flowInstanceId = data.flowInstanceId,
            stage = stageName,
            failedAttemptCount = failedAttemptCount,
            externalRetryAllowed = handling.externalRetryAllowed,
            autoRetryMaxAttempts = handling.autoRetry?.maxAttempts,
            nextAutoRetryAt = nextAutoRetryAt,
            lastErrorType = error::class.qualifiedName ?: error::class.java.name,
            lastErrorMessage = error.message ?: error.toString(),
            updatedAt = clock.instant(),
        )
    }

    private fun tryAutoRetry(
        flowId: String,
        flow: Flow<Any, Stage, Event>,
        persister: StatePersister<Any>,
        loaded: InstanceData<Any>,
    ): Boolean {
        val retryState = retryStateStore.find(loaded.flowInstanceId) ?: return false
        val nextAutoRetryAt = retryState.nextAutoRetryAt ?: return false
        if (nextAutoRetryAt.isAfter(clock.instant())) return false

        val reset = loaded.copy(stageStatus = waitingStatus(flow, loaded.stage))
        val saved = persister.save(reset)
        retryStateStore.save(
            retryState.copy(
                autoRetryMaxAttempts = null,
                nextAutoRetryAt = null,
                updatedAt = clock.instant(),
            ),
        )
        historyStore.recordRetried(flowId, saved, RetryTrigger.Auto)
        enqueueTick(flowId, loaded.flowInstanceId)
        return true
    }

    private fun autoRetryTickTarget(stage: Stage): String = AUTO_RETRY_TICK_PREFIX + historyValueOf(stage)

    private fun decodeTickTargetStage(targetStage: String): String =
        if (targetStage.startsWith(AUTO_RETRY_TICK_PREFIX)) targetStage.removePrefix(AUTO_RETRY_TICK_PREFIX) else targetStage

    private fun isAutoRetryTick(targetStage: String?): Boolean =
        targetStage?.startsWith(AUTO_RETRY_TICK_PREFIX) == true

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

    private fun waitingStatus(flow: Flow<Any, Stage, Event>, stage: Stage): StageStatus {
        val definition = flow.stages[stage] ?: error("No definition for stage $stage")
        return when {
            definition.timer != null -> StageStatus.WaitingForTimer
            definition.eventHandlers.isNotEmpty() -> StageStatus.WaitingForEvent
            else -> StageStatus.PendingEngine
        }
    }
}
