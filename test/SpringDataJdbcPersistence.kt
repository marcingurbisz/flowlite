package io.flowlite.test

import io.flowlite.api.Event
import io.flowlite.api.EventStore
import io.flowlite.api.ProcessData
import io.flowlite.api.Stage
import io.flowlite.api.StageStatus
import io.flowlite.api.StatePersister
import java.util.UUID
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository

// --- Process persistence sample ---

@Table("order_confirmation")
data class OrderConfirmationRow(
    @Id
    @Column("id")
    val aggregateId: UUID,
    val stage: String,
    val stageStatus: String,
    val orderNumber: String,
    val confirmationType: String,
    val customerName: String,
    val isRemovedFromQueue: Boolean,
    val isCustomerInformed: Boolean,
    val confirmationTimestamp: String,
) : Persistable<UUID> {
    @Transient
    private var isNewAggregate: Boolean = false

    fun withNewAggregate(flag: Boolean): OrderConfirmationRow = apply { this.isNewAggregate = flag }

    override fun getId(): UUID = aggregateId
    override fun isNew(): Boolean = isNewAggregate
}

interface OrderConfirmationRepository : CrudRepository<OrderConfirmationRow, UUID>

@Table("employee_onboarding")
data class EmployeeOnboardingRow(
    @Id
    @Column("id")
    val aggregateId: UUID,
    val stage: String?,
    val stageStatus: String,
    val isOnboardingAutomated: Boolean,
    val isContractSigned: Boolean,
    val isExecutiveRole: Boolean,
    val isSecurityClearanceRequired: Boolean,
    val isFullOnboardingRequired: Boolean,
    val isManagerOrDirectorRole: Boolean,
    val isRemoteEmployee: Boolean,
    val userCreatedInSystem: Boolean,
    val employeeActivated: Boolean,
    val securityClearanceUpdated: Boolean,
    val departmentAccessSet: Boolean,
    val documentsGenerated: Boolean,
    val contractSentForSigning: Boolean,
    val statusUpdatedInHr: Boolean,
) : Persistable<UUID> {
    @Transient
    private var isNewAggregate: Boolean = false

    fun withNewAggregate(flag: Boolean): EmployeeOnboardingRow = apply { this.isNewAggregate = flag }

    override fun getId(): UUID = aggregateId
    override fun isNew(): Boolean = isNewAggregate
}

interface EmployeeOnboardingRepository : CrudRepository<EmployeeOnboardingRow, UUID>

class SpringDataOrderConfirmationPersister(
    private val repo: OrderConfirmationRepository,
) : StatePersister<OrderConfirmation> {
    override fun save(processData: ProcessData<OrderConfirmation>): Boolean {
        val state = processData.state
        val exists = repo.existsById(processData.flowInstanceId)
        val row = OrderConfirmationRow(
            aggregateId = processData.flowInstanceId,
            stage = processData.stage.toString(),
            stageStatus = processData.stageStatus.name,
            orderNumber = state.orderNumber,
            confirmationType = state.confirmationType.name,
            customerName = state.customerName,
            isRemovedFromQueue = state.isRemovedFromQueue,
            isCustomerInformed = state.isCustomerInformed,
            confirmationTimestamp = state.confirmationTimestamp,
        ).withNewAggregate(!exists)
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

class SpringDataEmployeeOnboardingPersister(
    private val repo: EmployeeOnboardingRepository,
) : StatePersister<EmployeeOnboarding> {
    override fun save(processData: ProcessData<EmployeeOnboarding>): Boolean {
        val s = processData.state
        val exists = repo.existsById(processData.flowInstanceId)
        repo.save(
            EmployeeOnboardingRow(
                aggregateId = processData.flowInstanceId,
                stage = processData.stage?.toString(),
                stageStatus = processData.stageStatus.name,
                isOnboardingAutomated = s.isOnboardingAutomated,
                isContractSigned = s.isContractSigned,
                isExecutiveRole = s.isExecutiveRole,
                isSecurityClearanceRequired = s.isSecurityClearanceRequired,
                isFullOnboardingRequired = s.isFullOnboardingRequired,
                isManagerOrDirectorRole = s.isManagerOrDirectorRole,
                isRemoteEmployee = s.isRemoteEmployee,
                userCreatedInSystem = s.userCreatedInSystem,
                employeeActivated = s.employeeActivated,
                securityClearanceUpdated = s.securityClearanceUpdated,
                departmentAccessSet = s.departmentAccessSet,
                documentsGenerated = s.documentsGenerated,
                contractSentForSigning = s.contractSentForSigning,
                statusUpdatedInHr = s.statusUpdatedInHR,
            ).withNewAggregate(!exists),
        )
        return true
    }

    override fun load(flowInstanceId: UUID): ProcessData<EmployeeOnboarding>? {
        val row = repo.findById(flowInstanceId).orElse(null) ?: return null
        val stage = row.stage?.let { EmployeeStage.valueOf(it) }
        val state = EmployeeOnboarding(
            processId = flowInstanceId.toString(),
            stage = stage,
            isOnboardingAutomated = row.isOnboardingAutomated,
            isContractSigned = row.isContractSigned,
            isExecutiveRole = row.isExecutiveRole,
            isSecurityClearanceRequired = row.isSecurityClearanceRequired,
            isFullOnboardingRequired = row.isFullOnboardingRequired,
            isManagerOrDirectorRole = row.isManagerOrDirectorRole,
            isRemoteEmployee = row.isRemoteEmployee,
            userCreatedInSystem = row.userCreatedInSystem,
            employeeActivated = row.employeeActivated,
            securityClearanceUpdated = row.securityClearanceUpdated,
            departmentAccessSet = row.departmentAccessSet,
            documentsGenerated = row.documentsGenerated,
            contractSentForSigning = row.contractSentForSigning,
            statusUpdatedInHR = row.statusUpdatedInHr,
        )
        return ProcessData(
            flowInstanceId = flowInstanceId,
            state = state,
            stage = (stage ?: EmployeeStage.WaitingForContractSigned) as Stage,
            stageStatus = StageStatus.valueOf(row.stageStatus),
        )
    }
}

// --- Event store sample ---

@Table("pending_events")
data class PendingEventRow(
    @Id val id: Long? = null,
    val flowId: String,
    val flowInstanceId: UUID,
    val eventType: String,
    val eventValue: String,
)

interface PendingEventRepository : CrudRepository<PendingEventRow, Long> {
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
        match.id?.let { repo.deleteById(it) }
        return candidateLookup[match.eventType to match.eventValue]
    }
}
