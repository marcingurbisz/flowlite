package io.flowlite.cockpit

import io.flowlite.HistoryEntryType
import io.flowlite.StageStatus
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

data class CockpitHistoryEntryDto(
    val occurredAt: Instant,
    val type: HistoryEntryType,
    val stage: String? = null,
    val fromStage: String? = null,
    val toStage: String? = null,
    val fromStatus: String? = null,
    val toStatus: String? = null,
    val event: String? = null,
    val errorType: String? = null,
    val errorMessage: String? = null,
    val errorStackTrace: String? = null,
)

enum class CockpitInstanceBucket {
    Active,
    Error,
    Completed,
}
