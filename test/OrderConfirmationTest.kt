package io.flowlite.test

import io.flowlite.api.*
import io.flowlite.api.StageStatus
import io.flowlite.test.OrderConfirmationEvent.ConfirmedPhysically
import io.flowlite.test.OrderConfirmationStage.*
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.slf4j.LoggerFactory

enum class OrderConfirmationStage : Stage {
    InitializingConfirmation,
    WaitingForConfirmation,
    RemovingFromConfirmationQueue,
    InformingCustomer,
}

enum class OrderConfirmationEvent : Event {
    ConfirmedPhysically,
    ConfirmedDigitally,
}

enum class ConfirmationType {
    DIGITAL, // Online payment, app confirmation, e-signature
    PHYSICAL, // Phone confirmation, in-person payment, paper signature
}

data class OrderConfirmation(
    val processId: String = "",
    val stage: OrderConfirmationStage,
    val orderNumber: String,
    val confirmationType: ConfirmationType,
    val customerName: String,
    val isRemovedFromQueue: Boolean = false,
    val isCustomerInformed: Boolean = false,
    val confirmationTimestamp: String = "",
)

private val orderLogger = LoggerFactory.getLogger("OrderConfirmationActions")

fun initializeOrderConfirmation(confirmation: OrderConfirmation): OrderConfirmation {
    orderLogger.info("Initializing order confirmation for order ${confirmation.orderNumber}")
    val timestamp = System.currentTimeMillis().toString()
    return confirmation.copy(stage = InitializingConfirmation, confirmationTimestamp = timestamp)
}

fun removeFromConfirmationQueue(confirmation: OrderConfirmation): OrderConfirmation {
    orderLogger.info("Removing order ${confirmation.orderNumber} from confirmation queue (digital processing)")
    return confirmation.copy(stage = RemovingFromConfirmationQueue, isRemovedFromQueue = true)
}

fun informCustomer(confirmation: OrderConfirmation): OrderConfirmation {
    val method =
        when (confirmation.confirmationType) {
            ConfirmationType.DIGITAL -> "app notification/email"
            ConfirmationType.PHYSICAL -> "phone call"
        }
    orderLogger.info(
        "Informing customer ${confirmation.customerName} via $method that order ${confirmation.orderNumber} is being prepared"
    )
    return confirmation.copy(stage = InformingCustomer, isCustomerInformed = true)
}

// FLOW-DEFINITION-START
fun createOrderConfirmationFlow(): Flow<OrderConfirmation> {
    return FlowBuilder<OrderConfirmation>()
        .stage(InitializingConfirmation, ::initializeOrderConfirmation)
        .stage(WaitingForConfirmation)
        .apply {
            waitFor(OrderConfirmationEvent.ConfirmedDigitally)
                .stage(RemovingFromConfirmationQueue, ::removeFromConfirmationQueue)
                .stage(InformingCustomer, ::informCustomer)
            waitFor(ConfirmedPhysically).join(InformingCustomer)
        }
        .end()
        .build()
}
// FLOW-DEFINITION-END

/**
 * Tests for the order confirmation flow definition and diagram generation.
 */
class OrderConfirmationTest : BehaviorSpec({

    given("an order confirmation flow") {
        val flow = createOrderConfirmationFlow()
        val generator = MermaidGenerator()

        `when`("generating a mermaid diagram") {
            val diagram = generator.generateDiagram("order-confirmation", flow)
            
            println("\n=== ORDER CONFIRMATION FLOW DIAGRAM ===")
            println(diagram)
            println("=== END DIAGRAM ===\n")

            then("should be a valid state diagram") {
                diagram shouldContain "stateDiagram-v2"
                diagram shouldContain "[*] --> InitializingConfirmation"
            }

            then("should contain all stages") {
                diagram shouldContain "InitializingConfirmation"
                diagram shouldContain "WaitingForConfirmation"
                diagram shouldContain "RemovingFromConfirmationQueue"
                diagram shouldContain "InformingCustomer"
            }

            then("should show automatic progressions") {
                diagram shouldContain "InitializingConfirmation --> WaitingForConfirmation"
                diagram shouldContain "RemovingFromConfirmationQueue --> InformingCustomer"
            }

            then("should contain event transitions") {
                diagram shouldContain "onEvent ConfirmedDigitally"
                diagram shouldContain "onEvent ConfirmedPhysically"
                diagram shouldContain "WaitingForConfirmation --> RemovingFromConfirmationQueue: onEvent ConfirmedDigitally"
                diagram shouldContain "WaitingForConfirmation --> InformingCustomer: onEvent ConfirmedPhysically"
            }

            then("should have terminal state") {
                diagram shouldContain "InformingCustomer --> [*]"
            }

            then("should include method names in stage descriptions") {
                diagram shouldContain "InitializingConfirmation: InitializingConfirmation initializeOrderConfirmation()"
                diagram shouldContain "RemovingFromConfirmationQueue: RemovingFromConfirmationQueue removeFromConfirmationQueue()"
                diagram shouldContain "InformingCustomer: InformingCustomer informCustomer()"
            }
        }
    }
})

class OrderConfirmationEngineTest : BehaviorSpec({
    given("order confirmation flow") {
        val engine = FlowEngine()
        val persister = InMemoryStatePersister<OrderConfirmation>()
        engine.registerFlow("order-confirmation", createOrderConfirmationFlow(), persister)

        `when`("processing digital confirmation path") {
            val processId = engine.startProcess(
                flowId = "order-confirmation",
                initialState = OrderConfirmation(
                    processId = "p-1",
                    stage = OrderConfirmationStage.WaitingForConfirmation,
                    orderNumber = "ORD-1",
                    confirmationType = ConfirmationType.DIGITAL,
                    customerName = "Alice",
                ),
            )

            then("it waits for confirmation event") {
                engine.getStatus("order-confirmation", processId) shouldBe
                    (OrderConfirmationStage.WaitingForConfirmation to StageStatus.PENDING)
            }

            then("it completes after digital confirmation event") {
                engine.sendEvent("order-confirmation", processId, OrderConfirmationEvent.ConfirmedDigitally)
                engine.getStatus("order-confirmation", processId) shouldBe
                    (OrderConfirmationStage.InformingCustomer to StageStatus.COMPLETED)
            }
        }

        `when`("processing physical confirmation path") {
            val processId = engine.startProcess(
                flowId = "order-confirmation",
                initialState = OrderConfirmation(
                    processId = "p-2",
                    stage = OrderConfirmationStage.WaitingForConfirmation,
                    orderNumber = "ORD-2",
                    confirmationType = ConfirmationType.PHYSICAL,
                    customerName = "Bob",
                ),
            )

            engine.sendEvent("order-confirmation", processId, OrderConfirmationEvent.ConfirmedPhysically)

            then("it informs customer and completes") {
                engine.getStatus("order-confirmation", processId) shouldBe
                    (OrderConfirmationStage.InformingCustomer to StageStatus.COMPLETED)
            }
        }
    }
})