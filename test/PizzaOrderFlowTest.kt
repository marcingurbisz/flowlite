package io.flowlite.test

import OrderEvent
import OrderStatus
import PaymentGatewayException
import PaymentMethod
import PizzaOrder
import completeOrder
import createPizzaOrder
import initializeCashPayment
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
        val pizzaOrderDefinition = createPizzaOrderFlow(orderActions, createDeliveryFlow(orderActions))
        flowEngine.registerFlow(pizzaOrderDefinition)

        val initialOrder = PizzaOrder(
            status = OrderStatus.NEW,
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
    private fun createDeliveryFlow(orderActions: OrderActions): ProcessDefinition<PizzaOrder> {
        val builder = FlowBuilder<PizzaOrder>(
            flowId = "delivery-subflow",
            stateClass = PizzaOrder::class,
            initialStatus = OrderStatus.DELIVERY_INITIALIZED
        )
        
        builder
            .doAction(
                action = { order: PizzaOrder -> initializeDelivery(order) },
                resultStatus = OrderStatus.DELIVERY_IN_PROGRESS
            )
            .onEvent(OrderEvent.DELIVERY_COMPLETED)
                .doAction(
                    action = { order: PizzaOrder -> completeOrder(order) },
                    resultStatus = OrderStatus.ORDER_COMPLETED
                )
                .end()
            .onEvent(OrderEvent.DELIVERY_FAILED)
                .doAction(orderActions.cancelOrder)
                .end()
                
        return builder.build()
    }

    /**
     * Container for reusable actions in the pizza order workflow.
     */
    class OrderActions {
        // Define ALL actions needed by createPizzaOrderFlow and createDeliveryFlow
        val cancelOrder = ActionWithStatus(
            action = { order: PizzaOrder -> sendOrderCancellation(order) },
            resultStatus = OrderStatus.ORDER_CANCELLATION_SENT
        )

        // Action for initializing online payment, including retry logic
        val initializeOnlinePaymentWithRetry = ActionWithStatus(
            action = { order: PizzaOrder -> initializeOnlinePayment(order) },
            resultStatus = OrderStatus.ONLINE_PAYMENT_INITIALIZED,
            retry = RetryStrategy(
                maxAttempts = 3,
                exponentialBackoff = true,
                retryOn = setOf(PaymentGatewayException::class)
            )
        )

        // Action for completing the order (used in delivery subflow)
        val completeOrder = ActionWithStatus(
            action = { order: PizzaOrder -> completeOrder(order) },
            resultStatus = OrderStatus.ORDER_COMPLETED
        )
        
        // Action for starting order preparation
        val startPreparation = ActionWithStatus(
            action = { order: PizzaOrder -> startOrderPreparation(order) },
            resultStatus = OrderStatus.ORDER_PREPARATION_STARTED
        )
        
        // Action for initializing cash payment
        val initializeCash = ActionWithStatus(
            action = { order: PizzaOrder -> initializeCashPayment(order) },
            resultStatus = OrderStatus.CASH_PAYMENT_INITIALIZED
        )
    }

    private fun createPizzaOrderFlow(orderActions: OrderActions, deliveryFlow: ProcessDefinition<PizzaOrder>): ProcessDefinition<PizzaOrder> {
        val builder = FlowBuilder<PizzaOrder>(
            flowId = "pizza-order-flow",
            stateClass = PizzaOrder::class,
            initialStatus = OrderStatus.NEW
        )
        
        builder
            // Initial action when process starts
            .doAction(
                action = { order: PizzaOrder -> createPizzaOrder(order) },
                resultStatus = OrderStatus.ORDER_CREATED
            )
            
            // From ORDER_CREATED, branch based on payment method using the new condition method
            .condition(
                predicate = { order -> order.paymentMethod == PaymentMethod.CASH },
                onTrue = { flow -> 
                    flow.doAction(orderActions.initializeCash)
                },
                onFalse = { flow ->
                    flow.doAction(orderActions.initializeOnlinePaymentWithRetry)
                }
            )
            
            // CASH_PAYMENT_INITIALIZED state and its transitions
            .onEvent(OrderEvent.PAYMENT_CONFIRMED)
                .doAction(orderActions.startPreparation)
            
            .onEvent(OrderEvent.CANCEL)
                .doAction(orderActions.cancelOrder)
                .end()
                
            // ONLINE_PAYMENT_INITIALIZED state and its transitions
            .onEvent(OrderEvent.PAYMENT_COMPLETED)
                .doAction(orderActions.startPreparation)
            .onEvent(OrderEvent.SWITCH_TO_CASH_PAYMENT)
                .doAction(orderActions.initializeCash)
            .onEvent(OrderEvent.PAYMENT_SESSION_EXPIRED)
                .transitionTo(OrderStatus.ONLINE_PAYMENT_EXPIRED)
                .onEvent(OrderEvent.CANCEL)
                .doAction(orderActions.cancelOrder)
                .end()
            .onEvent(OrderEvent.RETRY_PAYMENT)
                .doAction(orderActions.initializeOnlinePaymentWithRetry)
            .onEvent(OrderEvent.CANCEL)
                .doAction(orderActions.cancelOrder)
                .end()
            .onEvent(OrderEvent.READY_FOR_DELIVERY)
                .transitionTo(OrderStatus.DELIVERY_INITIALIZED)
                .subFlow(deliveryFlow)
            
        return builder.build()
    }
}

/**
 * Simple in-memory state persister for testing.
 */
class InMemoryStatePersister<T : Any> : StatePersister<T> {
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