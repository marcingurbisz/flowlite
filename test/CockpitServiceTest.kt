package io.flowlite.test

import io.flowlite.FlowLiteHistoryRepository
import io.flowlite.FlowLiteHistoryRow
import io.flowlite.FlowLiteInstanceSummaryRepository
import io.flowlite.HistoryEntryType
import io.flowlite.RetryState
import io.flowlite.RetryStateStore
import io.flowlite.RetryTrigger
import io.flowlite.SpringDataJdbcHistoryStore
import io.flowlite.StageStatus
import io.flowlite.toHistoryEntry
import io.flowlite.cockpit.CockpitInstanceBucket
import io.flowlite.cockpit.CockpitService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.beans.factory.getBean

class CockpitServiceTest : BehaviorSpec({
    val context = startTestApplication()
    val service = context.getBean<CockpitService>()
    val historyRepo = context.getBean<FlowLiteHistoryRepository>()
    val summaryRepo = context.getBean<FlowLiteInstanceSummaryRepository>()
    val retryStateStore = context.getBean<RetryStateStore>()
    val historyStore = context.getBean<SpringDataJdbcHistoryStore>()

    afterSpec {
        context.close()
    }

    given("listInstances") {
        `when`("history rows contain active, error, completed and cancelled instances") {
            val flowA = "flow-a"
            val flowB = "flow-b"

            val aRunning = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val aError1 = UUID.fromString("00000000-0000-0000-0000-000000000002")
            val aError2 = UUID.fromString("00000000-0000-0000-0000-000000000003")
            val bCancelled = UUID.fromString("00000000-0000-0000-0000-000000000004")
            val bCompleted = UUID.fromString("00000000-0000-0000-0000-000000000005")
            val bError = UUID.fromString("00000000-0000-0000-0000-000000000006")

            fun seedRows() {
                summaryRepo.deleteAll()
                historyRepo.deleteAll()
                listOf(
                    historyRow("2026-03-04T08:00:00Z", flowA, aRunning, HistoryEntryType.Started, stage = "Init", toStatus = StageStatus.PendingEngine),
                    historyRow("2026-03-04T08:01:00Z", flowA, aRunning, HistoryEntryType.StatusChanged, stage = "Init", fromStatus = StageStatus.PendingEngine, toStatus = StageStatus.Running),
                    historyRow("2026-03-04T08:02:00Z", flowA, aRunning, HistoryEntryType.StageChanged, fromStage = "Init", toStage = "Review"),
                    historyRow("2026-03-04T08:03:00Z", flowA, aError1, HistoryEntryType.Error, stage = "Review", fromStatus = StageStatus.Running, toStatus = StageStatus.Error, errorMessage = "boom-1"),
                    historyRow("2026-03-04T08:04:00Z", flowA, aError2, HistoryEntryType.Error, stage = "Review", fromStatus = StageStatus.Running, toStatus = StageStatus.Error, errorMessage = "boom-2", failedAttemptCount = 2, autoRetryMaxAttempts = 5, nextAutoRetryAt = Instant.parse("2026-03-04T08:10:00Z"), externalRetryAllowed = true),
                    historyRow("2026-03-04T08:05:00Z", flowB, bCancelled, HistoryEntryType.Cancelled, stage = "Done", fromStatus = StageStatus.Running, toStatus = StageStatus.Cancelled),
                    historyRow("2026-03-04T08:06:00Z", flowB, bCompleted, HistoryEntryType.StatusChanged, stage = "Done", fromStatus = StageStatus.Running, toStatus = StageStatus.Completed),
                    historyRow("2026-03-04T08:07:00Z", flowB, bError, HistoryEntryType.Error, stage = "Investigate", fromStatus = StageStatus.Running, toStatus = StageStatus.Error, errorMessage = "boom-3"),
                ).forEach { historyStore.append(it.toHistoryEntry()) }
            }

            seedRows()

            then("listInstances returns expected sorting and bucket projections") {
                service.listInstances().map { it.flowInstanceId } shouldContainExactly listOf(
                    aError2,
                    aError1,
                    aRunning,
                    bError,
                    bCompleted,
                    bCancelled,
                )

                service.listInstances(bucket = CockpitInstanceBucket.Active).map { it.flowInstanceId } shouldContainExactly listOf(aRunning)
                service.listInstances(bucket = CockpitInstanceBucket.Error).map { it.flowInstanceId } shouldContainExactly listOf(aError2, aError1, bError)
                service.listInstances(bucket = CockpitInstanceBucket.Completed).map { it.flowInstanceId } shouldContainExactly listOf(bCompleted, bCancelled)
                service.listInstances(flowId = flowA).map { it.flowInstanceId } shouldContainExactly listOf(aError2, aError1, aRunning)
            }

            then("listInstances applies backend filters") {
                service.listInstances(
                    flowId = flowA,
                    status = StageStatus.Error,
                    stage = "Review",
                    errorMessage = "boom-2",
                    showIncompleteOnly = true,
                ).map { it.flowInstanceId } shouldContainExactly listOf(aError2)

                service.listInstances(searchTerm = bError.toString())
                    .map { it.flowInstanceId } shouldContainExactly listOf(bError)
            }

            then("listInstances and instance expose retry metadata for error rows") {
                retryStateStore.save(
                    RetryState(
                        flowId = flowA,
                        flowInstanceId = aError2,
                        stage = "Review",
                        failedAttemptCount = 2,
                        externalRetryAllowed = true,
                        autoRetryMaxAttempts = 5,
                        nextAutoRetryAt = Instant.parse("2026-03-04T08:10:00Z"),
                        lastErrorType = "java.io.IOException",
                        lastErrorMessage = "boom-2",
                        updatedAt = Instant.parse("2026-03-04T08:05:00Z"),
                    ),
                )

                val listDto = service.listInstances(flowId = flowA, status = StageStatus.Error)
                    .first { it.flowInstanceId == aError2 }
                listDto.retryInfo?.failedAttemptCount shouldBe 2
                listDto.retryInfo?.externalRetryAllowed shouldBe true
                listDto.retryInfo?.autoRetryActive shouldBe true

                val instanceDto = service.instance(flowA, aError2)
                instanceDto?.retryInfo?.autoRetryMaxAttempts shouldBe 5
                instanceDto?.retryInfo?.nextAutoRetryAt shouldBe Instant.parse("2026-03-04T08:10:00Z")
            }

        }
    }

    given("listFlows") {
        `when`("registered flows have mixed instance statuses") {
            val orderActive = UUID.fromString("00000000-0000-0000-0000-000000000101")
            val orderWaitingForEvent = UUID.fromString("00000000-0000-0000-0000-000000000105")
            val orderError = UUID.fromString("00000000-0000-0000-0000-000000000102")
            val onboardingCompleted = UUID.fromString("00000000-0000-0000-0000-000000000103")
            val onboardingWaitingForTimer = UUID.fromString("00000000-0000-0000-0000-000000000106")
            val onboardingPendingEngine = UUID.fromString("00000000-0000-0000-0000-000000000107")
            val unknownFlow = UUID.fromString("00000000-0000-0000-0000-000000000104")

            then("it returns diagrams and per-flow counters only for registered flows") {
                summaryRepo.deleteAll()
                historyRepo.deleteAll()
                listOf(
                    historyRow("2026-03-04T09:00:00Z", ORDER_CONFIRMATION_FLOW_ID, orderActive, HistoryEntryType.StatusChanged, stage = "WaitingForConfirmation", fromStatus = StageStatus.WaitingForEvent, toStatus = StageStatus.Running),
                    historyRow("2026-03-04T08:30:00Z", ORDER_CONFIRMATION_FLOW_ID, orderWaitingForEvent, HistoryEntryType.Started, stage = "WaitingForConfirmation", toStatus = StageStatus.WaitingForEvent),
                    historyRow("2026-03-04T09:01:00Z", ORDER_CONFIRMATION_FLOW_ID, orderError, HistoryEntryType.Error, stage = "InformingCustomer", fromStatus = StageStatus.Running, toStatus = StageStatus.Error, errorMessage = "order-failed"),
                    historyRow("2026-03-04T09:02:00Z", EMPLOYEE_ONBOARDING_FLOW_ID, onboardingCompleted, HistoryEntryType.StatusChanged, stage = "CompleteOnboarding", fromStatus = StageStatus.Running, toStatus = StageStatus.Completed),
                    historyRow("2026-03-04T08:00:00Z", EMPLOYEE_ONBOARDING_FLOW_ID, onboardingWaitingForTimer, HistoryEntryType.Started, stage = "DelayAfterHRUpdate", toStatus = StageStatus.WaitingForTimer),
                    historyRow("2026-03-04T07:30:00Z", EMPLOYEE_ONBOARDING_FLOW_ID, onboardingPendingEngine, HistoryEntryType.Started, stage = "GenerateOnboardingDocuments", toStatus = StageStatus.PendingEngine),
                    historyRow("2026-03-04T09:03:00Z", "unknown-flow", unknownFlow, HistoryEntryType.StatusChanged, stage = "X", fromStatus = StageStatus.PendingEngine, toStatus = StageStatus.Running),
                ).forEach { historyStore.append(it.toHistoryEntry()) }

                val flows = service.listFlows(longRunningThresholdSeconds = 3600)

                flows.map { it.flowId } shouldContainExactly listOf(EMPLOYEE_ONBOARDING_FLOW_ID, ORDER_CONFIRMATION_FLOW_ID)

                val onboarding = flows.first { it.flowId == EMPLOYEE_ONBOARDING_FLOW_ID }
                onboarding.activeCount shouldBe 2
                onboarding.errorCount shouldBe 0
                onboarding.completedCount shouldBe 1
                onboarding.longRunningCount shouldBe 1
                onboarding.notCompletedCount shouldBe 2
                onboarding.stageBreakdown shouldContainExactly listOf(
                    io.flowlite.cockpit.CockpitFlowStageDto(
                        stage = "DelayAfterHRUpdate",
                        totalCount = 1,
                        errorCount = 0,
                    ),
                    io.flowlite.cockpit.CockpitFlowStageDto(
                        stage = "GenerateOnboardingDocuments",
                        totalCount = 1,
                        errorCount = 0,
                    ),
                )
                onboarding.diagram.contains("stateDiagram-v2") shouldBe true

                val order = flows.first { it.flowId == ORDER_CONFIRMATION_FLOW_ID }
                order.activeCount shouldBe 2
                order.errorCount shouldBe 1
                order.completedCount shouldBe 0
                order.longRunningCount shouldBe 1
                order.notCompletedCount shouldBe 3
                order.stageBreakdown shouldContainExactly listOf(
                    io.flowlite.cockpit.CockpitFlowStageDto(
                        stage = "InformingCustomer",
                        totalCount = 1,
                        errorCount = 1,
                    ),
                    io.flowlite.cockpit.CockpitFlowStageDto(
                        stage = "WaitingForConfirmation",
                        totalCount = 2,
                        errorCount = 0,
                    ),
                )
                order.diagram.contains("stateDiagram-v2") shouldBe true
            }

            then("it derives cockpit status for pending event and timer stages") {
                summaryRepo.deleteAll()
                historyRepo.deleteAll()
                listOf(
                    historyRow("2026-03-04T09:00:00Z", ORDER_CONFIRMATION_FLOW_ID, orderActive, HistoryEntryType.StatusChanged, stage = "WaitingForConfirmation", fromStatus = StageStatus.WaitingForEvent, toStatus = StageStatus.Running),
                    historyRow("2026-03-04T08:30:00Z", ORDER_CONFIRMATION_FLOW_ID, orderWaitingForEvent, HistoryEntryType.Started, stage = "WaitingForConfirmation", toStatus = StageStatus.WaitingForEvent),
                    historyRow("2026-03-04T08:00:00Z", EMPLOYEE_ONBOARDING_FLOW_ID, onboardingWaitingForTimer, HistoryEntryType.Started, stage = "DelayAfterHRUpdate", toStatus = StageStatus.WaitingForTimer),
                ).forEach { historyStore.append(it.toHistoryEntry()) }

                val instances = service.listInstances().associateBy { it.flowInstanceId }

                instances[orderActive]?.cockpitStatus shouldBe StageStatus.Running
                instances[orderWaitingForEvent]?.cockpitStatus shouldBe StageStatus.WaitingForEvent
                instances[onboardingWaitingForTimer]?.cockpitStatus shouldBe StageStatus.WaitingForTimer
            }

            then("it applies backend long inactive cockpit status filters") {
                val now = Instant.now()
                val onboardingPendingEngine = UUID.fromString("00000000-0000-0000-0000-000000000107")

                summaryRepo.deleteAll()
                historyRepo.deleteAll()
                listOf(
                    historyRow(now.minus(Duration.ofMinutes(30)).toString(), ORDER_CONFIRMATION_FLOW_ID, orderActive, HistoryEntryType.StatusChanged, stage = "WaitingForConfirmation", fromStatus = StageStatus.WaitingForEvent, toStatus = StageStatus.Running),
                    historyRow(now.minus(Duration.ofHours(2)).toString(), ORDER_CONFIRMATION_FLOW_ID, orderWaitingForEvent, HistoryEntryType.Started, stage = "WaitingForConfirmation", toStatus = StageStatus.WaitingForEvent),
                    historyRow(now.minus(Duration.ofMinutes(90)).toString(), EMPLOYEE_ONBOARDING_FLOW_ID, onboardingWaitingForTimer, HistoryEntryType.Started, stage = "DelayAfterHRUpdate", toStatus = StageStatus.WaitingForTimer),
                    historyRow(now.minus(Duration.ofMinutes(95)).toString(), EMPLOYEE_ONBOARDING_FLOW_ID, onboardingPendingEngine, HistoryEntryType.Started, stage = "GenerateOnboardingDocuments", toStatus = StageStatus.PendingEngine),
                ).forEach { historyStore.append(it.toHistoryEntry()) }

                service.listInstances(
                    bucket = CockpitInstanceBucket.Active,
                    cockpitStatusFilter = "default",
                    longInactiveThresholdSeconds = 3600,
                ).map { it.flowInstanceId } shouldContainExactly listOf(onboardingPendingEngine)

                service.listInstances(
                    bucket = CockpitInstanceBucket.Active,
                    cockpitStatusFilter = StageStatus.WaitingForEvent.name,
                    longInactiveThresholdSeconds = 1800,
                ).map { it.flowInstanceId } shouldContainExactly listOf(orderWaitingForEvent)
            }
        }
    }

    given("timeline projection") {
        `when`("rows exist for a flow instance") {
            val flowId = ORDER_CONFIRMATION_FLOW_ID
            val id = UUID.fromString("00000000-0000-0000-0000-000000000201")

            then("timeline returns rows in repository order") {
                summaryRepo.deleteAll()
                historyRepo.deleteAll()
                listOf(
                    historyRow("2026-03-04T10:00:00Z", flowId, id, HistoryEntryType.Started, stage = "InitializingConfirmation", toStatus = StageStatus.PendingEngine),
                    historyRow("2026-03-04T10:01:00Z", flowId, id, HistoryEntryType.Error, stage = "InitializingConfirmation", fromStatus = StageStatus.Running, toStatus = StageStatus.Error, errorMessage = "boom", failedAttemptCount = 1, autoRetryMaxAttempts = 3, nextAutoRetryAt = Instant.parse("2026-03-04T10:05:00Z"), externalRetryAllowed = true),
                    historyRow("2026-03-04T10:02:00Z", flowId, id, HistoryEntryType.Retried, stage = "InitializingConfirmation", fromStatus = StageStatus.Error, toStatus = StageStatus.PendingEngine, retryTrigger = RetryTrigger.External),
                    historyRow("2026-03-04T10:03:00Z", flowId, id, HistoryEntryType.EventAppended, event = OrderConfirmationEvent.Confirmed.name),
                ).forEach { historyStore.append(it.toHistoryEntry()) }

                val timeline = service.timeline(flowId, id)
                timeline.map { it.type } shouldContainExactly listOf(HistoryEntryType.Started, HistoryEntryType.Error, HistoryEntryType.Retried, HistoryEntryType.EventAppended)
                timeline[1].failedAttemptCount shouldBe 1
                timeline[1].externalRetryAllowed shouldBe true
                timeline[2].retryTrigger shouldBe RetryTrigger.External.name
                timeline.map { it.event } shouldContainExactly listOf(null, null, null, OrderConfirmationEvent.Confirmed.name)
            }
        }
    }
})

private fun historyRow(
    occurredAt: String,
    flowId: String,
    flowInstanceId: UUID,
    type: HistoryEntryType,
    stage: String? = null,
    fromStage: String? = null,
    toStage: String? = null,
    fromStatus: StageStatus? = null,
    toStatus: StageStatus? = null,
    event: String? = null,
    errorType: String? = null,
    errorMessage: String? = null,
    errorStackTrace: String? = null,
    retryTrigger: RetryTrigger? = null,
    failedAttemptCount: Int? = null,
    autoRetryMaxAttempts: Int? = null,
    nextAutoRetryAt: Instant? = null,
    externalRetryAllowed: Boolean? = null,
) = FlowLiteHistoryRow(
    occurredAt = Instant.parse(occurredAt),
    flowId = flowId,
    flowInstanceId = flowInstanceId,
    type = type,
    stage = stage,
    fromStage = fromStage,
    toStage = toStage,
    fromStatus = fromStatus?.name,
    toStatus = toStatus?.name,
    event = event,
    errorType = errorType,
    errorMessage = errorMessage,
    errorStackTrace = errorStackTrace,
    retryTrigger = retryTrigger?.name,
    failedAttemptCount = failedAttemptCount,
    autoRetryMaxAttempts = autoRetryMaxAttempts,
    nextAutoRetryAt = nextAutoRetryAt,
    externalRetryAllowed = externalRetryAllowed,
)