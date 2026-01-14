package io.flowlite.test

import io.flowlite.api.Event
import io.flowlite.api.EventStore
import io.flowlite.api.ProcessData
import io.flowlite.api.StatePersister
import java.util.UUID
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository

// --- Process persistence sample ---

interface OrderConfirmationRepository : CrudRepository<OrderConfirmation, UUID>

interface EmployeeOnboardingRepository : CrudRepository<EmployeeOnboarding, UUID>

class SpringDataOrderConfirmationPersister(
    private val repo: OrderConfirmationRepository,
    private val template: JdbcAggregateTemplate,
) : StatePersister<OrderConfirmation> {
    override fun save(processData: ProcessData<OrderConfirmation>): Boolean {
        val stage = processData.stage as? OrderConfirmationStage
            ?: error("Unexpected stage ${processData.stage}")
        val entity = processData.state.copy(
            id = processData.flowInstanceId,
            stage = stage,
            stageStatus = processData.stageStatus,
        )
        if (repo.existsById(processData.flowInstanceId)) repo.save(entity) else template.insert(entity)
        return true
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

class SpringDataEmployeeOnboardingPersister(
    private val repo: EmployeeOnboardingRepository,
    private val template: JdbcAggregateTemplate,
) : StatePersister<EmployeeOnboarding> {
    override fun save(processData: ProcessData<EmployeeOnboarding>): Boolean {
        val stage = (processData.stage as? EmployeeStage) ?: EmployeeStage.WaitingForContractSigned
        val entity = processData.state.copy(
            id = processData.flowInstanceId,
            stage = stage,
            stageStatus = processData.stageStatus,
        )
        if (repo.existsById(processData.flowInstanceId)) repo.save(entity) else template.insert(entity)
        return true
    }

    override fun load(flowInstanceId: UUID): ProcessData<EmployeeOnboarding>? {
        val entity = repo.findById(flowInstanceId).orElse(null) ?: return null
        val stage = entity.stage ?: EmployeeStage.WaitingForContractSigned
        return ProcessData(
            flowInstanceId = flowInstanceId,
            state = entity.copy(stage = stage),
            stage = stage,
            stageStatus = entity.stageStatus,
        )
    }
}

// --- Event store sample ---

@Table("pending_events")
data class PendingEvent(
    @org.springframework.data.annotation.Id
    @org.springframework.data.relational.core.mapping.Column("id")
    val id: UUID? = null,
    @org.springframework.data.relational.core.mapping.Column("flow_id")
    val flowId: String,
    @org.springframework.data.relational.core.mapping.Column("flow_instance_id")
    val flowInstanceId: UUID,
    @org.springframework.data.relational.core.mapping.Column("event_type")
    val eventType: String,
    @org.springframework.data.relational.core.mapping.Column("event_value")
    val eventValue: String,
)

interface PendingEventRepository : CrudRepository<PendingEvent, UUID> {
    fun findByFlowIdAndFlowInstanceId(flowId: String, flowInstanceId: UUID): List<PendingEvent>
}

class SpringDataEventStore(
    private val repo: PendingEventRepository,
) : EventStore {
    override fun append(flowId: String, flowInstanceId: UUID, event: Event) {
        val type = event::class.qualifiedName ?: event::class.java.name
        val value = (event as? Enum<*>)?.name ?: event.toString()
        repo.save(
            PendingEvent(
                id = null,
                flowId = flowId,
                flowInstanceId = flowInstanceId,
                eventType = type,
                eventValue = value,
            ),
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
        match.id?.let { repo.deleteById(it) }
        return candidateLookup[match.eventType to match.eventValue]
    }
}
