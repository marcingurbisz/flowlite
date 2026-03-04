package io.flowlite.cockpit

import io.flowlite.Engine
import io.flowlite.FlowLiteHistoryRepository
import io.flowlite.FlowLiteHistoryRow
import io.flowlite.HistoryEntryType
import io.flowlite.MermaidGenerator
import io.flowlite.StageStatus
import io.flowlite.historyValueOf
import io.flowlite.toHistoryEntry
import java.time.Instant
import java.util.UUID

data class CockpitFlowDto(
    val flowId: String,
    val diagram: String,
    val stages: List<String>,
    val notCompletedCount: Int,
    val errorCount: Int,
    val activeCount: Int,
    val completedCount: Int,
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
        val STAGE_ROW_TYPES = listOf(
            HistoryEntryType.Started.name,
            HistoryEntryType.StageChanged.name,
            HistoryEntryType.Error.name,
        )

        val STATUS_ROW_TYPES = listOf(
            HistoryEntryType.Started.name,
            HistoryEntryType.StatusChanged.name,
            HistoryEntryType.Cancelled.name,
            HistoryEntryType.Error.name,
        )

        val ERROR_ROW_TYPES = listOf(HistoryEntryType.Error.name)
    }

    fun listFlows(): List<CockpitFlowDto> {
        val registered = engine.registeredFlows()
        val counts = countsByFlow(registered.keys)

        return registered.keys.sorted().mapNotNull { flowId ->
            val flow = registered[flowId] ?: return@mapNotNull null
            val c = counts[flowId] ?: FlowCounts(0, 0, 0, 0)
            CockpitFlowDto(
                flowId = flowId,
                diagram = mermaid.generateDiagram(flow),
                stages = flow.stages.keys.map { historyValueOf(it) },
                notCompletedCount = c.notCompleted,
                errorCount = c.error,
                activeCount = c.active,
                completedCount = c.completed,
            )
        }
    }

    fun listInstances(flowId: String? = null, bucket: CockpitInstanceBucket? = null): List<CockpitInstanceDto> {
        val stageByKey = historyRepo.findLatestRows(flowId, STAGE_ROW_TYPES).associateBy { it.asKey() }
        val statusByKey = historyRepo.findLatestRows(flowId, STATUS_ROW_TYPES).associateBy { it.asKey() }
        val lastErrorByKey = historyRepo.findLatestRows(flowId, ERROR_ROW_TYPES).associateBy { it.asKey() }

        val keys = (stageByKey.keys + statusByKey.keys + lastErrorByKey.keys).toSet()

        val summaries = keys.mapNotNull { key ->
            val stage = stageByKey[key]
            val status = statusByKey[key]
            val error = lastErrorByKey[key]

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
        val errors = listInstances(flowId, CockpitInstanceBucket.Error)
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

    private fun countsByFlow(flowIds: Collection<String>): Map<String, FlowCounts> {
        val summaries = listInstances(flowId = null, bucket = null)
            .filter { flowIds.contains(it.flowId) }

        return flowIds.associateWith { fid ->
            val rows = summaries.filter { it.flowId == fid }
            val active = rows.count { it.status == StageStatus.Pending || it.status == StageStatus.Running }
            val error = rows.count { it.status == StageStatus.Error }
            val completed = rows.count {
                it.status == StageStatus.Completed || it.status == StageStatus.Cancelled
            }
            val notCompleted = rows.size - completed
            FlowCounts(active = active, error = error, completed = completed, notCompleted = notCompleted)
        }
    }

    private fun FlowLiteHistoryRow.asKey() = InstanceKey(flowId = flowId, flowInstanceId = flowInstanceId)

    private fun FlowLiteHistoryRow.stageValue() = toStage ?: stage

    private fun FlowLiteHistoryRow.statusValue(): StageStatus? {
        val entry = toHistoryEntry()
        if (entry.type == HistoryEntryType.Error) return StageStatus.Error
        return entry.toStatus
    }
}
