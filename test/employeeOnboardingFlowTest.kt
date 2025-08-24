package io.flowlite.test

import io.flowlite.api.*
import io.flowlite.api.Event
import io.flowlite.api.Flow
import io.flowlite.api.FlowBuilder
import io.flowlite.api.Stage
import io.flowlite.test.EmployeeEvent.*
import io.kotest.core.spec.style.BehaviorSpec
import io.flowlite.test.EmployeeStage.*

class EmployeeOnboardingFlowTest : BehaviorSpec({

    given("an employee onboarding flow") {

        When("generating a mermaid diagram") {
            val flow = createEmployeeOnboardingFlow()
            val generator = MermaidGenerator()
            val diagram = generator.generateDiagram("employee-onboarding-flow", flow)

            println("\n=== EMPLOYEE ONBOARDING FLOW DIAGRAM ===")
            println(diagram)
            println("=== END DIAGRAM ===\n")

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
    WaitingForEmployeeDocumentsSigned
}

enum class EmployeeEvent : Event {
    ContractSigned,
    OnboardingComplete,
    EmployeeDocumentsSigned
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
    val statusUpdatedInHR: Boolean = false
)

// --- Action Functions ---

fun createUserInSystem(employee: EmployeeOnboarding): EmployeeOnboarding {
    println("[Action] Creating user account in system")
    return employee
}

fun activateEmployee(employee: EmployeeOnboarding): EmployeeOnboarding {
    println("[Action] Activating employee account")
    return employee
}

fun updateSecurityClearanceLevels(employee: EmployeeOnboarding): EmployeeOnboarding {
    println("[Action] Updating security clearance levels")
    return employee
}

fun setDepartmentAccess(employee: EmployeeOnboarding): EmployeeOnboarding {
    println("[Action] Setting department access permissions")
    return employee
}

fun generateEmployeeDocuments(employee: EmployeeOnboarding): EmployeeOnboarding {
    println("[Action] Generating employee documents")
    return employee
}

fun sendContractForSigning(employee: EmployeeOnboarding): EmployeeOnboarding {
    println("[Action] Sending contract for signing")
    return employee
}

fun updateStatusInHRSystem(employee: EmployeeOnboarding): EmployeeOnboarding {
    println("[Action] Updating status in HR system")
    return employee
}

// --- Flow Definition ---
// FLOW-DEFINITION-START
fun createEmployeeOnboardingFlow(): Flow<EmployeeOnboarding> {
    return FlowBuilder<EmployeeOnboarding>()
        .condition(
            predicate = { it.isOnboardingAutomated },
            description = "isOnboardingAutomated",
            onTrue = {
                // Automated path
                stage(CreateUserInSystem, ::createUserInSystem)
                    .condition( { it.isExecutiveRole || it.isSecurityClearanceRequired },
                        description = "isExecutiveRole || isSecurityClearanceRequired",
                        onFalse = {
                            stage(ActivateStandardEmployee, ::activateEmployee)
                                .stage(GenerateEmployeeDocuments, ::generateEmployeeDocuments)
                                .stage(SendContractForSigning, ::sendContractForSigning)
                                .stage(WaitingForEmployeeDocumentsSigned)
                                .waitFor(EmployeeDocumentsSigned)
                                .stage(WaitingForContractSigned)
                                .waitFor(ContractSigned, condition = {it.isContractSigned})
                                .condition({ it.isExecutiveRole || it.isSecurityClearanceRequired },
                                    description = "isExecutiveRole || isSecurityClearanceRequired",
                                    onTrue = {
                                        stage(ActivateSpecializedEmployee, ::activateEmployee)
                                            .stage(UpdateStatusInHRSystem, ::updateStatusInHRSystem) },
                                    onFalse = {
                                        stage(WaitingForOnboardingCompletion).waitFor(OnboardingComplete).join(UpdateStatusInHRSystem)}
                                ) },
                        onTrue = {
                            stage(UpdateSecurityClearanceLevels, ::updateSecurityClearanceLevels)
                                .condition( {it.isSecurityClearanceRequired },
                                    description = "isSecurityClearanceRequired",
                                    onTrue = {condition ({it.isFullOnboardingRequired},
                                        description = "isFullOnboardingRequired",
                                        onTrue = {stage(SetDepartmentAccess, ::setDepartmentAccess).join(GenerateEmployeeDocuments)},
                                        onFalse = {join(GenerateEmployeeDocuments)})},
                                    onFalse = {join(WaitingForContractSigned)})
                        })
            },
            onFalse = {
                // Manual path
                join(WaitingForContractSigned)
            }
        )
        .build()
}
// FLOW-DEFINITION-END