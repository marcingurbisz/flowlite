package io.flowlite.test

import io.flowlite.Engine
import io.flowlite.FlowLiteHistoryRepository
import io.flowlite.HistoryEntryType
import io.flowlite.MermaidGenerator
import io.flowlite.ActionContext
import io.flowlite.StageStatus
import io.flowlite.test.EmployeeStage.Delay5Min
import io.flowlite.test.EmployeeStage.DelayAfterHRUpdate
import io.flowlite.test.EmployeeEvent.ManualApproval
import io.flowlite.test.EmployeeEvent.OnboardingAgreementSigned
import io.flowlite.test.EmployeeStage.CompleteOnboarding
import io.flowlite.test.EmployeeStage.CreateEmployeeProfile
import io.flowlite.test.EmployeeStage.WaitingForOnboardingAgreementSigned
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
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
    val actions = TestApplicationExtension.context().getBean<EmployeeOnboardingActions>()

    given("an employee onboarding flow") {
        `when`("generating a mermaid diagram") {
            val flow = createEmployeeOnboardingFlow(actions)
            val generator = MermaidGenerator()
            val diagram = generator.generateDiagram(flow)

            then("should generate diagram successfully") {
                assert(diagram.isNotEmpty())
                assert(diagram.contains("stateDiagram-v2"))
            }
        }
    }

    given("the IT business-hours timer") {
        val employee = EmployeeOnboarding(stage = CreateEmployeeProfile)

        fun scheduledAt(now: String): Instant =
            actions.effectiveITWorkingDateTime(
                ActionContext(
                    flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
                    flowInstanceId = UUID.randomUUID(),
                    now = Instant.parse(now),
                ),
                employee,
            )

        `when`("the current time is already inside Warsaw business hours") {
            then("it returns the current instant without extra delay") {
                scheduledAt("2026-04-15T08:30:00Z") shouldBe Instant.parse("2026-04-15T08:30:00Z")
            }
        }

        `when`("the current time is before Warsaw business hours") {
            then("it waits until the same-day 08:00 Warsaw opening") {
                scheduledAt("2026-04-15T04:30:00Z") shouldBe Instant.parse("2026-04-15T06:00:00Z")
            }
        }

        `when`("the current time is after Warsaw business hours") {
            then("it waits until the next business-day 08:00 Warsaw opening") {
                scheduledAt("2026-04-15T18:30:00Z") shouldBe Instant.parse("2026-04-16T06:00:00Z")
            }
        }

        `when`("the current time is after business hours on Friday") {
            then("it skips the weekend and waits until Monday 08:00 Warsaw") {
                scheduledAt("2026-04-17T18:30:00Z") shouldBe Instant.parse("2026-04-20T06:00:00Z")
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

            then("it reaches the timer stage") {
                awaitStatus(
                    timeout = Duration.ofSeconds(5),
                    fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, flowInstanceId) },
                    expected = Delay5Min to StageStatus.Pending,
                )
            }

            `when`("the timer elapses") {
                clock.advanceBy(Duration.ofMinutes(5))

                then("it finishes onboarding") {
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

            `when`("the action save is released after the external update") {
                try {
                    awaitLatch(entered, "entered")

                    val external = repo.findById(id).orElseThrow()
                    repo.save(external.copy(isRemoteEmployee = true))

                    allowProceedToSave.countDown()
                    awaitLatch(saved, "saved")

                    then("it reaches the first timer without losing the external business update") {
                        awaitStatus(
                            timeout = Duration.ofSeconds(5),
                            fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, id) },
                            expected = Delay5Min to StageStatus.Pending,
                        )
                    }

                    `when`("both timers elapse") {
                        clock.advanceBy(Duration.ofMinutes(5))
                        awaitStatus(
                            timeout = Duration.ofSeconds(5),
                            fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, id) },
                            expected = DelayAfterHRUpdate to StageStatus.Pending,
                        )

                        clock.advanceBy(Duration.ofMinutes(5))

                        then("it completes and keeps the external update") {
                            awaitStatus(
                                timeout = Duration.ofSeconds(5),
                                fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, id) },
                                expected = CompleteOnboarding to StageStatus.Completed,
                            )

                            val final = repo.findById(id).orElseThrow()
                            require(final.employeeProfileCreated)
                            require(final.isRemoteEmployee)
                        }
                    }
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

            `when`("the action returns after the external update") {
                try {
                    awaitLatch(entered, "entered")
                    awaitLatch(saved, "saved")

                    val external = repo.findById(id).orElseThrow()
                    repo.save(external.copy(isManagerOrDirectorRole = true))

                    allowReturnAfterSave.countDown()

                    then("it reaches the first timer without losing the external update") {
                        awaitStatus(
                            timeout = Duration.ofSeconds(5),
                            fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, id) },
                            expected = Delay5Min to StageStatus.Pending,
                        )
                    }

                    `when`("both timers elapse") {
                        clock.advanceBy(Duration.ofMinutes(5))
                        awaitStatus(
                            timeout = Duration.ofSeconds(5),
                            fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, id) },
                            expected = DelayAfterHRUpdate to StageStatus.Pending,
                        )

                        clock.advanceBy(Duration.ofMinutes(5))

                        then("it completes and preserves the post-save update") {
                            awaitStatus(
                                timeout = Duration.ofSeconds(5),
                                fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, id) },
                                expected = CompleteOnboarding to StageStatus.Completed,
                            )

                            val final = repo.findById(id).orElseThrow()
                            require(final.employeeProfileCreated)
                            require(final.isManagerOrDirectorRole)
                        }
                    }
                } finally {
                    EmployeeOnboardingTestHooks.clear(id)
                }
            }
        }
    }
})
