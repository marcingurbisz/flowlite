package io.flowlite.test

import io.flowlite.api.*

/**
 * Represents the status of a pizza order.
 */
enum class OrderStatus : Status {
    New,                // Initial state before any processing
    OrderCreated,          // Order details recorded
    CashPaymentInitialized, // Intermediate state for cash
    OnlinePaymentInitialized, // Intermediate state for online
    OnlinePaymentExpired, // Online payment session timed out
    OrderPreparationStarted, // Kitchen starts making the pizza
    DeliveryInitialized,   // Delivery process started
    DeliveryInProgress,   // Pizza is out for delivery
    OrderCompleted,        // Pizza delivered successfully
    OrderCancellationSent, // Order was cancelled
}

/**
 * Represents events that can occur during the pizza order lifecycle.
 */
enum class OrderEvent : Event {
    PaymentConfirmed,      // Cash payment received
    PaymentCompleted,      // Online payment successful
    SwitchToCashPayment, // Customer decided to pay cash instead of online
    PaymentSessionExpired,// Online payment link/session expired
    RetryPayment,          // Customer wants to try online payment again
    ReadyForDelivery,     // Pizza is ready for delivery pickup/dispatch
    DeliveryCompleted,     // Delivery confirmed
    DeliveryFailed,        // Delivery could not be completed
    Cancel                  // Customer or system cancels the order
}

/**
 * Payment methods available.
 */
enum class PaymentMethod {
    CASH, ONLINE
}

data class PizzaOrder(
    val processId: String = "", // Will be set by the engine when starting
    val status: OrderStatus, // Ensure OrderStatus is imported/defined correctly
    val customerName: String,
    val paymentMethod: PaymentMethod,
    val paymentTransactionId: String? = null
)

// --- Top-Level Action Functions (returning new instances) ---

fun createPizzaOrder(order: PizzaOrder): PizzaOrder =
    order.copy(status = OrderStatus.OrderCreated)

fun initializeCashPayment(order: PizzaOrder): PizzaOrder =
    order.copy(status = OrderStatus.CashPaymentInitialized)

fun initializeOnlinePayment(order: PizzaOrder): PizzaOrder {
    // Simulate generating a transaction ID
    val transactionId = "TXN-" + System.currentTimeMillis()
    println("[Action] Initializing online payment for order ${order.processId}, transaction ID: $transactionId")
    // In a real app, might throw PaymentGatewayException if initialization fails
    return order.copy(status = OrderStatus.OnlinePaymentInitialized, paymentTransactionId = transactionId)
}

fun startOrderPreparation(order: PizzaOrder): PizzaOrder =
    order.copy(status = OrderStatus.OrderPreparationStarted)

fun initializeDelivery(order: PizzaOrder): PizzaOrder =
    order.copy(status = OrderStatus.DeliveryInitialized)

fun completeOrder(order: PizzaOrder): PizzaOrder =
    order.copy(status = OrderStatus.OrderCompleted)

fun sendOrderCancellation(order: PizzaOrder): PizzaOrder =
    order.copy(status = OrderStatus.OrderCancellationSent)

/**
 * Custom exception for payment gateway issues.
 */
class PaymentGatewayException(message: String) : Exception(message)

// --- Flow Definition ---

/**
 * Creates the main pizza order flow definition.
 */
fun createPizzaOrderFlow(): FlowBuilder<PizzaOrder> {

    // Define main pizza order flow
    return FlowBuilder<PizzaOrder>()
        .doAction(
            action = { order -> createPizzaOrder(order) },
            status = OrderStatus.OrderCreated
        )
        .condition(
            predicate = { order -> order.paymentMethod == PaymentMethod.CASH },
            onTrue = {
                it
                    .doAction({ order -> initializeCashPayment(order) }, OrderStatus.CashPaymentInitialized).apply {
                        onEvent(OrderEvent.PaymentConfirmed)
                            .doAction({ order -> startOrderPreparation(order) }, OrderStatus.OrderPreparationStarted)
                            .apply {
                                onEvent(OrderEvent.ReadyForDelivery)
                                    .doAction({ order -> initializeDelivery(order) }, OrderStatus.DeliveryInitialized)
                                    .transitionTo(OrderStatus.DeliveryInProgress)
                                onEvent(OrderEvent.DeliveryCompleted)
                                    .doAction(
                                        action = { order -> completeOrder(order) },
                                        status = OrderStatus.OrderCompleted
                                    )
                                    .end()
                                onEvent(OrderEvent.DeliveryFailed)
                                    .doAction(
                                        action = { order -> sendOrderCancellation(order) },
                                        status = OrderStatus.OrderCancellationSent
                                    )
                                    .end()
                            }
                        onEvent(OrderEvent.Cancel)
                            .joinActionWithStatus(OrderStatus.OrderCancellationSent)
                    }
            },
            onFalse = {
                it
                    .doAction(
                        action = { order -> initializeOnlinePayment(order) },
                        status = OrderStatus.OnlinePaymentInitialized,
                        retry = RetryStrategy(
                            maxAttempts = 3,
                            delayMs = 1000,
                            exponentialBackoff = true,
                            retryOn = setOf(PaymentGatewayException::class)
                        )
                    ).apply {
                        onEvent(OrderEvent.PaymentCompleted)
                            .joinActionWithStatus(OrderStatus.OrderPreparationStarted)
                        onEvent(OrderEvent.SwitchToCashPayment)
                            .joinActionWithStatus(OrderStatus.CashPaymentInitialized)
                        onEvent(OrderEvent.Cancel)
                            .joinActionWithStatus(OrderStatus.OrderCancellationSent)
                        onEvent(OrderEvent.PaymentSessionExpired)
                            .transitionTo(OrderStatus.OnlinePaymentExpired).apply {
                                onEvent(OrderEvent.RetryPayment)
                                    .joinActionWithStatus(OrderStatus.OnlinePaymentInitialized)
                            }
                    }
            }
        )
}

/**
 * Simple in-memory state persister for testing purposes
 */
class InMemoryStatePersister<T : Any> : StatePersister<T> {
    private val states = mutableMapOf<String, T>()

    override fun save(processId: String, state: T) {
        states[processId] = state
    }

    override fun load(processId: String): T? {
        return states[processId]
    }
}
