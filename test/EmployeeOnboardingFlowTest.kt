package io.flowlite.test

import io.flowlite.Engine
import io.flowlite.FlowLiteHistoryRepository
import io.flowlite.HistoryEntryType
import io.flowlite.MermaidGenerator
import io.flowlite.StageStatus
import io.flowlite.test.EmployeeStage.Delay5Min
import io.flowlite.test.EmployeeStage.DelayAfterHRUpdate
import io.flowlite.test.EmployeeEvent.ManualApproval
import io.flowlite.test.EmployeeEvent.OnboardingAgreementSigned
import io.flowlite.test.EmployeeStage.CompleteOnboarding
import io.flowlite.test.EmployeeStage.CreateEmployeeProfile
import io.flowlite.test.EmployeeStage.WaitingForOnboardingAgreementSigned
import io.kotest.core.spec.style.BehaviorSpec
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.springframework.beans.factory.getBean

class EmployeeOnboardingFlowTest : BehaviorSpec({
    extension(TestApplicationExtension)

    val engine = TestApplicationExtension.context().getBean<Engine>()
    val repo = TestApplicationExtension.context().getBean<EmployeeOnboardingRepository>()
    val historyRepo = TestApplicationExtension.context().getBean<FlowLiteHistoryRepository>()
    val clock = TestApplicationExtension.context().getBean<AdjustableClock>()

    given("an employee onboarding flow") {
        `when`("generating a mermaid diagram") {
            val actions = TestApplicationExtension.context().getBean<EmployeeOnboardingActions>()
            val flow = createEmployeeOnboardingFlow(actions)
            val generator = MermaidGenerator()
            val diagram = generator.generateDiagram(flow)

            then("should generate diagram successfully") {
                assert(diagram.isNotEmpty())
                assert(diagram.contains("stateDiagram-v2"))
            }
        }
    }

    given("employee onboarding flow - manual approval path") {
        clock.resetOffset()
        val flowInstanceId = engine.startInstance(
            flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
            initialState = EmployeeOnboarding(
                stage = WaitingForOnboardingAgreementSigned,
                isOnboardingAutomated = false,
                isNotManualPath = false,
                isNotContractor = true,
            ),
        )

        then("it starts at waiting for onboarding agreement") {
            awaitStatus(
                fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, flowInstanceId) },
                expected = WaitingForOnboardingAgreementSigned to StageStatus.Pending,
            )
        }

        `when`("agreement is signed and manual approval arrives") {
            engine.sendEvent(EMPLOYEE_ONBOARDING_FLOW_ID, flowInstanceId, OnboardingAgreementSigned)
            engine.sendEvent(EMPLOYEE_ONBOARDING_FLOW_ID, flowInstanceId, ManualApproval)

            then("it finishes onboarding") {
                awaitStatus(
                    timeout = Duration.ofSeconds(5),
                    fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, flowInstanceId) },
                    expected = Delay5Min to StageStatus.Pending,
                )

                clock.advanceBy(Duration.ofMinutes(5))

                awaitStatus(
                    timeout = Duration.ofSeconds(5),
                    fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, flowInstanceId) },
                    expected = CompleteOnboarding to StageStatus.Completed,
                )

                val timeline = historyRepo.findTimeline(EMPLOYEE_ONBOARDING_FLOW_ID, flowInstanceId)
                require(timeline.isNotEmpty()) { "Expected non-empty history timeline" }
                require(timeline.any { it.type == HistoryEntryType.EventAppended && it.event == OnboardingAgreementSigned.name })
                require(timeline.any { it.type == HistoryEntryType.EventAppended && it.event == ManualApproval.name })
            }
        }
    }

    given("employee onboarding persister - optimistic locking with external updates") {
        fun awaitLatch(latch: CountDownLatch, name: String) {
            require(latch.await(2, TimeUnit.SECONDS)) { "Timed out waiting for $name" }
        }

        `when`("an external update happens during action execution") {
            clock.resetOffset()
            val id = UUID.randomUUID()

            val entered = CountDownLatch(1)
            val allowProceedToSave = CountDownLatch(1)
            val saved = CountDownLatch(1)
            val allowReturnAfterSave = CountDownLatch(0)
            EmployeeOnboardingTestHooks.set(
                id,
                EmployeeOnboardingActionHooks().apply {
                    createEmployeeProfileHooks = EmployeeOnboardingActionHooks.CreateEmployeeProfileHooks(
                        entered = entered,
                        allowProceedToSave = allowProceedToSave,
                        saved = saved,
                        allowReturnAfterSave = allowReturnAfterSave,
                    )
                },
            )

            repo.save(
                EmployeeOnboarding(
                    id = id,
                    stage = CreateEmployeeProfile,
                    stageStatus = StageStatus.Pending,
                    isOnboardingAutomated = true,
                    needsTrainingProgram = false,
                    isNotManualPath = true,
                    isExecutiveOrManagement = true,
                    isNotContractor = false,
                ),
            )

            engine.sendEvent(EMPLOYEE_ONBOARDING_FLOW_ID, id, OnboardingAgreementSigned)
            engine.startInstance(EMPLOYEE_ONBOARDING_FLOW_ID, id)

            then("persister merges engine progress with external business updates") {
                try {
                    awaitLatch(entered, "entered")

                    val external = repo.findById(id).orElseThrow()
                    repo.save(external.copy(isRemoteEmployee = true))

                    allowProceedToSave.countDown()
                    awaitLatch(saved, "saved")

                    awaitStatus(
                        timeout = Duration.ofSeconds(5),
                        fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, id) },
                        expected = Delay5Min to StageStatus.Pending,
                    )

                    clock.advanceBy(Duration.ofMinutes(5))

                    awaitStatus(
                        timeout = Duration.ofSeconds(5),
                        fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, id) },
                        expected = DelayAfterHRUpdate to StageStatus.Pending,
                    )

                    clock.advanceBy(Duration.ofMinutes(5))

                    awaitStatus(
                        timeout = Duration.ofSeconds(5),
                        fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, id) },
                        expected = CompleteOnboarding to StageStatus.Completed,
                    )

                    val final = repo.findById(id).orElseThrow()
                    require(final.employeeProfileCreated)
                    require(final.isRemoteEmployee)
                } finally {
                    EmployeeOnboardingTestHooks.clear(id)
                }
            }
        }

        `when`("an external update happens after action saves but before it returns") {
            clock.resetOffset()
            val id = UUID.randomUUID()

            val entered = CountDownLatch(1)
            val allowProceedToSave = CountDownLatch(0)
            val saved = CountDownLatch(1)
            val allowReturnAfterSave = CountDownLatch(1)
            EmployeeOnboardingTestHooks.set(
                id,
                EmployeeOnboardingActionHooks().apply {
                    createEmployeeProfileHooks = EmployeeOnboardingActionHooks.CreateEmployeeProfileHooks(
                        entered = entered,
                        allowProceedToSave = allowProceedToSave,
                        saved = saved,
                        allowReturnAfterSave = allowReturnAfterSave,
                    )
                },
            )

            repo.save(
                EmployeeOnboarding(
                    id = id,
                    stage = CreateEmployeeProfile,
                    stageStatus = StageStatus.Pending,
                    isOnboardingAutomated = true,
                    needsTrainingProgram = false,
                    isNotManualPath = true,
                    isExecutiveOrManagement = true,
                    isNotContractor = false,
                ),
            )

            engine.sendEvent(EMPLOYEE_ONBOARDING_FLOW_ID, id, OnboardingAgreementSigned)
            engine.startInstance(EMPLOYEE_ONBOARDING_FLOW_ID, id)

            then("persister merges without losing the external update") {
                try {
                    awaitLatch(entered, "entered")
                    awaitLatch(saved, "saved")

                    val external = repo.findById(id).orElseThrow()
                    repo.save(external.copy(isManagerOrDirectorRole = true))

                    allowReturnAfterSave.countDown()

                    awaitStatus(
                        timeout = Duration.ofSeconds(5),
                        fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, id) },
                        expected = Delay5Min to StageStatus.Pending,
                    )

                    clock.advanceBy(Duration.ofMinutes(5))

                    awaitStatus(
                        timeout = Duration.ofSeconds(5),
                        fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, id) },
                        expected = DelayAfterHRUpdate to StageStatus.Pending,
                    )

                    clock.advanceBy(Duration.ofMinutes(5))

                    awaitStatus(
                        timeout = Duration.ofSeconds(5),
                        fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, id) },
                        expected = CompleteOnboarding to StageStatus.Completed,
                    )

                    val final = repo.findById(id).orElseThrow()
                    require(final.employeeProfileCreated)
                    require(final.isManagerOrDirectorRole)
                } finally {
                    EmployeeOnboardingTestHooks.clear(id)
                }
            }
        }
    }
})
