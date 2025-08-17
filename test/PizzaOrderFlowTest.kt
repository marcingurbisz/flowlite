package io.flowlite.test

import io.flowlite.api.FlowEngine
import io.kotest.core.spec.style.BehaviorSpec

/**
 * Test that demonstrates defining a pizza order workflow using the FlowLite API.
 * This test focuses on the process definition capabilities, not execution.
 */
class PizzaOrderFlowTest : BehaviorSpec({

    given("a pizza order flow") {
        val flowEngine = FlowEngine()
        
        `when`("registering the flow with the engine") {
            flowEngine.registerFlow(
                flowId = "pizza-order",
                stateClass = PizzaOrder::class,
                flow = createPizzaOrderFlow(),
                statePersister = InMemoryStatePersister()
            )

            then("should be able to start a process instance") {
                flowEngine.startProcess("pizza-order", PizzaOrder(
                    customerName = "customer-name",
                    stage = OrderStage.Started,
                    paymentMethod = PaymentMethod.ONLINE,
                ))
            }
        }
    }
})