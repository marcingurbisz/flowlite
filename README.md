# FlowLite

FlowLite is a lightweight, developer-friendly workflow engine for Kotlin that makes defining business processes intuitive and maintainable. It provides a fluent API for defining process flows that are both easy to code and easy to understand.

## Why FlowLite?

Traditional business process management (BPM) solutions like Camunda are powerful but can be complex and heavyweight. FlowLite offers:

- **Developer-first approach**: Designed with Kotlin developers in mind
- **Type-safe fluent API**: Leverage Kotlin's type system to create robust workflows
- **Visual representation**: Automatically generates diagrams from your code
- **Minimal learning curve**: Natural syntax that reads like plain English
- **Customizable**: Easy to integrate with your existing codebase
- **Composable**: Build complex flows from smaller, reusable pieces
- **Lightweight**

## Assumptions
* FlowLite uses an Action-Oriented approach for stages, where stage names indicate ongoing activities (e.g., "InitializingPayment")
* Each stage has an associated StageStatus e.g. (PENDING, IN_PROGRESS, COMPLETED, ERROR)
* The combination of stage and StageStatus (plus eventually retry_count and retry configuration) fully defines what the engine should do next
* Execution of the next step in the flow is triggered by the "execute next step in flow instance x" message
* Assumptions for mermaid diagrams
  * The Rectangle represents stages with their associated actions. Format: StageName `actionName()`
  * When an action fails, the stage will be marked with a StageStatus of failed (not shown in diagram)
  * It will be possible to add a retry strategy for each stage.
  * Arrows represent transitions between stages, triggered by action completion (and StageStatus change) or events
  * Choice nodes represent routing decisions
  * Events can trigger stage transitions. They represent external triggers that change the process stage (e.g., `onEvent SwitchToCashPayment`)
  * Terminal stages are represented by transitions to `[*]`

### Error Handling

#### Exception Handling Principles

* Exceptions thrown from actions will cause the stage to fail
* FlowLite differentiates between two types of exceptions:
  * **Process Exceptions**: Unexpected errors that represent technical issues (database connection failures, system unavailability)
  * **Business Exceptions**: Expected exceptions that represent valid business cases (payment declined, validation errors) (those which implements `BusinessException` marker interface)
* Business exceptions are designed with the assumption that a process supervisor will review the case and potentially retry the stage with corrected data.
* Stage status transitions to different error states depending on exception type:
  * PROCESS_ERROR for technical/system exceptions
  * BUSINESS_ERROR for expected business exceptions
* Process errors can be retried via the FlowLite cockpit. Business exceptions are displayed differently (or not at all). 
* Error information is preserved for debugging and analysis

#### Error Storage

FlowLite uses a separate error repository to store and manage error information

## TODO

* Cleanup implementation after generator implementation vibe code session
  * fix todos in the code
  * method names missing on generated diagram
  * Add api for adding description to condition
  * remove println
  * do we need internal addStage?
  * var action -> val action?
  * implement join in StageBuilder
  * make condition implementation more compact
  * what to do with FlowApiTest
  * combine stage(stage: Stage, action: (item: T) -> T) and stage(stage: Stage) ?
* Define second flow?
* Full implementation of engine with working example
* Implement error handling
* onTrue/onFalse as methods?
* add startChildFlow
* add subFlow
* Waiting on multiple events (event with conditional?)
* History of changes

## Process Example

### Diagram

```mermaid
stateDiagram-v2
    state if_payment_method <<choice>>
    
    [*] --> Started
    Started --> if_payment_method
    CancellingOrder --> [*]
    if_payment_method --> InitializingCashPayment: paymentMethod = CASH
    if_payment_method --> InitializingOnlinePayment: paymentMethod = ONLINE
    InitializingCashPayment: InitializingCashPayment initializeCashPayment()
    InitializingCashPayment --> StartingOrderPreparation: onEvent PaymentConfirmed
    InitializingCashPayment --> CancellingOrder: onEvent Cancel
    InitializingOnlinePayment: InitializingOnlinePayment initializeOnlinePayment()
    InitializingOnlinePayment --> InitializingCashPayment: onEvent SwitchToCashPayment
    InitializingOnlinePayment --> StartingOrderPreparation: onEvent PaymentCompleted
    InitializingOnlinePayment --> CancellingOrder: onEvent Cancel 
    InitializingOnlinePayment --> ExpiringOnlinePayment: onEvent PaymentSessionExpired
    ExpiringOnlinePayment: ExpiringOnlinePayment handlePaymentExpiration()
    ExpiringOnlinePayment --> InitializingOnlinePayment: onEvent RetryPayment
    ExpiringOnlinePayment --> CancellingOrder: onEvent Cancel
    StartingOrderPreparation: StartingOrderPreparation startOrderPreparation()
    StartingOrderPreparation --> InitializingDelivery: onEvent ReadyForDelivery
     
    InitializingDelivery: InitializingDelivery initializeDelivery()
    InitializingDelivery --> CompletingOrder: onEvent DeliveryCompleted
    InitializingDelivery --> CancellingOrder: onEvent DeliveryFailed
    
    CompletingOrder: CompletingOrder completeOrder()
    CancellingOrder: CancellingOrder sendOrderCancellation()
    CompletingOrder --> [*]
```

### Code

See [PizzaDomain.kt](test/PizzaDomain.kt) and [PizzaOrderFlowTest.kt](test/PizzaOrderFlowTest.kt)

## Parallel Execution (Idea)

FlowLite achieves parallelism through parent-child flow relationships.

### Parent-Child Flow Model

FlowLite implements parallel execution using a parent-child flow model where:

- Parent flows can start one or more child flows
- Child flows execute independently and in parallel
- Parent flows can wait for specific child flows at designated stages
- The parent flow continues its own execution while child flows run

### Diagram Example

The following diagram illustrates a typical parent-child flow pattern:

```mermaid
stateDiagram-v2
    ValidatingBasicInfo: ValidatingBasicInfo validateBasicInfo()
    [*] --> ValidatingBasicInfo
    
    state fork_state <<fork>>
    ValidatingBasicInfo --> fork_state
    
    fork_state --> RunningCreditCheck: Main Flow
    fork_state --> VerifyingIncome: startChildFlow(Income)
    
    RunningCreditCheck: RunningCreditCheck performCreditCheck()
    RunningCreditCheck --> ReviewingCreditHistory
    
    ReviewingCreditHistory: ReviewingCreditHistory analyzeCreditHistory()
    ReviewingCreditHistory --> EvaluatingInitialEligibility
    
    VerifyingIncome: VerifyingIncome verifyIncome()
    VerifyingIncome --> CalculatingDebtToIncome
    
    CalculatingDebtToIncome: CalculatingDebtToIncome calculateRatios()
    CalculatingDebtToIncome --> VerifyingEmployment
    
    VerifyingEmployment: VerifyingEmployment contactEmployer()
    VerifyingEmployment --> WaitingForIncomeVerification: Income child flow ends
    
    EvaluatingInitialEligibility: EvaluatingInitialEligibility assessInitialRisk()
    EvaluatingInitialEligibility --> WaitingForIncomeVerification
    
    WaitingForIncomeVerification: WaitingForIncomeVerification waitForChildFlows(Income)
    
    WaitingForIncomeVerification --> MakingFinalDecision
    MakingFinalDecision: MakingFinalDecision determineLoanApproval()
    
    MakingFinalDecision --> [*]
```

### API for Parallel Execution

```kotlin
// Starting a child flow
fun <T : Any, R : Any> FlowBuilder<T>.startChildFlow(
    childFlowId: String,
    initialStateMapper: (parentState: T) -> R,
): FlowBuilder<T>

// Processing child flow results
fun <T : Any, R : Any> FlowBuilder<T>.waitForChildFlow(
    childFlowId: String,
    resultMapper: (parentState: T, childResult: R) -> T,
): FlowBuilder<T>
```
