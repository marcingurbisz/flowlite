package io.flowlite.test

import io.flowlite.api.*
import io.flowlite.test.OrderConfirmationEvent.ConfirmedDigitally
import io.flowlite.test.OrderConfirmationEvent.ConfirmedPhysically
import io.flowlite.test.OrderConfirmationStage.*
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.repository.CrudRepository
import org.slf4j.LoggerFactory
import java.util.UUID

class OrderConfirmationTest : BehaviorSpec({
    extension(TestApplicationExtension)

    val engine = TestApplicationExtension.engine
    val persister = TestApplicationExtension.orderPersister

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

        `when`("processing digital confirmation path (engine generates id)") {
            val processId = engine.startProcess(
                flowId = "order-confirmation",
                initialState = OrderConfirmation(
                    stage = InitializingConfirmation,
                    orderNumber = "ORD-1",
                    confirmationType = ConfirmationType.DIGITAL,
                    customerName = "Alice",
                ),
            )

            then("it waits for confirmation event") {
                awaitStatus(
                    fetch = { engine.getStatus("order-confirmation", processId) },
                    expected = WaitingForConfirmation to StageStatus.PENDING,
                )
            }

            then("it completes after digital confirmation event") {
                engine.sendEvent("order-confirmation", processId, ConfirmedDigitally)
                awaitStatus(
                    fetch = { engine.getStatus("order-confirmation", processId) },
                    expected = InformingCustomer to StageStatus.COMPLETED,
                )
            }
        }

        `when`("processing physical confirmation path (caller-supplied id)") {
            val processId = UUID.randomUUID()
            val prePersisted = OrderConfirmation(
                id = processId,
                stage = InitializingConfirmation,
                orderNumber = "ORD-2",
                confirmationType = ConfirmationType.PHYSICAL,
                customerName = "Bob",
            )
            persister.save(
                ProcessData(
                    flowInstanceId = processId,
                    state = prePersisted,
                    stage = InitializingConfirmation,
                    stageStatus = StageStatus.PENDING,
                ),
            )

            engine.startProcess(
                flowId = "order-confirmation",
                flowInstanceId = processId,
            )

            engine.sendEvent("order-confirmation", processId, ConfirmedPhysically)

            then("it informs customer and completes") {
                awaitStatus(
                    fetch = { engine.getStatus("order-confirmation", processId) },
                    expected = InformingCustomer to StageStatus.COMPLETED,
                )
            }
        }
    }
})

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
    @Id
    val id: UUID? = null,
    @Version
    val version: Long = 0,
    val stage: OrderConfirmationStage,
    val stageStatus: StageStatus = StageStatus.PENDING,
    val orderNumber: String,
    val confirmationType: ConfirmationType,
    val customerName: String,
    val isRemovedFromQueue: Boolean = false,
    val isCustomerInformed: Boolean = false,
    val confirmationTimestamp: String = "",
)

interface OrderConfirmationRepository : CrudRepository<OrderConfirmation, UUID>

class SpringDataOrderConfirmationPersister(
    private val repo: OrderConfirmationRepository,
) : StatePersister<OrderConfirmation> {
    override fun save(processData: ProcessData<OrderConfirmation>): SaveResult<OrderConfirmation> {
        val stage = processData.stage as? OrderConfirmationStage
            ?: error("Unexpected stage ${processData.stage}")
        val entity = processData.state.copy(
            id = processData.flowInstanceId,
            stage = stage,
            stageStatus = processData.stageStatus,
        )
        val saved = try {
            repo.save(entity)
        } catch (ex: OptimisticLockingFailureException) {
            return SaveResult.Conflict
        }
        return SaveResult.Saved(
            processData.copy(
                state = saved,
                stage = saved.stage,
                stageStatus = saved.stageStatus,
            ),
        )
    }

    override fun load(flowInstanceId: UUID): ProcessData<OrderConfirmation>? {
        val entity = repo.findById(flowInstanceId).orElse(null) ?: return null
        return ProcessData(
            flowInstanceId = flowInstanceId,
            state = entity,
            stage = entity.stage,
            stageStatus = entity.stageStatus,
        )
    }
}

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
            waitFor(ConfirmedDigitally)
                .stage(RemovingFromConfirmationQueue, ::removeFromConfirmationQueue)
                .stage(InformingCustomer, ::informCustomer)
            waitFor(ConfirmedPhysically).join(InformingCustomer)
        }
        .end()
        .build()
}
// FLOW-DEFINITION-END
