package io.flowlite.cockpit

import io.flowlite.Engine
import io.flowlite.Event
import io.flowlite.Flow
import io.flowlite.FlowLiteHistoryRepository
import io.flowlite.FlowLiteHistoryRow
import io.flowlite.FlowLiteInstanceSummaryRepository
import io.flowlite.FlowLiteInstanceSummaryRow
import io.flowlite.HistoryEntryType
import io.flowlite.MermaidGenerator
import io.flowlite.Stage
import io.flowlite.StageDefinition
import io.flowlite.StageStatus
import io.flowlite.historyValueOf
import io.flowlite.toHistoryEntry
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class CockpitFlowStageDto(
    val stage: String,
    val totalCount: Int,
    val errorCount: Int,
)

data class CockpitFlowDto(
    val flowId: String,
    val diagram: String,
    val stages: List<String>,
    val notCompletedCount: Int,
    val errorCount: Int,
    val activeCount: Int,
    val completedCount: Int,
    val longRunningCount: Int,
    val stageBreakdown: List<CockpitFlowStageDto>,
)

data class CockpitInstanceDto(
    val flowId: String,
    val flowInstanceId: UUID,
    val stage: String?,
    val status: StageStatus?,
    val activityStatus: CockpitActivityStatus?,
    val lastUpdatedAt: Instant,
    val lastErrorMessage: String? = null,
)

data class CockpitErrorGroupDto(
    val flowId: String,
    val stage: String?,
    val count: Int,
    val instanceIds: List<UUID>,
)

enum class CockpitInstanceBucket {
    Active,
    Error,
    Completed,
}

enum class CockpitActivityStatus {
    Running,
    Pending,
    WaitingForTimer,
    WaitingForEvent,
}

class CockpitService(
    private val engine: Engine,
    private val mermaid: MermaidGenerator,
    private val historyRepo: FlowLiteHistoryRepository,
    private val summaryRepo: FlowLiteInstanceSummaryRepository,
) {
    private companion object {
        val STAGE_ROW_TYPES = setOf(
            HistoryEntryType.Started,
            HistoryEntryType.StageChanged,
            HistoryEntryType.ManualStageChanged,
            HistoryEntryType.Error,
        )

        val STATUS_ROW_TYPES = setOf(
            HistoryEntryType.Started,
            HistoryEntryType.StatusChanged,
            HistoryEntryType.Retried,
            HistoryEntryType.ManualStageChanged,
            HistoryEntryType.Cancelled,
            HistoryEntryType.Error,
        )

        val ERROR_ROW_TYPES = setOf(HistoryEntryType.Error)

        val INSTANCE_SUMMARY_ROW_TYPES = (STAGE_ROW_TYPES + STATUS_ROW_TYPES + ERROR_ROW_TYPES)
            .map { it.name }

        val ROW_RECENCY = compareBy<FlowLiteHistoryRow> { it.occurredAt }
            .thenBy { it.id?.toString() ?: "" }
    }

    fun listFlows(longRunningThresholdSeconds: Long = 3600): List<CockpitFlowDto> {
        val registered = engine.registeredFlows()
        val summariesByFlow = loadInstanceSummaries(flowId = null)
            .filter { registered.containsKey(it.flowId) }
            .groupBy { it.flowId }
        val now = Instant.now()
        val longRunningThreshold = Duration.ofSeconds(longRunningThresholdSeconds.coerceAtLeast(1))

        return registered.keys.sorted().mapNotNull { flowId ->
            val flow = registered[flowId] ?: return@mapNotNull null
            val summaries = summariesByFlow[flowId].orEmpty()
            val counts = summaries.toFlowCounts()
            val incomplete = summaries.filter { it.status != StageStatus.Completed && it.status != StageStatus.Cancelled }
            val stageBreakdown = incomplete
                .asSequence()
                .mapNotNull { summary -> summary.stage?.let { stage -> stage to summary.status } }
                .groupBy(keySelector = { it.first }, valueTransform = { it.second })
                .map { (stage, statuses) ->
                    CockpitFlowStageDto(
                        stage = stage,
                        totalCount = statuses.size,
                        errorCount = statuses.count { it == StageStatus.Error },
                    )
                }
                .sortedBy { it.stage }
            val longRunningCount = summaries.count {
                it.activityStatus.isCountedAsLongInactiveByDefault() && Duration.between(it.lastUpdatedAt, now) > longRunningThreshold
            }

            CockpitFlowDto(
                flowId = flowId,
                diagram = mermaid.generateDiagram(flow),
                stages = flow.stages.keys.map { historyValueOf(it) },
                notCompletedCount = counts.notCompleted,
                errorCount = counts.error,
                activeCount = counts.active,
                completedCount = counts.completed,
                longRunningCount = longRunningCount,
                stageBreakdown = stageBreakdown,
            )
        }
    }

    fun listInstances(
        flowId: String? = null,
        bucket: CockpitInstanceBucket? = null,
        status: StageStatus? = null,
        searchTerm: String? = null,
        stage: String? = null,
        errorMessage: String? = null,
        showIncompleteOnly: Boolean = false,
        activityFilter: String? = null,
        longInactiveThresholdSeconds: Long? = null,
    ): List<CockpitInstanceDto> {
        val summaries = loadInstanceSummaries(flowId)

        val now = Instant.now()
        val longInactiveThreshold = longInactiveThresholdSeconds
            ?.coerceAtLeast(1)
            ?.let(Duration::ofSeconds)
        val normalizedSearchTerm = searchTerm?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
        val normalizedStage = stage?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedErrorMessage = errorMessage?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
        val normalizedActivityFilter = activityFilter?.trim()?.takeIf { it.isNotEmpty() && it != "all" }

        val filtered = when (bucket) {
            null -> summaries
            CockpitInstanceBucket.Active -> summaries.filter {
                it.status == StageStatus.Pending || it.status == StageStatus.Running
            }
            CockpitInstanceBucket.Error -> summaries.filter { it.status == StageStatus.Error }
            CockpitInstanceBucket.Completed -> summaries.filter {
                it.status == StageStatus.Completed || it.status == StageStatus.Cancelled
            }
        }
            .filter { status == null || it.status == status }
            .filter { summary ->
                normalizedSearchTerm == null ||
                    summary.flowId.lowercase().contains(normalizedSearchTerm) ||
                    summary.flowInstanceId.toString().lowercase().contains(normalizedSearchTerm)
            }
            .filter { summary -> normalizedStage == null || summary.stage == normalizedStage }
            .filter { summary ->
                normalizedErrorMessage == null ||
                    summary.lastErrorMessage?.lowercase()?.contains(normalizedErrorMessage) == true
            }
            .filter { summary -> !showIncompleteOnly || (summary.status != StageStatus.Completed && summary.status != StageStatus.Cancelled) }
            .filter { summary -> matchesActivityFilter(summary.activityStatus, normalizedActivityFilter) }
            .filter { summary ->
                longInactiveThreshold == null || Duration.between(summary.lastUpdatedAt, now) > longInactiveThreshold
            }

        return filtered.sortedWith(
            compareBy<CockpitInstanceDto> { it.flowId }
                .thenByDescending { it.lastUpdatedAt }
                .thenBy { it.flowInstanceId },
        )
    }

    fun listErrorGroups(
        flowId: String? = null,
        stageContains: String? = null,
        errorMessage: String? = null,
    ): List<CockpitErrorGroupDto> {
        val normalizedStage = stageContains?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
        val normalizedErrorMessage = errorMessage?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
        val errors = loadInstanceSummaries(flowId)
            .filter { it.status == StageStatus.Error }
            .filter { summary ->
                normalizedStage == null || summary.stage?.lowercase()?.contains(normalizedStage) == true
            }
            .filter { summary ->
                normalizedErrorMessage == null ||
                    summary.lastErrorMessage?.lowercase()?.contains(normalizedErrorMessage) == true
            }
        return errors.groupBy { it.flowId to it.stage }
            .map { (k, instances) ->
                CockpitErrorGroupDto(
                    flowId = k.first,
                    stage = k.second,
                    count = instances.size,
                    instanceIds = instances.map { it.flowInstanceId }.sortedBy { it.toString() },
                )
            }
            .sortedWith(compareBy<CockpitErrorGroupDto> { it.flowId }.thenBy { it.stage ?: "" })
    }

    fun instance(flowId: String, flowInstanceId: UUID): CockpitInstanceDto? {
        return loadInstanceSummaries(flowId)
            .firstOrNull { it.flowInstanceId == flowInstanceId }
    }

    fun timeline(flowId: String, flowInstanceId: UUID): List<FlowLiteHistoryRow> {
        return historyRepo.findTimeline(flowId = flowId, flowInstanceId = flowInstanceId)
    }

    fun retry(flowId: String, flowInstanceId: UUID) {
        engine.retry(flowId, flowInstanceId)
    }

    fun cancel(flowId: String, flowInstanceId: UUID) {
        engine.cancel(flowId, flowInstanceId)
    }

    fun changeStage(flowId: String, flowInstanceId: UUID, stage: String) {
        engine.changeStage(flowId, flowInstanceId, stage)
    }

    private data class FlowCounts(
        val active: Int,
        val error: Int,
        val completed: Int,
        val notCompleted: Int,
    )

    private data class InstanceKey(val flowId: String, val flowInstanceId: UUID)

    private typealias StageDefinitionsByFlow = Map<String, Map<String, StageDefinition<Any, Stage, Event>>>

    private fun loadInstanceSummaries(flowId: String?): List<CockpitInstanceDto> {
        val stageDefinitionsByFlow = stageDefinitionsByFlow()
        val summaryRows = summaryRepo.findAllSummaries(flowId)
        val rows = if (summaryRows.isNotEmpty()) summaryRows else backfillSummaries(flowId)

        return rows.map { row ->
            val statusValue = row.status?.let(StageStatus::valueOf)
            CockpitInstanceDto(
                flowId = row.flowId,
                flowInstanceId = row.flowInstanceId,
                stage = row.stage,
                status = statusValue,
                activityStatus = classifyActivityStatus(stageDefinitionsByFlow[row.flowId], row.stage, statusValue),
                lastUpdatedAt = row.updatedAt,
                lastErrorMessage = row.lastErrorMessage,
            )
        }
    }

    private fun backfillSummaries(flowId: String?): List<FlowLiteInstanceSummaryRow> {
        val rowsByKey = historyRepo.findLatestRowsPerType(flowId, INSTANCE_SUMMARY_ROW_TYPES)
            .groupBy { it.asKey() }

        val summaries = rowsByKey.mapNotNull { (key, rows) ->
            val stage = rows.latestStageRow()
            val status = rows.latestOfType(STATUS_ROW_TYPES)
            val error = rows.latestOfType(ERROR_ROW_TYPES)
            val stageValue = stage?.stageValue()
            val statusValue = status?.statusValue()?.name

            val lastUpdated = listOfNotNull(stage?.occurredAt, status?.occurredAt, error?.occurredAt)
                .maxOrNull()
                ?: return@mapNotNull null

            FlowLiteInstanceSummaryRow(
                flowId = key.flowId,
                flowInstanceId = key.flowInstanceId,
                stage = stageValue,
                status = statusValue,
                lastErrorMessage = error?.errorMessage,
                updatedAt = lastUpdated,
            )
        }

        if (summaries.isNotEmpty()) {
            summaryRepo.saveAll(summaries)
        }

        return summaries
    }

    private fun List<CockpitInstanceDto>.toFlowCounts(): FlowCounts {
        val active = count { it.status == StageStatus.Pending || it.status == StageStatus.Running }
        val error = count { it.status == StageStatus.Error }
        val completed = count { it.status == StageStatus.Completed || it.status == StageStatus.Cancelled }
        val notCompleted = size - completed
        return FlowCounts(active = active, error = error, completed = completed, notCompleted = notCompleted)
    }

    private fun stageDefinitionsByFlow(): StageDefinitionsByFlow {
        return engine.registeredFlows().mapValues { (_, flow) ->
            flow.stages.entries.associate { (stage, definition) -> historyValueOf(stage) to definition }
        }
    }

    private fun classifyActivityStatus(
        stageDefinitions: Map<String, StageDefinition<Any, Stage, Event>>?,
        stage: String?,
        status: StageStatus?,
    ): CockpitActivityStatus? {
        return when (status) {
            StageStatus.Running -> CockpitActivityStatus.Running
            StageStatus.Pending -> {
                val definition = stage?.let { stageDefinitions?.get(it) }
                when {
                    definition?.timer != null -> CockpitActivityStatus.WaitingForTimer
                    definition?.eventHandlers?.isNotEmpty() == true -> CockpitActivityStatus.WaitingForEvent
                    else -> CockpitActivityStatus.Pending
                }
            }
            else -> null
        }
    }

    private fun matchesActivityFilter(activityStatus: CockpitActivityStatus?, activityFilter: String?): Boolean {
        if (activityFilter == null) return true
        if (activityFilter == "default") return activityStatus.isCountedAsLongInactiveByDefault()
        return activityStatus?.name == activityFilter
    }

    private fun CockpitActivityStatus?.isCountedAsLongInactiveByDefault(): Boolean {
        return this == CockpitActivityStatus.Running || this == CockpitActivityStatus.Pending
    }

    private fun FlowLiteHistoryRow.asKey() = InstanceKey(flowId = flowId, flowInstanceId = flowInstanceId)

    private fun FlowLiteHistoryRow.stageValue() = toStage ?: stage

    private fun List<FlowLiteHistoryRow>.latestOfType(types: Set<HistoryEntryType>) = asSequence()
        .filter { it.type in types }
        .maxWithOrNull(ROW_RECENCY)

    private fun List<FlowLiteHistoryRow>.latestStageRow() =
        latestOfType(STAGE_ROW_TYPES)
            ?: asSequence().filter { it.stageValue() != null }.maxWithOrNull(ROW_RECENCY)

    private fun FlowLiteHistoryRow.statusValue(): StageStatus? {
        val entry = toHistoryEntry()
        if (entry.type == HistoryEntryType.Error) return StageStatus.Error
        return entry.toStatus
    }
}
