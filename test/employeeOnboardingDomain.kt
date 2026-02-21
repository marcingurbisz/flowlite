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
import java.util.UUID
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
