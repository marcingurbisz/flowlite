package io.flowlite.test

import io.flowlite.api.Event
import io.flowlite.api.Status

/**
 * Pizza order domain class representing a pizza order in our sample workflow.
 */
class PizzaOrder {
    var paymentMethod: PaymentMethod = PaymentMethod.ONLINE
    
    // Mock methods that would perform actual business logic in a real application
    fun createPizzaOrder() {
        // Logic to create an order
    }
    
    fun initializeCashPayment() {
        // Logic to initialize cash payment
    }
    
    fun initializeOnlinePayment() {
        // Logic to initialize online payment
    }
    
    fun startOrderPreparation() {
        // Logic to start preparing the order
    }
    
    fun markOrderReadyForDelivery() {
        // Logic to mark order as ready for delivery
    }
    
    fun initializeDelivery() {
        // Logic to initialize delivery
    }
    
    fun completeOrder() {
        // Logic to complete the order
    }
    
    fun sendOrderCancellation() {
        // Logic to send cancellation notification
    }
}

/**
 * Payment methods for a pizza order.
 */
enum class PaymentMethod {
    CASH,
    ONLINE
}

/**
 * Status enum for the pizza order workflow.
 */
enum class OrderStatus : Status {
    CREATED,
    ORDER_CREATED,
    CASH_PAYMENT_INITIALIZED,
    PAYMENT_WAITING,
    ONLINE_PAYMENT_WAITING,
    ONLINE_PAYMENT_EXPIRED,
    ORDER_PREPARATION_STARTED,
    ORDER_READY_FOR_DELIVERY,
    DELIVERY_INITIALIZED,
    DELIVERY_IN_PROGRESS,
    ORDER_COMPLETED,
    ORDER_CANCELLATION_SENT
}

/**
 * Events that can occur in the pizza order workflow.
 */
enum class OrderEvent : Event {
    PAYMENT_CONFIRMED,
    PAYMENT_COMPLETED,
    SWITCH_TO_CASH_PAYMENT,
    PAYMENT_SESSION_EXPIRED,
    RETRY_PAYMENT,
    CANCEL,
    DELIVERY_COMPLETED,
    DELIVERY_FAILED
}

/**
 * Exception for payment gateway failures.
 */
class PaymentGatewayException(message: String) : Exception(message)