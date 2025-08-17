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

* Exceptions thrown from actions will cause the stage to fail
* FlowLite differentiates between two types of exceptions:
  * **Process Exceptions**: Unexpected errors that represent technical issues (database connection failures, system unavailability)
  * **Business Exceptions**: Expected exceptions that represent valid business cases (payment declined, validation errors) (those which implements `BusinessException` marker interface)
* Business exceptions are designed with the assumption that a process supervisor will review the case and potentially retry the stage with corrected data.
* Stage status transitions to different error states depending on an exception type:
  * PROCESS_ERROR for technical/system exceptions
  * BUSINESS_ERROR for expected business exceptions
* Process errors can be retried via the FlowLite cockpit. Business exceptions are displayed differently (or not at all). 
* Error information is preserved for debugging and analysis
* FlowLite uses a separate error repository to store and manage error information

## Development Guide

### Windows Setup

If you're cloning this repository on Windows, symbolic links (like `CLAUDE.md -> README.md`) require special Git configuration:

**Option 1: Enable symlinks globally (recommended)**
```bash
git config --global core.symlinks true
git clone <repository-url>
```

**Option 2: Clone with symlinks enabled**
```bash
git clone -c core.symlinks=true <repository-url>
```

**Requirements:** Git for Windows 2.10.2+, NTFS file system, and either Developer Mode enabled or Administrator privileges.

If symbolic links don't work, `CLAUDE.md` will appear as a text file containing "README.md" - in this case, just refer to README.md directly.

### Build and Test Commands
- `./gradlew build` - Build the entire project
- `./gradlew test` - Run all tests
- `./gradlew clean` - Clean build artifacts
- `./gradlew check` - Run all verification tasks

### Code Structure

FlowLite uses a **flat directory structure** to keep the codebase simple and organized:

- `source/` - All main source code (flat structure, no subdirectories for main package)
- `test/` - All test code (flat structure)
- Resources are placed directly in source directory alongside code files, not in a separate resources directory

**Directory Guidelines:**
- Do not create subdirectories for the main package
- For subpackages within the main package, subdirectories are optional - create them only if needed for organization
- Maintain consistency with the existing flat structure

### Core Architecture

#### Flow Definition System (`source/FlowApi.kt`)
- `FlowBuilder<T>` - Fluent API for defining workflows
- `StageBuilder<T>` - Builder for individual stages within flows
- `EventBuilder<T>` - Builder for event-based transitions
- `Flow<T>` - Immutable flow definition container

#### Core Interfaces
- `Stage` - Enum-based stage definitions (action-oriented naming)
- `Event` - Enum-based event definitions for transitions
- `StatePersister<T>` - Interface for persisting workflow state

#### Flow Components
- `StageDefinition<T>` - Contains stage action, event handlers, condition handler, and next stage
- `ConditionHandler<T>` - Handles conditional branching
- `EventHandler<T>` - Handles event-based transitions
- `FlowEngine` - Runtime engine for executing flows

#### Diagram Generation (`source/MermaidGenerator.kt`)
- `MermaidGenerator` - Converts flow definitions to Mermaid diagrams

### Stage Transitions

FlowLite supports three types of stage transitions:

1. **Automatic Progression**: Sequential stages automatically flow to the next stage
   ```kotlin
   .stage(InitializingConfirmation, ::initializeOrderConfirmation)
   .stage(WaitingForConfirmation) // Automatic progression
   ```

2. **Event-Based Transitions**: Explicit events trigger transitions
   ```kotlin
   .onEvent(PaymentConfirmed).stage(ProcessingPayment, ::processPayment)
   ```

3. **Conditional Branching**: Logic-based routing decisions
   ```kotlin
   .condition(
       predicate = { it.paymentMethod == PaymentMethod.CASH },
       onTrue = { /* cash flow */ },
       onFalse = { /* online flow */ }
   )
   ```

4. **Join Operations**: Reference existing stages from other branches
   ```kotlin
   .onEvent(PaymentCompleted).join(ProcessingOrder)
   ```

### Design Principles
- **Action-Oriented Stages**: Stage names indicate ongoing activities
- **Type Safety**: Leverages Kotlin's type system for robust workflow definitions
- **Immutable Flow Definitions**: Built flows are immutable containers
- **Cross-Branch References**: `join()` allows stages to reference stages defined in other branches

### Examples
- Pizza Order Flow: `test/PizzaDomain.kt` and `test/PizzaOrderFlowTest.kt`
- Order Confirmation Flow: `test/OrderConfirmationTest.kt`

### Development Notes
- Uses Kotlin 2.1 with Java 21 toolchain
- Context receivers enabled with `-Xcontext-receivers` flag
- Kotest for testing with BehaviorSpec style and MockK for mocking
- Gradle build system with Maven publishing configuration

## TODO
* Migrate from deprecated kotlinOptions to compilerOptions DSL
* review readme
* method names missing on generated diagram
* Add api for adding description to condition
* remove println
* var action -> val action?
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
