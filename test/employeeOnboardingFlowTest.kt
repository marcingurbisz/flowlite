package io.flowlite.test

import io.flowlite.api.*
import io.flowlite.test.EmployeeEvent.*
import io.flowlite.test.EmployeeStage.*
import io.kotest.core.spec.style.BehaviorSpec
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.repository.CrudRepository
import org.slf4j.LoggerFactory
import java.util.UUID

const val EMPLOYEE_ONBOARDING_FLOW_ID = "employee-onboarding"

class EmployeeOnboardingFlowTest : BehaviorSpec({
    extension(TestApplicationExtension)

    val engine = TestApplicationExtension.engine

    given("an employee onboarding flow") {
        `when`("generating a mermaid diagram") {
            val flow = createEmployeeOnboardingFlow()
            val generator = MermaidGenerator()
            val diagram = generator.generateDiagram("employee-onboarding-flow", flow)

            then("should generate diagram successfully") {
                assert(diagram.isNotEmpty())
                assert(diagram.contains("stateDiagram-v2"))
            }
        }
    }

    given("employee onboarding flow - manual path") {
        val processId = engine.startProcess(
            flowId = EMPLOYEE_ONBOARDING_FLOW_ID,
            initialState = EmployeeOnboarding(
                stage = WaitingForContractSigned,
                isOnboardingAutomated = false,
                isExecutiveRole = false,
                isSecurityClearanceRequired = false,
                isFullOnboardingRequired = false,
            ),
        )

        then("it starts at waiting for contract signature") {
            awaitStatus(
                fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, processId) },
                expected = WaitingForContractSigned to StageStatus.PENDING,
            )
        }

        `when`("contract is signed and onboarding completes") {
            engine.sendEvent(EMPLOYEE_ONBOARDING_FLOW_ID, processId, ContractSigned)
            engine.sendEvent(EMPLOYEE_ONBOARDING_FLOW_ID, processId, OnboardingComplete)

            then("it finishes in HR system update stage") {
                awaitStatus(
                    fetch = { engine.getStatus(EMPLOYEE_ONBOARDING_FLOW_ID, processId) },
                    expected = UpdateStatusInHRSystem to StageStatus.COMPLETED,
                )
            }
        }
    }
})

// --- Domain Classes ---

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
    val stageStatus: StageStatus = StageStatus.PENDING,
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
    override fun save(processData: ProcessData<EmployeeOnboarding>): SaveResult<EmployeeOnboarding> {
        val stage = processData.stage as? EmployeeStage
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

    override fun load(flowInstanceId: UUID): ProcessData<EmployeeOnboarding>? {
        val entity = repo.findById(flowInstanceId).orElse(null) ?: return null
        return ProcessData(
            flowInstanceId = flowInstanceId,
            state = entity,
            stage = entity.stage,
            stageStatus = entity.stageStatus,
        )
    }
}

// --- Action Functions ---

fun createUserInSystem(employee: EmployeeOnboarding): EmployeeOnboarding {
    onboardingLogger.info("Creating user account in system")
    return employee
}

fun activateEmployee(employee: EmployeeOnboarding): EmployeeOnboarding {
    onboardingLogger.info("Activating employee account")
    return employee
}

fun updateSecurityClearanceLevels(employee: EmployeeOnboarding): EmployeeOnboarding {
    onboardingLogger.info("Updating security clearance levels")
    return employee
}

fun setDepartmentAccess(employee: EmployeeOnboarding): EmployeeOnboarding {
    onboardingLogger.info("Setting department access permissions")
    return employee
}

fun generateEmployeeDocuments(employee: EmployeeOnboarding): EmployeeOnboarding {
    onboardingLogger.info("Generating employee documents")
    return employee
}

fun sendContractForSigning(employee: EmployeeOnboarding): EmployeeOnboarding {
    onboardingLogger.info("Sending contract for signing")
    return employee
}

fun updateStatusInHRSystem(employee: EmployeeOnboarding): EmployeeOnboarding {
    onboardingLogger.info("Updating status in HR system")
    return employee
}
private val onboardingLogger = LoggerFactory.getLogger("EmployeeOnboardingActions")

fun createEmployeeOnboardingFlow(): Flow<EmployeeOnboarding> {
    val flow = // FLOW-DEFINITION-START
        FlowBuilder<EmployeeOnboarding>()
            .condition(
                predicate = { it.isOnboardingAutomated },
                description = "isOnboardingAutomated",
                onTrue = {
                    // Automated path
                    stage(CreateUserInSystem, ::createUserInSystem)
                        .condition(
                            { it.isExecutiveRole || it.isSecurityClearanceRequired },
                            description = "isExecutiveRole || isSecurityClearanceRequired",
                            onFalse = {
                                stage(ActivateStandardEmployee, ::activateEmployee)
                                    .stage(GenerateEmployeeDocuments, ::generateEmployeeDocuments)
                                    .stage(SendContractForSigning, ::sendContractForSigning)
                                    .stage(WaitingForEmployeeDocumentsSigned)
                                    .waitFor(EmployeeDocumentsSigned)
                                    .stage(WaitingForContractSigned)
                                    .waitFor(ContractSigned)
                                    .condition(
                                        { it.isExecutiveRole || it.isSecurityClearanceRequired },
                                        description = "isExecutiveRole || isSecurityClearanceRequired",
                                        onTrue = {
                                            stage(ActivateSpecializedEmployee, ::activateEmployee)
                                                .stage(UpdateStatusInHRSystem, ::updateStatusInHRSystem)
                                        },
                                        onFalse = {
                                            stage(WaitingForOnboardingCompletion)
                                                .waitFor(OnboardingComplete)
                                                .join(UpdateStatusInHRSystem)
                                        },
                                    )
                            },
                            onTrue = {
                                stage(UpdateSecurityClearanceLevels, ::updateSecurityClearanceLevels)
                                    .condition(
                                        { it.isSecurityClearanceRequired },
                                        description = "isSecurityClearanceRequired",
                                        onTrue = {
                                            condition(
                                                { it.isFullOnboardingRequired },
                                                description = "isFullOnboardingRequired",
                                                onTrue = {
                                                    stage(SetDepartmentAccess, ::setDepartmentAccess)
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
                    // Manual path
                    join(WaitingForContractSigned)
                },
            )
            .build()
    // FLOW-DEFINITION-END
    return flow
}

