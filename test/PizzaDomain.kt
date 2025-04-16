import io.flowlite.api.Event
import io.flowlite.api.Status

/**
 * Represents the status of a pizza order.
 */
enum class OrderStatus : Status {
    CREATED,                // Initial state before any processing
    ORDER_CREATED,          // Order details recorded
    CASH_PAYMENT_INITIALIZED, // Intermediate state for cash
    ONLINE_PAYMENT_INITIALIZED, // Intermediate state for online
    ONLINE_PAYMENT_EXPIRED, // Online payment session timed out
    ORDER_PREPARATION_STARTED, // Kitchen starts making the pizza
    DELIVERY_INITIALIZED,   // Delivery process started
    DELIVERY_IN_PROGRESS,   // Pizza is out for delivery
    ORDER_COMPLETED,        // Pizza delivered successfully
    ORDER_CANCELLATION_SENT // Order was cancelled
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

/**
 * Represents the state of a single pizza order.
 * This is an immutable data class.
 *
 * @property processId Unique identifier for this specific order instance (assigned by the engine).
 * @property status The current status of the order in the workflow.
 * @property customerName Name of the customer.
 * @property toppings List of toppings requested.
 * @property paymentMethod How the customer intends to pay.
 * @property paymentTransactionId Optional ID for online payment attempts.
 */
data class PizzaOrder(
    val processId: String = "", // Will be set by the engine when starting
    val status: OrderStatus, // Ensure OrderStatus is imported/defined correctly
    val customerName: String,
    val toppings: List<String> = listOf("Cheese"),
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
