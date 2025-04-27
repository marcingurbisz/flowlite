package io.flowlite.test

import io.flowlite.api.*

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

fun initializeCashPayment(order: PizzaOrder): PizzaOrder = order.copy(stage = OrderStage.InitializingCashPayment)

fun initializeOnlinePayment(order: PizzaOrder): PizzaOrder {
    // Simulate generating a transaction ID
    val transactionId = "TXN-" + System.currentTimeMillis()
    println("[Action] Initializing online payment for order ${order.processId}, transaction ID: $transactionId")
    // In a real app, might throw PaymentGatewayException if initialization fails
    return order.copy(stage = OrderStage.InitializingOnlinePayment, paymentTransactionId = transactionId)
}

fun startOrderPreparation(order: PizzaOrder): PizzaOrder = order.copy(stage = OrderStage.StartingOrderPreparation)

fun initializeDelivery(order: PizzaOrder): PizzaOrder = order.copy(stage = OrderStage.InitializingDelivery)

fun completeOrder(order: PizzaOrder): PizzaOrder = order.copy(stage = OrderStage.CompletingOrder)

fun sendOrderCancellation(order: PizzaOrder): PizzaOrder = order.copy(stage = OrderStage.CancellingOrder)

/** Custom exception for payment gateway issues. */
class PaymentGatewayException(message: String) : Exception(message)

// --- Flow Definition ---

/** Creates the main pizza order flow definition. */
fun createPizzaOrderFlow(): FlowBuilder<PizzaOrder> {

    // Define main pizza order flow
    return FlowBuilder<PizzaOrder>(OrderStage.Started).condition({ it.paymentMethod == PaymentMethod.CASH }) {
        doAction(::initializeCashPayment, OrderStage.InitializingCashPayment).apply {
            onEvent(OrderEvent.PaymentConfirmed)
                .doAction(::startOrderPreparation, OrderStage.StartingOrderPreparation)
                .onEvent(OrderEvent.ReadyForDelivery)
                .doAction(::initializeDelivery, OrderStage.InitializingDelivery)
                .apply {
                    onEvent(OrderEvent.DeliveryCompleted).doAction(::completeOrder, OrderStage.CompletingOrder).end()
                    onEvent(OrderEvent.DeliveryFailed)
                        .doAction(::sendOrderCancellation, OrderStage.CancellingOrder)
                        .end()
                }
            onEvent(OrderEvent.Cancel).join(OrderStage.CancellingOrder)
        }
    } onFalse
        {
            doAction(
                    action = ::initializeOnlinePayment,
                    stage = OrderStage.InitializingOnlinePayment,
                    retry =
                        RetryStrategy(
                            maxAttempts = 3,
                            delayMs = 1000,
                            exponentialBackoff = true,
                            retryOn = setOf(PaymentGatewayException::class),
                        ),
                )
                .apply {
                    onEvent(OrderEvent.PaymentCompleted).join(OrderStage.StartingOrderPreparation)
                    onEvent(OrderEvent.SwitchToCashPayment).join(OrderStage.InitializingCashPayment)
                    onEvent(OrderEvent.Cancel).join(OrderStage.CancellingOrder)
                    onEvent(OrderEvent.PaymentSessionExpired).transitionTo(OrderStage.ExpiringOnlinePayment).apply {
                        onEvent(OrderEvent.RetryPayment).join(OrderStage.InitializingOnlinePayment)
                        onEvent(OrderEvent.Cancel).join(OrderStage.CancellingOrder)
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
