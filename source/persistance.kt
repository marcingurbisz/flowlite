package io.flowlite

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.util.UUID

/**
 * Runtime status of a single active stage for a flow instance.
 */
enum class StageStatus {
    Running,
    WaitingForTimer,
    WaitingForEvent,
    PendingEngine,
    Error,
    Completed, // used only for terminal stages
    Cancelled, // used when an instance is manually canceled
}

/**
 * Persisted view of a flow instance.
 */
data class InstanceData<T : Any>(
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
    fun save(instanceData: InstanceData<T>): InstanceData<T>

    /** Load current flow instance data; throws if the flow instance does not exist. */
    fun load(flowInstanceId: UUID): InstanceData<T>

    /**
     * Attempt to transition stage status atomically (compare-and-set).
     *
     * Implementations must ensure the update is applied only if both `expectedStage` and `expectedStageStatus` match
     * the current persisted values. Returns `true` if the transition was applied, otherwise `false`.
     *
    * This is used by the engine primarily to claim single-flight processing (`WaitingFor*`/`PendingEngine` -> `Running`).
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

data class ScheduledTick(
    val flowId: String,
    val flowInstanceId: UUID,
    val notBefore: Instant = Instant.now(),
    val targetStage: String? = null,
    val autoRetry: Boolean = false,
)

interface TickScheduler {
    fun setTickHandler(handler: (ScheduledTick) -> Unit)
    fun scheduleTick(
        flowId: String,
        flowInstanceId: UUID,
        notBefore: Instant = Instant.now(),
        targetStage: String? = null,
        autoRetry: Boolean = false,
    )

    fun findScheduledTick(
        flowId: String,
        flowInstanceId: UUID,
        targetStage: String,
        autoRetry: Boolean = false,
    ): ScheduledTick? = null
}

/**
 * Optional, application-provided store for durable history of flow instance changes.
 *
 * This is intended for building observability features (e.g. Cockpit): instance timelines,
 * error details, and auditing of stage/status transitions.
 */
interface HistoryStore {
    fun append(entry: HistoryEntry)
}

interface FailureClassifier<T : Any> {
    fun classify(
        context: ActionContext,
        stage: Stage,
        state: T,
        error: Exception,
        failedAttemptCount: Int,
    ): FailureHandling
}

data class FailureHandling(
    val autoRetry: AutoRetryPlan? = null,
    val externalRetryAllowed: Boolean = false,
)

data class AutoRetryPlan(
    val maxAttempts: Int,
    val backoffStrategy: BackoffStrategy,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    }

    fun nextDelay(failedAttemptCount: Int): java.time.Duration = backoffStrategy.delayForAttempt(failedAttemptCount)
}

fun interface BackoffStrategy {
    fun delayForAttempt(failedAttemptCount: Int): java.time.Duration

    companion object {
        fun fixed(delay: java.time.Duration) = BackoffStrategy { _ -> delay }
    }
}

enum class RetryTrigger {
    Auto,
    External,
    Cockpit,
}

data class RetryState(
    val flowId: String,
    val flowInstanceId: UUID,
    val stage: String,
    val failedAttemptCount: Int,
    val externalRetryAllowed: Boolean,
    val autoRetryMaxAttempts: Int? = null,
    val nextAutoRetryAt: Instant? = null,
    val lastErrorType: String? = null,
    val lastErrorMessage: String? = null,
    val updatedAt: Instant = Instant.now(),
) {
    val autoRetryActive: Boolean
        get() = autoRetryMaxAttempts != null && nextAutoRetryAt != null
}

interface RetryStateStore {
    fun save(state: RetryState): RetryState

    fun find(flowInstanceId: UUID): RetryState?

    fun delete(flowInstanceId: UUID)
}

object NoopRetryStateStore : RetryStateStore {
    override fun save(state: RetryState): RetryState = state

    override fun find(flowInstanceId: UUID): RetryState? = null

    override fun delete(flowInstanceId: UUID) = Unit
}

enum class HistoryEntryType {
    Started,
    EventAppended,
    StatusChanged,
    StageChanged,
    Retried,
    ManualStageChanged,
    Cancelled,
    Error,
}

sealed class HistoryEntry(
    open val flowId: String,
    open val flowInstanceId: UUID,
    open val type: HistoryEntryType,
    open val occurredAt: Instant = Instant.now(),
    open val stage: String? = null,
    open val fromStage: String? = null,
    open val toStage: String? = null,
    open val fromStatus: StageStatus? = null,
    open val toStatus: StageStatus? = null,
    open val event: String? = null,
    open val errorType: String? = null,
    open val errorMessage: String? = null,
    open val errorStackTrace: String? = null,
    open val retryTrigger: RetryTrigger? = null,
    open val failedAttemptCount: Int? = null,
    open val autoRetryMaxAttempts: Int? = null,
    open val nextAutoRetryAt: Instant? = null,
    open val externalRetryAllowed: Boolean? = null,
) {
    data class Started(
        override val flowId: String,
        override val flowInstanceId: UUID,
        override val occurredAt: Instant = Instant.now(),
        override val stage: String? = null,
        override val toStatus: StageStatus? = null,
    ) : HistoryEntry(
        flowId = flowId,
        flowInstanceId = flowInstanceId,
        type = HistoryEntryType.Started,
        occurredAt = occurredAt,
        stage = stage,
        toStatus = toStatus,
    )

    data class EventAppended(
        override val flowId: String,
        override val flowInstanceId: UUID,
        override val occurredAt: Instant = Instant.now(),
        override val event: String? = null,
    ) : HistoryEntry(
        flowId = flowId,
        flowInstanceId = flowInstanceId,
        type = HistoryEntryType.EventAppended,
        occurredAt = occurredAt,
        event = event,
    )

    data class StatusChanged(
        override val flowId: String,
        override val flowInstanceId: UUID,
        override val occurredAt: Instant = Instant.now(),
        override val stage: String? = null,
        override val fromStatus: StageStatus? = null,
        override val toStatus: StageStatus? = null,
    ) : HistoryEntry(
        flowId = flowId,
        flowInstanceId = flowInstanceId,
        type = HistoryEntryType.StatusChanged,
        occurredAt = occurredAt,
        stage = stage,
        fromStatus = fromStatus,
        toStatus = toStatus,
    )

    data class StageChanged(
        override val flowId: String,
        override val flowInstanceId: UUID,
        override val occurredAt: Instant = Instant.now(),
        override val fromStage: String? = null,
        override val toStage: String? = null,
        override val event: String? = null,
    ) : HistoryEntry(
        flowId = flowId,
        flowInstanceId = flowInstanceId,
        type = HistoryEntryType.StageChanged,
        occurredAt = occurredAt,
        fromStage = fromStage,
        toStage = toStage,
        event = event,
    )

    data class Retried(
        override val flowId: String,
        override val flowInstanceId: UUID,
        override val occurredAt: Instant = Instant.now(),
        override val stage: String? = null,
        override val fromStatus: StageStatus? = StageStatus.Error,
        override val toStatus: StageStatus? = StageStatus.PendingEngine,
        override val retryTrigger: RetryTrigger? = null,
    ) : HistoryEntry(
        flowId = flowId,
        flowInstanceId = flowInstanceId,
        type = HistoryEntryType.Retried,
        occurredAt = occurredAt,
        stage = stage,
        fromStatus = fromStatus,
        toStatus = toStatus,
        retryTrigger = retryTrigger,
    )

    data class ManualStageChanged(
        override val flowId: String,
        override val flowInstanceId: UUID,
        override val occurredAt: Instant = Instant.now(),
        override val fromStage: String? = null,
        override val toStage: String? = null,
        override val fromStatus: StageStatus? = null,
        override val toStatus: StageStatus? = null,
    ) : HistoryEntry(
        flowId = flowId,
        flowInstanceId = flowInstanceId,
        type = HistoryEntryType.ManualStageChanged,
        occurredAt = occurredAt,
        fromStage = fromStage,
        toStage = toStage,
        fromStatus = fromStatus,
        toStatus = toStatus,
    )

    data class Cancelled(
        override val flowId: String,
        override val flowInstanceId: UUID,
        override val occurredAt: Instant = Instant.now(),
        override val stage: String? = null,
        override val fromStatus: StageStatus? = null,
        override val toStatus: StageStatus? = StageStatus.Cancelled,
    ) : HistoryEntry(
        flowId = flowId,
        flowInstanceId = flowInstanceId,
        type = HistoryEntryType.Cancelled,
        occurredAt = occurredAt,
        stage = stage,
        fromStatus = fromStatus,
        toStatus = toStatus,
    )

    data class Error(
        override val flowId: String,
        override val flowInstanceId: UUID,
        override val occurredAt: Instant = Instant.now(),
        override val stage: String? = null,
        override val fromStatus: StageStatus? = null,
        override val toStatus: StageStatus? = null,
        override val errorType: String? = null,
        override val errorMessage: String? = null,
        override val errorStackTrace: String? = null,
        override val failedAttemptCount: Int? = null,
        override val autoRetryMaxAttempts: Int? = null,
        override val nextAutoRetryAt: Instant? = null,
        override val externalRetryAllowed: Boolean? = null,
    ) : HistoryEntry(
        flowId = flowId,
        flowInstanceId = flowInstanceId,
        type = HistoryEntryType.Error,
        occurredAt = occurredAt,
        stage = stage,
        fromStatus = fromStatus,
        toStatus = toStatus,
        errorType = errorType,
        errorMessage = errorMessage,
        errorStackTrace = errorStackTrace,
        failedAttemptCount = failedAttemptCount,
        autoRetryMaxAttempts = autoRetryMaxAttempts,
        nextAutoRetryAt = nextAutoRetryAt,
        externalRetryAllowed = externalRetryAllowed,
    )
}

object NoopHistoryStore : HistoryStore {
    override fun append(entry: HistoryEntry) = Unit
}

internal fun historyValueOf(value: Any) =
    (value as? Enum<*>)?.name ?: value.toString()

private val historyLog = KotlinLogging.logger {}

// --- Best-effort history recording helpers ---
// These helpers are intentionally best-effort: observability must not block engine progress.
// Keep them internal and at the end of this file.

internal fun HistoryStore.appendBestEffort(entry: HistoryEntry) {
    try {
        append(entry)
    } catch (e: Exception) {
        historyLog.warn(e) { "HistoryStore.append failed for ${entry.flowId}/${entry.flowInstanceId} type=${entry.type}" }
    }
}

internal fun HistoryStore.recordStarted(flowId: String, data: InstanceData<Any>) {
    appendBestEffort(
        HistoryEntry.Started(
            flowId = flowId,
            flowInstanceId = data.flowInstanceId,
            stage = historyValueOf(data.stage),
            toStatus = data.stageStatus,
        ),
    )
}

internal fun HistoryStore.recordCancelled(flowId: String, data: InstanceData<Any>, from: StageStatus) {
    appendBestEffort(
        HistoryEntry.Cancelled(
            flowId = flowId,
            flowInstanceId = data.flowInstanceId,
            stage = historyValueOf(data.stage),
            fromStatus = from,
        ),
    )
}

internal fun HistoryStore.recordEventAppended(flowId: String, flowInstanceId: UUID, event: Event) {
    appendBestEffort(
        HistoryEntry.EventAppended(
            flowId = flowId,
            flowInstanceId = flowInstanceId,
            event = historyValueOf(event),
        ),
    )
}

internal fun HistoryStore.recordStatusChanged(flowId: String, data: InstanceData<Any>, from: StageStatus, to: StageStatus) {
    appendBestEffort(
        HistoryEntry.StatusChanged(
            flowId = flowId,
            flowInstanceId = data.flowInstanceId,
            stage = historyValueOf(data.stage),
            fromStatus = from,
            toStatus = to,
        ),
    )
}

internal fun HistoryStore.recordStageChanged(flowId: String, data: InstanceData<Any>, from: Stage, to: Stage, event: Event? = null) {
    appendBestEffort(
        HistoryEntry.StageChanged(
            flowId = flowId,
            flowInstanceId = data.flowInstanceId,
            fromStage = historyValueOf(from),
            toStage = historyValueOf(to),
            event = event?.let { historyValueOf(it) },
        ),
    )
}

internal fun HistoryStore.recordRetried(flowId: String, data: InstanceData<Any>, trigger: RetryTrigger) {
    appendBestEffort(
        HistoryEntry.Retried(
            flowId = flowId,
            flowInstanceId = data.flowInstanceId,
            stage = historyValueOf(data.stage),
            fromStatus = StageStatus.Error,
            toStatus = data.stageStatus,
            retryTrigger = trigger,
        ),
    )
}

internal fun HistoryStore.recordManualStageChanged(
    flowId: String,
    data: InstanceData<Any>,
    fromStage: Stage,
    toStage: Stage,
    fromStatus: StageStatus,
    toStatus: StageStatus,
) {
    appendBestEffort(
        HistoryEntry.ManualStageChanged(
            flowId = flowId,
            flowInstanceId = data.flowInstanceId,
            fromStage = historyValueOf(fromStage),
            toStage = historyValueOf(toStage),
            fromStatus = fromStatus,
            toStatus = toStatus,
        ),
    )
}

internal fun HistoryStore.recordError(
    flowId: String,
    data: InstanceData<Any>,
    ex: Exception,
    retryState: RetryState? = null,
) {
    appendBestEffort(
        HistoryEntry.Error(
            flowId = flowId,
            flowInstanceId = data.flowInstanceId,
            stage = historyValueOf(data.stage),
            fromStatus = StageStatus.Running,
            toStatus = StageStatus.Error,
            errorType = ex::class.qualifiedName ?: ex::class.java.name,
            errorMessage = ex.message ?: ex.toString(),
            errorStackTrace = ex.stackTraceToString(),
            failedAttemptCount = retryState?.failedAttemptCount,
            autoRetryMaxAttempts = retryState?.autoRetryMaxAttempts,
            nextAutoRetryAt = retryState?.nextAutoRetryAt,
            externalRetryAllowed = retryState?.externalRetryAllowed,
        ),
    )
}
