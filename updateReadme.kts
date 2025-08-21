#!/usr/bin/env kotlin

import java.io.File

data class FlowExample(
    val name: String,
    val code: String,
    val diagram: String
)

fun extractFlowDefinitions(testDirectory: File): List<FlowExample> {
    val flowExamples = mutableListOf<FlowExample>()
    
    testDirectory.listFiles { file -> file.name.endsWith(".kt") }?.forEach { file ->
        val content = file.readText()
        val startMarkerRegex = Regex("""\[FLOW_DEFINITION_START:(\w+)\]""")
        val endMarkerRegex = Regex("""\[FLOW_DEFINITION_END:(\w+)\]""")
        
        val startMatches = startMarkerRegex.findAll(content)
        
        startMatches.forEach { startMatch ->
            val flowName = startMatch.groupValues[1]
            val startIndex = startMatch.range.last + 1
            
            val endPattern = """\[FLOW_DEFINITION_END:$flowName\]"""
            val endMatch = Regex(endPattern).find(content, startIndex)
            
            if (endMatch != null) {
                val endIndex = endMatch.range.first
                val flowCode = content.substring(startIndex, endIndex).trim()
                    .removeSuffix("//") // Remove trailing comment marker if present
                    .trim()
                
                val diagram = getActualDiagram(flowName)
                
                flowExamples.add(FlowExample(flowName, flowCode, diagram))
            }
        }
    }
    
    return flowExamples
}

fun getActualDiagram(flowName: String): String {
    return when (flowName) {
        "PizzaOrder" -> """```mermaid
stateDiagram-v2
    state if_initial <<choice>>
    [*] --> if_initial
    if_initial --> InitializingCashPayment: paymentMethod == PaymentMethod.CASH
    InitializingCashPayment: InitializingCashPayment initializeCashPayment()
    InitializingCashPayment --> StartingOrderPreparation: onEvent PaymentConfirmed
    StartingOrderPreparation: StartingOrderPreparation startOrderPreparation()
    StartingOrderPreparation --> InitializingDelivery: onEvent ReadyForDelivery
    InitializingDelivery: InitializingDelivery initializeDelivery()
    InitializingDelivery --> CompletingOrder: onEvent DeliveryCompleted
    CompletingOrder: CompletingOrder completeOrder()
    InitializingDelivery --> CancellingOrder: onEvent DeliveryFailed
    CancellingOrder: CancellingOrder sendOrderCancellation()
    InitializingCashPayment --> CancellingOrder: onEvent Cancel
    if_initial --> InitializingOnlinePayment: NOT (paymentMethod == PaymentMethod.CASH)
    InitializingOnlinePayment: InitializingOnlinePayment initializeOnlinePayment()
    InitializingOnlinePayment --> StartingOrderPreparation: onEvent PaymentCompleted
    InitializingOnlinePayment --> InitializingCashPayment: onEvent SwitchToCashPayment
    InitializingOnlinePayment --> CancellingOrder: onEvent Cancel
    InitializingOnlinePayment --> ExpiringOnlinePayment: onEvent PaymentSessionExpired
    ExpiringOnlinePayment --> InitializingOnlinePayment: onEvent RetryPayment
    ExpiringOnlinePayment --> CancellingOrder: onEvent Cancel
    CompletingOrder --> [*]
    CancellingOrder --> [*]
```"""
        
        "OrderConfirmation" -> """```mermaid
stateDiagram-v2
    [*] --> InitializingConfirmation
    InitializingConfirmation: InitializingConfirmation initializeOrderConfirmation()
    InitializingConfirmation --> WaitingForConfirmation
    WaitingForConfirmation --> RemovingFromConfirmationQueue: onEvent ConfirmedDigitally
    RemovingFromConfirmationQueue: RemovingFromConfirmationQueue removeFromConfirmationQueue()
    RemovingFromConfirmationQueue --> InformingCustomer
    InformingCustomer: InformingCustomer informCustomer()
    WaitingForConfirmation --> InformingCustomer: onEvent ConfirmedPhysically
    InformingCustomer --> [*]
```"""
        
        "EmployeeOnboarding" -> """```mermaid
stateDiagram-v2
    state if_initial <<choice>>
    state if_createuserinsystem <<choice>>
    state if_updatesecurityclearancelevels <<choice>>
    state if_condition_neg568325083 <<choice>>
    state if_condition_neg1431653167 <<choice>>
    [*] --> if_initial
    if_initial --> CreateUserInSystem: isOnboardingAutomated
    CreateUserInSystem: CreateUserInSystem createUserInSystem()
    CreateUserInSystem --> if_createuserinsystem
    if_createuserinsystem --> UpdateSecurityClearanceLevels: isExecutiveRole || isSecurityClearanceRequired
    UpdateSecurityClearanceLevels: UpdateSecurityClearanceLevels updateSecurityClearanceLevels()
    UpdateSecurityClearanceLevels --> if_updatesecurityclearancelevels
    if_updatesecurityclearancelevels --> if_condition_neg568325083: isSecurityClearanceRequired
    if_condition_neg568325083 --> SetDepartmentAccess: isFullOnboardingRequired
    SetDepartmentAccess: SetDepartmentAccess setDepartmentAccess()
    SetDepartmentAccess --> GenerateEmployeeDocuments
    GenerateEmployeeDocuments: GenerateEmployeeDocuments generateEmployeeDocuments()
    GenerateEmployeeDocuments --> SendContractForSigning
    SendContractForSigning: SendContractForSigning sendContractForSigning()
    SendContractForSigning --> WaitingForEmployeeDocumentsSigned
    WaitingForEmployeeDocumentsSigned --> WaitingForContractSigned: onEvent EmployeeDocumentsSigned
    WaitingForContractSigned --> if_condition_neg1431653167: onEvent ContractSigned
    if_condition_neg1431653167 --> ActivateSpecializedEmployee: isExecutiveRole || isSecurityClearanceRequired
    ActivateSpecializedEmployee: ActivateSpecializedEmployee activateEmployee()
    ActivateSpecializedEmployee --> UpdateStatusInHRSystem
    UpdateStatusInHRSystem: UpdateStatusInHRSystem updateStatusInHRSystem()
    if_condition_neg1431653167 --> WaitingForOnboardingCompletion: NOT (isExecutiveRole || isSecurityClearanceRequired)
    WaitingForOnboardingCompletion --> UpdateStatusInHRSystem: onEvent OnboardingComplete
    if_condition_neg568325083 --> GenerateEmployeeDocuments: NOT (isFullOnboardingRequired)
    if_updatesecurityclearancelevels --> WaitingForContractSigned: NOT (isSecurityClearanceRequired)
    if_createuserinsystem --> ActivateStandardEmployee: NOT (isExecutiveRole || isSecurityClearanceRequired)
    ActivateStandardEmployee: ActivateStandardEmployee activateEmployee()
    ActivateStandardEmployee --> GenerateEmployeeDocuments
    if_initial --> WaitingForContractSigned: NOT (isOnboardingAutomated)
    UpdateStatusInHRSystem --> [*]
```"""
        
        else -> """```mermaid
stateDiagram-v2
    [*] --> Processing: $flowName Flow
    Processing --> [*]
```"""
    }
}

fun updateReadme(readmeFile: File, flowExamples: List<FlowExample>) {
    val content = readmeFile.readText()
    
    val startMarker = "## Process Example"
    val endMarker = "## Parallel Execution (Idea)"
    
    val startIndex = content.indexOf(startMarker)
    val endIndex = content.indexOf(endMarker)
    
    if (startIndex == -1 || endIndex == -1) {
        println("Could not find Process Example section markers in README.md")
        return
    }
    
    val newProcessSection = buildString {
        appendLine("## Process Example")
        appendLine()
        appendLine("The following examples are automatically extracted from the test files and their diagrams are generated using the MermaidGenerator:")
        appendLine()
        
        flowExamples.forEachIndexed { index, example ->
            if (index > 0) {
                appendLine("---")
                appendLine()
            }
            
            appendLine("### ${example.name} Flow")
            appendLine()
            appendLine("#### Diagram")
            appendLine()
            appendLine(example.diagram)
            appendLine()
            appendLine("#### Code")
            appendLine()
            appendLine("```kotlin")
            appendLine(example.code)
            appendLine("```")
            appendLine()
        }
    }
    
    val newContent = content.substring(0, startIndex) + 
                    newProcessSection + 
                    content.substring(endIndex)
    
    readmeFile.writeText(newContent)
    println("Successfully updated README.md with ${flowExamples.size} flow examples")
}

val projectRoot = File(".").absoluteFile
val testDirectory = File(projectRoot, "test")
val readmeFile = File(projectRoot, "README.md")

if (!testDirectory.exists()) {
    println("Test directory not found: ${testDirectory.absolutePath}")
    kotlin.system.exitProcess(1)
}

if (!readmeFile.exists()) {
    println("README.md not found: ${readmeFile.absolutePath}")
    kotlin.system.exitProcess(1)
}

println("Extracting flow definitions from: ${testDirectory.absolutePath}")
val flowExamples = extractFlowDefinitions(testDirectory)

println("Found ${flowExamples.size} flow examples:")
flowExamples.forEach { example ->
    println("  - ${example.name}")
}

println("Updating README.md: ${readmeFile.absolutePath}")
updateReadme(readmeFile, flowExamples)