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
                    stage = Started,
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

            then("should include method names in stage descriptions") {
                diagram shouldContain "$InitializingCashPayment: $InitializingCashPayment initializeCashPayment()"
                diagram shouldContain "$StartingOrderPreparation: $StartingOrderPreparation startOrderPreparation()"
                diagram shouldContain "$InitializingDelivery: $InitializingDelivery initializeDelivery()"
                diagram shouldContain "$CompletingOrder: $CompletingOrder completeOrder()"
                diagram shouldContain "$CancellingOrder: $CancellingOrder sendOrderCancellation()"
                diagram shouldContain "$InitializingOnlinePayment: $InitializingOnlinePayment initializeOnlinePayment()"
            }

            then("should include condition descriptions on transitions") {
                diagram shouldContain "state if_started <<choice>>"
                diagram shouldContain "if_started --> $InitializingCashPayment: (paymentMethod == PaymentMethod.CASH) == true"
                diagram shouldContain "if_started --> $InitializingOnlinePayment: (paymentMethod == PaymentMethod.CASH) == false"
            }
        }
    }
})

// --- Domain Classes ---

/** Represents the stage of a pizza order. */
enum class OrderStage : Stage {
    Started, // Order details recorded
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
    val stage: OrderStage, // Ensure OrderStage is imported/defined correctly
    val customerName: String,
    val paymentMethod: PaymentMethod,
    val paymentTransactionId: String? = null,
)

// --- Top-Level Action Functions (returning new instances) ---

fun initializeCashPayment(order: PizzaOrder): PizzaOrder = order.copy(stage = InitializingCashPayment)

fun initializeOnlinePayment(order: PizzaOrder): PizzaOrder {
    val transactionId = "TXN-" + System.currentTimeMillis()
    return order.copy(stage = InitializingOnlinePayment, paymentTransactionId = transactionId)
}

fun startOrderPreparation(order: PizzaOrder): PizzaOrder = order.copy(stage = StartingOrderPreparation)

fun initializeDelivery(order: PizzaOrder): PizzaOrder = order.copy(stage = InitializingDelivery)

fun completeOrder(order: PizzaOrder): PizzaOrder = order.copy(stage = CompletingOrder)

fun sendOrderCancellation(order: PizzaOrder): PizzaOrder = order.copy(stage = CancellingOrder)

// --- Flow Definition ---
fun createPizzaOrderFlow(): Flow<PizzaOrder> {

    // Define main pizza order flow
    return FlowBuilder<PizzaOrder>()
        .stage(Started)
        .condition(
            predicate = { it.paymentMethod == PaymentMethod.CASH },
            onTrue = {
                stage(InitializingCashPayment, ::initializeCashPayment).apply {
                    onEvent(PaymentConfirmed)
                        .stage(StartingOrderPreparation, ::startOrderPreparation)
                        .onEvent(ReadyForDelivery)
                        .stage(InitializingDelivery, ::initializeDelivery)
                        .apply {
                            onEvent(DeliveryCompleted).stage(CompletingOrder, ::completeOrder).end()
                            onEvent(DeliveryFailed).stage(CancellingOrder, ::sendOrderCancellation).end()
                        }
                    onEvent(Cancel).join(CancellingOrder)
                }
            },
            onFalse = {
                stage(InitializingOnlinePayment, ::initializeOnlinePayment).apply {
                    onEvent(PaymentCompleted).join(StartingOrderPreparation)
                    onEvent(SwitchToCashPayment).join(InitializingCashPayment)
                    onEvent(Cancel).join(CancellingOrder)
                    onEvent(PaymentSessionExpired).stage(ExpiringOnlinePayment).apply {
                        onEvent(RetryPayment).join(InitializingOnlinePayment)
                        onEvent(Cancel).join(CancellingOrder)
                    }
                }
            },
            description = "paymentMethod == PaymentMethod.CASH"
        )
        .build()
}

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