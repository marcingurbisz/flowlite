package io.flowlite.cockpit

import io.flowlite.Engine
import io.flowlite.Event
import io.flowlite.Flow
import io.flowlite.FlowLiteHistoryRepository
import io.flowlite.FlowLiteHistoryRow
import io.flowlite.FlowLiteFlowStageBreakdownRow
import io.flowlite.FlowLiteFlowSummaryAggregateRow
import io.flowlite.FlowLiteInstanceSummaryRepository
import io.flowlite.FlowLiteInstanceSummaryRow
import io.flowlite.MermaidGenerator
import io.flowlite.Stage
import io.flowlite.StageDefinition
import io.flowlite.StageStatus
import io.flowlite.historyValueOf
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
    val cockpitStatus: CockpitStatus,
    val lastUpdatedAt: Instant,
    val lastErrorMessage: String? = null,
)

data class CockpitErrorGroupDto(
    val flowId: String,
    val stage: String?,
    val count: Int,
)

enum class CockpitInstanceBucket {
    Active,
    Error,
    Completed,
}

enum class CockpitStatus {
    Running,
    WaitingForTimer,
    WaitingForEvent,
    PendingEngine,
    Error,
    Completed,
    Cancelled,
}

private data class RegisteredFlowMetadata(
    val diagram: String,
    val stages: List<String>,
    val stageDefinitions: Map<String, StageDefinition<Any, Stage, Event>>,
)

class CockpitService(
    private val engine: Engine,
    private val mermaid: MermaidGenerator,
    private val historyRepo: FlowLiteHistoryRepository,
    private val summaryRepo: FlowLiteInstanceSummaryRepository,
) {
    private val flowMetadataById by lazy {
        engine.registeredFlows().mapValues { (_, flow) ->
            RegisteredFlowMetadata(
                diagram = mermaid.generateDiagram(flow),
                stages = flow.stages.keys.map { historyValueOf(it) },
                stageDefinitions = flow.stages.entries.associate { (stage, definition) -> historyValueOf(stage) to definition },
            )
        }
    }

    fun listFlows(longRunningThresholdSeconds: Long = 3600): List<CockpitFlowDto> {
        val longRunningThreshold = Duration.ofSeconds(longRunningThresholdSeconds.coerceAtLeast(1))
        val updatedBefore = Instant.now().minus(longRunningThreshold)
        val countsByFlow = summaryRepo.findFlowSummaryAggregates(updatedBefore)
            .associateBy { it.flowId }
        val stageBreakdownByFlow = summaryRepo.findIncompleteStageBreakdown()
            .groupBy { it.flowId }

        return flowMetadataById.keys.sorted().mapNotNull { flowId ->
            val metadata = flowMetadataById[flowId] ?: return@mapNotNull null
            val counts = countsByFlow[flowId]
            val stageBreakdown = stageBreakdownByFlow[flowId].orEmpty().map { it.toDto() }

            CockpitFlowDto(
                flowId = flowId,
                diagram = metadata.diagram,
                stages = metadata.stages,
                notCompletedCount = counts?.notCompletedCount ?: 0,
                errorCount = counts?.errorCount ?: 0,
                activeCount = counts?.activeCount ?: 0,
                completedCount = counts?.completedCount ?: 0,
                longRunningCount = counts?.longRunningCount ?: 0,
                stageBreakdown = stageBreakdown,
            )
        }
    }

    fun listInstances(
        flowId: String? = null,
        bucket: CockpitInstanceBucket? = null,
        status: CockpitStatus? = null,
        searchTerm: String? = null,
        stage: String? = null,
        errorMessage: String? = null,
        showIncompleteOnly: Boolean = false,
        cockpitStatusFilter: String? = null,
        longInactiveThresholdSeconds: Long? = null,
    ): List<CockpitInstanceDto> {
        val now = Instant.now()
        val longInactiveThreshold = longInactiveThresholdSeconds
            ?.coerceAtLeast(1)
            ?.let(Duration::ofSeconds)
        val normalizedSearchTerm = searchTerm?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
        val exactFlowInstanceId = normalizedSearchTerm?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val normalizedStage = stage?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedErrorMessage = errorMessage?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
        val normalizedCockpitStatusFilter = cockpitStatusFilter?.trim()?.takeIf { it.isNotEmpty() && it != "all" }
        val updatedBefore = longInactiveThreshold?.let { now.minus(it) }

        return summaryRepo.findFilteredSummaries(
            flowId = flowId,
            bucket = bucket?.name,
            status = status?.name,
            searchPattern = normalizedSearchTerm?.let { "%$it%" },
            searchFlowInstanceId = exactFlowInstanceId,
            stage = normalizedStage,
            errorMessagePattern = normalizedErrorMessage?.let { "%$it%" },
            showIncompleteOnly = showIncompleteOnly,
            cockpitStatusFilter = normalizedCockpitStatusFilter,
            updatedBefore = updatedBefore,
        ).map { row -> row.toDto() }
    }

    fun listErrorGroups(
        flowId: String? = null,
        stageContains: String? = null,
        errorMessage: String? = null,
    ): List<CockpitErrorGroupDto> {
        val normalizedStage = stageContains?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
        val normalizedErrorMessage = errorMessage?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
        return summaryRepo.findErrorGroups(
            flowId = flowId,
            stagePattern = normalizedStage?.let { "%$it%" },
            errorMessagePattern = normalizedErrorMessage?.let { "%$it%" },
        ).map {
            CockpitErrorGroupDto(
                flowId = it.flowId,
                stage = it.stage,
                count = it.count,
            )
        }
    }

    fun instance(flowId: String, flowInstanceId: UUID): CockpitInstanceDto? {
        return summaryRepo.findSummary(flowId, flowInstanceId)
            ?.toDto()
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

    private fun FlowLiteInstanceSummaryRow.toDto(): CockpitInstanceDto {
        val statusValue = runCatching { CockpitStatus.valueOf(cockpitStatus) }.getOrDefault(CockpitStatus.PendingEngine)
        return CockpitInstanceDto(
            flowId = flowId,
            flowInstanceId = flowInstanceId,
            stage = stage,
            cockpitStatus = statusValue,
            lastUpdatedAt = updatedAt,
            lastErrorMessage = lastErrorMessage,
        )
    }

    private fun FlowLiteFlowStageBreakdownRow.toDto() =
        CockpitFlowStageDto(
            stage = stage,
            totalCount = totalCount,
            errorCount = errorCount,
        )

}

internal fun classifyCockpitStatus(
    stageDefinitions: Map<String, StageDefinition<Any, Stage, Event>>?,
    stage: String?,
    status: StageStatus?,
): CockpitStatus? {
    return when (status) {
        StageStatus.Running -> CockpitStatus.Running
        StageStatus.Pending -> {
            val definition = stage?.let { stageDefinitions?.get(it) }
            when {
                definition?.timer != null -> CockpitStatus.WaitingForTimer
                definition?.eventHandlers?.isNotEmpty() == true -> CockpitStatus.WaitingForEvent
                else -> CockpitStatus.PendingEngine
            }
        }
        StageStatus.Error -> CockpitStatus.Error
        StageStatus.Completed -> CockpitStatus.Completed
        StageStatus.Cancelled -> CockpitStatus.Cancelled
        null -> null
    }
}
