package io.flowlite.test

import io.flowlite.api.ActionWithStatus
import io.flowlite.api.FlowBuilder
import io.flowlite.api.FlowContext
import io.flowlite.api.FlowEngine
import io.flowlite.api.RetryStrategy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull

/**
 * Test that demonstrates defining a pizza order workflow using the FlowLite API.
 * This test focuses on the process definition capabilities, not execution.
 */
class PizzaOrderFlowTest {
    // Simple mock implementation of FlowContext for the test
    private val testFlowContext = object : FlowContext {
        private val map = mutableMapOf<String, Any?>()
        
        override fun <T> get(key: String): T? = map[key] as? T
        override fun set(key: String, value: Any?) { map[key] = value }
    }

    @Test
    fun `test pizza order flow definition`() {
        // Define reusable actions first
        val orderActions = OrderActions(testFlowContext)
        
        // Define delivery flow
        val deliveryFlow = FlowBuilder<PizzaOrder>(OrderStatus.DELIVERY_INITIALIZED)
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
        
        // Define order preparation flow
        val orderPreparationFlow = FlowBuilder<PizzaOrder>(OrderStatus.ORDER_PREPARATION_STARTED)
            .doAction(
                action = { order -> order.startOrderPreparation() },
                resultStatus = OrderStatus.ORDER_READY_FOR_DELIVERY
            )
            .doAction(
                action = { order -> order.markOrderReadyForDelivery() },
                resultStatus = OrderStatus.DELIVERY_INITIALIZED
            )
            .subFlow(deliveryFlow)
        
        // Define cash payment flow
        val cashPaymentFlow = FlowBuilder<PizzaOrder>(OrderStatus.CASH_PAYMENT_INITIALIZED)
            .doAction(
                action = { order -> order.initializeCashPayment() },
                resultStatus = OrderStatus.PAYMENT_WAITING
            )
            .onEvent(OrderEvent.PAYMENT_CONFIRMED)
                .subFlow(orderPreparationFlow)
            .onEvent(OrderEvent.CANCEL)
                .doAction(orderActions.cancelOrder)
                .end()
        
        // Define online payment flow
        val onlinePaymentFlow = FlowBuilder<PizzaOrder>(OrderStatus.ONLINE_PAYMENT_INITIALIZED)
            .doAction(orderActions.initializeOnlinePayment)
            .onEvent(OrderEvent.PAYMENT_COMPLETED)
                .subFlow(orderPreparationFlow)
            .onEvent(OrderEvent.SWITCH_TO_CASH_PAYMENT)
                .subFlow(cashPaymentFlow)
            .onEvent(OrderEvent.CANCEL)
                .doAction(orderActions.cancelOrder)
                .end()
            .onEvent(OrderEvent.PAYMENT_SESSION_EXPIRED)
                .transitionTo(OrderStatus.ONLINE_PAYMENT_EXPIRED)
                .onEvent(OrderEvent.RETRY_PAYMENT)
                    .doAction(orderActions.initializeOnlinePayment)
                    .goTo(FlowBuilder<PizzaOrder>(OrderStatus.ONLINE_PAYMENT_INITIALIZED))
                .onEvent(OrderEvent.CANCEL)
                    .doAction(orderActions.cancelOrder)
                    .end()
        
        // Define main pizza order flow
        val pizzaOrderFlow = FlowBuilder<PizzaOrder>(OrderStatus.CREATED)
            .doAction(
                action = { order -> order.evaluateOrderEligibility() },
                resultStatus = OrderStatus.ORDER_ELIGIBILITY_EVALUATED
            )
            .condition(
                predicate = { order -> order.eligibility == Eligibility.VALID },
                onTrue = { flow -> 
                    flow.doAction(
                        action = { order -> order.createPizzaOrder() },
                        resultStatus = OrderStatus.ORDER_CREATED
                    )
                    .condition(
                        predicate = { order -> order.paymentMethod == PaymentMethod.CASH },
                        onTrue = { it.subFlow(cashPaymentFlow) },
                        onFalse = { it.subFlow(onlinePaymentFlow) }
                    )
                },
                onFalse = { flow -> 
                    flow.transitionTo(OrderStatus.INVALID_ORDER_ELIGIBILITY)
                        .onEvent(OrderEvent.ACKNOWLEDGE_ERROR)
                        .doAction(orderActions.cancelOrder)
                        .end()
                }
            )
        
        // Basic assertion to verify the flow was created
        assertNotNull(pizzaOrderFlow, "Pizza order flow should be created")
        
        // Register flow with engine (this would be required for execution)
        val flowEngine = FlowEngine<PizzaOrder>()
        flowEngine.registerFlow("pizza-order", pizzaOrderFlow)
    }
    
    /**
     * Container for reusable actions in the pizza order workflow.
     */
    class OrderActions(private val flowContext: FlowContext) {
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
}