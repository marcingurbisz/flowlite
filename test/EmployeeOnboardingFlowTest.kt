package io.flowlite.test

import io.flowlite.FlowEngine
import io.flowlite.FlowLiteHistoryRepository
import io.flowlite.HistoryEntryType
import io.flowlite.MermaidGenerator
import io.flowlite.StageStatus
import io.flowlite.test.EmployeeEvent.ContractSigned
import io.flowlite.test.EmployeeEvent.OnboardingComplete
import io.flowlite.test.EmployeeStage.CreateUserInSystem
import io.flowlite.test.EmployeeStage.UpdateStatusInHRSystem
import io.flowlite.test.EmployeeStage.WaitingForContractSigned
import io.kotest.core.spec.style.BehaviorSpec
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.springframework.beans.factory.getBean

class EmployeeOnboardingFlowTest : BehaviorSpec({
    extension(TestApplicationExtension)

    val engine = TestApplicationExtension.context().getBean<FlowEngine>()
    val repo = TestApplicationExtension.context().getBean<EmployeeOnboardingRepository>()
    val historyRepo = TestApplicationExtension.context().getBean<FlowLiteHistoryRepository>()

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

    given("employee onboarding flow - manual path") {
        val flowInstanceId = engine.startInstance(
            flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
            initialState = EmployeeOnboarding(
                stage = WaitingForContractSigned,
                isOnboardingAutomated = false,
                isExecutiveRole = false,
                isSecurityClearanceRequired = false,
                isFullOnboardingRequired = false,
            ),
        )

        then("it starts at waiting for contract signature") {
            awaitStatus(
                fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, flowInstanceId) },
                expected = WaitingForContractSigned to StageStatus.Pending,
            )
        }

        `when`("contract is signed and onboarding completes") {
            engine.sendEvent(EMPLOYEE_ONBOARDING_FLOW_ID, flowInstanceId, ContractSigned)
            engine.sendEvent(EMPLOYEE_ONBOARDING_FLOW_ID, flowInstanceId, OnboardingComplete)

            then("it finishes in HR system update stage") {
                awaitStatus(
                    fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, flowInstanceId) },
                    expected = UpdateStatusInHRSystem to StageStatus.Completed,
                )

                val timeline = historyRepo.findTimeline(EMPLOYEE_ONBOARDING_FLOW_ID, flowInstanceId)
                require(timeline.isNotEmpty()) { "Expected non-empty history timeline" }
                require(timeline.any { it.type == HistoryEntryType.EventAppended.name && it.event == ContractSigned.name })
                require(timeline.any { it.type == HistoryEntryType.EventAppended.name && it.event == OnboardingComplete.name })
            }
        }
    }

    given("employee onboarding persister - optimistic locking with external updates") {
        fun awaitLatch(latch: CountDownLatch, name: String) {
            require(latch.await(2, TimeUnit.SECONDS)) { "Timed out waiting for $name" }
        }

        `when`("an external update happens during action execution") {
            val id = UUID.randomUUID()

            val entered = CountDownLatch(1)
            val allowProceedToSave = CountDownLatch(1)
            val saved = CountDownLatch(1)
            val allowReturnAfterSave = CountDownLatch(0)
            EmployeeOnboardingTestHooks.set(
                id,
                EmployeeOnboardingActionHooks().apply {
                    createUserInSystemHooks = EmployeeOnboardingActionHooks.CreateUserInSystemHooks(
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
                    stage = CreateUserInSystem,
                    stageStatus = StageStatus.Pending,
                    isOnboardingAutomated = true,
                    isExecutiveRole = true,
                    isSecurityClearanceRequired = false,
                ),
            )

            engine.sendEvent(EMPLOYEE_ONBOARDING_FLOW_ID, id, ContractSigned)
            engine.startInstance(EMPLOYEE_ONBOARDING_FLOW_ID, id)

            then("persister merges engine progress with external business updates") {
                try {
                    awaitLatch(entered, "entered")

                    val external = repo.findById(id).orElseThrow()
                    repo.save(external.copy(isRemoteEmployee = true))

                    allowProceedToSave.countDown()
                    awaitLatch(saved, "saved")

                    awaitStatus(
                        fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, id) },
                        expected = UpdateStatusInHRSystem to StageStatus.Completed,
                    )

                    val final = repo.findById(id).orElseThrow()
                    require(final.userCreatedInSystem)
                    require(final.isRemoteEmployee)
                } finally {
                    EmployeeOnboardingTestHooks.clear(id)
                }
            }
        }

        `when`("an external update happens after action saves but before it returns") {
            val id = UUID.randomUUID()

            val entered = CountDownLatch(1)
            val allowProceedToSave = CountDownLatch(0)
            val saved = CountDownLatch(1)
            val allowReturnAfterSave = CountDownLatch(1)
            EmployeeOnboardingTestHooks.set(
                id,
                EmployeeOnboardingActionHooks().apply {
                    createUserInSystemHooks = EmployeeOnboardingActionHooks.CreateUserInSystemHooks(
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
                    stage = CreateUserInSystem,
                    stageStatus = StageStatus.Pending,
                    isOnboardingAutomated = true,
                    isExecutiveRole = true,
                    isSecurityClearanceRequired = false,
                ),
            )

            engine.sendEvent(EMPLOYEE_ONBOARDING_FLOW_ID, id, ContractSigned)
            engine.startInstance(EMPLOYEE_ONBOARDING_FLOW_ID, id)

            then("persister merges without losing the external update") {
                try {
                    awaitLatch(entered, "entered")
                    awaitLatch(saved, "saved")

                    val external = repo.findById(id).orElseThrow()
                    repo.save(external.copy(isManagerOrDirectorRole = true))

                    allowReturnAfterSave.countDown()

                    awaitStatus(
                        fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, id) },
                        expected = UpdateStatusInHRSystem to StageStatus.Completed,
                    )

                    val final = repo.findById(id).orElseThrow()
                    require(final.userCreatedInSystem)
                    require(final.isManagerOrDirectorRole)
                } finally {
                    EmployeeOnboardingTestHooks.clear(id)
                }
            }
        }
    }
})
