package io.flowlite.cockpit

import io.flowlite.Engine
import io.flowlite.FlowLiteHistoryRepository
import io.flowlite.FlowLiteHistoryRow
import io.flowlite.HistoryEntryType
import io.flowlite.MermaidGenerator
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

class CockpitService(
    private val engine: Engine,
    private val mermaid: MermaidGenerator,
    private val historyRepo: FlowLiteHistoryRepository,
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

    fun listFlows(longRunningThresholdMinutes: Long = 60): List<CockpitFlowDto> {
        val registered = engine.registeredFlows()
        val summariesByFlow = loadInstanceSummaries(flowId = null)
            .filter { registered.containsKey(it.flowId) }
            .groupBy { it.flowId }
        val now = Instant.now()
        val longRunningThreshold = Duration.ofMinutes(longRunningThresholdMinutes.coerceAtLeast(1))

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
                it.status == StageStatus.Running && Duration.between(it.lastUpdatedAt, now) > longRunningThreshold
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

    fun listInstances(flowId: String? = null, bucket: CockpitInstanceBucket? = null): List<CockpitInstanceDto> {
        val summaries = loadInstanceSummaries(flowId)

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

        return filtered.sortedWith(
            compareBy<CockpitInstanceDto> { it.flowId }
                .thenByDescending { it.lastUpdatedAt }
                .thenBy { it.flowInstanceId },
        )
    }

    fun listErrorGroups(flowId: String? = null): List<CockpitErrorGroupDto> {
        val errors = loadInstanceSummaries(flowId)
            .filter { it.status == StageStatus.Error }
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

    private fun loadInstanceSummaries(flowId: String?): List<CockpitInstanceDto> {
        val rowsByKey = historyRepo.findLatestRowsPerType(flowId, INSTANCE_SUMMARY_ROW_TYPES)
            .groupBy { it.asKey() }

        return rowsByKey.mapNotNull { (key, rows) ->
            val stage = rows.latestStageRow()
            val status = rows.latestOfType(STATUS_ROW_TYPES)
            val error = rows.latestOfType(ERROR_ROW_TYPES)

            val lastUpdated = listOfNotNull(stage?.occurredAt, status?.occurredAt, error?.occurredAt)
                .maxOrNull()
                ?: return@mapNotNull null

            CockpitInstanceDto(
                flowId = key.flowId,
                flowInstanceId = key.flowInstanceId,
                stage = stage?.stageValue(),
                status = status?.statusValue(),
                lastUpdatedAt = lastUpdated,
                lastErrorMessage = error?.errorMessage,
            )
        }
    }

    private fun List<CockpitInstanceDto>.toFlowCounts(): FlowCounts {
        val active = count { it.status == StageStatus.Pending || it.status == StageStatus.Running }
        val error = count { it.status == StageStatus.Error }
        val completed = count { it.status == StageStatus.Completed || it.status == StageStatus.Cancelled }
        val notCompleted = size - completed
        return FlowCounts(active = active, error = error, completed = completed, notCompleted = notCompleted)
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
