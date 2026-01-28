package io.flowlite.test

import io.flowlite.api.FlowEngine
import java.util.UUID
import org.springframework.beans.factory.BeanRegistrar
import org.springframework.beans.factory.BeanRegistrarDsl
import org.springframework.beans.factory.support.BeanRegistryAdapter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.support.GenericApplicationContext
import org.springframework.data.relational.core.mapping.NamingStrategy
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import javax.sql.DataSource

@SpringBootApplication
open class TestApplication

object Beans {
    fun registrar(): BeanRegistrar = BeanRegistrarDsl {
        registerBean<NamingStrategy> {
            SnakeCaseNamingStrategy()
        }

        registerBean<DataSource> {
            val ds = SingleConnectionDataSource(
                "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
                true,
            )
            val jdbc = NamedParameterJdbcTemplate(ds)
            createOrderConfirmationTable(jdbc)
            createEmployeeOnboardingTable(jdbc)
            createPendingEventTable(jdbc)
            createScheduledTasksTable(jdbc)
            ds
        }

        registerBean<DbSchedulerTickScheduler> {
            DbSchedulerTickScheduler(bean()).also { it.start() }
        }

        registerBean {
            SpringDataEventStore(bean<PendingEventRepository>())
        }

        registerBean {
            SpringDataOrderConfirmationPersister(bean<OrderConfirmationRepository>())
        }

        registerBean {
            EmployeeOnboardingActions(bean<EmployeeOnboardingRepository>())
        }

        registerBean {
            SpringDataEmployeeOnboardingPersister(bean<EmployeeOnboardingRepository>())
        }

        registerBean<FlowEngine> {
            val eventStore = bean<SpringDataEventStore>()
            val tickScheduler = bean<DbSchedulerTickScheduler>()
            val orderPersister = bean<SpringDataOrderConfirmationPersister>()
            val onboardingPersister = bean<SpringDataEmployeeOnboardingPersister>()
            val onboardingActions = bean<EmployeeOnboardingActions>()

            FlowEngine(eventStore = eventStore, tickScheduler = tickScheduler).also { engine ->
                engine.registerFlow(ORDER_CONFIRMATION_FLOW_ID, createOrderConfirmationFlow(), orderPersister)
                engine.registerFlow(EMPLOYEE_ONBOARDING_FLOW_ID, createEmployeeOnboardingFlow(onboardingActions), onboardingPersister)
            }
        }
    }
}

fun startTestApplication() = runApplication<TestApplication>(
    "--spring.main.web-application-type=none",
) {
    addInitializers(
        ApplicationContextInitializer<GenericApplicationContext> { gac ->
            BeanRegistryAdapter(gac, gac, gac.environment, BeanRegistrarDsl::class.java)
                .register(Beans.registrar())
        },
    )
}

class SnakeCaseNamingStrategy : NamingStrategy {
    override fun getColumnName(property: RelationalPersistentProperty): String = property.name.toSnakeCase()
    override fun getTableName(type: Class<*>): String = type.simpleName.toSnakeCase()
}

private fun String.toSnakeCase(): String {
    val sb = StringBuilder()
    for (i in this.indices) {
        val c = this[i]
        if (c.isUpperCase()) {
            if (i != 0) sb.append('_')
            sb.append(c.lowercaseChar())
        } else {
            sb.append(c)
        }
    }
    return sb.toString()
        .replace(Regex("_h_r\\b"), "_hr")
}

private fun createScheduledTasksTable(jdbc: NamedParameterJdbcTemplate) {
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

private fun createPendingEventTable(jdbc: NamedParameterJdbcTemplate) {
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
}

private fun createOrderConfirmationTable(jdbc: NamedParameterJdbcTemplate) {
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
}

private fun createEmployeeOnboardingTable(jdbc: NamedParameterJdbcTemplate) {
    jdbc.jdbcTemplate.execute(
        """
        create table if not exists employee_onboarding (
            id uuid default random_uuid() primary key,
            version bigint not null default 0,
            stage varchar(128) not null,
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
}
