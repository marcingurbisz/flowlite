package io.flowlite.test

import OrderEvent
import OrderStatus
import PaymentGatewayException
import PaymentMethod
import PizzaOrder
import completeOrder
import createPizzaOrder
import initializeDelivery
import initializeOnlinePayment
import io.flowlite.api.*
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import sendOrderCancellation
import startOrderPreparation
import java.util.concurrent.ConcurrentHashMap

/**
 * Test that demonstrates defining a pizza order workflow using the FlowLite API.
 * This test focuses on the process definition capabilities, not execution.
 */
class PizzaOrderFlowTest {

    @Test
    fun `test pizza order flow execution`() {
        // Arrange: Engine, Persister, Definition
        val orderActions = OrderActions() // Define actions needed for the flow
        val persister = InMemoryStatePersister<PizzaOrder>()
        val flowEngine = FlowEngine(statePersister = persister) // Use named argument

        // Define the full pizza order flow using the corrected builder pattern
        val pizzaOrderDefinition = createPizzaOrderFlow(orderActions)
        flowEngine.registerFlow(pizzaOrderDefinition)

        val initialOrder = PizzaOrder(
            status = OrderStatus.CREATED,
            customerName = "Test Customer",
            paymentMethod = PaymentMethod.CASH // Start with CASH for simpler event trigger
        )

        // Act 1: Start the process
        val processId = flowEngine.startProcess(pizzaOrderDefinition.id, initialOrder)

        // Assert 1: Process ID generated, initial state persisted with correct status
        assertNotNull(processId)
        org.junit.jupiter.api.Assertions.assertTrue(processId.isNotEmpty(), "Process ID should not be empty")

        var loadedState: PizzaOrder? = persister.load(processId)
        assertNotNull(loadedState, "State should be persisted after start")
        // The engine should automatically execute the first action from CREATED to ORDER_CREATED
        // and then follow the condition to CASH_PAYMENT_INITIALIZED
        org.junit.jupiter.api.Assertions.assertEquals(OrderStatus.CASH_PAYMENT_INITIALIZED, loadedState?.status, "Status should be CASH_PAYMENT_INITIALIZED after start for CASH method")
        org.junit.jupiter.api.Assertions.assertEquals("Test Customer", loadedState?.customerName)

        // Act 2: Trigger an event
        flowEngine.triggerEvent(processId, OrderEvent.PAYMENT_CONFIRMED)

        // Assert 2: State transitioned correctly and was persisted
        loadedState = persister.load(processId)
        assertNotNull(loadedState, "State should still be persisted after event")
        org.junit.jupiter.api.Assertions.assertEquals(OrderStatus.ORDER_PREPARATION_STARTED, loadedState?.status, "Status should be ORDER_PREPARATION_STARTED after PAYMENT_CONFIRMED event")
    }

    /**
     * Creates the delivery flow which handles delivery initialization, completion, and failures.
     */
    private fun createDeliveryFlow(orderActions: OrderActions): FlowBuilder<PizzaOrder> {
        return FlowBuilder<PizzaOrder>(
            flowId = "delivery-subflow",
            stateClass = PizzaOrder::class,
            initialStatus = OrderStatus.DELIVERY_INITIALIZED
        )
            .doAction(
                action = { order: PizzaOrder -> initializeDelivery(order) }, // Call top-level function
                resultStatus = OrderStatus.DELIVERY_IN_PROGRESS
            )
            .onEvent(OrderEvent.DELIVERY_COMPLETED)
                .doAction(
                    action = { order: PizzaOrder -> completeOrder(order) }, // Call top-level function
                    resultStatus = OrderStatus.ORDER_COMPLETED
                )
                .end()
            .onEvent(OrderEvent.DELIVERY_FAILED)
                .doAction(orderActions.cancelOrder)
                .end()
    }

    /**
     * Container for reusable actions in the pizza order workflow.
     */
    class OrderActions {
        // Define ALL actions needed by createPizzaOrderFlow and createDeliveryFlow
        val cancelOrder = ActionWithStatus(
            action = { order: PizzaOrder -> sendOrderCancellation(order) }, // Call top-level function
            resultStatus = OrderStatus.ORDER_CANCELLATION_SENT
        )

        // Action for initializing online payment, including retry logic
        val initializeOnlinePaymentWithRetry = ActionWithStatus(
            action = { order: PizzaOrder -> initializeOnlinePayment(order) }, // Call top-level function
            resultStatus = OrderStatus.ONLINE_PAYMENT_INITIALIZED, // Status after successful init
            retry = RetryStrategy(
                maxAttempts = 3,
                exponentialBackoff = true,
                retryOn = setOf(PaymentGatewayException::class) // Ensure PaymentGatewayException is imported/defined
            )
        )

        // Action for completing the order (used in delivery subflow)
        val completeOrder = ActionWithStatus(
            action = { order: PizzaOrder -> completeOrder(order) },
            resultStatus = OrderStatus.ORDER_COMPLETED
        )
    }

    private fun createPizzaOrderFlow(orderActions: OrderActions): ProcessDefinition<PizzaOrder> {
        // Correctly use the FlowBuilder constructor and chain methods
        val builder = FlowBuilder<PizzaOrder>(
            flowId = "pizza-order-flow",
            stateClass = PizzaOrder::class,
            initialStatus = OrderStatus.CREATED
        )

        builder
            .doAction(
                action = { order: PizzaOrder -> createPizzaOrder(order) },
                resultStatus = OrderStatus.ORDER_CREATED
            )
            .whenStateIs(OrderStatus.ORDER_CREATED).and { it.paymentMethod == PaymentMethod.CASH }
                .goTo(OrderStatus.CASH_PAYMENT_INITIALIZED)

            // Events from CASH_PAYMENT_INITIALIZED
            .whenStateIs(OrderStatus.CASH_PAYMENT_INITIALIZED)
                .onEvent(OrderEvent.PAYMENT_CONFIRMED)
                    .doAction(
                        action = { order: PizzaOrder -> startOrderPreparation(order) },
                        resultStatus = OrderStatus.ORDER_PREPARATION_STARTED
                    )
                    .end()

            // --- ONLINE Payment Path ---
            .whenStateIs(OrderStatus.ORDER_CREATED).and { it.paymentMethod == PaymentMethod.ONLINE }
                .goTo(OrderStatus.ONLINE_PAYMENT_INITIALIZED)

            // Action in ONLINE_PAYMENT_INITIALIZED
            .whenStateIs(OrderStatus.ONLINE_PAYMENT_INITIALIZED)
                .doAction(orderActions.initializeOnlinePaymentWithRetry) // Uses action with retry
                // Status change to ONLINE_PAYMENT_INITIALIZED happens via action's resultStatus

            // Events from ONLINE_PAYMENT_INITIALIZED
            .whenStateIs(OrderStatus.ONLINE_PAYMENT_INITIALIZED)
                .onEvent(OrderEvent.PAYMENT_COMPLETED)
                    .doAction(
                        action = { order: PizzaOrder -> startOrderPreparation(order) },
                        resultStatus = OrderStatus.ORDER_PREPARATION_STARTED
                    )
                    .end()
                .onEvent(OrderEvent.SWITCH_TO_CASH_PAYMENT)
                    .goTo(OrderStatus.CASH_PAYMENT_INITIALIZED) // Transition to cash path
                .onEvent(OrderEvent.PAYMENT_SESSION_EXPIRED)
                    .goTo(OrderStatus.ONLINE_PAYMENT_EXPIRED)
                .onEvent(OrderEvent.CANCEL)
                    .doAction(orderActions.cancelOrder) // Use shared cancel action
                    .end()

            // --- Order Preparation and Delivery Subflow ---
            .whenStateIs(OrderStatus.ORDER_PREPARATION_STARTED)
                // Action is implicitly defined by transitions from payment states
                .onEvent(OrderEvent.READY_FOR_DELIVERY)
                    .goTo(OrderStatus.DELIVERY_INITIALIZED) // Trigger delivery subflow

            // --- Delivery Subflow Trigger --- // Implicitly handled by goTo(DELIVERY_INITIALIZED)
            .whenStateIs(OrderStatus.DELIVERY_INITIALIZED) // Entry point for the subflow
                .subFlow(createDeliveryFlow(orderActions))

        return builder.build() // Call build() at the end
    }
}

/**
 * Simple in-memory state persister for testing.
 */
class InMemoryStatePersister<T : Any> : StatePersister<T> { // Add T: Any constraint
    private val storage = ConcurrentHashMap<String, T>()

    override fun save(processId: String, state: T) {
        println("[Persister] Saving state for process '$processId': $state")
        storage[processId] = state
    }

    override fun load(processId: String): T? {
        println("[Persister] Loading state for process '$processId'")
        return storage[processId]
    }
}