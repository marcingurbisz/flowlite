package io.flowlite.test

import io.flowlite.api.FlowEngine
import org.junit.jupiter.api.Test

/**
 * Test that demonstrates defining a pizza order workflow using the FlowLite API.
 * This test focuses on the process definition capabilities, not execution.
 */
class PizzaOrderFlowTest {

    @Test
    fun `test pizza order flow`() {

        // Register flow with engine (this would be required for execution)
        val flowEngine = FlowEngine()
        
        // Register the flow with the engine using the new API
        flowEngine.registerFlow(
            flowId = "pizza-order",
            stateClass = PizzaOrder::class,
            flow = createPizzaOrderFlow(),
            statePersister = InMemoryStatePersister()
        )

        // Start a process instance
        val processId = flowEngine.startProcess("pizza-order", PizzaOrder(
            customerName = "customer-name",
            stage = OrderStage.Started,
            paymentMethod = PaymentMethod.ONLINE,
        ))
        
        // Example of how to trigger an event for this process
        // flowEngine.triggerEvent<PizzaOrder>(processId, "pizza-order", OrderEvent.PaymentConfirmed)
    }
}