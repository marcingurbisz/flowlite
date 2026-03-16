package io.flowlite.test

import io.flowlite.Engine
import io.flowlite.StageStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.getBean

class ShowcaseFlowSeederTest : BehaviorSpec({
    given("showcase seeding") {
        `when`("showcase events are delayed until a wait stage is reached") {
            then("seeded order confirmations stay pending before the confirmation event is sent") {
                startTestApplication().use { context ->
                    val engine = context.getBean<Engine>()
                    val orderRepo = context.getBean<OrderConfirmationRepository>()

                    ShowcaseFlowSeeder(
                        engine = engine,
                        enabled = true,
                        maxActionDelayMs = 0L,
                        actionFailureRate = 0.0,
                        maxEventDelayMs = 2_000L,
                        eventDelayProvider = { maxDelayMs -> maxDelayMs },
                    ).use {
                        waitUntil(timeoutMs = 4_000L) {
                            orderRepo.findAll().any { order ->
                                order.orderNumber.startsWith("SHOW-") &&
                                    order.stage == OrderConfirmationStage.WaitingForConfirmation &&
                                    order.stageStatus == StageStatus.Pending
                            }
                        } shouldBe true

                        waitUntil(timeoutMs = 7_000L) {
                            orderRepo.findAll().any { order ->
                                order.orderNumber.startsWith("SHOW-") &&
                                    order.stage == OrderConfirmationStage.InformingCustomer &&
                                    order.stageStatus == StageStatus.Completed &&
                                    order.isCustomerInformed
                            }
                        } shouldBe true
                    }
                }
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
