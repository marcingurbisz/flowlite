package io.flowlite.test

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.SchedulerBuilder
import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.exceptions.TaskInstanceCurrentlyExecutingException
import com.github.kagkarlsson.scheduler.task.Task
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import io.flowlite.api.TickScheduler
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

class DbSchedulerTickScheduler(dataSource: DataSource) : TickScheduler, AutoCloseable {
    private val taskName = "flowlite-tick"
    private var tickHandler: ((String, UUID) -> Unit)? = null

    private val task: OneTimeTask<String> = Tasks.oneTime(taskName, String::class.java)
        .execute { instance, _ ->
            val handler = tickHandler ?: return@execute
            val parts = instance.data.split("|", limit = 2)
            if (parts.size != 2) return@execute
            val flowId = parts[0]
            val flowInstanceId = UUID.fromString(parts[1])
            handler(flowId, flowInstanceId)
        }

    private val scheduler: Scheduler = SchedulerBuilder(dataSource, listOf<Task<*>>(task))
        .pollingInterval(Duration.ofMillis(10))
        .threads(4)
        .enableImmediateExecution()
        .build()

    fun start() {
        scheduler.start()
    }

    override fun setTickHandler(handler: (String, UUID) -> Unit) {
        tickHandler = handler
    }

    override fun scheduleTick(flowId: String, flowInstanceId: UUID) {
        val data = "$flowId|$flowInstanceId"
        // Single-flight across JVMs: coalesce all ticks for this instance into a single db-scheduler task instance.
        // WHEN_EXISTS_RESCHEDULE ensures that if a tick is already scheduled, we bring execution_time forward.
        val instanceId = "$flowId|$flowInstanceId"
        val schedulable = task.schedulableInstance(instanceId, data)

        // Important: db-scheduler explicitly forbids modifying a picked (currently executing) execution.
        // If we just swallow that exception, we may lose a wakeup for an event that arrived after the
        // running tick checked the mailbox and returned. To avoid "lost tick" in tests, we retry for
        // a short bounded time until the execution finishes and the row becomes schedulable again.
        val deadlineNanos = System.nanoTime() + Duration.ofMillis(750).toNanos()
        var backoffMillis = 5L
        while (true) {
            try {
                scheduler.schedule(schedulable, SchedulerClient.ScheduleOptions.WHEN_EXISTS_RESCHEDULE)
                return
            } catch (_: TaskInstanceCurrentlyExecutingException) {
                if (System.nanoTime() >= deadlineNanos) return
                try {
                    Thread.sleep(backoffMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                if (backoffMillis < 50L) backoffMillis += 5L
            } catch (_: Throwable) {
                // best-effort
                return
            }
        }
    }

    override fun close() {
        scheduler.stop()
    }
}
