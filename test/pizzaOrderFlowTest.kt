package io.flowlite.test

import io.flowlite.api.Event
import io.flowlite.api.Flow
import io.flowlite.api.FlowBuilder
import io.flowlite.api.FlowEngine
import io.flowlite.api.MermaidGenerator
import io.flowlite.api.Stage
import io.flowlite.api.StatePersister
import io.flowlite.test.OrderEvent.*
import io.flowlite.test.OrderStage.*
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class PizzaOrderFlowTest : BehaviorSpec({

    given("a pizza order flow") {
        val flowEngine = FlowEngine()
        
        When("registering the flow with the engine") {
            flowEngine.registerFlow(
                flowId = "pizza-order",
                stateClass = PizzaOrder::class,
                flow = createPizzaOrderFlow(),
                statePersister = InMemoryStatePersister()
            )

            then("should be able to start a process instance") {
                flowEngine.startProcess("pizza-order", PizzaOrder(
                    customerName = "customer-name",
                    paymentMethod = PaymentMethod.ONLINE,
                ))
            }
        }
        
        When("generating a mermaid diagram") {
            val generator = MermaidGenerator()
            val diagram = generator.generateDiagram("pizza-order", createPizzaOrderFlow())
            
            println("\n=== PIZZA ORDER FLOW DIAGRAM ===")
            println(diagram)
            println("=== END DIAGRAM ===\n")

            then("should start with stateDiagram-v2 syntax") {
                diagram shouldContain "stateDiagram-v2"
            }

            then("should have initial condition") {
                diagram shouldContain "[*] --> if_initial"
            }

            then("should contain all stages") {
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

            then("should include method names in stage descriptions") {
                diagram shouldContain "$InitializingCashPayment: $InitializingCashPayment initializeCashPayment()"
                diagram shouldContain "$StartingOrderPreparation: $StartingOrderPreparation startOrderPreparation()"
                diagram shouldContain "$InitializingDelivery: $InitializingDelivery initializeDelivery()"
                diagram shouldContain "$CompletingOrder: $CompletingOrder completeOrder()"
                diagram shouldContain "$CancellingOrder: $CancellingOrder sendOrderCancellation()"
                diagram shouldContain "$InitializingOnlinePayment: $InitializingOnlinePayment initializeOnlinePayment()"
            }

            then("should include condition descriptions on transitions") {
                diagram shouldContain "state if_initial <<choice>>"
                diagram shouldContain "if_initial --> $InitializingCashPayment: paymentMethod == PaymentMethod.CASH"
                diagram shouldContain "if_initial --> $InitializingOnlinePayment: NOT (paymentMethod == PaymentMethod.CASH)"
            }
        }
    }
})

// --- Domain Classes ---

/** Represents the stage of a pizza order. */
enum class OrderStage : Stage {
    InitializingCashPayment, // Processing cash payment
    InitializingOnlinePayment, // Processing online payment
    ExpiringOnlinePayment, // Online payment session timed out
    StartingOrderPreparation, // Kitchen starts making the pizza
    InitializingDelivery, // Delivery process started
    CompletingOrder, // Pizza delivered successfully
    CancellingOrder, // Order was cancelled
}

/** Represents events that can occur during the pizza order lifecycle. */
enum class OrderEvent : Event {
    PaymentConfirmed, // Cash payment received
    PaymentCompleted, // Online payment successful
    SwitchToCashPayment, // Customer decided to pay cash instead of online
    PaymentSessionExpired, // Online payment link/session expired
    RetryPayment, // Customer wants to try online payment again
    ReadyForDelivery, // Pizza is ready for delivery pickup/dispatch
    DeliveryCompleted, // Delivery confirmed
    DeliveryFailed, // Delivery could not be completed
    Cancel, // Customer or system cancels the order
}

/** Payment methods available. */
enum class PaymentMethod {
    CASH,
    ONLINE,
}

data class PizzaOrder(
    val processId: String = "", // Will be set by the engine when starting
    val customerName: String,
    val paymentMethod: PaymentMethod,
    val paymentTransactionId: String? = null,
)

// --- Top-Level Action Functions (returning new instances) ---

fun initializeCashPayment(order: PizzaOrder): PizzaOrder = order

fun initializeOnlinePayment(order: PizzaOrder): PizzaOrder {
    val transactionId = "TXN-" + System.currentTimeMillis()
    return order.copy(paymentTransactionId = transactionId)
}

fun startOrderPreparation(order: PizzaOrder): PizzaOrder = order

fun initializeDelivery(order: PizzaOrder): PizzaOrder = order

fun completeOrder(order: PizzaOrder): PizzaOrder = order

fun sendOrderCancellation(order: PizzaOrder): PizzaOrder = order

// --- Flow Definition ---
// [FLOW_DEFINITION_START:PizzaOrder]
fun createPizzaOrderFlow(): Flow<PizzaOrder> {

    // Define main pizza order flow
    return FlowBuilder<PizzaOrder>()
        .condition(
            predicate = { it.paymentMethod == PaymentMethod.CASH },
            onTrue = {
                stage(InitializingCashPayment, ::initializeCashPayment).apply {
                    waitFor(PaymentConfirmed)
                        .stage(StartingOrderPreparation, ::startOrderPreparation)
                        .waitFor(ReadyForDelivery)
                        .stage(InitializingDelivery, ::initializeDelivery)
                        .apply {
                            waitFor(DeliveryCompleted).stage(CompletingOrder, ::completeOrder).end()
                            waitFor(DeliveryFailed).stage(CancellingOrder, ::sendOrderCancellation).end()
                        }
                    waitFor(Cancel).join(CancellingOrder)
                }
            },
            onFalse = {
                stage(InitializingOnlinePayment, ::initializeOnlinePayment).apply {
                    waitFor(PaymentCompleted).join(StartingOrderPreparation)
                    waitFor(SwitchToCashPayment).join(InitializingCashPayment)
                    waitFor(Cancel).join(CancellingOrder)
                    waitFor(PaymentSessionExpired).stage(ExpiringOnlinePayment).apply {
                        waitFor(RetryPayment).join(InitializingOnlinePayment)
                        waitFor(Cancel).join(CancellingOrder)
                    }
                }
            },
            description = "paymentMethod == PaymentMethod.CASH"
        )
        .build()
}
// [FLOW_DEFINITION_END:PizzaOrder]

/** Simple in-memory state persister for testing purposes */
class InMemoryStatePersister<T : Any> : StatePersister<T> {
    private val states = mutableMapOf<String, T>()

    override fun save(processId: String, state: T) {
        states[processId] = state
    }

    override fun load(processId: String): T? {
        return states[processId]
    }
}