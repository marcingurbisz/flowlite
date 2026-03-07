package io.flowlite.test

import io.flowlite.Engine
import io.flowlite.FlowLiteHistoryRepository
import io.flowlite.FlowLiteTickRepository
import io.flowlite.PendingEventRepository
import io.flowlite.SpringDataJdbcEventStore
import io.flowlite.SpringDataJdbcHistoryStore
import io.flowlite.SpringDataJdbcTickScheduler
import io.flowlite.cockpit.CockpitUiStaticConfig
import io.flowlite.cockpit.CockpitService
import io.flowlite.cockpit.cockpitRouter
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import io.kotest.core.listeners.ProjectListener
import org.springframework.beans.factory.BeanRegistrar
import org.springframework.beans.factory.BeanRegistrarDsl
import org.springframework.beans.factory.support.BeanRegistryAdapter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.env.Environment
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories
import org.springframework.data.relational.core.mapping.NamingStrategy
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import javax.sql.DataSource

@SpringBootApplication
@EnableJdbcRepositories(basePackages = ["io.flowlite"])
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
            initializeTestSchema(ds, TestDatabaseDialect.H2)
            ds
        }

        registerBean {
            NamedParameterJdbcTemplate(bean<DataSource>())
        }

        registerBean {
            SpringDataJdbcTickScheduler(bean<FlowLiteTickRepository>())
        }

        registerBean {
            SpringDataJdbcEventStore(bean<PendingEventRepository>())
        }

        registerBean {
            SpringDataJdbcHistoryStore(bean<FlowLiteHistoryRepository>())
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

        registerBean {
            io.flowlite.MermaidGenerator()
        }

        registerBean<Engine> {
            val eventStore = bean<SpringDataJdbcEventStore>()
            val tickScheduler = bean<SpringDataJdbcTickScheduler>()
            val historyStore = bean<SpringDataJdbcHistoryStore>()
            val orderPersister = bean<SpringDataOrderConfirmationPersister>()
            val onboardingPersister = bean<SpringDataEmployeeOnboardingPersister>()
            val onboardingActions = bean<EmployeeOnboardingActions>()

            Engine(eventStore = eventStore, tickScheduler = tickScheduler, historyStore = historyStore).also { engine ->
                engine.registerFlow(ORDER_CONFIRMATION_FLOW_ID, createOrderConfirmationFlow(), orderPersister)
                engine.registerFlow(EMPLOYEE_ONBOARDING_FLOW_ID, createEmployeeOnboardingFlow(onboardingActions), onboardingPersister)
            }
        }

        registerBean {
            val environment = bean<Environment>()
            ShowcaseFlowSeeder(
                engine = bean<Engine>(),
                enabled = environment.getProperty("flowlite.showcase.enabled", Boolean::class.java, false),
                maxActionDelayMs = environment.getProperty("flowlite.showcase.max-action-delay-ms", Long::class.java, 60_000L),
                actionFailureRate = environment.getProperty("flowlite.showcase.action-failure-rate", Double::class.java, 0.1),
            )
        }

        registerBean {
            CockpitService(
                engine = bean<Engine>(),
                mermaid = bean<io.flowlite.MermaidGenerator>(),
                historyRepo = bean<FlowLiteHistoryRepository>(),
            )
        }

        registerBean {
            CockpitUiStaticConfig()
        }

        registerBean<RouterFunction<ServerResponse>> {
            cockpitRouter(bean<CockpitService>())
        }
    }
}

private fun startApplication(
    webType: String,
    showcaseEnabled: Boolean = webType == "servlet",
) = runApplication<TestApplication>(
    "--spring.main.web-application-type=$webType",
    "--flowlite.showcase.enabled=$showcaseEnabled",
) {
    addInitializers(
        ApplicationContextInitializer<GenericApplicationContext> { gac ->
            BeanRegistryAdapter(gac, gac, gac.environment, BeanRegistrarDsl::class.java)
                .register(Beans.registrar())
        },
    )
}

object ShowcaseActionBehavior {
    private data class Config(
        val enabled: Boolean,
        val maxDelayMs: Long,
        val failureRate: Double,
    )

    private val configRef = AtomicReference(Config(enabled = false, maxDelayMs = 0L, failureRate = 0.0))

    fun configure(enabled: Boolean, maxDelayMs: Long, failureRate: Double) {
        configRef.set(
            Config(
                enabled = enabled,
                maxDelayMs = maxDelayMs.coerceAtLeast(0),
                failureRate = failureRate.coerceIn(0.0, 1.0),
            ),
        )
    }

    fun apply(actionName: String, isShowcaseInstance: Boolean) {
        val config = configRef.get()
        if (!config.enabled || !isShowcaseInstance) return

        val delayMs = if (config.maxDelayMs == 0L) 0L else ThreadLocalRandom.current().nextLong(config.maxDelayMs + 1)
        if (delayMs > 0) {
            showcaseLog.info { "Showcase delay for action '$actionName': ${delayMs}ms" }
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }

        if (config.failureRate > 0.0 && ThreadLocalRandom.current().nextDouble() < config.failureRate) {
            throw IllegalStateException("Showcase simulated failure in action '$actionName'")
        }
    }
}

private val showcaseLog = KotlinLogging.logger {}

private class ShowcaseFlowSeeder(
    private val engine: Engine,
    enabled: Boolean,
    maxActionDelayMs: Long,
    actionFailureRate: Double,
) : AutoCloseable {
    private val sequence = AtomicLong(0)
    private val executor =
        if (enabled) {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "flowlite-showcase-seeder").apply { isDaemon = true }
            }
        } else {
            null
        }

    init {
        ShowcaseActionBehavior.configure(
            enabled = enabled,
            maxDelayMs = maxActionDelayMs,
            failureRate = actionFailureRate,
        )

        if (enabled) {
            seedOnce()
            executor?.scheduleAtFixedRate(::seedOnceSafely, 5, 5, TimeUnit.SECONDS)
        }
    }

    private fun seedOnceSafely() {
        runCatching { seedOnce() }
    }

    private fun seedOnce() {
        val index = sequence.incrementAndGet()
        val confirmationType = if (index % 2L == 0L) ConfirmationType.Digital else ConfirmationType.Physical

        val order = OrderConfirmation(
            stage = OrderConfirmationStage.InitializingConfirmation,
            orderNumber = "SHOW-$index",
            confirmationType = confirmationType,
            customerName = "Showcase Customer $index",
        )
        val orderId = engine.startInstance(ORDER_CONFIRMATION_FLOW_ID, order)
        engine.sendEvent(ORDER_CONFIRMATION_FLOW_ID, orderId, OrderConfirmationEvent.Confirmed)

        val employee = EmployeeOnboarding(
            stage = EmployeeStage.CreateEmployeeProfile,
            isOnboardingAutomated = true,
            needsTrainingProgram = true,
            isEngineeringRole = index % 2L == 0L,
            isFullSecuritySetup = index % 3L == 0L,
            wereDocumentsSignedPhysically = index % 2L != 0L,
            isNotManualPath = true,
            isExecutiveOrManagement = index % 4L == 0L,
            hasComplianceChecks = index % 5L == 0L,
            isNotContractor = true,
            isShowcaseInstance = true,
        )
        val employeeId = engine.startInstance(EMPLOYEE_ONBOARDING_FLOW_ID, employee)
        engine.sendEvent(EMPLOYEE_ONBOARDING_FLOW_ID, employeeId, EmployeeEvent.ContractSigned)
        engine.sendEvent(EMPLOYEE_ONBOARDING_FLOW_ID, employeeId, EmployeeEvent.OnboardingAgreementSigned)
        engine.sendEvent(EMPLOYEE_ONBOARDING_FLOW_ID, employeeId, EmployeeEvent.ComplianceComplete)
    }

    override fun close() {
        executor?.shutdownNow()
    }
}

fun startTestApplication() = startApplication("none")

fun startTestWebApplication(showcaseEnabled: Boolean = true) = startApplication("servlet", showcaseEnabled = showcaseEnabled)

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

object TestApplicationExtension : ProjectListener {
    @Volatile var context: ConfigurableApplicationContext? = null

    fun context(): ConfigurableApplicationContext {
        val existing = context
        if (existing != null) return existing

        return synchronized(this) {
            val recheck = context
            if (recheck != null) return recheck
            val started = startTestApplication()
            context = started
            started
        }
    }

    override suspend fun beforeProject() {
        context()
    }

    override suspend fun afterProject() {
        synchronized(this) {
            context?.close()
            context = null
        }
    }
}
