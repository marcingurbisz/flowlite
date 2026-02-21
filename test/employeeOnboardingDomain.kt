package io.flowlite.test

import io.flowlite.Event
import io.flowlite.Flow
import io.flowlite.FlowBuilder
import io.flowlite.InstanceData
import io.flowlite.Stage
import io.flowlite.StageStatus
import io.flowlite.StatePersister
import io.flowlite.test.EmployeeEvent.ContractSigned
import io.flowlite.test.EmployeeEvent.EmployeeDocumentsSigned
import io.flowlite.test.EmployeeEvent.OnboardingComplete
import io.flowlite.test.EmployeeStage.ActivateSpecializedEmployee
import io.flowlite.test.EmployeeStage.ActivateStandardEmployee
import io.flowlite.test.EmployeeStage.GenerateEmployeeDocuments
import io.flowlite.test.EmployeeStage.SendContractForSigning
import io.flowlite.test.EmployeeStage.SetDepartmentAccess
import io.flowlite.test.EmployeeStage.UpdateSecurityClearanceLevels
import io.flowlite.test.EmployeeStage.UpdateStatusInHRSystem
import io.flowlite.test.EmployeeStage.WaitingForContractSigned
import io.flowlite.test.EmployeeStage.WaitingForEmployeeDocumentsSigned
import io.flowlite.test.EmployeeStage.WaitingForOnboardingCompletion
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.repository.CrudRepository

const val EMPLOYEE_ONBOARDING_FLOW_ID = "employee-onboarding"

enum class EmployeeStage : Stage {
    CreateUserInSystem,
    ActivateStandardEmployee,
    ActivateSpecializedEmployee,
    UpdateSecurityClearanceLevels,
    SetDepartmentAccess,
    GenerateEmployeeDocuments,
    SendContractForSigning,
    WaitingForContractSigned,
    WaitingForOnboardingCompletion,
    UpdateStatusInHRSystem,
    WaitingForEmployeeDocumentsSigned,
}

enum class EmployeeEvent : Event {
    ContractSigned,
    OnboardingComplete,
    EmployeeDocumentsSigned,
}

data class EmployeeOnboarding(
    @Id
    val id: UUID? = null,
    @Version
    val version: Long = 0,
    val stage: EmployeeStage,
    val stageStatus: StageStatus = StageStatus.Pending,
    val isOnboardingAutomated: Boolean = false,
    val isContractSigned: Boolean = false,
    val isExecutiveRole: Boolean = false,
    val isSecurityClearanceRequired: Boolean = false,
    val isFullOnboardingRequired: Boolean = false,
    val isManagerOrDirectorRole: Boolean = false,
    val isRemoteEmployee: Boolean = false,
    val userCreatedInSystem: Boolean = false,
    val employeeActivated: Boolean = false,
    val securityClearanceUpdated: Boolean = false,
    val departmentAccessSet: Boolean = false,
    val documentsGenerated: Boolean = false,
    val contractSentForSigning: Boolean = false,
    val statusUpdatedInHR: Boolean = false,
)

interface EmployeeOnboardingRepository : CrudRepository<EmployeeOnboarding, UUID>

class SpringDataEmployeeOnboardingPersister(
    private val repo: EmployeeOnboardingRepository,
) : StatePersister<EmployeeOnboarding> {
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

    override fun save(instanceData: InstanceData<EmployeeOnboarding>): InstanceData<EmployeeOnboarding> {
        val stage = instanceData.stage as? EmployeeStage
            ?: error("Unexpected stage ${instanceData.stage}")

        val saved = repo.saveWithOptimisticLockRetry(
            id = instanceData.flowInstanceId,
            initial = instanceData.state.copy(
                id = instanceData.flowInstanceId,
                stage = stage,
                stageStatus = instanceData.stageStatus,
            ),
        ) { latest -> latest.copy(stage = stage, stageStatus = instanceData.stageStatus) }
        return instanceData.copy(state = saved)
    }

    override fun load(flowInstanceId: UUID): InstanceData<EmployeeOnboarding> {
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

fun createEmployeeOnboardingFlow(actions: EmployeeOnboardingActions): Flow<EmployeeOnboarding, EmployeeStage, EmployeeEvent> {
    val flow = // FLOW-DEFINITION-START
        FlowBuilder<EmployeeOnboarding, EmployeeStage, EmployeeEvent>()
            .condition(
                predicate = { it.isOnboardingAutomated },
                description = "isOnboardingAutomated",
                onTrue = {
                    stage(EmployeeStage.CreateUserInSystem, actions::createUserInSystem)
                        .condition(
                            { it.isExecutiveRole || it.isSecurityClearanceRequired },
                            description = "isExecutiveRole || isSecurityClearanceRequired",
                            onFalse = {
                                stage(ActivateStandardEmployee, actions::activateEmployee)
                                    .stage(GenerateEmployeeDocuments, actions::generateEmployeeDocuments)
                                    .stage(SendContractForSigning, actions::sendContractForSigning)
                                    .stage(WaitingForEmployeeDocumentsSigned)
                                    .waitFor(EmployeeDocumentsSigned)
                                    .stage(WaitingForContractSigned)
                                    .waitFor(ContractSigned)
                                    .condition(
                                        { it.isExecutiveRole || it.isSecurityClearanceRequired },
                                        description = "isExecutiveRole || isSecurityClearanceRequired",
                                        onTrue = {
                                            stage(ActivateSpecializedEmployee, actions::activateEmployee)
                                                .stage(UpdateStatusInHRSystem, actions::updateStatusInHRSystem)
                                        },
                                        onFalse = {
                                            stage(WaitingForOnboardingCompletion)
                                                .waitFor(OnboardingComplete)
                                                .join(UpdateStatusInHRSystem)
                                        },
                                    )
                            },
                            onTrue = {
                                stage(UpdateSecurityClearanceLevels, actions::updateSecurityClearanceLevels)
                                    .condition(
                                        { it.isSecurityClearanceRequired },
                                        description = "isSecurityClearanceRequired",
                                        onTrue = {
                                            condition(
                                                { it.isFullOnboardingRequired },
                                                description = "isFullOnboardingRequired",
                                                onTrue = {
                                                    stage(SetDepartmentAccess, actions::setDepartmentAccess)
                                                        .join(GenerateEmployeeDocuments)
                                                },
                                                onFalse = { join(GenerateEmployeeDocuments) },
                                            )
                                        },
                                        onFalse = { join(WaitingForContractSigned) },
                                    )
                            },
                        )
                },
                onFalse = {
                    join(WaitingForContractSigned)
                },
            )
            .build()
    // FLOW-DEFINITION-END
    return flow
}

class EmployeeOnboardingActions(
    private val repo: EmployeeOnboardingRepository,
) {
    fun createUserInSystem(employee: EmployeeOnboarding): EmployeeOnboarding {
        employeeOnboardingLog.info { "Creating user account in system" }
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }

        EmployeeOnboardingTestHooks.get(id)?.createUserInSystemHooks?.let { hooks ->
            hooks.entered.countDown()
            require(hooks.allowProceedToSave.await(2, TimeUnit.SECONDS)) { "Timed out waiting for allowProceedToSave" }
        }

        val savedEntity = repo.saveWithOptimisticLockRetry(
            id = id,
            initial = employee,
        ) { latest ->
            latest.copy(userCreatedInSystem = true)
        }

        EmployeeOnboardingTestHooks.get(id)?.createUserInSystemHooks?.let { hooks ->
            hooks.saved.countDown()
            require(hooks.allowReturnAfterSave.await(2, TimeUnit.SECONDS)) { "Timed out waiting for allowReturnAfterSave" }
        }

        return savedEntity
    }

    fun activateEmployee(employee: EmployeeOnboarding): EmployeeOnboarding {
        employeeOnboardingLog.info { "Activating employee account" }
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repo.saveWithOptimisticLockRetry(
            id = id,
            initial = employee,
        ) { latest ->
            latest.copy(employeeActivated = true)
        }
    }

    fun updateSecurityClearanceLevels(employee: EmployeeOnboarding): EmployeeOnboarding {
        employeeOnboardingLog.info { "Updating security clearance levels" }
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repo.saveWithOptimisticLockRetry(
            id = id,
            initial = employee,
        ) { latest ->
            latest.copy(securityClearanceUpdated = true)
        }
    }

    fun setDepartmentAccess(employee: EmployeeOnboarding): EmployeeOnboarding {
        employeeOnboardingLog.info { "Setting department access permissions" }
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repo.saveWithOptimisticLockRetry(
            id = id,
            initial = employee,
        ) { latest ->
            latest.copy(departmentAccessSet = true)
        }
    }

    fun generateEmployeeDocuments(employee: EmployeeOnboarding): EmployeeOnboarding {
        employeeOnboardingLog.info { "Generating employee documents" }
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repo.saveWithOptimisticLockRetry(
            id = id,
            initial = employee,
        ) { latest ->
            latest.copy(documentsGenerated = true)
        }
    }

    fun sendContractForSigning(employee: EmployeeOnboarding): EmployeeOnboarding {
        employeeOnboardingLog.info { "Sending contract for signing" }
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repo.saveWithOptimisticLockRetry(
            id = id,
            initial = employee,
        ) { latest ->
            latest.copy(contractSentForSigning = true)
        }
    }

    fun updateStatusInHRSystem(employee: EmployeeOnboarding): EmployeeOnboarding {
        employeeOnboardingLog.info { "Updating status in HR system" }
        val id = requireNotNull(employee.id) { "EmployeeOnboarding.id must be set when action is executed" }
        return repo.saveWithOptimisticLockRetry(
            id = id,
            initial = employee,
        ) { latest ->
            latest.copy(statusUpdatedInHR = true)
        }
    }
}

object EmployeeOnboardingTestHooks {
    private val actionHooksByInstanceId = ConcurrentHashMap<UUID, EmployeeOnboardingActionHooks>()

    fun set(instanceId: UUID, hooks: EmployeeOnboardingActionHooks) {
        actionHooksByInstanceId[instanceId] = hooks
    }

    fun get(instanceId: UUID): EmployeeOnboardingActionHooks? = actionHooksByInstanceId[instanceId]

    fun clear(instanceId: UUID) {
        actionHooksByInstanceId.remove(instanceId)
    }
}

class EmployeeOnboardingActionHooks {
    @Volatile
    var createUserInSystemHooks: CreateUserInSystemHooks? = null

    class CreateUserInSystemHooks(
        val entered: java.util.concurrent.CountDownLatch,
        val allowProceedToSave: java.util.concurrent.CountDownLatch,
        val saved: java.util.concurrent.CountDownLatch,
        val allowReturnAfterSave: java.util.concurrent.CountDownLatch,
    )
}

private val employeeOnboardingLog = KotlinLogging.logger {}
