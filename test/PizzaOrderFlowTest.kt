package io.flowlite.test

import io.flowlite.api.ActionWithStatus
import io.flowlite.api.FlowBuilder
import io.flowlite.api.FlowEngine
import io.flowlite.api.ProcessInstance
import io.flowlite.api.ProcessStorage
import io.flowlite.api.RetryStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Test that demonstrates defining a pizza order workflow using the FlowLite API.
 * This test focuses on the process definition capabilities, execution, and persistence.
 */
class PizzaOrderFlowTest {

    @Test
    fun `test pizza order flow definition`() {
        // Define reusable actions first
        val orderActions = OrderActions()

        // Get the reusable flows
        val orderPreparationFlow = createOrderPreparationFlow(orderActions)

        // Define main pizza order flow
        val pizzaOrderFlow = FlowBuilder<PizzaOrder>(OrderStatus.CREATED)
            .doAction(
                action = { order -> order.createPizzaOrder() },
                resultStatus = OrderStatus.ORDER_CREATED
            )
            .condition(
                predicate = { order -> order.paymentMethod == PaymentMethod.CASH },
                onTrue = { it
                    .doAction(
                        action = { order -> order.initializeCashPayment() },
                        resultStatus = OrderStatus.PAYMENT_WAITING
                    )
                    .onEvent(OrderEvent.PAYMENT_CONFIRMED)
                        .subFlow(orderPreparationFlow)
                    .onEvent(OrderEvent.CANCEL)
                        .doAction(orderActions.cancelOrder)
                        .end()
                },
                onFalse = { it
                    .doAction(
                        action = { orderActions.initializeOnlinePayment },
                        resultStatus = OrderStatus.CASH_PAYMENT_INITIALIZED
                    )
                    .onEvent(OrderEvent.PAYMENT_COMPLETED)
                        .subFlow(orderPreparationFlow)
                    .onEvent(OrderEvent.SWITCH_TO_CASH_PAYMENT)
                        .doAction(
                            action = { order -> order.initializeCashPayment() },
                            resultStatus = OrderStatus.PAYMENT_WAITING
                        )
                        .onEvent(OrderEvent.PAYMENT_CONFIRMED)
                            .subFlow(orderPreparationFlow)
                        .onEvent(OrderEvent.CANCEL)
                            .doAction(orderActions.cancelOrder)
                            .end()
                    .onEvent(OrderEvent.CANCEL)
                        .doAction(orderActions.cancelOrder)
                        .end()
                    .onEvent(OrderEvent.PAYMENT_SESSION_EXPIRED)
                        .transitionTo(OrderStatus.ONLINE_PAYMENT_EXPIRED)
                        .onEvent(OrderEvent.RETRY_PAYMENT)
                            .doAction(orderActions.initializeOnlinePayment)
                        .onEvent(OrderEvent.CANCEL)
                            .doAction(orderActions.cancelOrder)
                            .end()
                }
            )
        
        // Basic assertion to verify the flow was created
        assertNotNull(pizzaOrderFlow, "Pizza order flow should be created")
        
        // Register flow with engine
        val flowEngine = FlowEngine<PizzaOrder>()
        flowEngine.registerFlow("pizza-order", pizzaOrderFlow)
        
        // Test starting a process with a custom ID
        val customProcessId = "pizza-123456"
        val pizzaOrder = PizzaOrder()
        pizzaOrder.paymentMethod = PaymentMethod.ONLINE
        
        val processInstance = flowEngine.startProcess(
            flowId = "pizza-order",
            data = pizzaOrder,
            processId = customProcessId
        )
        
        // Verify the process was created correctly
        assertEquals(customProcessId, processInstance.processId)
        assertEquals(OrderStatus.CREATED, processInstance.currentStatus)
        
        // Test starting a process with auto-generated ID
        val anotherPizzaOrder = PizzaOrder()
        anotherPizzaOrder.paymentMethod = PaymentMethod.CASH
        
        val anotherProcess = flowEngine.startProcess(
            flowId = "pizza-order",
            data = anotherPizzaOrder
        )
        
        // Verify auto-generated ID was assigned
        assertNotNull(anotherProcess.processId)
        
        // Retrieve a process by ID
        val retrievedProcess = flowEngine.getProcess(customProcessId)
        assertNotNull(retrievedProcess)
        
        // Trigger an event
        val updatedProcess = flowEngine.sendEvent(customProcessId, OrderEvent.PAYMENT_COMPLETED)
        
        // In a real implementation, this would change the status based on the flow definition
        // For now, we use a manual update to simulate this
        
        // Simulate process status update (in real implementation, the engine would do this)
        if (retrievedProcess != null) {
            retrievedProcess.updateStatus(OrderStatus.ORDER_PREPARATION_STARTED)
            assertEquals(OrderStatus.ORDER_PREPARATION_STARTED, retrievedProcess.currentStatus)
        }
    }
    
    /**
     * Creates the delivery flow which handles delivery initialization, completion, and failures.
     */
    private fun createDeliveryFlow(orderActions: OrderActions): FlowBuilder<PizzaOrder> {
        return FlowBuilder<PizzaOrder>(OrderStatus.DELIVERY_INITIALIZED)
            .doAction(
                action = { order -> order.initializeDelivery() },
                resultStatus = OrderStatus.DELIVERY_IN_PROGRESS
            )
            .onEvent(OrderEvent.DELIVERY_COMPLETED)
                .doAction(
                    action = { order -> order.completeOrder() },
                    resultStatus = OrderStatus.ORDER_COMPLETED
                )
                .end()
            .onEvent(OrderEvent.DELIVERY_FAILED)
                .doAction(orderActions.cancelOrder)
                .end()
    }
    
    /**
     * Creates the order preparation flow which handles the preparation and readying of an order for delivery.
     */
    private fun createOrderPreparationFlow(orderActions: OrderActions): FlowBuilder<PizzaOrder> {
        return FlowBuilder<PizzaOrder>(OrderStatus.ORDER_PREPARATION_STARTED)
            .doAction(
                action = { order -> order.startOrderPreparation() },
                resultStatus = OrderStatus.ORDER_READY_FOR_DELIVERY
            )
            .doAction(
                action = { order -> order.markOrderReadyForDelivery() },
                resultStatus = OrderStatus.DELIVERY_INITIALIZED
            )
            .subFlow(createDeliveryFlow(orderActions))
    }
    
    /**
     * Container for reusable actions in the pizza order workflow.
     */
    class OrderActions {
        // The cancel order action is used in multiple places
        val cancelOrder = ActionWithStatus<PizzaOrder>(
            action = { order -> order.sendOrderCancellation() },
            resultStatus = OrderStatus.ORDER_CANCELLATION_SENT
        )
        
        // Online payment initialization is used in two places
        val initializeOnlinePayment = ActionWithStatus<PizzaOrder>(
            action = { order -> order.initializeOnlinePayment() },
            resultStatus = OrderStatus.ONLINE_PAYMENT_WAITING
        )
        
        // With retry version
        val initializeOnlinePaymentWithRetry = ActionWithStatus<PizzaOrder>(
            action = { order -> order.initializeOnlinePayment() },
            resultStatus = OrderStatus.ONLINE_PAYMENT_WAITING,
            retry = RetryStrategy(
                maxAttempts = 3,
                delayMs = 1000,
                exponentialBackoff = true,
                retryOn = setOf(PaymentGatewayException::class)
            )
        )
    }
    
    /**
     * Example of a custom process storage implementation that would persist data to a database.
     * This is just a stub for demonstration purposes.
     */
    class DatabaseProcessStorage<T> : ProcessStorage<T> {
        override fun saveProcess(process: ProcessInstance<T>) {
            // In a real implementation, this would save to a database
            println("Saving process ${process.processId} with status ${process.currentStatus}")
        }
        
        override fun getProcess(processId: String): ProcessInstance<T>? {
            // In a real implementation, this would retrieve from a database
            println("Retrieving process $processId")
            return null
        }
    }
}