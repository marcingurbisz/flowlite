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

enum class CockpitErrorFilter {
    Final,
    ExternalRetry,
    AutoRetryActive,
}

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
        val finalErrorRowsByFlow = summaryRepo.findFilteredSummaries(
            flowId = null,
            bucket = CockpitInstanceBucket.Error.name,
            status = null,
            searchPattern = null,
            searchFlowInstanceId = null,
            stage = null,
            errorMessagePattern = null,
            showIncompleteOnly = false,
            cockpitStatusFilter = null,
            updatedBefore = null,
        )
            .filter { it.matchesErrorFilter(CockpitErrorFilter.Final) }
            .groupBy { it.flowId }

        return flowMetadataById.keys.sorted().mapNotNull { flowId ->
            val metadata = flowMetadataById[flowId] ?: return@mapNotNull null
            val counts = countsByFlow[flowId]
            val finalErrorRows = finalErrorRowsByFlow[flowId].orEmpty()
            val finalErrorCountByStage = finalErrorRows
                .groupingBy { it.stage }
                .eachCount()
            val stageBreakdown = metadata.stages.mapNotNull { stageName ->
                val stageRows = summaryRepo.findFilteredSummaries(
                    flowId = flowId,
                    bucket = null,
                    status = null,
                    searchPattern = null,
                    searchFlowInstanceId = null,
                    stage = stageName,
                    errorMessagePattern = null,
                    showIncompleteOnly = true,
                    cockpitStatusFilter = null,
                    updatedBefore = null,
                )
                if (stageRows.isEmpty()) return@mapNotNull null
                CockpitFlowStageDto(
                    stage = stageName,
                    totalCount = stageRows.size,
                    errorCount = finalErrorCountByStage[stageName] ?: 0,
                )
            }

            CockpitFlowDto(
                flowId = flowId,
                diagram = metadata.diagram,
                stages = metadata.stages,
                notCompletedCount = counts?.notCompletedCount ?: 0,
                errorCount = finalErrorRows.size,
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
        errorFilter: CockpitErrorFilter? = null,
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
        return rows
            .filter { row -> errorFilter == null || row.matchesErrorFilter(errorFilter) }
            .map { row -> row.toDto() }
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
        val statusValue = runCatching { StageStatus.valueOf(cockpitStatus) }.getOrDefault(StageStatus.PendingEngine)
        val retryInfo = when {
            statusValue != StageStatus.Error -> null
            failedAttemptCount == null || externalRetryAllowed == null -> null
            else -> CockpitRetryInfoDto(
                externalRetryAllowed = externalRetryAllowed,
                autoRetryActive = autoRetryMaxAttempts != null && nextAutoRetryAt != null,
                failedAttemptCount = failedAttemptCount,
                autoRetryMaxAttempts = autoRetryMaxAttempts,
                nextAutoRetryAt = nextAutoRetryAt,
            )
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
    private fun FlowLiteInstanceSummaryRow.matchesErrorFilter(filter: CockpitErrorFilter): Boolean {
        if (cockpitStatus != StageStatus.Error.name) return false
        return when (filter) {
            CockpitErrorFilter.Final -> !isExternalRetryAllowed() && !isAutoRetryActive()
            CockpitErrorFilter.ExternalRetry -> isExternalRetryAllowed()
            CockpitErrorFilter.AutoRetryActive -> isAutoRetryActive()
        }
    }

    private fun FlowLiteInstanceSummaryRow.isExternalRetryAllowed() = externalRetryAllowed == true

    private fun FlowLiteInstanceSummaryRow.isAutoRetryActive() = autoRetryMaxAttempts != null && nextAutoRetryAt != null

}
