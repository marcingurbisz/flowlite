package io.flowlite.test

import io.flowlite.Event
import io.flowlite.Flow
import io.flowlite.InstanceData
import io.flowlite.Stage
import io.flowlite.StageStatus
import io.flowlite.StatePersister
import io.flowlite.flow
import io.flowlite.test.OrderConfirmationEvent.ConfirmedDigitally
import io.flowlite.test.OrderConfirmationEvent.ConfirmedPhysically
import io.flowlite.test.OrderConfirmationStage.InformingCustomer
import io.flowlite.test.OrderConfirmationStage.InitializingConfirmation
import io.flowlite.test.OrderConfirmationStage.RemovingFromConfirmationQueue
import io.flowlite.test.OrderConfirmationStage.WaitingForConfirmation
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.repository.CrudRepository

const val ORDER_CONFIRMATION_FLOW_ID = "order-confirmation"

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
    Digital,
    Physical,
}

data class OrderConfirmation(
    @Id
    val id: UUID? = null,
    @Version
    val version: Long = 0,
    val stage: OrderConfirmationStage,
    val stageStatus: StageStatus = StageStatus.Pending,
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
    orderLog.info { "Initializing order confirmation for order ${confirmation.orderNumber}" }
    val timestamp = System.currentTimeMillis().toString()
    return confirmation.copy(confirmationTimestamp = timestamp)
}

fun removeFromConfirmationQueue(confirmation: OrderConfirmation): OrderConfirmation {
    orderLog.info { "Removing order ${confirmation.orderNumber} from confirmation queue (digital processing)" }
    return confirmation.copy(isRemovedFromQueue = true)
}

fun informCustomer(confirmation: OrderConfirmation): OrderConfirmation {
    val method =
        when (confirmation.confirmationType) {
            ConfirmationType.Digital -> "app notification/email"
            ConfirmationType.Physical -> "phone call"
        }
    orderLog.info { "Informing customer ${confirmation.customerName} via $method that order ${confirmation.orderNumber} is being prepared" }
    return confirmation.copy(isCustomerInformed = true)
}

// FLOW-DEFINITION-START
fun createOrderConfirmationFlow(): Flow<OrderConfirmation, OrderConfirmationStage, OrderConfirmationEvent> {
    return flow {
        stage(InitializingConfirmation, ::initializeOrderConfirmation)
        stage(WaitingForConfirmation, block = {
            onEvent(ConfirmedDigitally) {
                stage(RemovingFromConfirmationQueue, ::removeFromConfirmationQueue)
                stage(InformingCustomer, ::informCustomer)
            }
            onEvent(ConfirmedPhysically) { joinTo(InformingCustomer) }
        })
    }
}
// FLOW-DEFINITION-END

private val orderLog = KotlinLogging.logger {}
