package io.flowlite.test

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.SchedulerBuilder
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
        .threads(1)
        .enableImmediateExecution()
        .build()

    init {
        createScheduledTasksTable(dataSource)
        scheduler.start()
    }

    override fun setTickHandler(handler: (String, UUID) -> Unit) {
        tickHandler = handler
    }

    override fun scheduleTick(flowId: String, flowInstanceId: UUID) {
        val data = "$flowId|$flowInstanceId"
        val instanceId = "$flowId|$flowInstanceId|${UUID.randomUUID()}"
        scheduler.schedule(task.schedulableInstance(instanceId, data))
    }

    override fun close() {
        scheduler.stop()
    }
}
