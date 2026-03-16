package io.flowlite.test

import io.flowlite.FlowLiteHistoryRepository
import io.flowlite.FlowLiteHistoryRow
import io.flowlite.HistoryEntryType
import io.flowlite.StageStatus
import io.flowlite.cockpit.CockpitErrorGroupDto
import io.flowlite.cockpit.CockpitInstanceBucket
import io.flowlite.cockpit.CockpitService
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID
import org.springframework.beans.factory.getBean

class CockpitServiceTest : BehaviorSpec({
    val context = startTestApplication()
    val service = context.getBean<CockpitService>()
    val historyRepo = context.getBean<FlowLiteHistoryRepository>()

    afterSpec {
        context.close()
    }

    given("listInstances and listErrorGroups") {
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
                historyRepo.deleteAll()
                historyRepo.save(historyRow("2026-03-04T08:00:00Z", flowA, aRunning, HistoryEntryType.Started, stage = "Init", toStatus = StageStatus.Pending))
                historyRepo.save(historyRow("2026-03-04T08:01:00Z", flowA, aRunning, HistoryEntryType.StatusChanged, stage = "Init", fromStatus = StageStatus.Pending, toStatus = StageStatus.Running))
                historyRepo.save(historyRow("2026-03-04T08:02:00Z", flowA, aRunning, HistoryEntryType.StageChanged, fromStage = "Init", toStage = "Review"))

                historyRepo.save(historyRow("2026-03-04T08:03:00Z", flowA, aError1, HistoryEntryType.Error, stage = "Review", fromStatus = StageStatus.Running, toStatus = StageStatus.Error, errorMessage = "boom-1"))
                historyRepo.save(historyRow("2026-03-04T08:04:00Z", flowA, aError2, HistoryEntryType.Error, stage = "Review", fromStatus = StageStatus.Running, toStatus = StageStatus.Error, errorMessage = "boom-2"))

                historyRepo.save(historyRow("2026-03-04T08:05:00Z", flowB, bCancelled, HistoryEntryType.Cancelled, stage = "Done", fromStatus = StageStatus.Running, toStatus = StageStatus.Cancelled))
                historyRepo.save(historyRow("2026-03-04T08:06:00Z", flowB, bCompleted, HistoryEntryType.StatusChanged, stage = "Done", fromStatus = StageStatus.Running, toStatus = StageStatus.Completed))
                historyRepo.save(historyRow("2026-03-04T08:07:00Z", flowB, bError, HistoryEntryType.Error, stage = "Investigate", fromStatus = StageStatus.Running, toStatus = StageStatus.Error, errorMessage = "boom-3"))
            }

            then("listInstances returns expected sorting and bucket projections") {
                seedRows()

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

            then("listErrorGroups groups by flow and stage") {
                seedRows()

                service.listErrorGroups() shouldContainExactly listOf(
                    CockpitErrorGroupDto(
                        flowId = flowA,
                        stage = "Review",
                        count = 2,
                        instanceIds = listOf(aError1, aError2),
                    ),
                    CockpitErrorGroupDto(
                        flowId = flowB,
                        stage = "Investigate",
                        count = 1,
                        instanceIds = listOf(bError),
                    ),
                )

                service.listErrorGroups(flowId = flowA) shouldContainExactly listOf(
                    CockpitErrorGroupDto(
                        flowId = flowA,
                        stage = "Review",
                        count = 2,
                        instanceIds = listOf(aError1, aError2),
                    ),
                )
            }
        }
    }

    given("listFlows") {
        `when`("registered flows have mixed instance statuses") {
            val orderActive = UUID.fromString("00000000-0000-0000-0000-000000000101")
            val orderError = UUID.fromString("00000000-0000-0000-0000-000000000102")
            val onboardingCompleted = UUID.fromString("00000000-0000-0000-0000-000000000103")
            val unknownFlow = UUID.fromString("00000000-0000-0000-0000-000000000104")

            then("it returns diagrams and per-flow counters only for registered flows") {
                historyRepo.deleteAll()
                historyRepo.save(historyRow("2026-03-04T09:00:00Z", ORDER_CONFIRMATION_FLOW_ID, orderActive, HistoryEntryType.StatusChanged, stage = "WaitingForConfirmation", fromStatus = StageStatus.Pending, toStatus = StageStatus.Running))
                historyRepo.save(historyRow("2026-03-04T09:01:00Z", ORDER_CONFIRMATION_FLOW_ID, orderError, HistoryEntryType.Error, stage = "InformingCustomer", fromStatus = StageStatus.Running, toStatus = StageStatus.Error, errorMessage = "order-failed"))
                historyRepo.save(historyRow("2026-03-04T09:02:00Z", EMPLOYEE_ONBOARDING_FLOW_ID, onboardingCompleted, HistoryEntryType.StatusChanged, stage = "CompleteOnboarding", fromStatus = StageStatus.Running, toStatus = StageStatus.Completed))
                historyRepo.save(historyRow("2026-03-04T09:03:00Z", "unknown-flow", unknownFlow, HistoryEntryType.StatusChanged, stage = "X", fromStatus = StageStatus.Pending, toStatus = StageStatus.Running))

                val flows = service.listFlows()

                flows.map { it.flowId } shouldContainExactly listOf(EMPLOYEE_ONBOARDING_FLOW_ID, ORDER_CONFIRMATION_FLOW_ID)

                val onboarding = flows.first { it.flowId == EMPLOYEE_ONBOARDING_FLOW_ID }
                onboarding.activeCount shouldBe 0
                onboarding.errorCount shouldBe 0
                onboarding.completedCount shouldBe 1
                onboarding.longRunningCount shouldBe 0
                onboarding.notCompletedCount shouldBe 0
                onboarding.stageBreakdown shouldBe emptyList()
                onboarding.diagram.contains("stateDiagram-v2") shouldBe true

                val order = flows.first { it.flowId == ORDER_CONFIRMATION_FLOW_ID }
                order.activeCount shouldBe 1
                order.errorCount shouldBe 1
                order.completedCount shouldBe 0
                order.longRunningCount shouldBe 1
                order.notCompletedCount shouldBe 2
                order.stageBreakdown shouldContainExactly listOf(
                    io.flowlite.cockpit.CockpitFlowStageDto(
                        stage = "InformingCustomer",
                        totalCount = 1,
                        errorCount = 1,
                    ),
                    io.flowlite.cockpit.CockpitFlowStageDto(
                        stage = "WaitingForConfirmation",
                        totalCount = 1,
                        errorCount = 0,
                    ),
                )
                order.diagram.contains("stateDiagram-v2") shouldBe true
            }
        }
    }

    given("timeline projection") {
        `when`("rows exist for a flow instance") {
            val flowId = ORDER_CONFIRMATION_FLOW_ID
            val id = UUID.fromString("00000000-0000-0000-0000-000000000201")

            then("timeline returns rows in repository order") {
                historyRepo.deleteAll()
                historyRepo.save(historyRow("2026-03-04T10:00:00Z", flowId, id, HistoryEntryType.Started, stage = "InitializingConfirmation", toStatus = StageStatus.Pending))
                historyRepo.save(historyRow("2026-03-04T10:01:00Z", flowId, id, HistoryEntryType.EventAppended, event = OrderConfirmationEvent.Confirmed.name))

                val timeline = service.timeline(flowId, id)
                timeline.map { it.type } shouldContainExactly listOf(HistoryEntryType.Started, HistoryEntryType.EventAppended)
                timeline.map { it.event } shouldContainExactly listOf(null, OrderConfirmationEvent.Confirmed.name)
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
)