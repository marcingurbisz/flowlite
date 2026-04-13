package io.flowlite.test

import io.flowlite.Engine
import io.flowlite.Event
import io.flowlite.FlowLiteHistoryRepository
import io.flowlite.FlowLiteInstanceSummaryRepository
import io.flowlite.FlowLiteTickRepository
import io.flowlite.PendingEventRepository
import io.flowlite.StageStatus
import io.flowlite.SpringDataJdbcEventStore
import io.flowlite.SpringDataJdbcHistoryStore
import io.flowlite.SpringDataJdbcTickScheduler
import io.flowlite.historyValueOf
import io.flowlite.cockpit.CockpitUiStaticConfig
import io.flowlite.cockpit.CockpitService
import io.flowlite.cockpit.classifyCockpitActivityStatus
import io.flowlite.cockpit.cockpitRouter
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
import org.springframework.core.env.getProperty
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

        registerBean<AdjustableClock> {
            AdjustableClock.systemUTC()
        }

        registerBean {
            SpringDataJdbcTickScheduler(
                tickRepo = bean<FlowLiteTickRepository>(),
                clock = bean<AdjustableClock>(),
            )
        }

        registerBean {
            SpringDataJdbcEventStore(bean<PendingEventRepository>())
        }

        registerBean {
            SpringDataJdbcHistoryStore(bean<FlowLiteHistoryRepository>(), bean<FlowLiteInstanceSummaryRepository>())
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

            Engine(
                eventStore = eventStore,
                tickScheduler = tickScheduler,
                historyStore = historyStore,
                clock = bean<AdjustableClock>(),
            ).also { engine ->
                engine.registerFlow(ORDER_CONFIRMATION_FLOW_ID, createOrderConfirmationFlow(), orderPersister)
                engine.registerFlow(EMPLOYEE_ONBOARDING_FLOW_ID, createEmployeeOnboardingFlow(onboardingActions), onboardingPersister)
                historyStore.setActivityStatusResolver { flowId, stage, status ->
                    val stageDefinitions = engine.registeredFlows()[flowId]
                        ?.stages
                        ?.entries
                        ?.associate { entry -> historyValueOf(entry.key) to entry.value }
                    classifyCockpitActivityStatus(stageDefinitions, stage, status)?.name
                }
                historyStore.refreshActivityStatuses()
            }
        }

        registerBean {
            val environment = bean<Environment>()
            ShowcaseFlowSeeder(
                engine = bean<Engine>(),
                enabled = environment.getProperty<Boolean>("flowlite.showcase.enabled", false),
                initialSeedCount = environment.getProperty<Int>("flowlite.showcase.initial-seed-count", 1),
                repeatSeedingEnabled = environment.getProperty<Boolean>("flowlite.showcase.repeat-seeding-enabled", true),
                maxActionDelayMs = environment.getProperty<Long>("flowlite.showcase.max-action-delay-ms", 60_000L),
                actionFailureRate = environment.getProperty<Double>("flowlite.showcase.action-failure-rate", 0.1),
                maxEventDelayMs = environment.getProperty<Long>("flowlite.showcase.max-event-delay-ms", 60_000L),
            )
        }

        registerBean {
            CockpitService(
                engine = bean<Engine>(),
                mermaid = bean<io.flowlite.MermaidGenerator>(),
                historyRepo = bean<FlowLiteHistoryRepository>(),
                summaryRepo = bean<FlowLiteInstanceSummaryRepository>(),
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
    extraArgs: Array<out String> = emptyArray(),
) = runApplication<TestApplication>(
    *listOf(
        "--spring.main.web-application-type=$webType",
        "--flowlite.showcase.enabled=$showcaseEnabled",
        *extraArgs,
    ).toTypedArray(),
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

internal class ShowcaseFlowSeeder(
    private val engine: Engine,
    enabled: Boolean,
    initialSeedCount: Int = 1,
    repeatSeedingEnabled: Boolean = true,
    maxActionDelayMs: Long,
    actionFailureRate: Double,
    private val maxEventDelayMs: Long,
    private val eventDelayProvider: (Long) -> Long = { maxDelayMs ->
        ThreadLocalRandom.current().nextLong(maxDelayMs) + 1
    },
) : AutoCloseable {
    private data class PendingShowcaseEvent(
        val flowId: String,
        val flowInstanceId: UUID,
        val waitingStage: String,
        val event: Event,
    )

    private val stagePollIntervalMs = 250L

    private val sequence = AtomicLong(0)
    private val pendingEventTasks = ConcurrentHashMap<String, Future<*>>()
    private val seedExecutor =
        if (enabled && repeatSeedingEnabled) {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "flowlite-showcase-seeder").apply { isDaemon = true }
            }
        } else {
            null
        }
    private val eventExecutor: ExecutorService? =
        if (enabled) {
            Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                    .name("flowlite-showcase-event-", 0)
                    .factory(),
            )
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
            seedBatch(initialSeedCount.coerceAtLeast(0))
            seedExecutor?.scheduleAtFixedRate(::seedOnceSafely, 5, 5, TimeUnit.SECONDS)
        }
    }

    private fun seedBatch(batchSize: Int) {
        repeat(batchSize) {
            seedOnce()
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
        queuePendingEvent(
            flowId = ORDER_CONFIRMATION_FLOW_ID,
            flowInstanceId = orderId,
            waitingStage = OrderConfirmationStage.WaitingForConfirmation.name,
            event = OrderConfirmationEvent.Confirmed,
        )

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
        queuePendingEvent(
            flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
            flowInstanceId = employeeId,
            waitingStage = EmployeeStage.WaitingForContractSigned.name,
            event = EmployeeEvent.ContractSigned,
        )
        queuePendingEvent(
            flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
            flowInstanceId = employeeId,
            waitingStage = EmployeeStage.WaitingForOnboardingAgreementSigned.name,
            event = EmployeeEvent.OnboardingAgreementSigned,
        )
        if (employee.isNotManualPath && !employee.isExecutiveOrManagement && employee.hasComplianceChecks) {
            queuePendingEvent(
                flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
                flowInstanceId = employeeId,
                waitingStage = EmployeeStage.WaitingForComplianceComplete.name,
                event = EmployeeEvent.ComplianceComplete,
            )
        }
        if (!employee.isNotManualPath) {
            queuePendingEvent(
                flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
                flowInstanceId = employeeId,
                waitingStage = EmployeeStage.WaitingForManualApproval.name,
                event = EmployeeEvent.ManualApproval,
            )
        }
    }

    private fun queuePendingEvent(
        flowId: String,
        flowInstanceId: UUID,
        waitingStage: String,
        event: Event,
    ) {
        val key = pendingEventKey(flowId, flowInstanceId, waitingStage, event)
        val pending = PendingShowcaseEvent(
            flowId = flowId,
            flowInstanceId = flowInstanceId,
            waitingStage = waitingStage,
            event = event,
        )
        val executor = eventExecutor ?: return
        pendingEventTasks.computeIfAbsent(key) {
            executor.submit {
                try {
                    awaitWaitingStageAndSend(pending)
                } finally {
                    pendingEventTasks.remove(key)
                }
            }
        }
    }

    private fun awaitWaitingStageAndSend(pending: PendingShowcaseEvent) {
        var matchedWaitingStage = false

        while (!Thread.currentThread().isInterrupted) {
            val status = runCatching { engine.getStatus(pending.flowId, pending.flowInstanceId) }
                .getOrElse { return }
            val currentStage = stageKey(status.first)
            val currentStatus = status.second

            if (currentStatus == StageStatus.Completed || currentStatus == StageStatus.Cancelled) {
                return
            }

            if (currentStage == pending.waitingStage && currentStatus == StageStatus.Pending) {
                matchedWaitingStage = true
                val delayMs = nextEventDelayMs()
                if (delayMs > 0L) {
                    showcaseLog.info {
                        "Showcase event ${pending.event} for ${pending.flowId}/${pending.flowInstanceId} will be sent in ${delayMs}ms while waiting on ${pending.waitingStage}"
                    }
                    if (!sleepSafely(delayMs)) return
                }

                val refreshedStatus = runCatching { engine.getStatus(pending.flowId, pending.flowInstanceId) }
                    .getOrElse { return }
                val refreshedStage = stageKey(refreshedStatus.first)
                val refreshedStageStatus = refreshedStatus.second
                if (refreshedStage != pending.waitingStage || refreshedStageStatus != StageStatus.Pending) {
                    return
                }

                runCatching {
                    engine.sendEvent(pending.flowId, pending.flowInstanceId, pending.event)
                }.onFailure { error ->
                    showcaseLog.error(error) {
                        "Failed to send showcase event ${pending.event} for ${pending.flowId}/${pending.flowInstanceId}"
                    }
                }
                return
            }

            if (matchedWaitingStage && currentStage != pending.waitingStage) {
                return
            }

            if (!sleepSafely(stagePollIntervalMs)) return
        }
    }

    private fun sleepSafely(delayMs: Long): Boolean =
        try {
            Thread.sleep(delayMs)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private fun nextEventDelayMs(): Long {
        if (maxEventDelayMs <= 0L) return 0L
        return eventDelayProvider(maxEventDelayMs).coerceIn(1L, maxEventDelayMs)
    }

    private fun stageKey(stage: Any): String =
        (stage as? Enum<*>)?.name ?: stage.toString()

    private fun pendingEventKey(
        flowId: String,
        flowInstanceId: UUID,
        waitingStage: String,
        event: Event,
    ): String = "$flowId/$flowInstanceId/$waitingStage/${event::class.java.name}:${event}"

    override fun close() {
        pendingEventTasks.values.forEach { it.cancel(true) }
        pendingEventTasks.clear()
        ShowcaseActionBehavior.configure(
            enabled = false,
            maxDelayMs = 0L,
            failureRate = 0.0,
        )
        seedExecutor?.shutdownNow()
        eventExecutor?.shutdownNow()
    }
}

fun startTestApplication() = startApplication("none")

fun startTestWebApplication(
    showcaseEnabled: Boolean = true,
    extraArgs: Array<out String> = emptyArray(),
) = startApplication("servlet", showcaseEnabled = showcaseEnabled, extraArgs = extraArgs)

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
