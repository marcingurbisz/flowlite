package io.flowlite.test

import io.flowlite.api.MermaidGenerator
import io.flowlite.test.OrderEvent.*
import io.flowlite.test.OrderStage.*
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Tests the MermaidGenerator to ensure it properly converts Flow objects into Mermaid diagram syntax.
 */
class MermaidGeneratorTest : BehaviorSpec({

    given("a pizza order flow") {
        val pizzaFlow = createPizzaOrderFlow()
        val generator = MermaidGenerator()

        `when`("generating a mermaid diagram") {
            val diagram = generator.generateDiagram("pizza-order", pizzaFlow)

            then("should start with stateDiagram-v2 syntax") {
                diagram shouldContain "stateDiagram-v2"
            }

            then("should have initial state") {
                diagram shouldContain "[*] --> $Started"
            }

            then("should contain all stages") {
                diagram shouldContain Started.toString()
                diagram shouldContain InitializingCashPayment.toString()
                diagram shouldContain InitializingOnlinePayment.toString()
                diagram shouldContain ExpiringOnlinePayment.toString()
                diagram shouldContain StartingOrderPreparation.toString()
                diagram shouldContain InitializingDelivery.toString()
                diagram shouldContain CompletingOrder.toString()
                diagram shouldContain CancellingOrder.toString()
            }

            then("should contain key event transitions") {
                diagram shouldContain "onEvent $PaymentConfirmed"
                diagram shouldContain "onEvent $PaymentCompleted"
                diagram shouldContain "onEvent $SwitchToCashPayment"
                diagram shouldContain "onEvent $ReadyForDelivery"
                diagram shouldContain "onEvent $DeliveryCompleted"
            }

            then("should have terminal states") {
                val hasTerminalState = diagram.contains("$CompletingOrder --> [*]") || 
                                     diagram.contains("$CancellingOrder --> [*]")
                hasTerminalState shouldBe true
            }

            then("should have correct event-stage connections") {
                diagram shouldContain "$StartingOrderPreparation --> $InitializingDelivery: onEvent $ReadyForDelivery"
                diagram shouldContain "$InitializingCashPayment --> $StartingOrderPreparation: onEvent $PaymentConfirmed"
                diagram shouldContain "$InitializingOnlinePayment --> $StartingOrderPreparation: onEvent $PaymentCompleted"
                diagram shouldContain "$InitializingDelivery --> $CompletingOrder: onEvent $DeliveryCompleted"
                diagram shouldContain "$InitializingDelivery --> $CancellingOrder: onEvent $DeliveryFailed"
                diagram shouldContain "$ExpiringOnlinePayment --> $InitializingOnlinePayment: onEvent $RetryPayment"
            }

            then("should not have incorrect event attachments") {
                diagram shouldNotContain "$InitializingCashPayment --> $InitializingDelivery: onEvent $ReadyForDelivery"
            }
        }
    }
})