package io.flowlite.test

import io.flowlite.api.Event
import io.flowlite.api.EventStore
import io.flowlite.api.ProcessData
import io.flowlite.api.Stage
import io.flowlite.api.StageStatus
import io.flowlite.api.StatePersister
import java.util.UUID
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository

// --- Process persistence sample ---

@Table("order_confirmation")
data class OrderConfirmationRow(
    @Id val id: UUID,
    val stage: String,
    val stageStatus: String,
    val orderNumber: String,
    val confirmationType: String,
    val customerName: String,
    val isRemovedFromQueue: Boolean,
    val isCustomerInformed: Boolean,
    val confirmationTimestamp: String,
)

interface OrderConfirmationRepository : CrudRepository<OrderConfirmationRow, UUID>

class SpringDataOrderConfirmationPersister(
    private val repo: OrderConfirmationRepository,
) : StatePersister<OrderConfirmation> {
    override fun save(processData: ProcessData<OrderConfirmation>): Boolean {
        val state = processData.state
        val row = OrderConfirmationRow(
            id = processData.flowInstanceId,
            stage = processData.stage.toString(),
            stageStatus = processData.stageStatus.name,
            orderNumber = state.orderNumber,
            confirmationType = state.confirmationType.name,
            customerName = state.customerName,
            isRemovedFromQueue = state.isRemovedFromQueue,
            isCustomerInformed = state.isCustomerInformed,
            confirmationTimestamp = state.confirmationTimestamp,
        )
        repo.save(row)
        return true
    }

    override fun load(flowInstanceId: UUID): ProcessData<OrderConfirmation>? {
        val row = repo.findById(flowInstanceId).orElse(null) ?: return null
        val state = OrderConfirmation(
            processId = flowInstanceId.toString(),
            stage = OrderConfirmationStage.valueOf(row.stage),
            orderNumber = row.orderNumber,
            confirmationType = ConfirmationType.valueOf(row.confirmationType),
            customerName = row.customerName,
            isRemovedFromQueue = row.isRemovedFromQueue,
            isCustomerInformed = row.isCustomerInformed,
            confirmationTimestamp = row.confirmationTimestamp,
        )
        return ProcessData(
            flowInstanceId = flowInstanceId,
            state = state,
            stage = OrderConfirmationStage.valueOf(row.stage) as Stage,
            stageStatus = StageStatus.valueOf(row.stageStatus),
        )
    }
}

// --- Event store sample ---

@Table("pending_events")
data class PendingEventRow(
    @Id val id: UUID? = null,
    val flowId: String,
    val flowInstanceId: UUID,
    val eventType: String,
    val eventValue: String,
)

interface PendingEventRepository : CrudRepository<PendingEventRow, UUID> {
    fun findByFlowIdAndFlowInstanceId(flowId: String, flowInstanceId: UUID): List<PendingEventRow>
}

class SpringDataEventStore(
    private val repo: PendingEventRepository,
) : EventStore {
    override fun append(flowId: String, flowInstanceId: UUID, event: Event) {
        val type = event::class.qualifiedName ?: event::class.java.name
        val value = (event as? Enum<*>)?.name ?: event.toString()
        repo.save(
            PendingEventRow(
                flowId = flowId,
                flowInstanceId = flowInstanceId,
                eventType = type,
                eventValue = value,
            )
        )
    }

    override fun poll(flowId: String, flowInstanceId: UUID, candidates: Collection<Event>): Event? {
        if (candidates.isEmpty()) return null
        val rows = repo.findByFlowIdAndFlowInstanceId(flowId, flowInstanceId)
        val candidateLookup = candidates.associateBy {
            val type = it::class.qualifiedName ?: it::class.java.name
            val value = (it as? Enum<*>)?.name ?: it.toString()
            type to value
        }
        val match = rows.firstOrNull { row -> candidateLookup.containsKey(row.eventType to row.eventValue) }
            ?: return null
        repo.deleteById(match.id!!)
        return candidateLookup[match.eventType to match.eventValue]
    }
}
