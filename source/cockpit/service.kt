package io.flowlite.cockpit

import io.flowlite.FlowEngine
import io.flowlite.FlowLiteHistoryRepository
import io.flowlite.FlowLiteHistoryRow
import io.flowlite.HistoryEntryType
import io.flowlite.MermaidGenerator
import io.flowlite.StageStatus
import io.flowlite.historyValueOf
import io.flowlite.toHistoryEntry
import java.util.UUID

class CockpitService(
    private val engine: FlowEngine,
    private val mermaid: MermaidGenerator,
    private val historyRepo: FlowLiteHistoryRepository,
) {
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
        val stageByKey = historyRepo.findLatestStageRows(flowId).associateBy { it.asKey() }
        val statusByKey = historyRepo.findLatestStatusRows(flowId).associateBy { it.asKey() }
        val lastErrorByKey = historyRepo.findLatestErrorRows(flowId).associateBy { it.asKey() }

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

    fun timeline(flowId: String, flowInstanceId: UUID): List<CockpitHistoryEntryDto> {
        return historyRepo.findTimeline(flowId = flowId, flowInstanceId = flowInstanceId)
            .map { it.toHistoryEntry() }
            .map {
                CockpitHistoryEntryDto(
                    occurredAt = it.occurredAt,
                    type = it.type,
                    stage = it.stage,
                    fromStage = it.fromStage,
                    toStage = it.toStage,
                    fromStatus = it.fromStatus?.name,
                    toStatus = it.toStatus?.name,
                    event = it.event,
                    errorType = it.errorType,
                    errorMessage = it.errorMessage,
                    errorStackTrace = it.errorStackTrace,
                )
            }
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

    private fun FlowLiteHistoryRow.stageValue(): String? = toStage ?: stage

    private fun FlowLiteHistoryRow.statusValue(): StageStatus? {
        val entry = toHistoryEntry()
        if (entry.type == HistoryEntryType.Error) return StageStatus.Error
        return entry.toStatus
    }
}
