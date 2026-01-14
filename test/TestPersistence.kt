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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

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
    private val jdbc: NamedParameterJdbcTemplate = context.getBean(NamedParameterJdbcTemplate::class.java)

    private val orderRepo = context.getBean(OrderConfirmationRepository::class.java)
    private val onboardingRepo = context.getBean(EmployeeOnboardingRepository::class.java)
    private val eventRepo = context.getBean(PendingEventRepository::class.java)

    init {
        createTables()
    }

    fun orderPersister(): StatePersister<OrderConfirmation> = SpringDataOrderConfirmationPersister(orderRepo)
    fun onboardingPersister(): StatePersister<EmployeeOnboarding> = SpringDataEmployeeOnboardingPersister(onboardingRepo)
    fun eventStore(): EventStore = SpringDataEventStore(eventRepo)
    fun tickScheduler(): DbSchedulerTickScheduler = DbSchedulerTickScheduler(dataSource)

    fun close() = context.close()

    private fun createTables() {
        jdbc.jdbcTemplate.execute(
            """
            create table if not exists order_confirmation (
                id uuid default random_uuid() primary key,
                version bigint not null default 0,
                stage varchar(128) not null,
                stage_status varchar(32) not null default 'PENDING',
                order_number varchar(128) not null,
                confirmation_type varchar(32) not null,
                customer_name varchar(128) not null,
                is_removed_from_queue boolean not null,
                is_customer_informed boolean not null,
                confirmation_timestamp varchar(64) not null
            );
            """.trimIndent(),
        )

        jdbc.jdbcTemplate.execute(
            """
            create table if not exists employee_onboarding (
                id uuid default random_uuid() primary key,
                version bigint not null default 0,
                stage varchar(128),
                stage_status varchar(32) not null default 'PENDING',
                is_onboarding_automated boolean not null,
                is_contract_signed boolean not null,
                is_executive_role boolean not null,
                is_security_clearance_required boolean not null,
                is_full_onboarding_required boolean not null,
                is_manager_or_director_role boolean not null,
                is_remote_employee boolean not null,
                user_created_in_system boolean not null,
                employee_activated boolean not null,
                security_clearance_updated boolean not null,
                department_access_set boolean not null,
                documents_generated boolean not null,
                contract_sent_for_signing boolean not null,
                status_updated_in_hr boolean not null
            );
            """.trimIndent(),
        )

        jdbc.jdbcTemplate.execute(
            """
            create table if not exists pending_event (
                id uuid default random_uuid() primary key,
                flow_id varchar(128) not null,
                flow_instance_id uuid not null,
                event_type varchar(256) not null,
                event_value varchar(256) not null
            );
            """.trimIndent(),
        )

        jdbc.jdbcTemplate.execute(
            """
            create table if not exists scheduled_tasks (
                task_name varchar(100) not null,
                task_instance varchar(100) not null,
                task_data blob,
                execution_time timestamp not null,
                picked boolean not null,
                picked_by varchar(50),
                last_success timestamp,
                last_failure timestamp,
                consecutive_failures int,
                last_heartbeat timestamp,
                version bigint not null,
                created_at timestamp default current_timestamp not null,
                last_updated_at timestamp default current_timestamp not null,
                primary key (task_name, task_instance)
            );
            """.trimIndent(),
        )

        jdbc.jdbcTemplate.execute(
            """
            create index if not exists idx_scheduled_tasks_execution_time on scheduled_tasks(execution_time);
            """.trimIndent(),
        )
    }
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
