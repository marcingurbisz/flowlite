package io.flowlite.test

import io.flowlite.api.*
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Test that demonstrates defining a pizza order workflow using the FlowLite API.
 * This test focuses on the process definition capabilities, not execution.
 */
class PizzaOrderFlowTest {

    @Test
    fun `test pizza order flow definition`() {
        // Define reusable actions first
        val orderActions = OrderActions()

        // Get the reusable flows
        val orderPreparationFlow = createOrderPreparationFlow(orderActions)

        // Define main pizza order flow
        val pizzaOrderFlow = FlowBuilder<PizzaOrder>()
            .doAction(
                action = { order -> createPizzaOrder(order) },
                resultStatus = OrderStatus.ORDER_CREATED
            )
            .condition(
                predicate = { order -> order.paymentMethod == PaymentMethod.CASH },
                onTrue = { it
                    .doAction(
                        action = { order -> initializeCashPayment(order) },
                        resultStatus = OrderStatus.CASH_PAYMENT_INITIALIZED
                    )
                    .onEvent(OrderEvent.PAYMENT_CONFIRMED)
                        .subFlow(orderPreparationFlow)
                    .onEvent(OrderEvent.CANCEL)
                        .doAction(orderActions.cancelOrder)
                        .end()
                },
                onFalse = { it
                    .doAction(
                        action = { order -> initializeOnlinePayment(order) },
                        resultStatus = OrderStatus.ONLINE_PAYMENT_INITIALIZED
                    )
                    .onEvent(OrderEvent.PAYMENT_COMPLETED)
                        .subFlow(orderPreparationFlow)
                    .onEvent(OrderEvent.SWITCH_TO_CASH_PAYMENT)
                        .doAction(
                            action = { order -> initializeCashPayment(order) },
                            resultStatus = OrderStatus.CASH_PAYMENT_INITIALIZED
                        )
                    .onEvent(OrderEvent.CANCEL)
                        .doAction(orderActions.cancelOrder)
                        .end()
                    .onEvent(OrderEvent.PAYMENT_SESSION_EXPIRED)
                        .transitionTo(OrderStatus.ONLINE_PAYMENT_EXPIRED)
                        .onEvent(OrderEvent.RETRY_PAYMENT)
                            .doAction(
                                action = { order -> initializeOnlinePayment(order) },
                                resultStatus = OrderStatus.ONLINE_PAYMENT_INITIALIZED
                            )
                        .onEvent(OrderEvent.CANCEL)
                            .doAction(orderActions.cancelOrder)
                            .end()
                }
            )
        
        // Basic assertion to verify the flow was created
        assertNotNull(pizzaOrderFlow, "Pizza order flow should be created")
        
        // Register flow with engine (this would be required for execution)
        val flowEngine = FlowEngine<PizzaOrder>(
            processDefinitions = mutableMapOf(),
            statePersister = InMemoryStatePersister()
        )
        
        // Register the flow with the engine using the new API
        flowEngine.registerFlow(
            flowId = "pizza-order",
            stateClass = PizzaOrder::class,
            flowBuilder = pizzaOrderFlow
        )

        flowEngine.startProcess("pizza-order", PizzaOrder(
            customerName = "customer-name",
            status = OrderStatus.NEW,
            paymentMethod = PaymentMethod.CASH,
        ))
    }
    
    /**
     * Creates the delivery flow which handles delivery initialization, completion, and failures.
     */
    private fun createDeliveryFlow(orderActions: OrderActions): FlowBuilder<PizzaOrder> {
        return FlowBuilder<PizzaOrder>()
            .doAction(
                action = { order -> initializeDelivery(order) },
                resultStatus = OrderStatus.DELIVERY_INITIALIZED
            )
            .transitionTo(OrderStatus.DELIVERY_IN_PROGRESS)
            .onEvent(OrderEvent.DELIVERY_COMPLETED)
                .doAction(
                    action = { order -> completeOrder(order) },
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
        return FlowBuilder<PizzaOrder>()
            .doAction(
                action = { order -> startOrderPreparation(order) },
                resultStatus = OrderStatus.ORDER_PREPARATION_STARTED
            )
            .onEvent(OrderEvent.READY_FOR_DELIVERY)
                .doAction(
                    action = { order -> initializeDelivery(order) },
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
            action = { order -> sendOrderCancellation(order) },
            resultStatus = OrderStatus.ORDER_CANCELLATION_SENT
        )
        
        // Online payment initialization is used in two places
        val initializeOnlinePayment = ActionWithStatus<PizzaOrder>(
            action = { order -> initializeOnlinePayment(order) },
            resultStatus = OrderStatus.ONLINE_PAYMENT_INITIALIZED
        )
        
        // With retry version
        val initializeOnlinePaymentWithRetry = ActionWithStatus<PizzaOrder>(
            action = { order -> initializeOnlinePayment(order) },
            resultStatus = OrderStatus.ONLINE_PAYMENT_INITIALIZED,
            retry = RetryStrategy(
                maxAttempts = 3,
                delayMs = 1000,
                exponentialBackoff = true,
                retryOn = setOf(PaymentGatewayException::class)
            )
        )
    }
    
    /**
     * Simple in-memory state persister for testing purposes
     */
    class InMemoryStatePersister : StatePersister<PizzaOrder> {
        private val states = mutableMapOf<String, PizzaOrder>()
        
        override fun save(processId: String, state: PizzaOrder) {
            states[processId] = state
        }
        
        override fun load(processId: String): PizzaOrder? {
            return states[processId]
        }
    }
}