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
    fun listFlows(longRunningThresholdSeconds: Long = 3600): List<CockpitFlowDto> {
        val registered = engine.registeredFlows()
        val longRunningThreshold = Duration.ofSeconds(longRunningThresholdSeconds.coerceAtLeast(1))
        val updatedBefore = Instant.now().minus(longRunningThreshold)
        val countsByFlow = summaryRepo.findFlowSummaryAggregates(updatedBefore)
            .associateBy { it.flowId }
        val stageBreakdownByFlow = summaryRepo.findIncompleteStageBreakdown()
            .groupBy { it.flowId }

        return registered.keys.sorted().mapNotNull { flowId ->
            val flow = registered[flowId] ?: return@mapNotNull null
            val counts = countsByFlow[flowId]
            val stageBreakdown = stageBreakdownByFlow[flowId].orEmpty().map { it.toDto() }

            CockpitFlowDto(
                flowId = flowId,
                diagram = mermaid.generateDiagram(flow),
                stages = flow.stages.keys.map { historyValueOf(it) },
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
        status: StageStatus? = null,
        searchTerm: String? = null,
        stage: String? = null,
        errorMessage: String? = null,
        showIncompleteOnly: Boolean = false,
        activityFilter: String? = null,
        longInactiveThresholdSeconds: Long? = null,
    ): List<CockpitInstanceDto> {
        val now = Instant.now()
        val longInactiveThreshold = longInactiveThresholdSeconds
            ?.coerceAtLeast(1)
            ?.let(Duration::ofSeconds)
        val normalizedSearchTerm = searchTerm?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
        val normalizedStage = stage?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedErrorMessage = errorMessage?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
        val normalizedActivityFilter = activityFilter?.trim()?.takeIf { it.isNotEmpty() && it != "all" }
        val updatedBefore = longInactiveThreshold?.let { now.minus(it) }
        val stageDefinitionsByFlow = stageDefinitionsByFlow()

        return summaryRepo.findFilteredSummaries(
            flowId = flowId,
            bucket = bucket?.name,
            status = status?.name,
            searchPattern = normalizedSearchTerm?.let { "%$it%" },
            stage = normalizedStage,
            errorMessagePattern = normalizedErrorMessage?.let { "%$it%" },
            showIncompleteOnly = showIncompleteOnly,
            activityFilter = normalizedActivityFilter,
            updatedBefore = updatedBefore,
        ).map { row -> row.toDto(stageDefinitionsByFlow) }
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
        val stageDefinitionsByFlow = stageDefinitionsByFlow()
        return summaryRepo.findSummary(flowId, flowInstanceId)
            ?.toDto(stageDefinitionsByFlow)
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

    private typealias StageDefinitionsByFlow = Map<String, Map<String, StageDefinition<Any, Stage, Event>>>

    private fun loadInstanceSummaries(flowId: String?): List<CockpitInstanceDto> {
        val stageDefinitionsByFlow = stageDefinitionsByFlow()
        val rows = if (flowId == null) summaryRepo.findAllSummaries() else summaryRepo.findAllSummariesByFlowId(flowId)

        return rows.map { row -> row.toDto(stageDefinitionsByFlow) }
    }

    private fun stageDefinitionsByFlow(registeredFlows: Map<String, Flow<Any, Stage, Event>> = engine.registeredFlows()): StageDefinitionsByFlow {
        return registeredFlows.mapValues { (_, flow) ->
            flow.stages.entries.associate { (stage, definition) -> historyValueOf(stage) to definition }
        }
    }

    private fun FlowLiteInstanceSummaryRow.toDto(stageDefinitionsByFlow: StageDefinitionsByFlow): CockpitInstanceDto {
        val statusValue = status?.let(StageStatus::valueOf)
        val activityStatusValue = activityStatus?.let(CockpitActivityStatus::valueOf)
            ?: classifyCockpitActivityStatus(stageDefinitionsByFlow[flowId], stage, statusValue)
        return CockpitInstanceDto(
            flowId = flowId,
            flowInstanceId = flowInstanceId,
            stage = stage,
            status = statusValue,
            activityStatus = activityStatusValue,
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

internal fun classifyCockpitActivityStatus(
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
