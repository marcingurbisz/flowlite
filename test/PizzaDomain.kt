package io.flowlite.test

import io.flowlite.api.*

/**
 * Represents the status of a pizza order.
 */
enum class OrderStatus : Status {
    NEW,                // Initial state before any processing
    ORDER_CREATED,          // Order details recorded
    CASH_PAYMENT_INITIALIZED, // Intermediate state for cash
    ONLINE_PAYMENT_INITIALIZED, // Intermediate state for online
    ONLINE_PAYMENT_EXPIRED, // Online payment session timed out
    ORDER_PREPARATION_STARTED, // Kitchen starts making the pizza
    DELIVERY_INITIALIZED,   // Delivery process started
    DELIVERY_IN_PROGRESS,   // Pizza is out for delivery
    ORDER_COMPLETED,        // Pizza delivered successfully
    ORDER_CANCELLATION_SENT, // Order was cancelled
    ONLINE_PAYMENT_WAITING,
    PAYMENT_WAITING,
}

/**
 * Represents events that can occur during the pizza order lifecycle.
 */
enum class OrderEvent : Event {
    PAYMENT_CONFIRMED,      // Cash payment received
    PAYMENT_COMPLETED,      // Online payment successful
    SWITCH_TO_CASH_PAYMENT, // Customer decided to pay cash instead of online
    PAYMENT_SESSION_EXPIRED,// Online payment link/session expired
    RETRY_PAYMENT,          // Customer wants to try online payment again
    READY_FOR_DELIVERY,     // Pizza is ready for delivery pickup/dispatch
    DELIVERY_COMPLETED,     // Delivery confirmed
    DELIVERY_FAILED,        // Delivery could not be completed
    CANCEL                  // Customer or system cancels the order
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
    order.copy(status = OrderStatus.ORDER_CREATED)

fun initializeCashPayment(order: PizzaOrder): PizzaOrder = 
    order.copy(status = OrderStatus.CASH_PAYMENT_INITIALIZED)

fun initializeOnlinePayment(order: PizzaOrder): PizzaOrder {
    // Simulate generating a transaction ID
    val transactionId = "TXN-" + System.currentTimeMillis()
    println("[Action] Initializing online payment for order ${order.processId}, transaction ID: $transactionId")
    // In a real app, might throw PaymentGatewayException if initialization fails
    return order.copy(status = OrderStatus.ONLINE_PAYMENT_INITIALIZED, paymentTransactionId = transactionId)
}

fun startOrderPreparation(order: PizzaOrder): PizzaOrder = 
    order.copy(status = OrderStatus.ORDER_PREPARATION_STARTED)

fun initializeDelivery(order: PizzaOrder): PizzaOrder = 
    order.copy(status = OrderStatus.DELIVERY_INITIALIZED)

fun completeOrder(order: PizzaOrder): PizzaOrder = 
    order.copy(status = OrderStatus.ORDER_COMPLETED)

fun sendOrderCancellation(order: PizzaOrder): PizzaOrder = 
    order.copy(status = OrderStatus.ORDER_CANCELLATION_SENT)

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
            status = OrderStatus.ORDER_CREATED
        )
        .condition(
            predicate = { order -> order.paymentMethod == PaymentMethod.CASH },
            onTrue = { it
                .doAction(
                    action = { order -> initializeCashPayment(order) },
                    status = OrderStatus.CASH_PAYMENT_INITIALIZED
                )
                .transitionTo(OrderStatus.PAYMENT_WAITING)
                .onEvent(OrderEvent.PAYMENT_CONFIRMED)
                    .doAction(
                        action = { order -> startOrderPreparation(order) },
                        status = OrderStatus.ORDER_PREPARATION_STARTED
                    )
                    .onEvent(OrderEvent.READY_FOR_DELIVERY)
                        .doAction(
                            action = { order -> initializeDelivery(order) },
                            status = OrderStatus.DELIVERY_INITIALIZED
                        )
                        .transitionTo(OrderStatus.DELIVERY_IN_PROGRESS)
                        .onEvent(OrderEvent.DELIVERY_COMPLETED)
                            .doAction(
                                action = { order -> completeOrder(order) },
                                status = OrderStatus.ORDER_COMPLETED
                            )
                            .end()
                        .onEvent(OrderEvent.DELIVERY_FAILED)
                            .doAction(
                                action = { order -> sendOrderCancellation(order) },
                                status = OrderStatus.ORDER_CANCELLATION_SENT
                            )
                            .end()
                .onEvent(OrderEvent.CANCEL)
                    .joinActionWithStatus(OrderStatus.ORDER_CANCELLATION_SENT)
            },
            onFalse = { it
                .doAction(
                    action = { order -> initializeOnlinePayment(order) },
                    status = OrderStatus.ONLINE_PAYMENT_INITIALIZED,
                    retry = RetryStrategy(
                        maxAttempts = 3,
                        delayMs = 1000,
                        exponentialBackoff = true,
                        retryOn = setOf(PaymentGatewayException::class)
                    )
                )
                .transitionTo(OrderStatus.ONLINE_PAYMENT_WAITING)
                .onEvent(OrderEvent.PAYMENT_COMPLETED)
                    .joinActionWithStatus(OrderStatus.ORDER_PREPARATION_STARTED)
                .onEvent(OrderEvent.SWITCH_TO_CASH_PAYMENT)
                    .joinActionWithStatus(OrderStatus.CASH_PAYMENT_INITIALIZED)
                .onEvent(OrderEvent.CANCEL)
                    .joinActionWithStatus(OrderStatus.ORDER_CANCELLATION_SENT)
                .onEvent(OrderEvent.PAYMENT_SESSION_EXPIRED)
                    .transitionTo(OrderStatus.ONLINE_PAYMENT_EXPIRED)
                    .onEvent(OrderEvent.RETRY_PAYMENT)
                        .joinActionWithStatus(OrderStatus.ONLINE_PAYMENT_INITIALIZED)
                    .onEvent(OrderEvent.CANCEL)
                        .joinActionWithStatus(OrderStatus.ORDER_CANCELLATION_SENT)
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
