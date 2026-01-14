package io.flowlite.test

import io.flowlite.api.*
import io.flowlite.test.EmployeeEvent.*
import io.flowlite.test.EmployeeStage.*
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory

class EmployeeOnboardingFlowTest :
    BehaviorSpec({
        given("an employee onboarding flow") {
            When("generating a mermaid diagram") {
                val flow = createEmployeeOnboardingFlow()
                val generator = MermaidGenerator()
                val diagram = generator.generateDiagram("employee-onboarding-flow", flow)

                then("should generate diagram successfully") {
                    // Just verify that diagram was generated (not empty)
                    assert(diagram.isNotEmpty())
                    assert(diagram.contains("stateDiagram-v2"))
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
    val processId: String = "",
    val stage: EmployeeStage? = null,
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

class EmployeeOnboardingEngineTest : BehaviorSpec({
    given("employee onboarding flow - manual path") {
        val persistence = TestPersistence()
        val eventStore = persistence.eventStore()
        val tickScheduler = persistence.tickScheduler()
        val engine = FlowEngine(eventStore = eventStore, tickScheduler = tickScheduler)
        val persister = persistence.onboardingPersister()
        engine.registerFlow("employee-onboarding", createEmployeeOnboardingFlow(), persister)

        val processId = engine.startProcess(
            flowId = "employee-onboarding",
            initialState = EmployeeOnboarding(
                processId = "emp-1",
                isOnboardingAutomated = false,
                isExecutiveRole = false,
                isSecurityClearanceRequired = false,
                isFullOnboardingRequired = false,
            ),
        )

        then("it starts at waiting for contract signature") {
            tickScheduler.drain()
            engine.getStatus("employee-onboarding", processId) shouldBe
                (EmployeeStage.WaitingForContractSigned to StageStatus.PENDING)
        }

        `when`("contract is signed and onboarding completes") {
            engine.sendEvent("employee-onboarding", processId, EmployeeEvent.ContractSigned)
            engine.sendEvent("employee-onboarding", processId, EmployeeEvent.OnboardingComplete)
            tickScheduler.drain()

            then("it finishes in HR system update stage") {
                engine.getStatus("employee-onboarding", processId) shouldBe
                    (EmployeeStage.UpdateStatusInHRSystem to StageStatus.COMPLETED)
            }
        }
    }
})
