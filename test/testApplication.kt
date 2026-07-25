package io.flowlite.test

import io.flowlite.Engine
import io.flowlite.Event
import io.flowlite.FlowLiteHistoryRepository
import io.flowlite.FlowLiteInstanceSummaryRepository
import io.flowlite.FlowLiteTickRepository
import io.flowlite.HistoryEntry
import io.flowlite.InstanceData
import io.flowlite.PendingEventRepository
import io.flowlite.RetryState
import io.flowlite.StageStatus
import io.flowlite.Stage
import io.flowlite.SpringDataJdbcEventStore
import io.flowlite.SpringDataJdbcHistoryStore
import io.flowlite.SpringDataJdbcRetryStateStore
import io.flowlite.SpringDataJdbcTickScheduler
import io.flowlite.RetryStateStore
import io.flowlite.recordError
import io.flowlite.historyValueOf
import io.flowlite.cockpit.CockpitUiStaticConfig
import io.flowlite.cockpit.CockpitService
import io.flowlite.cockpit.cockpitRouter
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.time.Clock
import java.nio.file.Files
import java.nio.file.Path
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
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.BeanRegistrar
import org.springframework.beans.factory.BeanRegistrarDsl
import org.springframework.beans.factory.support.BeanRegistryAdapter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.env.Environment
import org.springframework.core.env.getProperty
import org.springframework.core.Ordered
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories
import org.springframework.data.relational.core.mapping.NamingStrategy
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import javax.management.ObjectName
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

        registerBean<RetryStateStore> {
            SpringDataJdbcRetryStateStore(bean())
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
                retryStateStore = bean(),
                clock = bean<AdjustableClock>(),
            ).also { engine ->
                engine.registerFlow(
                    ORDER_CONFIRMATION_FLOW_ID,
                    createOrderConfirmationFlow(),
                    orderPersister,
                    failureClassifier = orderRetryFailureClassifier(),
                )
                engine.registerFlow(EMPLOYEE_ONBOARDING_FLOW_ID, createEmployeeOnboardingFlow(onboardingActions), onboardingPersister)
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
            val environment = bean<Environment>()
            ShowcaseErrorCatalogSeeder(
                historyStore = bean<SpringDataJdbcHistoryStore>(),
                retryStateStore = bean<RetryStateStore>(),
                orderRepo = bean<OrderConfirmationRepository>(),
                employeeRepo = bean<EmployeeOnboardingRepository>(),
                enabled = environment.getProperty<Boolean>("flowlite.showcase.enabled", false),
                clock = bean<AdjustableClock>(),
            )
        }

        registerBean {
            val environment = bean<Environment>()
            PeriodicMemoryLogger(
                enabled = environment.getProperty<Boolean>("flowlite.diagnostics.memory-log-enabled", false),
                intervalSeconds = environment.getProperty<Long>("flowlite.diagnostics.memory-log-interval-seconds", 60L),
                rawEnabledEnv = System.getenv("FLOWLITE_DIAGNOSTICS_MEMORY_LOG_ENABLED"),
                rawIntervalEnv = System.getenv("FLOWLITE_DIAGNOSTICS_MEMORY_LOG_INTERVAL_SECONDS"),
            )
        }

        registerBean {
            val environment = bean<Environment>()
            PeriodicThreadDumpLogger(
                enabled = environment.getProperty<Boolean>("flowlite.diagnostics.thread-dump-enabled", false),
                intervalSeconds = environment.getProperty<Long>("flowlite.diagnostics.thread-dump-interval-seconds", 3600L),
                rawEnabledEnv = System.getenv("FLOWLITE_DIAGNOSTICS_THREAD_DUMP_ENABLED"),
                rawIntervalEnv = System.getenv("FLOWLITE_DIAGNOSTICS_THREAD_DUMP_INTERVAL_SECONDS"),
                rawJdkJavaOptionsEnv = System.getenv("JDK_JAVA_OPTIONS"),
            )
        }

        registerBean {
            val environment = bean<Environment>()
            FilterRegistrationBean(HttpAccessLogFilter(
                enabled = environment.getProperty<Boolean>("flowlite.diagnostics.http-access-log-enabled", false),
                includeQueryString = environment.getProperty<Boolean>("flowlite.diagnostics.http-access-log-include-query-string", true),
            )).apply {
                order = Ordered.LOWEST_PRECEDENCE
            }
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
private val diagnosticsLog = KotlinLogging.logger {}
private val accessLog = KotlinLogging.logger("flowlite.access")

internal class HttpAccessLogFilter(
    private val enabled: Boolean,
    private val includeQueryString: Boolean,
    private val nanoTimeProvider: () -> Long = System::nanoTime,
    private val logSink: (String) -> Unit = { message -> accessLog.info { message } },
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !enabled

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val startedAt = nanoTimeProvider()
        var failure: Throwable? = null

        try {
            filterChain.doFilter(request, response)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val durationMs = TimeUnit.NANOSECONDS.toMillis((nanoTimeProvider() - startedAt).coerceAtLeast(0L))
            logSink(
                "http access method=${request.method} target=${request.requestTarget(includeQueryString)} status=${response.status} durationMs=$durationMs remoteAddr=${request.remoteAddr ?: "-"} failure=${failure?.javaClass?.simpleName ?: "-"}",
            )
        }
    }
}

private fun HttpServletRequest.requestTarget(includeQueryString: Boolean): String {
    val uri = requestURI ?: "/"
    val query = queryString?.takeIf { includeQueryString && it.isNotBlank() } ?: return uri
    return "$uri?$query"
}

internal class PeriodicMemoryLogger(
    enabled: Boolean,
    intervalSeconds: Long,
    private val rawEnabledEnv: String? = null,
    private val rawIntervalEnv: String? = null,
) : AutoCloseable {
    private val executor =
        if (enabled) {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "flowlite-memory-diagnostics").apply { isDaemon = true }
            }
        } else {
            null
        }

    init {
        val effectiveIntervalSeconds = intervalSeconds.coerceAtLeast(5L)
        diagnosticsLog.info {
            "memory diagnostics config enabled=$enabled requestedIntervalSeconds=$intervalSeconds effectiveIntervalSeconds=$effectiveIntervalSeconds rawEnabledEnv=$rawEnabledEnv rawIntervalEnv=$rawIntervalEnv"
        }

        if (enabled) {
            logMemorySnapshot("startup")
            executor?.scheduleAtFixedRate(
                { logMemorySnapshot("periodic") },
                effectiveIntervalSeconds,
                effectiveIntervalSeconds,
                TimeUnit.SECONDS,
            )
        }
    }

    private fun logMemorySnapshot(reason: String) {
        val runtime = Runtime.getRuntime()
        val totalBytes = runtime.totalMemory()
        val freeBytes = runtime.freeMemory()
        val usedBytes = totalBytes - freeBytes
        val maxBytes = runtime.maxMemory()
        val heapUsage = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        val nonHeapUsage = ManagementFactory.getMemoryMXBean().nonHeapMemoryUsage
        val rssBytes = readProcessResidentSetBytes()
        val directBytes = bufferPoolUsageBytes("direct")
        val mappedBytes = bufferPoolUsageBytes("mapped")

        diagnosticsLog.info {
            "memory diagnostics reason=$reason usedMiB=${usedBytes.toMiB()} committedMiB=${totalBytes.toMiB()} maxMiB=${maxBytes.toMiB()} freeMiB=${freeBytes.toMiB()} heapUsedMiB=${heapUsage.used.toMiB()} heapCommittedMiB=${heapUsage.committed.toMiB()} nonHeapUsedMiB=${nonHeapUsage.used.toMiB()} rssMiB=${rssBytes.toMiBOrUnavailable()} directMiB=${directBytes.toMiBOrUnavailable()} mappedMiB=${mappedBytes.toMiBOrUnavailable()} threads=${Thread.getAllStackTraces().size}"
        }
    }

    override fun close() {
        executor?.shutdownNow()
    }
}

internal class PeriodicThreadDumpLogger(
    enabled: Boolean,
    intervalSeconds: Long,
    private val rawEnabledEnv: String? = null,
    private val rawIntervalEnv: String? = null,
    private val rawJdkJavaOptionsEnv: String? = null,
    private val pinnedThreadTracingMode: String? = System.getProperty("jdk.tracePinnedThreads"),
    private val pidProvider: () -> Long = { ProcessHandle.current().pid() },
    private val threadDumpProvider: () -> String = ::dumpThreadsViaDiagnosticCommand,
) : AutoCloseable {
    private val executor =
        if (enabled) {
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "flowlite-thread-dump-diagnostics").apply { isDaemon = true }
            }
        } else {
            null
        }

    init {
        val effectiveIntervalSeconds = intervalSeconds.coerceAtLeast(60L)
        diagnosticsLog.info {
            "thread dump diagnostics config enabled=$enabled requestedIntervalSeconds=$intervalSeconds effectiveIntervalSeconds=$effectiveIntervalSeconds rawEnabledEnv=$rawEnabledEnv rawIntervalEnv=$rawIntervalEnv pinnedThreadTracingMode=${pinnedThreadTracingMode ?: "disabled"} rawJdkJavaOptionsEnv=$rawJdkJavaOptionsEnv pid=${pidProvider()}"
        }

        if (enabled) {
            dumpThreads("startup")
            executor?.scheduleAtFixedRate(
                { dumpThreads("periodic") },
                effectiveIntervalSeconds,
                effectiveIntervalSeconds,
                TimeUnit.SECONDS,
            )
        }
    }

    private fun dumpThreads(reason: String) {
        val pid = pidProvider()

        runCatching {
            ThreadDumpResult(output = threadDumpProvider(), source = "diagnostic-command-mbean")
        }
            .onSuccess { result ->
                diagnosticsLog.info {
                    "thread dump diagnostics start reason=$reason pid=$pid source=${result.source}"
                }
                result.output.lineSequence().forEach { line ->
                    diagnosticsLog.info { "thread dump reason=$reason $line" }
                }
                diagnosticsLog.info {
                    "thread dump diagnostics end reason=$reason pid=$pid source=${result.source}"
                }
            }
            .onFailure { error ->
                diagnosticsLog.error(error) {
                    "thread dump diagnostics failed reason=$reason pid=$pid source=diagnostic-command-mbean"
                }
            }
    }

    override fun close() {
        executor?.shutdownNow()
    }
}

private data class ThreadDumpResult(
    val output: String,
    val source: String,
)

private fun dumpThreadsViaDiagnosticCommand(): String {
    val server = ManagementFactory.getPlatformMBeanServer()
    val name = ObjectName("com.sun.management:type=DiagnosticCommand")
    return server.invoke(
        name,
        "threadPrint",
        arrayOf(emptyArray<String>()),
        arrayOf(Array<String>::class.java.name),
    ).toString()
}

private fun Long.toMiB(): Long = this / (1024 * 1024)

private fun Long?.toMiBOrUnavailable(): String = this?.toMiB()?.toString() ?: "n/a"

private fun readProcessResidentSetBytes(): Long? {
    val statusPath = Path.of("/proc/self/status")
    if (!Files.isReadable(statusPath)) return null
    return Files.readAllLines(statusPath)
        .firstOrNull { it.startsWith("VmRSS:") }
        ?.removePrefix("VmRSS:")
        ?.trim()
        ?.split(Regex("\\s+"))
        ?.firstOrNull()
        ?.toLongOrNull()
        ?.times(1024)
}

private fun bufferPoolUsageBytes(poolNamePrefix: String): Long? {
    val pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java)
        .filter { it.name.startsWith(poolNamePrefix, ignoreCase = true) }
    if (pools.isEmpty()) return null
    return pools.sumOf { it.memoryUsed.coerceAtLeast(0L) }
}

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

        showcaseLog.info {
            "showcase seeder config enabled=$enabled initialSeedCount=$initialSeedCount repeatSeedingEnabled=$repeatSeedingEnabled maxActionDelayMs=$maxActionDelayMs actionFailureRate=$actionFailureRate maxEventDelayMs=$maxEventDelayMs"
        }

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

            if (
                currentStatus == StageStatus.Completed ||
                currentStatus == StageStatus.Cancelled ||
                currentStatus == StageStatus.Error
            ) {
                return
            }

            if (currentStage == pending.waitingStage && currentStatus == StageStatus.WaitingForEvent) {
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
                if (refreshedStage != pending.waitingStage || refreshedStageStatus != StageStatus.WaitingForEvent) {
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

    internal fun pendingEventTaskCount(): Int = pendingEventTasks.size

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
