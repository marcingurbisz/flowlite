package io.flowlite.impl.springdatajdbc

import io.flowlite.api.Event
import io.flowlite.api.EventStore
import io.flowlite.api.StoredEvent
import io.flowlite.api.TickScheduler
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
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

@Table("FLOWLITE_TICK")
data class FlowLiteTick(
    @Id
    val id: UUID,
    val flowId: String,
    val flowInstanceId: UUID,
    @Version
    var version: Long? = null, // Only so Spring Data JDBC treats the aggregate as "new" with an assigned (non-null) id.
)

interface FlowLiteTickRepository : CrudRepository<FlowLiteTick, UUID> {
    @Query(
        """
        select *
        from flowlite_tick
        order by id asc
        limit :limit
        """,
    )
    fun findNextBatch(limit: Int): List<FlowLiteTick>
}

class SpringDataJdbcTickScheduler(
    private val tickRepo: FlowLiteTickRepository,
    private val idleDelay: Duration = Duration.ofMillis(1000),
    workerThreads: Int = 4,
) : TickScheduler, SmartLifecycle {

    private companion object {
        private val log = KotlinLogging.logger {}
    }

    @Volatile private var tickHandler: ((String, UUID) -> Unit)? = null
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

    override fun setTickHandler(handler: (String, UUID) -> Unit) {
        tickHandler = handler
    }

    override fun scheduleTick(flowId: String, flowInstanceId: UUID) {
        tickRepo.save(
            FlowLiteTick(
                id = UUID.randomUUID(),
                flowId = flowId,
                flowInstanceId = flowInstanceId,
            ),
        )
    }

    private fun runPollLoop() {
        val handler = requireNotNull(tickHandler)
        while (!shutdownInitiated.get()) {
            try {
                val ticks = tickRepo.findNextBatch(batchSize)
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

    private fun handleTick(handler: (String, UUID) -> Unit, tick: FlowLiteTick) {
        try {
            handler(tick.flowId, tick.flowInstanceId)
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

    override fun isAutoStartup(): Boolean = true

    override fun isRunning(): Boolean = pollerThread != null && !shutdownInitiated.get()
}

// --- Event store sample ---

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
