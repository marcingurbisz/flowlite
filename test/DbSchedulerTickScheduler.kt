package io.flowlite.test

import io.flowlite.api.TickScheduler
import io.flowlite.api.TickRedeliveryRequestedException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository

@Table("FLOWLITE_TICK")
data class FlowLiteTick(
    @Id
    val id: UUID? = null,
    val flowId: String,
    val flowInstanceId: UUID,
)

interface FlowLiteTickRepository : CrudRepository<FlowLiteTick, UUID>

class DbSchedulerTickScheduler(
    private val tickRepo: FlowLiteTickRepository,
) : TickScheduler, AutoCloseable {

    private var tickHandler: ((String, UUID) -> Unit)? = null
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
        Thread.ofVirtual().name("flowlite-tick-worker-", 0).factory(),
    )

    fun start() {
        if (!started.compareAndSet(false, true)) return
        executor.scheduleWithFixedDelay(
            { pollOnce() },
            0,
            10,
            TimeUnit.MILLISECONDS,
        )
    }

    override fun setTickHandler(handler: (String, UUID) -> Unit) {
        tickHandler = handler
    }

    override fun scheduleTick(flowId: String, flowInstanceId: UUID) {
        if (closed.get()) return

        tickRepo.save(
            FlowLiteTick(
                flowId = flowId,
                flowInstanceId = flowInstanceId,
            ),
        )
    }

    private fun pollOnce() {
        val handler = tickHandler ?: return
        if (closed.get()) return

        val iterator = tickRepo.findAll().iterator()
        if (!iterator.hasNext()) return
        val tick = iterator.next()

        try {
            tickRepo.deleteById(tick.id)
        } catch (_: Exception) {
            return
        }

        try {
            handler(tick.flowId, tick.flowInstanceId)
        } catch (t: Throwable) {
            when (t) {
                is TickRedeliveryRequestedException -> {
                    logger.debug("Tick redelivery requested for {}/{}: {}", tick.flowId, tick.flowInstanceId, t.message)
                    scheduleTick(tick.flowId, tick.flowInstanceId)
                }
                else -> {
                    logger.error("Tick handler failed for {}/{}", tick.flowId, tick.flowInstanceId, t)
                }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdownNow()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DbSchedulerTickScheduler::class.java)
    }
}
