package io.flowlite

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder
import org.springframework.context.SmartLifecycle
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository

// --- Tick scheduler ---

@Table("FLOWLITE_TICK")
data class FlowLiteTick(
    @Id
    val id: UUID,
    val flowId: String,
    val flowInstanceId: UUID,
    val notBefore: Instant,
    val targetStage: String? = null,
    @Version
    var version: Long? = null, // Only so Spring Data JDBC treats the aggregate as "new" with an assigned (non-null) id.
)

interface FlowLiteTickRepository : CrudRepository<FlowLiteTick, UUID> {
    @Query(
        """
        select *
        from flowlite_tick
        where not_before <= :now
        order by not_before asc, id asc
        limit :limit
        """,
    )
    fun findDueBatch(now: Instant, limit: Int): List<FlowLiteTick>

    @Query(
        """
        select *
        from flowlite_tick
        where flow_id = :flowId
          and flow_instance_id = :flowInstanceId
          and target_stage = :targetStage
        order by not_before asc, id asc
        limit 1
        """,
    )
    fun findScheduledTick(flowId: String, flowInstanceId: UUID, targetStage: String): FlowLiteTick?
}

class SpringDataJdbcTickScheduler(
    private val tickRepo: FlowLiteTickRepository,
    private val idleDelay: Duration = Duration.ofMillis(1000),
    workerThreads: Int = 60,
    private val clock: Clock = Clock.systemUTC(),
) : TickScheduler, SmartLifecycle {

    private companion object {
        private val log = KotlinLogging.logger {}
    }

    @Volatile private var tickHandler: ((ScheduledTick) -> Unit)? = null
    private val shutdownInitiated = AtomicBoolean(false)

    @Volatile private var pollerThread: Thread? = null

    private val workers = SimpleAsyncTaskExecutorBuilder()
        .threadNamePrefix("flowlite-tick-worker-")
        .concurrencyLimit(workerThreads)
        .taskTerminationTimeout(Duration.ofMinutes(5))
        .build()

    private val batchSize = (workerThreads * 2).coerceAtLeast(1)

    override fun start() {
        pollerThread = Thread.ofVirtual()
            .name("flowlite-tick-poller")
            .start {
                runPollLoop()
            }
    }

    override fun setTickHandler(handler: (ScheduledTick) -> Unit) {
        tickHandler = handler
    }

    override fun scheduleTick(
        flowId: String,
        flowInstanceId: UUID,
        notBefore: Instant,
        targetStage: String?,
    ) {
        tickRepo.save(
            FlowLiteTick(
                id = UUID.randomUUID(),
                flowId = flowId,
                flowInstanceId = flowInstanceId,
                notBefore = notBefore,
                targetStage = targetStage,
            ),
        )
    }

    override fun findScheduledTick(flowId: String, flowInstanceId: UUID, targetStage: String): ScheduledTick? =
        tickRepo.findScheduledTick(flowId, flowInstanceId, targetStage)?.toScheduledTick()

    private fun runPollLoop() {
        val handler = requireNotNull(tickHandler)
        while (!shutdownInitiated.get()) {
            try {
                val ticks = tickRepo.findDueBatch(clock.instant(), batchSize)
                if (ticks.isEmpty()) {
                    Thread.sleep(idleDelay.toMillis())
                    continue
                }
                for (tick in ticks) {
                    if (shutdownInitiated.get()) return
                    try {
                        tickRepo.delete(tick)
                    } catch (_: OptimisticLockingFailureException) {
                        continue
                    } catch (e: Exception) {
                        log.error(e) { "Tick claim failed" }
                        continue
                    }
                    workers.execute { handleTick(handler, tick) }
                }
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                log.error(e) { "Tick poll failed" }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun handleTick(handler: (ScheduledTick) -> Unit, tick: FlowLiteTick) {
        try {
            handler(
                ScheduledTick(
                    flowId = tick.flowId,
                    flowInstanceId = tick.flowInstanceId,
                    notBefore = tick.notBefore,
                    targetStage = tick.targetStage,
                ),
            )
        } catch (e: Exception) {
            log.error(e) { "Tick handler failed for ${tick.flowId}/${tick.flowInstanceId}" }
        }
    }

    override fun stop() {
        log.warn { "Spring invoked stop() even though SmartLifecycle.stop(callback: Runnable) is implemented; this is unexpected" }
        stop {}
    }

    override fun stop(callback: Runnable) {
        shutdownInitiated.set(true)
        Thread.ofVirtual()
            .name("flowlite-tick-scheduler-stop")
            .start {
                try {
                    pollerThread?.interrupt()
                    workers.close()
                    pollerThread?.join()
                } catch (e: Exception) {
                    log.error(e) { "Exception during stopping threads" }
                }
                callback.run()
            }
    }

    override fun isAutoStartup() = true

    override fun isRunning() = pollerThread != null && !shutdownInitiated.get()
}

private fun FlowLiteTick.toScheduledTick() =
    ScheduledTick(
        flowId = flowId,
        flowInstanceId = flowInstanceId,
        notBefore = notBefore,
        targetStage = targetStage,
    )

// --- Event store ---

data class PendingEvent(
    @Id val id: UUID? = null,
    val flowId: String,
    val flowInstanceId: UUID,
    val eventType: String,
    val eventValue: String,
)

interface PendingEventRepository : CrudRepository<PendingEvent, UUID> {
    fun findByFlowIdAndFlowInstanceId(flowId: String, flowInstanceId: UUID): List<PendingEvent>
}

class SpringDataJdbcEventStore(
    private val repo: PendingEventRepository,
) : EventStore {
    override fun append(flowId: String, flowInstanceId: UUID, event: Event) {
        val type = event::class.qualifiedName ?: event::class.java.name
        val value = (event as? Enum<*>)?.name ?: event.toString()
        repo.save(
            PendingEvent(
                id = null,
                flowId = flowId,
                flowInstanceId = flowInstanceId,
                eventType = type,
                eventValue = value,
            ),
        )
    }

    override fun peek(flowId: String, flowInstanceId: UUID, candidates: Collection<Event>): StoredEvent? {
        if (candidates.isEmpty()) return null
        val rows = repo.findByFlowIdAndFlowInstanceId(flowId, flowInstanceId)
        val candidateLookup = candidates.associateBy {
            val type = it::class.qualifiedName ?: it::class.java.name
            val value = (it as? Enum<*>)?.name ?: it.toString()
            type to value
        }
        val match = rows.firstOrNull { row -> candidateLookup.containsKey(row.eventType to row.eventValue) }
            ?: return null
        val id = match.id ?: return null
        val event = candidateLookup[match.eventType to match.eventValue] ?: return null
        return StoredEvent(id = id, event = event)
    }

    override fun delete(eventId: UUID): Boolean {
        val row = repo.findById(eventId).orElse(null) ?: return false
        repo.deleteById(row.id!!)
        return true
    }
}

// --- History store ---

@Table("FLOWLITE_HISTORY")
data class FlowLiteHistoryRow(
    @Id val id: UUID? = null,
    val occurredAt: Instant,
    val flowId: String,
    val flowInstanceId: UUID,
    val type: HistoryEntryType,
    val stage: String? = null,
    val fromStage: String? = null,
    val toStage: String? = null,
    val fromStatus: String? = null,
    val toStatus: String? = null,
    val event: String? = null,
    val errorType: String? = null,
    val errorMessage: String? = null,
    val errorStackTrace: String? = null,
)

@Table("FLOWLITE_INSTANCE_SUMMARY")
data class FlowLiteInstanceSummaryRow(
    @Id val id: UUID? = null,
    val flowId: String,
    val flowInstanceId: UUID,
    val stage: String? = null,
    val status: String,
    val cockpitStatus: String,
    val lastErrorMessage: String? = null,
    val updatedAt: Instant,
)

data class FlowLiteFlowSummaryAggregateRow(
    val flowId: String,
    val activeCount: Int,
    val errorCount: Int,
    val completedCount: Int,
    val notCompletedCount: Int,
    val longRunningCount: Int,
)

data class FlowLiteFlowStageBreakdownRow(
    val flowId: String,
    val stage: String,
    val totalCount: Int,
    val errorCount: Int,
)

interface FlowLiteHistoryRepository : CrudRepository<FlowLiteHistoryRow, UUID> {
    @Query(
        """
        select *
        from flowlite_history
        where flow_id = :flowId and flow_instance_id = :flowInstanceId
        order by occurred_at asc, id asc
        """,
    )
    fun findTimeline(flowId: String, flowInstanceId: UUID): List<FlowLiteHistoryRow>
}

interface FlowLiteInstanceSummaryRepository : CrudRepository<FlowLiteInstanceSummaryRow, UUID> {
    @Query(
        """
        select *
        from flowlite_instance_summary
        where flow_id = :flowId and flow_instance_id = :flowInstanceId
        """,
    )
    fun findSummary(flowId: String, flowInstanceId: UUID): FlowLiteInstanceSummaryRow?

    @Query(
        """
        select *
        from flowlite_instance_summary
        where (:flowId is null or flow_id = :flowId)
          and (
              :bucket is null
              or (:bucket = 'Active' and cockpit_status in ('Running', 'WaitingForTimer', 'WaitingForEvent', 'PendingEngine'))
              or (:bucket = 'Error' and cockpit_status = 'Error')
              or (:bucket = 'Completed' and cockpit_status in ('Completed', 'Cancelled'))
          )
          and (:status is null or cockpit_status = :status)
          and (
              :searchPattern is null
              or lower(flow_id) like :searchPattern
              or lower(cast(flow_instance_id as varchar(36))) like :searchPattern
              or (:searchFlowInstanceId is not null and flow_instance_id = :searchFlowInstanceId)
          )
          and (:stage is null or stage = :stage)
          and (:errorMessagePattern is null or lower(last_error_message) like :errorMessagePattern)
          and (:showIncompleteOnly = false or cockpit_status not in ('Completed', 'Cancelled'))
          and (
              :cockpitStatusFilter is null
              or (:cockpitStatusFilter = 'default' and cockpit_status in ('Running', 'PendingEngine'))
              or cockpit_status = :cockpitStatusFilter
          )
          and (:updatedBefore is null or updated_at < :updatedBefore)
        order by flow_id asc, updated_at desc, flow_instance_id asc
        """,
    )
    fun findFilteredSummaries(
        flowId: String?,
        bucket: String?,
        status: String?,
        searchPattern: String?,
        searchFlowInstanceId: UUID?,
        stage: String?,
        errorMessagePattern: String?,
        showIncompleteOnly: Boolean,
        cockpitStatusFilter: String?,
        updatedBefore: Instant?,
    ): List<FlowLiteInstanceSummaryRow>

    @Query(
        """
        select
            flow_id as flow_id,
            sum(case when cockpit_status in ('Running', 'WaitingForTimer', 'WaitingForEvent', 'PendingEngine') then 1 else 0 end) as active_count,
            sum(case when cockpit_status = 'Error' then 1 else 0 end) as error_count,
            sum(case when cockpit_status in ('Completed', 'Cancelled') then 1 else 0 end) as completed_count,
            sum(case when cockpit_status not in ('Completed', 'Cancelled') then 1 else 0 end) as not_completed_count,
            sum(case when cockpit_status in ('Running', 'PendingEngine') and updated_at < :updatedBefore then 1 else 0 end) as long_running_count
        from flowlite_instance_summary
        group by flow_id
        order by flow_id asc
        """,
    )
    fun findFlowSummaryAggregates(updatedBefore: Instant): List<FlowLiteFlowSummaryAggregateRow>

    @Query(
        """
        select
            flow_id as flow_id,
            stage as stage,
            count(*) as total_count,
            sum(case when status = 'Error' then 1 else 0 end) as error_count
        from flowlite_instance_summary
        where stage is not null
                    and cockpit_status not in ('Completed', 'Cancelled')
        group by flow_id, stage
        order by flow_id asc, stage asc
        """,
    )
    fun findIncompleteStageBreakdown(): List<FlowLiteFlowStageBreakdownRow>
}

class SpringDataJdbcHistoryStore(
    private val repo: FlowLiteHistoryRepository,
    private val summaryRepo: FlowLiteInstanceSummaryRepository,
) : HistoryStore {
    @Volatile
    private var cockpitStatusResolver: ((flowId: String, stage: String?, status: StageStatus?) -> String?)? = null

    fun setCockpitStatusResolver(resolver: (flowId: String, stage: String?, status: StageStatus?) -> String?) {
        cockpitStatusResolver = resolver
    }

    override fun append(entry: HistoryEntry) {
        repo.save(
            FlowLiteHistoryRow(
                id = null,
                occurredAt = entry.occurredAt,
                flowId = entry.flowId,
                flowInstanceId = entry.flowInstanceId,
                type = entry.type,
                stage = entry.stage,
                fromStage = entry.fromStage,
                toStage = entry.toStage,
                fromStatus = entry.fromStatus?.name,
                toStatus = entry.toStatus?.name,
                event = entry.event,
                errorType = entry.errorType,
                errorMessage = entry.errorMessage,
                errorStackTrace = entry.errorStackTrace,
            ),
        )

        if (!entry.affectsSummary()) return

        val existing = summaryRepo.findSummary(entry.flowId, entry.flowInstanceId)
        val next = (existing ?: FlowLiteInstanceSummaryRow(
            id = null,
            flowId = entry.flowId,
            flowInstanceId = entry.flowInstanceId,
            status = StageStatus.Pending.name,
            cockpitStatus = "PendingEngine",
            updatedAt = entry.occurredAt,
        )).apply(entry, cockpitStatusResolver)
        summaryRepo.save(next)
    }
}

private fun HistoryEntry.affectsSummary(): Boolean = type != HistoryEntryType.EventAppended

private fun FlowLiteInstanceSummaryRow.apply(
    entry: HistoryEntry,
    cockpitStatusResolver: ((flowId: String, stage: String?, status: StageStatus?) -> String?)?,
): FlowLiteInstanceSummaryRow {
    val nextStage = when (entry.type) {
        HistoryEntryType.Started,
        HistoryEntryType.StatusChanged,
        HistoryEntryType.Retried,
        HistoryEntryType.Cancelled,
        HistoryEntryType.Error,
        -> entry.stage ?: stage
        HistoryEntryType.StageChanged,
        HistoryEntryType.ManualStageChanged,
        -> entry.toStage ?: stage
        HistoryEntryType.EventAppended -> stage
    }

    val nextStatus = when (entry.type) {
        HistoryEntryType.Started,
        HistoryEntryType.StatusChanged,
        HistoryEntryType.Retried,
        HistoryEntryType.Cancelled,
        HistoryEntryType.Error,
        HistoryEntryType.ManualStageChanged,
        -> entry.toStatus?.name ?: status
        HistoryEntryType.StageChanged,
        HistoryEntryType.EventAppended,
        -> status
    }

    val nextStatusValue = runCatching { StageStatus.valueOf(nextStatus) }.getOrNull()
    val nextCockpitStatus = cockpitStatusResolver?.invoke(entry.flowId, nextStage, nextStatusValue) ?: cockpitStatus

    val nextErrorMessage = when {
        entry.type == HistoryEntryType.Error -> entry.errorMessage
        nextStatus == StageStatus.Error.name -> lastErrorMessage
        else -> null
    }

    return copy(
        stage = nextStage,
        status = nextStatus,
        cockpitStatus = nextCockpitStatus,
        lastErrorMessage = nextErrorMessage,
        updatedAt = entry.occurredAt,
    )
}

fun FlowLiteHistoryRow.toHistoryEntry() =
    when (type) {
        HistoryEntryType.Started -> HistoryEntry.Started(
            flowId = flowId,
            flowInstanceId = flowInstanceId,
            occurredAt = occurredAt,
            stage = stage,
            toStatus = toStatus?.let { runCatching { StageStatus.valueOf(it) }.getOrNull() },
        )
        HistoryEntryType.EventAppended -> HistoryEntry.EventAppended(
            flowId = flowId,
            flowInstanceId = flowInstanceId,
            occurredAt = occurredAt,
            event = event,
        )
        HistoryEntryType.StatusChanged -> HistoryEntry.StatusChanged(
            flowId = flowId,
            flowInstanceId = flowInstanceId,
            occurredAt = occurredAt,
            stage = stage,
            fromStatus = fromStatus?.let { runCatching { StageStatus.valueOf(it) }.getOrNull() },
            toStatus = toStatus?.let { runCatching { StageStatus.valueOf(it) }.getOrNull() },
        )
        HistoryEntryType.StageChanged -> HistoryEntry.StageChanged(
            flowId = flowId,
            flowInstanceId = flowInstanceId,
            occurredAt = occurredAt,
            fromStage = fromStage,
            toStage = toStage,
            event = event,
        )
        HistoryEntryType.Retried -> HistoryEntry.Retried(
            flowId = flowId,
            flowInstanceId = flowInstanceId,
            occurredAt = occurredAt,
            stage = stage,
            fromStatus = fromStatus?.let { runCatching { StageStatus.valueOf(it) }.getOrNull() },
            toStatus = toStatus?.let { runCatching { StageStatus.valueOf(it) }.getOrNull() },
        )
        HistoryEntryType.ManualStageChanged -> HistoryEntry.ManualStageChanged(
            flowId = flowId,
            flowInstanceId = flowInstanceId,
            occurredAt = occurredAt,
            fromStage = fromStage,
            toStage = toStage,
            fromStatus = fromStatus?.let { runCatching { StageStatus.valueOf(it) }.getOrNull() },
            toStatus = toStatus?.let { runCatching { StageStatus.valueOf(it) }.getOrNull() },
        )
        HistoryEntryType.Cancelled -> HistoryEntry.Cancelled(
            flowId = flowId,
            flowInstanceId = flowInstanceId,
            occurredAt = occurredAt,
            stage = stage,
            fromStatus = fromStatus?.let { runCatching { StageStatus.valueOf(it) }.getOrNull() },
            toStatus = toStatus?.let { runCatching { StageStatus.valueOf(it) }.getOrNull() },
        )
        HistoryEntryType.Error -> HistoryEntry.Error(
            flowId = flowId,
            flowInstanceId = flowInstanceId,
            occurredAt = occurredAt,
            stage = stage,
            fromStatus = fromStatus?.let { runCatching { StageStatus.valueOf(it) }.getOrNull() },
            toStatus = toStatus?.let { runCatching { StageStatus.valueOf(it) }.getOrNull() },
            errorType = errorType,
            errorMessage = errorMessage,
            errorStackTrace = errorStackTrace,
        )
    }
