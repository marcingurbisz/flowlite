package io.flowlite.test

import io.flowlite.Engine
import io.flowlite.StageStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.getBean

class ShowcaseFlowSeederTest : BehaviorSpec({
    val context = startTestApplication()
    val engine = context.getBean<Engine>()
    val orderRepo = context.getBean<OrderConfirmationRepository>()
    val employeeRepo = context.getBean<EmployeeOnboardingRepository>()

    afterSpec {
        context.close()
    }

    given("showcase seeding") {
        `when`("a larger startup batch is requested for perf testing") {
            val seeder = ShowcaseFlowSeeder(
                engine = engine,
                enabled = true,
                initialSeedCount = 5,
                repeatSeedingEnabled = false,
                maxActionDelayMs = 0L,
                actionFailureRate = 0.0,
                maxEventDelayMs = 0L,
            )

            val seededOrders = waitUntil(timeoutMs = 2_000L) {
                orderRepo.findAll().count { it.orderNumber.startsWith("SHOW-") } == 5
            }
            val seededEmployees = waitUntil(timeoutMs = 2_000L) {
                employeeRepo.findAll().count { it.isShowcaseInstance } == 5
            }

            seeder.close()

            then("the requested startup batch size is created immediately") {
                seededOrders shouldBe true
                seededEmployees shouldBe true
            }
        }

        `when`("showcase events are delayed until a wait stage is reached") {
            val seeder = ShowcaseFlowSeeder(
                engine = engine,
                enabled = true,
                initialSeedCount = 1,
                repeatSeedingEnabled = true,
                maxActionDelayMs = 0L,
                actionFailureRate = 0.0,
                maxEventDelayMs = 2_000L,
                eventDelayProvider = { maxDelayMs -> maxDelayMs },
            )
            val reachedWaitingState = waitUntil(timeoutMs = 4_000L) {
                orderRepo.findAll().any { order ->
                    order.orderNumber.startsWith("SHOW-") &&
                        order.stage == OrderConfirmationStage.WaitingForConfirmation &&
                        order.stageStatus == StageStatus.Pending
                }
            }
            val reachedCompletedState = waitUntil(timeoutMs = 7_000L) {
                orderRepo.findAll().any { order ->
                    order.orderNumber.startsWith("SHOW-") &&
                        order.stage == OrderConfirmationStage.InformingCustomer &&
                        order.stageStatus == StageStatus.Completed &&
                        order.isCustomerInformed
                }
            }

            seeder.close()

            then("seeded order confirmations stay pending before the confirmation event is sent") {
                reachedWaitingState shouldBe true
            }

            then("the delayed confirmation eventually completes the showcase order") {
                reachedCompletedState shouldBe true
            }
        }
    }
})

private fun waitUntil(
    timeoutMs: Long,
    intervalMs: Long = 100L,
    condition: () -> Boolean,
): Boolean {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (System.nanoTime() < deadline) {
        if (condition()) return true
        Thread.sleep(intervalMs)
    }
    return condition()
}
