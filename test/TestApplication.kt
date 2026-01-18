package io.flowlite.test

import io.flowlite.api.EventStore
import io.flowlite.api.StatePersister
import java.util.UUID
import javax.sql.DataSource
import org.springframework.beans.factory.BeanRegistrar
import org.springframework.beans.factory.BeanRegistrarDsl
import org.springframework.beans.factory.support.BeanRegistryAdapter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.support.GenericApplicationContext
import org.springframework.context.ApplicationContextInitializer
import org.springframework.data.relational.core.mapping.NamingStrategy
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource

@SpringBootApplication
open class TestApplication

fun testRegistrar(): BeanRegistrar = BeanRegistrarDsl {
    registerBean<NamingStrategy> {
        SnakeCaseNamingStrategy()
    }
    registerBean<DataSource> {
        SingleConnectionDataSource(
            "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
            true,
        )
    }
    registerBean<NamedParameterJdbcTemplate> {
        NamedParameterJdbcTemplate(bean<DataSource>())
    }
}

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
