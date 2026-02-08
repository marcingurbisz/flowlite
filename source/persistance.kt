package io.flowlite

import java.util.UUID

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
