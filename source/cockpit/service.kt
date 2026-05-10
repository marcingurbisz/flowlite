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
import io.flowlite.HistoryEntryType
import io.flowlite.MermaidGenerator
import io.flowlite.RetryState
import io.flowlite.RetryStateStore
import io.flowlite.Stage
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
    val cockpitStatus: StageStatus,
    val lastUpdatedAt: Instant,
    val lastErrorMessage: String? = null,
    val retryInfo: CockpitRetryInfoDto? = null,
)

data class CockpitRetryInfoDto(
    val externalRetryAllowed: Boolean,
    val autoRetryActive: Boolean,
    val failedAttemptCount: Int,
    val autoRetryMaxAttempts: Int? = null,
    val nextAutoRetryAt: Instant? = null,
)

enum class CockpitInstanceBucket {
    Active,
    Error,
    Completed,
}

private data class RegisteredFlowMetadata(
    val diagram: String,
    val stages: List<String>,
)

class CockpitService(
    private val engine: Engine,
    private val mermaid: MermaidGenerator,
    private val historyRepo: FlowLiteHistoryRepository,
    private val summaryRepo: FlowLiteInstanceSummaryRepository,
    private val retryStateStore: RetryStateStore,
) {
    private val flowMetadataById by lazy {
        engine.registeredFlows().mapValues { (_, flow) ->
            RegisteredFlowMetadata(
                diagram = mermaid.generateDiagram(flow),
                stages = flow.stages.keys.map { historyValueOf(it) },
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
        status: StageStatus? = null,
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

        val rows = summaryRepo.findFilteredSummaries(
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
        )
        val batchRetryStatesByInstanceId = retryStateStore.findAll(rows.map { it.flowInstanceId })
            .associateBy { it.flowInstanceId }
        val retryStatesByInstanceId = if (rows.isNotEmpty() && batchRetryStatesByInstanceId.isEmpty()) {
            rows.associate { row -> row.flowInstanceId to retryStateStore.find(row.flowInstanceId) }
        } else {
            batchRetryStatesByInstanceId
        }
        return rows.map { row -> row.toDto(retryStatesByInstanceId[row.flowInstanceId]) }
    }

    fun instance(flowId: String, flowInstanceId: UUID): CockpitInstanceDto? {
        return summaryRepo.findSummary(flowId, flowInstanceId)
            ?.toDto(retryStateStore.find(flowInstanceId))
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

    private fun FlowLiteInstanceSummaryRow.toDto(retryState: RetryState?): CockpitInstanceDto {
        val statusValue = runCatching { StageStatus.valueOf(cockpitStatus) }.getOrDefault(StageStatus.PendingEngine)
        val retryInfo = when {
            statusValue != StageStatus.Error -> null
            retryState != null -> CockpitRetryInfoDto(
                externalRetryAllowed = retryState.externalRetryAllowed,
                autoRetryActive = retryState.autoRetryActive,
                failedAttemptCount = retryState.failedAttemptCount,
                autoRetryMaxAttempts = retryState.autoRetryMaxAttempts,
                nextAutoRetryAt = retryState.nextAutoRetryAt,
            )
            else -> historyRepo.findTimeline(flowId, flowInstanceId)
                .lastOrNull { it.type == HistoryEntryType.Error }
                ?.let { historyRow ->
                    CockpitRetryInfoDto(
                        externalRetryAllowed = historyRow.externalRetryAllowed == true,
                        autoRetryActive = historyRow.autoRetryMaxAttempts != null && historyRow.nextAutoRetryAt != null,
                        failedAttemptCount = historyRow.failedAttemptCount ?: 0,
                        autoRetryMaxAttempts = historyRow.autoRetryMaxAttempts,
                        nextAutoRetryAt = historyRow.nextAutoRetryAt,
                    )
                }
        }
        return CockpitInstanceDto(
            flowId = flowId,
            flowInstanceId = flowInstanceId,
            stage = stage,
            cockpitStatus = statusValue,
            lastUpdatedAt = updatedAt,
            lastErrorMessage = lastErrorMessage,
            retryInfo = retryInfo,
        )
    }

    private fun FlowLiteFlowStageBreakdownRow.toDto() =
        CockpitFlowStageDto(
            stage = stage,
            totalCount = totalCount,
            errorCount = errorCount,
        )

}
