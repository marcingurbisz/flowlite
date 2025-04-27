package io.flowlite.test

import io.flowlite.api.Event
import io.flowlite.api.FlowBuilder
import io.flowlite.api.Stage
import io.flowlite.api.StatePersister
import io.flowlite.test.OrderEvent.*
import io.flowlite.test.OrderStage.*

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
    // Simulate generating a transaction ID
    val transactionId = "TXN-" + System.currentTimeMillis()
    println("[Action] Initializing online payment for order ${order.processId}, transaction ID: $transactionId")
    // In a real app, might throw PaymentGatewayException if initialization fails
    return order.copy(stage = InitializingOnlinePayment, paymentTransactionId = transactionId)
}

fun startOrderPreparation(order: PizzaOrder): PizzaOrder = order.copy(stage = StartingOrderPreparation)

fun initializeDelivery(order: PizzaOrder): PizzaOrder = order.copy(stage = InitializingDelivery)

fun completeOrder(order: PizzaOrder): PizzaOrder = order.copy(stage = CompletingOrder)

fun sendOrderCancellation(order: PizzaOrder): PizzaOrder = order.copy(stage = CancellingOrder)

/** Custom exception for payment gateway issues. */
class PaymentGatewayException(message: String) : Exception(message)

// --- Flow Definition ---

/** Creates the main pizza order flow definition. */
fun createPizzaOrderFlow(): FlowBuilder<PizzaOrder> {

    // Define main pizza order flow
    return FlowBuilder<PizzaOrder>().stage(Started).condition({ it.paymentMethod == PaymentMethod.CASH }) {
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
    } onFalse
        {
            stage(InitializingOnlinePayment, ::initializeOnlinePayment).apply {
                onEvent(PaymentCompleted).join(StartingOrderPreparation)
                onEvent(SwitchToCashPayment).join(InitializingCashPayment)
                onEvent(Cancel).join(CancellingOrder)
                onEvent(PaymentSessionExpired).stage(ExpiringOnlinePayment).apply {
                    onEvent(RetryPayment).join(InitializingOnlinePayment)
                    onEvent(Cancel).join(CancellingOrder)
                }
            }
        }
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
