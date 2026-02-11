package io.flowlite.test

import io.flowlite.*
import io.flowlite.test.OrderConfirmationEvent.ConfirmedDigitally
import io.flowlite.test.OrderConfirmationEvent.ConfirmedPhysically
import io.flowlite.test.OrderConfirmationStage.*
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.repository.CrudRepository
import java.util.UUID

const val ORDER_CONFIRMATION_FLOW_ID = "order-confirmation"

class OrderConfirmationTest : BehaviorSpec({
    extension(TestApplicationExtension)

    val engine = TestApplicationExtension.engine
    val persister = TestApplicationExtension.orderPersister

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
            val flowInstanceId = engine.startInstance(
                flowId = ORDER_CONFIRMATION_FLOW_ID,
                initialState = OrderConfirmation(
                    stage = InitializingConfirmation,
                    orderNumber = "ORD-1",
                    confirmationType = ConfirmationType.DIGITAL,
                    customerName = "Alice",
                ),
            )

            then("it waits for confirmation event") {
                awaitStatus(
                    fetch = { engine.getStatus(ORDER_CONFIRMATION_FLOW_ID, flowInstanceId) },
                    expected = WaitingForConfirmation to StageStatus.PENDING,
                )
            }

            then("it completes after digital confirmation event") {
                engine.sendEvent(ORDER_CONFIRMATION_FLOW_ID, flowInstanceId, ConfirmedDigitally)
                awaitStatus(
                    fetch = { engine.getStatus(ORDER_CONFIRMATION_FLOW_ID, flowInstanceId) },
                    expected = InformingCustomer to StageStatus.COMPLETED,
                )
            }
        }

        `when`("processing physical confirmation path (caller-supplied id)") {
            val flowInstanceId = UUID.randomUUID()
            val prePersisted = OrderConfirmation(
                id = flowInstanceId,
                stage = InitializingConfirmation,
                orderNumber = "ORD-2",
                confirmationType = ConfirmationType.PHYSICAL,
                customerName = "Bob",
            )
            persister.save(
                InstanceData(
                    flowInstanceId = flowInstanceId,
                    state = prePersisted,
                    stage = InitializingConfirmation,
                    stageStatus = StageStatus.PENDING,
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
    override fun tryTransitionStageStatus(
        flowInstanceId: UUID,
        expectedStage: Stage,
        expectedStageStatus: StageStatus,
        newStageStatus: StageStatus,
    ): Boolean {
        val current = load(flowInstanceId)
        if (current.stage != expectedStage) return false
        if (current.stageStatus != expectedStageStatus) return false

        return try {
            repo.save(
                current.state.copy(
                    stageStatus = newStageStatus,
                ),
            )
            true
        } catch (_: OptimisticLockingFailureException) {
            false
        }
    }

    override fun save(instanceData: InstanceData<OrderConfirmation>): InstanceData<OrderConfirmation> {
        val stage = instanceData.stage as? OrderConfirmationStage
            ?: error("Unexpected stage ${instanceData.stage}")
        val entity = instanceData.state.copy(
            id = instanceData.flowInstanceId,
            stage = stage,
            stageStatus = instanceData.stageStatus,
        )
        val saved = repo.save(entity)
        return instanceData.copy(
            state = saved,
            stage = saved.stage,
            stageStatus = saved.stageStatus,
        )
    }

    override fun load(flowInstanceId: UUID): InstanceData<OrderConfirmation> {
        val entity = repo.findById(flowInstanceId).orElse(null)
            ?: error("Flow instance '$flowInstanceId' not found")
        return InstanceData(
            flowInstanceId = flowInstanceId,
            state = entity,
            stage = entity.stage,
            stageStatus = entity.stageStatus,
        )
    }
}

fun initializeOrderConfirmation(confirmation: OrderConfirmation): OrderConfirmation {
    log.info { "Initializing order confirmation for order ${confirmation.orderNumber}" }
    val timestamp = System.currentTimeMillis().toString()
    return confirmation.copy(confirmationTimestamp = timestamp)
}

fun removeFromConfirmationQueue(confirmation: OrderConfirmation): OrderConfirmation {
    log.info { "Removing order ${confirmation.orderNumber} from confirmation queue (digital processing)" }
    return confirmation.copy(isRemovedFromQueue = true)
}

fun informCustomer(confirmation: OrderConfirmation): OrderConfirmation {
    val method =
        when (confirmation.confirmationType) {
            ConfirmationType.DIGITAL -> "app notification/email"
            ConfirmationType.PHYSICAL -> "phone call"
        }
    log.info { "Informing customer ${confirmation.customerName} via $method that order ${confirmation.orderNumber} is being prepared" }
    return confirmation.copy(isCustomerInformed = true)
}

// FLOW-DEFINITION-START
fun createOrderConfirmationFlow(): Flow<OrderConfirmation, OrderConfirmationStage, OrderConfirmationEvent> {
    return FlowBuilder<OrderConfirmation, OrderConfirmationStage, OrderConfirmationEvent>()
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

private val log = KotlinLogging.logger {}
