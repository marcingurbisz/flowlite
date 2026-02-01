package io.flowlite.test

import io.flowlite.api.TickScheduler
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder

@Table("FLOWLITE_TICK")
data class FlowLiteTick(
    @Id
    val id: UUID,
    val flowId: String,
    val flowInstanceId: UUID,
    @Version
    var version: Long? = null, //only so Spring Data JDBC treats the aggregate as "new" when we use an assigned (non-null) id.
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

    @Modifying
    @Query(
        """
        delete from flowlite_tick
        where id = :id
        """,
    )
    fun tryDeleteById(id: UUID): Int
}

class DbTickScheduler(
    private val tickRepo: FlowLiteTickRepository,
    private val idleDelay: Duration = Duration.ofMillis(1000),
    workerThreads: Int = 4,
) : TickScheduler, AutoCloseable {

    @Volatile private var tickHandler: ((String, UUID) -> Unit)? = null
    private val closed = AtomicBoolean(false)

    @Volatile private var pollerThread: Thread? = null

    private val workers = SimpleAsyncTaskExecutorBuilder()
        .threadNamePrefix("flowlite-tick-worker-")
        .concurrencyLimit(workerThreads)
        .build()

    private val batchSize = (workerThreads * 2).coerceAtLeast(1)

    fun start() {
        pollerThread = Thread.ofVirtual()
            .name("flowlite-tick-poller")
            .start {
                runPollLoop()
            }
    }

    override fun setTickHandler(handler: (String, UUID) -> Unit) {
        tickHandler = handler
        start()
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
        while (!closed.get()) {
            try {
                val ticks = tickRepo.findNextBatch(batchSize)
                if (ticks.isEmpty()) {
                    Thread.sleep(idleDelay.toMillis())
                    continue
                }
                for (tick in ticks) {
                    if (closed.get()) return
                    val deleted = try {
                        tickRepo.tryDeleteById(tick.id)
                    } catch (_: Exception) {
                        0
                    }
                    if (deleted != 1) continue
                    workers.execute { handleTick(requireNotNull(tickHandler), tick) }
                }
            } catch (_: InterruptedException) {
                return
            } catch (t: Throwable) {
                log.error(t) { "Tick poll failed" }
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
        } catch (t: Throwable) {
            log.error(t) { "Tick handler failed for ${tick.flowId}/${tick.flowInstanceId}" }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pollerThread?.interrupt()
        // Best-effort: worker threads are per-task and should terminate after completion.
        // Give in-flight tasks a short grace period.
        Thread.sleep(TimeUnit.MILLISECONDS.toMillis(100))
    }
}

private val log = KotlinLogging.logger {}
