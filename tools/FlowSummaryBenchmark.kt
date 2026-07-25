package io.flowlite.tools

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.sql.DataSource
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.math.ceil
import kotlin.system.measureNanoTime
import org.springframework.jdbc.datasource.DriverManagerDataSource

private const val FINAL_ERROR_PREDICATE =
    "cockpit_status = 'Error' and coalesce(external_retry_allowed, false) = false " +
        "and (auto_retry_max_attempts is null or next_auto_retry_at is null)"
private const val INCOMPLETE_PREDICATE = "cockpit_status not in ('Completed', 'Cancelled')"
private const val ACTIVE_PREDICATE =
    "cockpit_status in ('Running', 'WaitingForTimer', 'WaitingForEvent', 'PendingEngine')"

private val currentFlowAggregateSql =
    """
    select
        flow_id,
        sum(case when $ACTIVE_PREDICATE then 1 else 0 end) as active_count,
        sum(case when $FINAL_ERROR_PREDICATE then 1 else 0 end) as error_count,
        sum(case when cockpit_status in ('Completed', 'Cancelled') then 1 else 0 end) as completed_count,
        sum(case when $INCOMPLETE_PREDICATE then 1 else 0 end) as not_completed_count,
        sum(case when cockpit_status in ('Running', 'PendingEngine') and updated_at < ? then 1 else 0 end) as long_running_count
    from flowlite_instance_summary
    group by flow_id
    order by flow_id asc
    """.trimIndent()

private val currentStageAggregateSql =
    """
    select
        flow_id,
        stage,
        count(*) as total_count,
        sum(case when $FINAL_ERROR_PREDICATE then 1 else 0 end) as error_count
    from flowlite_instance_summary
    where stage is not null
        and $INCOMPLETE_PREDICATE
    group by flow_id, stage
    order by flow_id asc, stage asc
    """.trimIndent()

private val positiveStatusStageAggregateSql =
    """
    select
        flow_id,
        stage,
        count(*) as total_count,
        sum(case when $FINAL_ERROR_PREDICATE then 1 else 0 end) as error_count
    from flowlite_instance_summary
    where stage is not null
        and cockpit_status in ('Running', 'WaitingForTimer', 'WaitingForEvent', 'PendingEngine', 'Error')
    group by flow_id, stage
    order by flow_id asc, stage asc
    """.trimIndent()

private val combinedAggregateSql =
    """
    select
        flow_id,
        stage,
        sum(case when $ACTIVE_PREDICATE then 1 else 0 end) as active_count,
        sum(case when $FINAL_ERROR_PREDICATE then 1 else 0 end) as error_count,
        sum(case when cockpit_status in ('Completed', 'Cancelled') then 1 else 0 end) as completed_count,
        sum(case when $INCOMPLETE_PREDICATE then 1 else 0 end) as not_completed_count,
        sum(case when cockpit_status in ('Running', 'PendingEngine') and updated_at < ? then 1 else 0 end) as long_running_count,
        sum(case when stage is not null and $INCOMPLETE_PREDICATE then 1 else 0 end) as stage_total_count,
        sum(case when stage is not null and $INCOMPLETE_PREDICATE and $FINAL_ERROR_PREDICATE then 1 else 0 end) as stage_error_count
    from flowlite_instance_summary
    group by flow_id, stage
    order by flow_id asc, stage asc
    """.trimIndent()

private val preaggregatedFlowSql =
    """
    select flow_id, active_count, error_count, completed_count, not_completed_count
    from flowlite_flow_stats
    order by flow_id asc
    """.trimIndent()

private val preaggregatedStageSql =
    """
    select flow_id, stage, total_count, error_count
    from flowlite_stage_stats
    order by flow_id asc, stage asc
    """.trimIndent()

private val longRunningSql =
    """
    select flow_id, count(*) as long_running_count
    from flowlite_instance_summary
    where cockpit_status in ('Running', 'PendingEngine')
        and updated_at < ?
    group by flow_id
    order by flow_id asc
    """.trimIndent()

private data class FlowCounts(
    val active: Long = 0,
    val errors: Long = 0,
    val completed: Long = 0,
    val incomplete: Long = 0,
    val longRunning: Long = 0,
) {
    operator fun plus(other: FlowCounts) = FlowCounts(
        active = active + other.active,
        errors = errors + other.errors,
        completed = completed + other.completed,
        incomplete = incomplete + other.incomplete,
        longRunning = longRunning + other.longRunning,
    )
}

private data class StageKey(val flowId: String, val stage: String)
private data class StageCounts(val total: Long, val errors: Long)
private data class FlowSnapshot(
    val flows: Map<String, FlowCounts>,
    val stages: Map<StageKey, StageCounts>,
)

private data class BenchmarkResult(
    val storage: String,
    val rows: Int,
    val variant: String,
    val concurrency: Int,
    val samples: Int,
    val p50Ms: Double,
    val p95Ms: Double,
    val maxMs: Double,
    val throughputPerSecond: Double,
)

private data class BenchmarkConfig(
    val storage: List<String> = listOf("memory", "file"),
    val rows: List<Int> = listOf(50_000, 200_000),
    val warmups: Int = 4,
    val sequentialSamples: Int = 16,
    val concurrentWorkers: Int = 8,
    val samplesPerWorker: Int = 5,
) {
    init {
        require(storage.isNotEmpty() && storage.all { it == "memory" || it == "file" })
        require(rows.isNotEmpty() && rows.all { it > 0 })
        require(warmups >= 0)
        require(sequentialSamples > 0)
        require(concurrentWorkers > 0)
        require(samplesPerWorker > 0)
    }

    companion object {
        fun parse(args: Array<String>): BenchmarkConfig {
            val values = args.mapNotNull { argument ->
                argument.removePrefix("--")
                    .split("=", limit = 2)
                    .takeIf { it.size == 2 }
                    ?.let { it[0] to it[1] }
            }.toMap()
            return BenchmarkConfig(
                storage = values["storage"]?.split(",") ?: listOf("memory", "file"),
                rows = values["rows"]?.split(",")?.map(String::toInt) ?: listOf(50_000, 200_000),
                warmups = values["warmups"]?.toInt() ?: 4,
                sequentialSamples = values["samples"]?.toInt() ?: 16,
                concurrentWorkers = values["concurrency"]?.toInt() ?: 8,
                samplesPerWorker = values["samples-per-worker"]?.toInt() ?: 5,
            )
        }
    }
}

fun main(args: Array<String>) {
    val config = BenchmarkConfig.parse(args)
    val results = mutableListOf<BenchmarkResult>()

    println(
        "Flow summary benchmark rows=${config.rows} warmups=${config.warmups} " +
            "samples=${config.sequentialSamples} concurrency=${config.concurrentWorkers} " +
            "samplesPerWorker=${config.samplesPerWorker}",
    )

    for (storage in config.storage) {
        for (rowCount in config.rows) {
            withDatabase(storage, rowCount) { jdbcUrl ->
                val driverManager = DriverManagerDataSource(jdbcUrl)
                createHikariDataSource(jdbcUrl, config.concurrentWorkers).use { hikari ->
                    val threshold = Instant.parse("2026-07-25T06:00:00Z")
                    val variants = linkedMapOf<String, () -> FlowSnapshot>(
                        "driver-manager-two-queries" to { currentSnapshot(driverManager, threshold) },
                        "hikari-two-queries" to { currentSnapshot(hikari, threshold) },
                        "hikari-one-query" to { combinedSnapshot(hikari, threshold) },
                        "hikari-preaggregated" to { preaggregatedSnapshot(hikari, threshold) },
                    )

                    val expected = variants.getValue("driver-manager-two-queries")()
                    variants.forEach { (name, operation) ->
                        check(operation() == expected) {
                            "Variant $name returned a different snapshot for storage=$storage rows=$rowCount"
                        }
                    }

                    variants.forEach { (name, operation) ->
                        results += benchmark(
                            storage = storage,
                            rows = rowCount,
                            variant = name,
                            concurrency = 1,
                            warmups = config.warmups,
                            samples = config.sequentialSamples,
                            operation = operation,
                        )
                        results += benchmarkConcurrent(
                            storage = storage,
                            rows = rowCount,
                            variant = name,
                            workers = config.concurrentWorkers,
                            warmups = config.warmups,
                            samplesPerWorker = config.samplesPerWorker,
                            operation = operation,
                        )
                    }

                    val beforeIndex = benchmark(
                        storage = storage,
                        rows = rowCount,
                        variant = "hikari-two-queries-current-indexes",
                        concurrency = 1,
                        warmups = config.warmups,
                        samples = config.sequentialSamples,
                    ) { currentSnapshot(hikari, threshold) }
                    createCandidateCoveringIndex(hikari)
                    val afterIndex = benchmark(
                        storage = storage,
                        rows = rowCount,
                        variant = "hikari-two-queries-covering-index",
                        concurrency = 1,
                        warmups = config.warmups,
                        samples = config.sequentialSamples,
                    ) { currentSnapshot(hikari, threshold) }
                    val positiveStatusAfterIndex = benchmark(
                        storage = storage,
                        rows = rowCount,
                        variant = "hikari-two-queries-positive-status-covering-index",
                        concurrency = 1,
                        warmups = config.warmups,
                        samples = config.sequentialSamples,
                    ) { currentSnapshot(hikari, threshold, positiveStatusStageAggregateSql) }
                    results += beforeIndex
                    results += afterIndex
                    results += positiveStatusAfterIndex

                    printExplainPlans(hikari, storage, rowCount)
                }
            }
        }
    }

    println()
    println("| storage | rows | variant | concurrency | samples | p50 ms | p95 ms | max ms | ops/s |")
    println("|---|---:|---|---:|---:|---:|---:|---:|---:|")
    results.forEach { result ->
        println(
            "| ${result.storage} | ${result.rows} | ${result.variant} | ${result.concurrency} | " +
                "${result.samples} | ${"%.3f".format(Locale.ROOT, result.p50Ms)} | " +
                "${"%.3f".format(Locale.ROOT, result.p95Ms)} | " +
                "${"%.3f".format(Locale.ROOT, result.maxMs)} | " +
                "${"%.2f".format(Locale.ROOT, result.throughputPerSecond)} |",
        )
    }
}

private fun withDatabase(storage: String, rows: Int, block: (String) -> Unit) {
    val tempDirectory = if (storage == "file") Files.createTempDirectory("flowlite-summary-benchmark-") else null
    val suffix = UUID.randomUUID().toString().replace("-", "")
    val jdbcUrl = when (storage) {
        "memory" -> "jdbc:h2:mem:flow_summary_$suffix;DB_CLOSE_DELAY=-1;QUERY_CACHE_SIZE=0"
        "file" ->
            "jdbc:h2:file:${tempDirectory!!.resolve("flow-summary")};" +
                "DB_CLOSE_DELAY=-1;QUERY_CACHE_SIZE=0"
        else -> error("Unsupported storage $storage")
    }

    try {
        forceGc()
        val heapBefore = usedHeapBytes()
        DriverManagerDataSource(jdbcUrl).connection.use { connection ->
            createSchema(connection)
            seedRows(connection, rows)
            createPreaggregatedTables(connection)
            connection.createStatement().use { statement -> statement.execute("checkpoint") }
        }
        forceGc()
        val heapAfter = usedHeapBytes()
        val databaseFileBytes = tempDirectory?.directorySizeBytes() ?: 0L
        println(
            "FOOTPRINT storage=$storage rows=$rows heapDeltaMiB=${(heapAfter - heapBefore).toMiB()} " +
                "heapUsedMiB=${heapAfter.toMiB()} databaseFileMiB=${databaseFileBytes.toMiB()}",
        )
        block(jdbcUrl)
    } finally {
        DriverManagerDataSource(jdbcUrl).connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute("shutdown") }
        }
        tempDirectory?.deleteRecursively()
    }
}

private fun createSchema(connection: Connection) {
    connection.createStatement().use { statement ->
        statement.execute(
            """
            create table flowlite_instance_summary (
                id uuid primary key,
                flow_id varchar(128) not null,
                flow_instance_id uuid not null,
                stage varchar(128),
                status varchar(32) not null,
                cockpit_status varchar(32) not null,
                failed_attempt_count int,
                external_retry_allowed boolean,
                auto_retry_max_attempts int,
                next_auto_retry_at timestamp,
                last_error_type varchar(512),
                last_error_message varchar(4000),
                updated_at timestamp not null
            )
            """.trimIndent(),
        )
        statement.execute(
            "create unique index idx_flowlite_instance_summary_key " +
                "on flowlite_instance_summary(flow_id, flow_instance_id)",
        )
        statement.execute(
            "create index idx_flowlite_instance_summary_instance " +
                "on flowlite_instance_summary(flow_instance_id)",
        )
        statement.execute(
            "create index idx_flowlite_instance_summary_status_stage " +
                "on flowlite_instance_summary(flow_id, cockpit_status, stage, updated_at, flow_instance_id)",
        )
        statement.execute(
            "create index idx_flowlite_instance_summary_cockpit_status " +
                "on flowlite_instance_summary(cockpit_status, updated_at, flow_id, flow_instance_id)",
        )
    }
}

private fun seedRows(connection: Connection, rows: Int) {
    val flows = arrayOf("employee-onboarding", "order-confirmation")
    val employeeStages = arrayOf(
        "CreateEmployeeProfile",
        "WaitingForContractSigned",
        "WaitingForOnboardingAgreementSigned",
        "WaitingForComplianceComplete",
        "UpdateHRSystem",
        "CompleteOnboarding",
    )
    val orderStages = arrayOf(
        "InitializingConfirmation",
        "WaitingForConfirmation",
        "RemovingFromConfirmationQueue",
        "InformingCustomer",
    )
    val statuses = arrayOf(
        "Completed",
        "Completed",
        "Completed",
        "Completed",
        "WaitingForEvent",
        "WaitingForEvent",
        "Error",
        "PendingEngine",
        "Running",
        "Cancelled",
    )
    val baseTime = Instant.parse("2026-07-25T08:00:00Z")

    connection.autoCommit = false
    connection.prepareStatement(
        """
        insert into flowlite_instance_summary(
            id, flow_id, flow_instance_id, stage, status, cockpit_status,
            failed_attempt_count, external_retry_allowed, auto_retry_max_attempts,
            next_auto_retry_at, updated_at
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    ).use { statement ->
        repeat(rows) { index ->
            val flowId = flows[index % flows.size]
            val stage = if (flowId == flows[0]) {
                employeeStages[index % employeeStages.size]
            } else {
                orderStages[index % orderStages.size]
            }
            val cockpitStatus = statuses[index % statuses.size]
            val externallyRetryable = cockpitStatus == "Error" && index % 3 == 0
            val activeAutoRetry = cockpitStatus == "Error" && index % 3 == 1
            val id = UUID(index.toLong(), index.toLong() xor Long.MIN_VALUE)

            statement.setObject(1, id)
            statement.setString(2, flowId)
            statement.setObject(3, id)
            statement.setString(4, stage)
            statement.setString(5, cockpitStatus)
            statement.setString(6, cockpitStatus)
            statement.setObject(7, if (cockpitStatus == "Error") index % 5 + 1 else null)
            statement.setObject(8, externallyRetryable)
            statement.setObject(9, if (activeAutoRetry) 5 else null)
            statement.setObject(10, if (activeAutoRetry) baseTime.plusSeconds(300) else null)
            statement.setObject(11, baseTime.minus(index.mod(7_200).toLong(), ChronoUnit.SECONDS))
            statement.addBatch()

            if ((index + 1) % 1_000 == 0) {
                statement.executeBatch()
                connection.commit()
            }
        }
        statement.executeBatch()
        connection.commit()
    }
    connection.autoCommit = true
}

private fun createPreaggregatedTables(connection: Connection) {
    connection.createStatement().use { statement ->
        statement.execute(
            """
            create table flowlite_flow_stats as
            select
                flow_id,
                sum(case when $ACTIVE_PREDICATE then 1 else 0 end) as active_count,
                sum(case when $FINAL_ERROR_PREDICATE then 1 else 0 end) as error_count,
                sum(case when cockpit_status in ('Completed', 'Cancelled') then 1 else 0 end) as completed_count,
                sum(case when $INCOMPLETE_PREDICATE then 1 else 0 end) as not_completed_count
            from flowlite_instance_summary
            group by flow_id
            """.trimIndent(),
        )
        statement.execute(
            """
            create table flowlite_stage_stats as
            select
                flow_id,
                stage,
                count(*) as total_count,
                sum(case when $FINAL_ERROR_PREDICATE then 1 else 0 end) as error_count
            from flowlite_instance_summary
            where stage is not null
                and $INCOMPLETE_PREDICATE
            group by flow_id, stage
            """.trimIndent(),
        )
        statement.execute("create unique index idx_flowlite_flow_stats on flowlite_flow_stats(flow_id)")
        statement.execute(
            "create unique index idx_flowlite_stage_stats on flowlite_stage_stats(flow_id, stage)",
        )
    }
}

private fun createCandidateCoveringIndex(dataSource: DataSource) {
    dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.execute(
                """
                create index idx_flowlite_instance_summary_flows_covering
                on flowlite_instance_summary(
                    cockpit_status,
                    flow_id,
                    stage,
                    updated_at,
                    external_retry_allowed,
                    auto_retry_max_attempts,
                    next_auto_retry_at
                )
                """.trimIndent(),
            )
        }
    }
}

private fun createHikariDataSource(jdbcUrl: String, maximumPoolSize: Int): HikariDataSource {
    val config = HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        this.maximumPoolSize = maximumPoolSize.coerceAtLeast(1)
        minimumIdle = maximumPoolSize.coerceAtLeast(1)
        poolName = "flow-summary-benchmark"
    }
    return HikariDataSource(config)
}

private fun currentSnapshot(
    dataSource: DataSource,
    threshold: Instant,
    stageAggregateSql: String = currentStageAggregateSql,
): FlowSnapshot {
    val flows = linkedMapOf<String, FlowCounts>()
    dataSource.connection.use { connection ->
        connection.prepareStatement(currentFlowAggregateSql).use { statement ->
            statement.setObject(1, threshold)
            statement.executeQuery().use { result ->
                while (result.next()) {
                    flows[result.getString("flow_id")] = result.toFlowCounts()
                }
            }
        }
    }

    val stages = linkedMapOf<StageKey, StageCounts>()
    dataSource.connection.use { connection ->
        connection.prepareStatement(stageAggregateSql).use { statement ->
            statement.executeQuery().use { result ->
                while (result.next()) {
                    stages[StageKey(result.getString("flow_id"), result.getString("stage"))] =
                        StageCounts(
                            total = result.getLong("total_count"),
                            errors = result.getLong("error_count"),
                        )
                }
            }
        }
    }
    return FlowSnapshot(flows, stages)
}

private fun combinedSnapshot(dataSource: DataSource, threshold: Instant): FlowSnapshot {
    val flows = linkedMapOf<String, FlowCounts>()
    val stages = linkedMapOf<StageKey, StageCounts>()
    dataSource.connection.use { connection ->
        connection.prepareStatement(combinedAggregateSql).use { statement ->
            statement.setObject(1, threshold)
            statement.executeQuery().use { result ->
                while (result.next()) {
                    val flowId = result.getString("flow_id")
                    flows[flowId] = flows.getOrDefault(flowId, FlowCounts()) + result.toFlowCounts()
                    result.getString("stage")?.let { stage ->
                        val total = result.getLong("stage_total_count")
                        if (total > 0) {
                            stages[StageKey(flowId, stage)] =
                                StageCounts(total, result.getLong("stage_error_count"))
                        }
                    }
                }
            }
        }
    }
    return FlowSnapshot(flows, stages)
}

private fun preaggregatedSnapshot(dataSource: DataSource, threshold: Instant): FlowSnapshot {
    val longRunning = linkedMapOf<String, Long>()
    val flows = linkedMapOf<String, FlowCounts>()
    val stages = linkedMapOf<StageKey, StageCounts>()
    dataSource.connection.use { connection ->
        connection.prepareStatement(longRunningSql).use { statement ->
            statement.setObject(1, threshold)
            statement.executeQuery().use { result ->
                while (result.next()) {
                    longRunning[result.getString("flow_id")] = result.getLong("long_running_count")
                }
            }
        }
        connection.prepareStatement(preaggregatedFlowSql).use { statement ->
            statement.executeQuery().use { result ->
                while (result.next()) {
                    val flowId = result.getString("flow_id")
                    flows[flowId] = FlowCounts(
                        active = result.getLong("active_count"),
                        errors = result.getLong("error_count"),
                        completed = result.getLong("completed_count"),
                        incomplete = result.getLong("not_completed_count"),
                        longRunning = longRunning[flowId] ?: 0,
                    )
                }
            }
        }
        connection.prepareStatement(preaggregatedStageSql).use { statement ->
            statement.executeQuery().use { result ->
                while (result.next()) {
                    stages[StageKey(result.getString("flow_id"), result.getString("stage"))] =
                        StageCounts(
                            total = result.getLong("total_count"),
                            errors = result.getLong("error_count"),
                        )
                }
            }
        }
    }
    return FlowSnapshot(flows, stages)
}

private fun ResultSet.toFlowCounts() = FlowCounts(
    active = getLong("active_count"),
    errors = getLong("error_count"),
    completed = getLong("completed_count"),
    incomplete = getLong("not_completed_count"),
    longRunning = getLong("long_running_count"),
)

private fun benchmark(
    storage: String,
    rows: Int,
    variant: String,
    concurrency: Int,
    warmups: Int,
    samples: Int,
    operation: () -> FlowSnapshot,
): BenchmarkResult {
    repeat(warmups) { operation() }
    val elapsed = List(samples) {
        measureNanoTime { operation() }
    }
    return result(storage, rows, variant, concurrency, elapsed)
}

private fun benchmarkConcurrent(
    storage: String,
    rows: Int,
    variant: String,
    workers: Int,
    warmups: Int,
    samplesPerWorker: Int,
    operation: () -> FlowSnapshot,
): BenchmarkResult {
    repeat(warmups) { operation() }
    val executor = Executors.newFixedThreadPool(workers)
    val started = System.nanoTime()
    val elapsed = try {
        executor.invokeAll(
            List(workers * samplesPerWorker) {
                Callable { measureNanoTime { operation() } }
            },
        ).map { it.get() }
    } finally {
        executor.shutdown()
    }
    val wallNanos = System.nanoTime() - started
    return result(storage, rows, variant, workers, elapsed, wallNanos)
}

private fun result(
    storage: String,
    rows: Int,
    variant: String,
    concurrency: Int,
    elapsed: List<Long>,
    wallNanos: Long = elapsed.sum(),
): BenchmarkResult {
    val sorted = elapsed.sorted()
    fun percentile(percent: Double): Double {
        val index = (ceil(percent * sorted.size).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index] / 1_000_000.0
    }
    return BenchmarkResult(
        storage = storage,
        rows = rows,
        variant = variant,
        concurrency = concurrency,
        samples = elapsed.size,
        p50Ms = percentile(0.50),
        p95Ms = percentile(0.95),
        maxMs = sorted.last() / 1_000_000.0,
        throughputPerSecond = elapsed.size / (wallNanos / 1_000_000_000.0),
    )
}

private fun printExplainPlans(dataSource: DataSource, storage: String, rows: Int) {
    dataSource.connection.use { connection ->
        println()
        println("EXPLAIN storage=$storage rows=$rows current-flow")
        explain(connection, currentFlowAggregateSql, Instant.parse("2026-07-25T06:00:00Z"))
        println("EXPLAIN storage=$storage rows=$rows current-stage")
        explain(connection, currentStageAggregateSql)
        println("EXPLAIN storage=$storage rows=$rows positive-status-stage")
        explain(connection, positiveStatusStageAggregateSql)
        println("EXPLAIN storage=$storage rows=$rows combined")
        explain(connection, combinedAggregateSql, Instant.parse("2026-07-25T06:00:00Z"))
        println("EXPLAIN storage=$storage rows=$rows long-running")
        explain(connection, longRunningSql, Instant.parse("2026-07-25T06:00:00Z"))
    }
}

private fun explain(connection: Connection, sql: String, parameter: Instant? = null) {
    connection.prepareStatement("explain $sql").use { statement ->
        parameter?.let { statement.setObject(1, it) }
        statement.executeQuery().use { result ->
            while (result.next()) {
                println(result.getString(1).replace(Regex("\\s+"), " "))
            }
        }
    }
}

private fun Path.deleteRecursively() {
    if (!exists()) return
    Files.walk(this).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Path::deleteIfExists)
    }
}

private fun Path.directorySizeBytes(): Long {
    if (!exists()) return 0
    return Files.walk(this).use { paths ->
        paths.filter(Files::isRegularFile).mapToLong(Files::size).sum()
    }
}

private fun forceGc() {
    System.gc()
    Thread.sleep(250)
}

private fun usedHeapBytes(): Long {
    val runtime = Runtime.getRuntime()
    return runtime.totalMemory() - runtime.freeMemory()
}

private fun Long.toMiB(): Long = this / (1024 * 1024)
