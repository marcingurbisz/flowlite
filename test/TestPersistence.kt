package io.flowlite.test

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.SchedulerBuilder
import com.github.kagkarlsson.scheduler.task.Task
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import io.flowlite.api.EventStore
import io.flowlite.api.StatePersister
import io.flowlite.api.TickScheduler
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource
import org.springframework.beans.factory.BeanRegistrarDsl
import org.springframework.beans.factory.support.BeanRegistryAdapter
import org.springframework.boot.runApplication
import org.springframework.context.support.GenericApplicationContext
import org.springframework.context.ApplicationContextInitializer

class TestPersistence {
    private val context = runApplication<TestApplication>(
        "--spring.main.web-application-type=none",
    ) {
        addInitializers(
            ApplicationContextInitializer<GenericApplicationContext> { gac ->
                BeanRegistryAdapter(gac, gac, gac.environment, BeanRegistrarDsl::class.java)
                    .register(testRegistrar())
            },
        )
    }

    val dataSource: DataSource = context.getBean(DataSource::class.java)
    private val orderRepo = context.getBean(OrderConfirmationRepository::class.java)
    private val onboardingRepo = context.getBean(EmployeeOnboardingRepository::class.java)
    private val eventRepo = context.getBean(PendingEventRepository::class.java)

    fun orderPersister(): StatePersister<OrderConfirmation> = SpringDataOrderConfirmationPersister(orderRepo)
    fun onboardingPersister(): StatePersister<EmployeeOnboarding> = SpringDataEmployeeOnboardingPersister(onboardingRepo)
    fun eventStore(): EventStore = SpringDataEventStore(eventRepo)
    fun tickScheduler(): DbSchedulerTickScheduler = DbSchedulerTickScheduler(dataSource)

    fun close() = context.close()

}

class DbSchedulerTickScheduler(dataSource: DataSource) : TickScheduler {
    private val taskName = "flowlite-tick"
    private val dispatches = mutableMapOf<Pair<String, UUID>, MutableList<() -> Unit>>()

    private val task: OneTimeTask<String> = Tasks.oneTime(taskName, String::class.java)
        .execute { instance, _ ->
            val parts = instance.data.split("|", limit = 2)
            if (parts.size != 2) return@execute
            val key = parts[0] to UUID.fromString(parts[1])
            val queue = dispatches[key]
            val runnable = queue?.firstOrNull()
            runnable?.invoke()
            if (queue != null && queue.isNotEmpty()) {
                queue.removeAt(0)
                if (queue.isEmpty()) {
                    dispatches.remove(key)
                }
            }
        }

    private val scheduler: Scheduler = SchedulerBuilder(dataSource, listOf<Task<*>>(task))
        .pollingInterval(Duration.ofMillis(10))
        .threads(1)
        .enableImmediateExecution()
        .build()

    init {
        scheduler.start()
    }

    override fun schedule(flowId: String, flowInstanceId: UUID, dispatch: () -> Unit) {
        dispatches.getOrPut(flowId to flowInstanceId) { mutableListOf() }.add(dispatch)
        val data = "$flowId|$flowInstanceId"
        val instanceId = "$flowId|$flowInstanceId|${UUID.randomUUID()}"
        scheduler.schedule(task.schedulableInstance(instanceId, data))
    }

    fun shutdown() {
        scheduler.stop()
    }
}
