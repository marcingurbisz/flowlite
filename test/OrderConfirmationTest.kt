package io.flowlite.test

import io.flowlite.*
import io.flowlite.test.OrderConfirmationEvent.ConfirmedDigitally
import io.flowlite.test.OrderConfirmationEvent.ConfirmedPhysically
import io.flowlite.test.OrderConfirmationStage.*
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import java.util.UUID
import org.springframework.beans.factory.getBean

class OrderConfirmationTest : BehaviorSpec({
    extension(TestApplicationExtension)

    val engine = TestApplicationExtension.context().getBean<FlowEngine>()
    val persister = TestApplicationExtension.context().getBean<SpringDataOrderConfirmationPersister>()

    given("an order confirmation flow") {
        val flow = createOrderConfirmationFlow()
        val generator = MermaidGenerator()

        `when`("generating a mermaid diagram") {
            val diagram = generator.generateDiagram(flow)

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

        `when`("processing digital confirmation path (engine generates id)") {
            val historyRepo = TestApplicationExtension.context().getBean<FlowLiteHistoryRepository>()
            val flowInstanceId = engine.startInstance(
                flowId = ORDER_CONFIRMATION_FLOW_ID,
                initialState = OrderConfirmation(
                    stage = InitializingConfirmation,
                    orderNumber = "ORD-1",
                    confirmationType = ConfirmationType.Digital,
                    customerName = "Alice",
                ),
            )

            then("it waits for confirmation event") {
                awaitStatus(
                    fetch = { engine.getStatus(ORDER_CONFIRMATION_FLOW_ID, flowInstanceId) },
                    expected = WaitingForConfirmation to StageStatus.Pending,
                )
            }

            then("it completes after digital confirmation event") {
                engine.sendEvent(ORDER_CONFIRMATION_FLOW_ID, flowInstanceId, ConfirmedDigitally)
                awaitStatus(
                    fetch = { engine.getStatus(ORDER_CONFIRMATION_FLOW_ID, flowInstanceId) },
                    expected = InformingCustomer to StageStatus.Completed,
                )

                val timeline = historyRepo.findTimeline(ORDER_CONFIRMATION_FLOW_ID, flowInstanceId)
                require(timeline.isNotEmpty()) { "Expected non-empty history timeline" }
                require(timeline.any { it.type == HistoryEntryType.InstanceStarted.name })
                require(timeline.any { it.type == HistoryEntryType.EventAppended.name && it.event == ConfirmedDigitally.name })
                require(timeline.any { it.type == HistoryEntryType.StageChanged.name && it.toStage == InformingCustomer.name })
            }
        }

        `when`("processing physical confirmation path (caller-supplied id)") {
            val historyRepo = TestApplicationExtension.context().getBean<FlowLiteHistoryRepository>()
            val flowInstanceId = UUID.randomUUID()
            val prePersisted = OrderConfirmation(
                id = flowInstanceId,
                stage = InitializingConfirmation,
                orderNumber = "ORD-2",
                confirmationType = ConfirmationType.Physical,
                customerName = "Bob",
            )
            persister.save(
                InstanceData(
                    flowInstanceId = flowInstanceId,
                    state = prePersisted,
                    stage = InitializingConfirmation,
                    stageStatus = StageStatus.Pending,
                ),
            )

            engine.startInstance(
                flowId = ORDER_CONFIRMATION_FLOW_ID,
                flowInstanceId = flowInstanceId,
            )

            engine.sendEvent(ORDER_CONFIRMATION_FLOW_ID, flowInstanceId, ConfirmedPhysically)

            then("it informs customer and completes") {
                awaitStatus(
                    fetch = { engine.getStatus(ORDER_CONFIRMATION_FLOW_ID, flowInstanceId) },
                    expected = InformingCustomer to StageStatus.Completed,
                )

                val timeline = historyRepo.findTimeline(ORDER_CONFIRMATION_FLOW_ID, flowInstanceId)
                require(timeline.isNotEmpty()) { "Expected non-empty history timeline" }
                require(timeline.any { it.type == HistoryEntryType.EventAppended.name && it.event == ConfirmedPhysically.name })
                require(timeline.any { it.type == HistoryEntryType.StageChanged.name && it.toStage == InformingCustomer.name })
            }
        }
    }
})
