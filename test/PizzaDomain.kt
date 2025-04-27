package io.flowlite.test

import io.flowlite.api.*

/** Represents the status of a pizza order. */
enum class OrderStatus : Status {
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
    val status: OrderStatus, // Ensure OrderStatus is imported/defined correctly
    val customerName: String,
    val paymentMethod: PaymentMethod,
    val paymentTransactionId: String? = null,
)

// --- Top-Level Action Functions (returning new instances) ---

fun initializeCashPayment(order: PizzaOrder): PizzaOrder = order.copy(status = OrderStatus.InitializingCashPayment)

fun initializeOnlinePayment(order: PizzaOrder): PizzaOrder {
    // Simulate generating a transaction ID
    val transactionId = "TXN-" + System.currentTimeMillis()
    println("[Action] Initializing online payment for order ${order.processId}, transaction ID: $transactionId")
    // In a real app, might throw PaymentGatewayException if initialization fails
    return order.copy(status = OrderStatus.InitializingOnlinePayment, paymentTransactionId = transactionId)
}

fun startOrderPreparation(order: PizzaOrder): PizzaOrder = order.copy(status = OrderStatus.StartingOrderPreparation)

fun initializeDelivery(order: PizzaOrder): PizzaOrder = order.copy(status = OrderStatus.InitializingDelivery)

fun completeOrder(order: PizzaOrder): PizzaOrder = order.copy(status = OrderStatus.CompletingOrder)

fun sendOrderCancellation(order: PizzaOrder): PizzaOrder = order.copy(status = OrderStatus.CancellingOrder)

/** Custom exception for payment gateway issues. */
class PaymentGatewayException(message: String) : Exception(message)

// --- Flow Definition ---

/** Creates the main pizza order flow definition. */
fun createPizzaOrderFlow(): FlowBuilder<PizzaOrder> {

    // Define main pizza order flow
    return FlowBuilder<PizzaOrder>(OrderStatus.Started).condition({ it.paymentMethod == PaymentMethod.CASH }) {
        doAction(::initializeCashPayment, OrderStatus.InitializingCashPayment).apply {
            onEvent(OrderEvent.PaymentConfirmed)
                .doAction(::startOrderPreparation, OrderStatus.StartingOrderPreparation)
                .onEvent(OrderEvent.ReadyForDelivery)
                .doAction(::initializeDelivery, OrderStatus.InitializingDelivery)
                .apply {
                    onEvent(OrderEvent.DeliveryCompleted).doAction(::completeOrder, OrderStatus.CompletingOrder).end()
                    onEvent(OrderEvent.DeliveryFailed)
                        .doAction(::sendOrderCancellation, OrderStatus.CancellingOrder)
                        .end()
                }
            onEvent(OrderEvent.Cancel).join(OrderStatus.CancellingOrder)
        }
    } onFalse
        {
            doAction(
                    action = ::initializeOnlinePayment,
                    status = OrderStatus.InitializingOnlinePayment,
                    retry =
                        RetryStrategy(
                            maxAttempts = 3,
                            delayMs = 1000,
                            exponentialBackoff = true,
                            retryOn = setOf(PaymentGatewayException::class),
                        ),
                )
                .apply {
                    onEvent(OrderEvent.PaymentCompleted).join(OrderStatus.StartingOrderPreparation)
                    onEvent(OrderEvent.SwitchToCashPayment).join(OrderStatus.InitializingCashPayment)
                    onEvent(OrderEvent.Cancel).join(OrderStatus.CancellingOrder)
                    onEvent(OrderEvent.PaymentSessionExpired).transitionTo(OrderStatus.ExpiringOnlinePayment).apply {
                        onEvent(OrderEvent.RetryPayment).join(OrderStatus.InitializingOnlinePayment)
                        onEvent(OrderEvent.Cancel).join(OderStatus.CancellingOrder)
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
