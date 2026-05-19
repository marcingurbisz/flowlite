package io.flowlite.test

import io.flowlite.HistoryEntry
import io.flowlite.InstanceData
import io.flowlite.RetryState
import io.flowlite.RetryStateStore
import io.flowlite.Stage
import io.flowlite.StageStatus
import io.flowlite.SpringDataJdbcHistoryStore
import io.flowlite.recordError
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.UUID

private val showcaseSeederLog = KotlinLogging.logger {}

internal class ShowcaseErrorCatalogSeeder(
    private val historyStore: SpringDataJdbcHistoryStore,
    private val retryStateStore: RetryStateStore,
    private val orderRepo: OrderConfirmationRepository,
    private val employeeRepo: EmployeeOnboardingRepository,
    enabled: Boolean,
    private val clock: Clock,
) {
    init {
        if (enabled) {
            seedCatalog()
        }
    }

    private fun seedCatalog() {
        seedFinalError()
        seedExternalRetryError()
        seedAutoRetryError()
        showcaseSeederLog.info { "Seeded showcase error catalog with final, external retry, and active auto-retry examples" }
    }

    private fun seedFinalError() {
        val flowInstanceId = deterministicId("showcase-error-final")
        val stage = EmployeeStage.UpdateHRSystem
        val state = EmployeeOnboarding(
            id = flowInstanceId,
            stage = stage,
            stageStatus = StageStatus.Error,
            isOnboardingAutomated = true,
            needsTrainingProgram = true,
            isEngineeringRole = true,
            isNotManualPath = true,
            isNotContractor = true,
            isShowcaseInstance = true,
            employeeProfileCreated = true,
            systemAccessActivated = true,
            externalAccountsCreated = true,
            benefitsEnrollmentUpdated = true,
            documentsGenerated = true,
            contractSentForSigning = true,
        )
        employeeRepo.save(state)

        historyStore.append(
            HistoryEntry.Started(
                flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
                flowInstanceId = flowInstanceId,
                occurredAt = clock.instant(),
                stage = stage.name,
                toStatus = StageStatus.PendingEngine,
            ),
        )
        historyStore.recordError(
            flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
            data = seededData(flowInstanceId, state, stage),
            ex = IllegalArgumentException("Showcase final error example in UpdateHRSystem"),
        )
    }

    private fun seedExternalRetryError() {
        val flowInstanceId = deterministicId("showcase-error-external-retry")
        val stage = OrderConfirmationStage.InformingCustomer
        val state = OrderConfirmation(
            id = flowInstanceId,
            stage = stage,
            stageStatus = StageStatus.Error,
            orderNumber = "SHOW-ERROR-EXTERNAL",
            confirmationType = ConfirmationType.Digital,
            customerName = "Showcase External Retry",
            isRemovedFromQueue = true,
            confirmationTimestamp = clock.instant().toString(),
        )
        val retryState = RetryState(
            flowId = ORDER_CONFIRMATION_FLOW_ID,
            flowInstanceId = flowInstanceId,
            stage = stage.name,
            failedAttemptCount = 2,
            externalRetryAllowed = true,
            autoRetryMaxAttempts = null,
            nextAutoRetryAt = null,
            lastErrorType = IllegalStateException::class.qualifiedName,
            lastErrorMessage = "Showcase external retry error example in InformingCustomer",
            updatedAt = clock.instant(),
        )
        orderRepo.save(state)

        historyStore.append(
            HistoryEntry.Started(
                flowId = ORDER_CONFIRMATION_FLOW_ID,
                flowInstanceId = flowInstanceId,
                occurredAt = clock.instant(),
                stage = stage.name,
                toStatus = StageStatus.PendingEngine,
            ),
        )
        historyStore.recordError(
            flowId = ORDER_CONFIRMATION_FLOW_ID,
            data = seededData(flowInstanceId, state, stage),
            ex = IllegalStateException(requireNotNull(retryState.lastErrorMessage)),
            retryState = retryState,
        )
        retryStateStore.save(retryState)
    }

    private fun seedAutoRetryError() {
        val flowInstanceId = deterministicId("showcase-error-auto-retry")
        val stage = OrderConfirmationStage.InitializingConfirmation
        val state = OrderConfirmation(
            id = flowInstanceId,
            stage = stage,
            stageStatus = StageStatus.Error,
            orderNumber = "SHOW-ERROR-AUTO",
            confirmationType = ConfirmationType.Physical,
            customerName = "Showcase Auto Retry",
        )
        val retryState = RetryState(
            flowId = ORDER_CONFIRMATION_FLOW_ID,
            flowInstanceId = flowInstanceId,
            stage = stage.name,
            failedAttemptCount = 1,
            externalRetryAllowed = true,
            autoRetryMaxAttempts = 3,
            nextAutoRetryAt = clock.instant().plusSeconds(300),
            lastErrorType = IllegalStateException::class.qualifiedName,
            lastErrorMessage = "Showcase auto retry error example in InitializingConfirmation",
            updatedAt = clock.instant(),
        )
        orderRepo.save(state)

        historyStore.append(
            HistoryEntry.Started(
                flowId = ORDER_CONFIRMATION_FLOW_ID,
                flowInstanceId = flowInstanceId,
                occurredAt = clock.instant(),
                stage = stage.name,
                toStatus = StageStatus.PendingEngine,
            ),
        )
        historyStore.recordError(
            flowId = ORDER_CONFIRMATION_FLOW_ID,
            data = seededData(flowInstanceId, state, stage),
            ex = IllegalStateException(requireNotNull(retryState.lastErrorMessage)),
            retryState = retryState,
        )
        retryStateStore.save(retryState)
    }

    private fun deterministicId(seed: String): UUID =
        UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8))

    private fun <T : Any, S> seededData(flowInstanceId: UUID, state: T, stage: S): InstanceData<T>
        where S : Enum<S>, S : Stage =
        InstanceData(
            flowInstanceId = flowInstanceId,
            state = state,
            stage = stage,
            stageStatus = StageStatus.Error,
        )
}