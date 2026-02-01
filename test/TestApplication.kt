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
import org.springframework.jdbc.datasource.DriverManagerDataSource
import javax.sql.DataSource

@SpringBootApplication
open class TestApplication

object Beans {
    fun registrar(): BeanRegistrar = BeanRegistrarDsl {
        registerBean<NamingStrategy> {
            SnakeCaseNamingStrategy()
        }

        registerBean<DataSource> {
            val dbName = UUID.randomUUID()
            val url = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1"
            val ds = DriverManagerDataSource(url)
            val jdbc = NamedParameterJdbcTemplate(ds)
            createOrderConfirmationTable(jdbc)
            createEmployeeOnboardingTable(jdbc)
            createPendingEventTable(jdbc)
            createTickTable(jdbc)
            ds
        }

        registerBean {
            NamedParameterJdbcTemplate(bean<DataSource>())
        }

        registerBean<DbTickScheduler> {
            DbTickScheduler(bean<FlowLiteTickRepository>())
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
            SpringDataEmployeeOnboardingPersister(
                repo = bean<EmployeeOnboardingRepository>(),
            )
        }

        registerBean<FlowEngine> {
            val eventStore = bean<SpringDataEventStore>()
            val tickScheduler = bean<DbTickScheduler>()
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

private fun createTickTable(jdbc: NamedParameterJdbcTemplate) {
    jdbc.jdbcTemplate.execute(
        """
        create table if not exists flowlite_tick (
            id uuid not null primary key,
            flow_id varchar(128) not null,
            flow_instance_id uuid not null,
            version bigint
        );
        """.trimIndent(),
    )

    jdbc.jdbcTemplate.execute(
        """
        create index if not exists idx_flowlite_tick_process on flowlite_tick(flow_id, flow_instance_id);
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
