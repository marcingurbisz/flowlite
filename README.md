# FlowLite

FlowLite is a lightweight, code-first workflow engine for Kotlin.

It’s designed for workflows owned by a single component/microservice/modulith (one JVM or a few replicas): explicit stages, durable “mailbox” events, and a straightforward tick-based execution model.

Note: FlowLite is actively evolving. Breaking changes may be introduced and backwards compatibility is not considered for now.

## Why FlowLite?

FlowLite at a glance:
- **Type-safe DSL** for stages, events, conditions, and joins
- **Mermaid diagrams from code** (no separate model to maintain)
- **Mailbox event semantics** via a pluggable `EventStore`
- **Tick-based runtime** with a single-flight claim (`PENDING -> RUNNING`)
- **Retries** via `retry(...)`
- **Cockpit** [prototype](https://claude.ai/public/artifacts/b4d9ad11-6ee4-44ba-ac22-c879e9af2e17)

FlowLite is intentionally biased toward the “single component owns the workflow” case. It focuses on:
- explicit wait states,
- durable pending events (events can arrive early and get consumed later),
- a small, predictable runtime model.

See this [article](https://medium.com/@marcin.gurbisz/flowlite-a-tiny-workflow-engine-cebb544aa4b9) for more context and motivation.

## Example flow

<!-- FlowDoc(order-confirmation) -->
```kotlin
fun createOrderConfirmationFlow(): Flow<OrderConfirmation, OrderConfirmationStage, OrderConfirmationEvent> {
    return FlowBuilder<OrderConfirmation, OrderConfirmationStage, OrderConfirmationEvent>()
        .stage(InitializingConfirmation, ::initializeOrderConfirmation)
        .stage(WaitingForConfirmation)
        .apply {
            waitFor(ConfirmedDigitally)
                .stage(RemovingFromConfirmationQueue, ::removeFromConfirmationQueue)
                .stage(InformingCustomer, ::informCustomer)
            waitFor(ConfirmedPhysically).join(InformingCustomer)
        }
        .end()
        .build()
}
```

```mermaid
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

```

<!-- FlowDoc.end -->

## Key concepts and assumptions

- **Stage**: A named step (enum implementing `Stage` interface). Represents “we are doing X” - activity-oriented naming (e.g. `InitializingPayment`).
- **Action**: Function executed when entering a stage `.stage(InitializingConfirmation, ::initializeOrderConfirmation)` (optional for stage).
- **Event**: External trigger causing a transition (implements `Event`). Submitted through engine API.
- **Condition**: Binary branching with a predicate. Renders as a choice node on diagram.
- **Join**: Converges control flow by pointing to an existing stage.
- **Flow**: Immutable definition produced by `FlowBuilder<T>.build()` and held in-memory.
- **StageStatus**: Lifecycle state of the single active stage:
    - `PENDING` – Active stage awaiting action execution or matching event.
    - `RUNNING` – Flow instance is currently being progressed by the engine. Remains `RUNNING` during the whole processing loop and is released back to `PENDING` when the instance needs to wait for an event.
    - `COMPLETED` – Only used for terminal stages. When a non-terminal stage finishes, the engine advances the pointer to the next stage with `PENDING` rather than persisting completion of the previous stage.
    - `ERROR` – Action failed; requires manual retry.
- **Tick**: An internal “work item / wake-up signal” that tells the engine “try to make progress for (flowId, flowInstanceId) now”.
    - A tick carries no business payload;
    - Ticks are emitted on `startInstance`, `sendEvent`, and `retry`.
- Client that uses FlowLite provides the persistence for both FlowLite specific (id, stage, stage status) and process specific data
- Single-token model: only one active stage at any moment (no parallelism within one flow).
- Code-first definitions → diagrams are generated from code.
- Mermaid diagram semantics: rectangle = stage (+ optional action); choice node = condition; `[*]` = terminal.
- Error handling: any exception marks stage `ERROR`; `retry` resets it back to `PENDING` and restarts from that stage.
- Migration: If the flow changes, migrations of existing instances are the responsibility of the application that uses FlowLite. No flow versioning nor migration support is planned in FlowLite.

### Stage Transitions

FlowLite supports 2 types of stage transitions:

1. **Automatic Progression**: Sequential stages automatically flow to the next stage
   ```kotlin
   flow
       .stage(InitializingConfirmation, ::initializeOrderConfirmation)
       .stage(WaitingForConfirmation) // Automatic progression
   ```

2. **Event-Based Transitions**: Explicit events trigger transitions
   ```kotlin
   flow.waitFor(PaymentConfirmed).stage(ProcessingPayment, ::processPayment)
   ```

Event waiting semantics (`waitFor`):
- A stage that calls `waitFor(EventX)` will transition when `EventX` is received.
- If `EventX` was emitted earlier (before the workflow reached this stage), it is persisted and delivered immediately when the stage is entered.

### Conditional Branching
   ```kotlin
   flow.condition(
       predicate = { it.paymentMethod == PaymentMethod.CASH },
       onTrue = { /* true flow */ },
       onFalse = { /* false flow */ }
   )
   ```
### Join Operations

Reference already defined stages using `join()`:
   ```kotlin
   flow.waitFor(PaymentCompleted).join(ProcessingOrder)
   ```

### Action functions

- Signature: `(state: T) -> T?`
    - Return a new state to persist or `null` to indicate no state changes.
    - Any changes to engine managed fields will be overridden before engine persist them.
- Guidelines:
    - Keep actions small and focused.
  
## More examples

The examples below are generated from test flows. Each flow builder is wrapped with
`// FLOW-DEFINITION-START` and `// FLOW-DEFINITION-END` markers in its test file.
To document a new flow, add it to the `documentedFlows` list in
`test/ReadmeUpdater.kt` with its id, title, source file path and factory
function.

Documentation refresh:
- The GitHub Action `.github/workflows/update-readme.yml` regenerate all flow code examples and Mermaid diagrams between the `FlowDoc` markers on pushes and commits the update
- Run `./gradlew updateReadme` if you want to run it locally

<!-- FlowDoc(all) -->
### Employee Onboarding

```kotlin
        FlowBuilder<EmployeeOnboarding, EmployeeStage, EmployeeEvent>()
            .condition(
                predicate = { it.isOnboardingAutomated },
                description = "isOnboardingAutomated",
                onTrue = {
                    // Automated path
                    stage(CreateUserInSystem, actions::createUserInSystem)
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
                    // Manual path
                    join(WaitingForContractSigned)
                },
            )
            .build()
```

```mermaid
stateDiagram-v2
    state if_isonboardingautomated <<choice>>
    state if_isexecutiverole_issecurityclearancerequired <<choice>>
    state if_issecurityclearancerequired <<choice>>
    state if_isfullonboardingrequired <<choice>>
    state if_isexecutiverole_issecurityclearancerequired_2 <<choice>>
    [*] --> if_isonboardingautomated
    if_isonboardingautomated --> CreateUserInSystem: isOnboardingAutomated
    CreateUserInSystem: CreateUserInSystem createUserInSystem()
    CreateUserInSystem --> if_isexecutiverole_issecurityclearancerequired
    if_isexecutiverole_issecurityclearancerequired --> UpdateSecurityClearanceLevels: isExecutiveRole || isSecurityClearanceRequired
    UpdateSecurityClearanceLevels: UpdateSecurityClearanceLevels updateSecurityClearanceLevels()
    UpdateSecurityClearanceLevels --> if_issecurityclearancerequired
    if_issecurityclearancerequired --> if_isfullonboardingrequired: isSecurityClearanceRequired
    if_isfullonboardingrequired --> SetDepartmentAccess: isFullOnboardingRequired
    SetDepartmentAccess: SetDepartmentAccess setDepartmentAccess()
    SetDepartmentAccess --> GenerateEmployeeDocuments
    GenerateEmployeeDocuments: GenerateEmployeeDocuments generateEmployeeDocuments()
    GenerateEmployeeDocuments --> SendContractForSigning
    SendContractForSigning: SendContractForSigning sendContractForSigning()
    SendContractForSigning --> WaitingForEmployeeDocumentsSigned
    WaitingForEmployeeDocumentsSigned --> WaitingForContractSigned: onEvent EmployeeDocumentsSigned
    WaitingForContractSigned --> if_isexecutiverole_issecurityclearancerequired_2: onEvent ContractSigned
    if_isexecutiverole_issecurityclearancerequired_2 --> ActivateSpecializedEmployee: isExecutiveRole || isSecurityClearanceRequired
    ActivateSpecializedEmployee: ActivateSpecializedEmployee activateEmployee()
    ActivateSpecializedEmployee --> UpdateStatusInHRSystem
    UpdateStatusInHRSystem: UpdateStatusInHRSystem updateStatusInHRSystem()
    if_isexecutiverole_issecurityclearancerequired_2 --> WaitingForOnboardingCompletion: NOT (isExecutiveRole || isSecurityClearanceRequired)
    WaitingForOnboardingCompletion --> UpdateStatusInHRSystem: onEvent OnboardingComplete
    if_isfullonboardingrequired --> GenerateEmployeeDocuments: NOT (isFullOnboardingRequired)
    if_issecurityclearancerequired --> WaitingForContractSigned: NOT (isSecurityClearanceRequired)
    if_isexecutiverole_issecurityclearancerequired --> ActivateStandardEmployee: NOT (isExecutiveRole || isSecurityClearanceRequired)
    ActivateStandardEmployee: ActivateStandardEmployee activateEmployee()
    ActivateStandardEmployee --> GenerateEmployeeDocuments
    if_isonboardingautomated --> WaitingForContractSigned: NOT (isOnboardingAutomated)
    UpdateStatusInHRSystem --> [*]

```

### Order Confirmation

```kotlin
fun createOrderConfirmationFlow(): Flow<OrderConfirmation, OrderConfirmationStage, OrderConfirmationEvent> {
    return FlowBuilder<OrderConfirmation, OrderConfirmationStage, OrderConfirmationEvent>()
        .stage(InitializingConfirmation, ::initializeOrderConfirmation)
        .stage(WaitingForConfirmation)
        .apply {
            waitFor(ConfirmedDigitally)
                .stage(RemovingFromConfirmationQueue, ::removeFromConfirmationQueue)
                .stage(InformingCustomer, ::informCustomer)
            waitFor(ConfirmedPhysically).join(InformingCustomer)
        }
        .end()
        .build()
}
```

```mermaid
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

```

<!-- FlowDoc.end -->

## Architecture

### API

* Defining flow - See [source/dsl.kt](source/dsl.kt):
  * `Stage`, `Event`
  * `FlowBuilder`, `StageBuilder`, `EventBuilder`, `Flow`.
* Registering flows, starting process instances, etc. - [source/engine.kt](source/engine.kt) (`FlowEngine`).
* Interfaces that must be implemented by client [source/persistance.kt](source/persistance.kt):
  * `StatePersister`
  * `EventStore`
  * `TickScheduler`

Reference implementations:
- `EventStore`: [test/SpringDataEventStore.kt](test/SpringDataEventStore.kt) (`SpringDataEventStore`).
- `TickScheduler`: [test/DbTickScheduler.kt](test/DbTickScheduler.kt) (`DbTickScheduler`, Spring Data JDBC-based polling scheduler).
- `StatePersister`: [test/OrderConfirmationTest.kt](test/OrderConfirmationTest.kt) (`SpringDataOrderConfirmationPersister`) and [test/employeeOnboardingFlowTest.kt](test/employeeOnboardingFlowTest.kt) (`SpringDataEmployeeOnboardingPersister`).
- Wiring example: [test/TestApplication.kt](test/TestApplication.kt).

See [Contracts](#contracts) for the persistence/scheduler interfaces.

### Runtime & Execution Model

1. Starting a flow instance persists instance data, calculates initial stage and enqueues a Tick. It's also possible to pre-create the flow instance earlier with your business data and later start processing by providing id.
2. Tick processing loop:
    - Load flow instance state (stage + status) via `StatePersister`.
    - If status `ERROR` → stop (await retry).
    - If status `RUNNING` → another worker currently owns the instance; stop (tick delivered while the instance is already being processed).
    - If status `PENDING`: atomically claim the instance by transitioning `PENDING -> RUNNING` (optimistic CAS in persistence).
    - While `RUNNING`, the engine will keep advancing through automatic transitions and actions.
        - If the current stage waits for events and no matching event exists: release the claim by setting status back to `PENDING`, enqueue a Tick, and stop.
            - Why enqueue: an event may arrive while the instance is `RUNNING` and its Tick can be delivered and ignored; enqueueing after releasing to `PENDING` ensures the event store is re-checked.
        - If the current stage consumes an event: advance to the next stage and continue (staying `RUNNING`).
        - If the current stage executes an action: run it and advance to the next stage and continue (staying `RUNNING`), or mark `COMPLETED` if terminal.
        - On failure: set `ERROR` (best-effort) and stop.
3. External events: `sendEvent(flowId, flowInstanceId, event)` inserts a event into `EventStore` and enqueues a Tick. The Tick will consume the event immediately if the instance is currently waiting for it; otherwise the event remains pending until eligible.

`RUNNING` acts as a single-flight claim for tick processing. If a JVM crashes mid-loop, the instance may remain `RUNNING` until application-defined recovery resets it.

### Transaction Boundaries
FlowLite does not start or manage transactions internally (to remain persistence-agnostic).

Practical guidance (DB-backed implementations):
- `startInstance`: persist the flow instance and enqueue a tick in the same transaction (or via an outbox) to avoid “flow instance created but never scheduled”.
- `sendEvent`: append the pending event and enqueue a tick in the same transaction (or via an outbox) to avoid “event stored but never scheduled”.
- `retry`: update `stage_status` to `PENDING` and enqueue a tick in the same transaction (or via an outbox).
- Tick handling (`processTick`): wrapping it into transaction is not recommended since this will create a big transaction that span over all transition and actions between start and first stage with event handler or terminal state.

### Persistence Approach

FlowLite is “application-owned” for persistence which means you need to provide persistence implementation.
At minimum, an application needs two persistence components:

1. **Flow instance state persistence** (`StatePersister<T>`)
    - Stores domain state **and** engine state for a single flow instance.
    - Required engine fields:
      - `id` (UUID, flow instance id)
      - `stage` (string/enum name)
      - `stage_status` (`PENDING` | `RUNNING` | `ERROR` | `COMPLETED`)
        - Engine fields are **owned by the engine** and must not be updated by external writers.
    - Plus your domain fields (customer data, order data, etc).

2. **Pending events persistence** (`EventStore`)
    - Stores events submitted via `sendEvent` until the flow reaches a stage that can consume them.
    - This is what enables “mailbox semantics”: events can arrive early and will be applied later.

In tests, examples of these integrations live in:
- `StatePersister`: `SpringDataOrderConfirmationPersister`, `SpringDataEmployeeOnboardingPersister`
- `EventStore`: `SpringDataEventStore`

### Contracts

`StatePersister<T>`:
- `load(flowInstanceId)` → current state (including engine fields); throws if the flow instance does not exist
- `save(instanceData)` → create or update; beside updated engine fields instanceData may contain domain modifications produced by actions (see action persistence guidance below); returns refreshed data on success.
    - Should be best-effort in the presence of concurrency (optimistic locking): retry and/or merge engine-owned fields (`stage`, `stage_status`) with a freshly loaded domain snapshot to avoid lost updates.
- `tryTransitionStageStatus(flowInstanceId, expectedStage, expectedStatus, newStatus)` → atomic compare-and-set transition of `stage_status` (guarded by both `stage` and `stage_status`). Returns true only if the expected values matched and the update was applied.
    - Used by the engine to claim single-flight processing (`PENDING -> RUNNING`).
    - Implementation options include:
        - Atomic CAS update (e.g. SQL `UPDATE ... WHERE id AND stage AND stage_status`).
        - `load` + check + `save` guarded by optimistic locking (`@Version`) and handling optimistic lock failures.
        - Persist engine state (stage/status) separately from business state.


`(state: T) -> T?` action persistence guidance:
- If an action needs to persist business changes (i.e. has side effects on the process business data), it is recommended that the action persists those changes itself and then returns the updated state to the engine.
- An action may also return updated state without saving and rely on the engine calling `StatePersister.save(...)` for persistence. In this case, the persister must handle concurrency correctly (optimistic locking / merge rules). See the concurrency notes below.
- If the action returns `null` engine will call `StatePersister.save(...)` with last loaded copy (before action execution) of data updated with stage advances

`TickScheduler` contract:
- `setTickHandler(handler)`
    - Called once by the engine during `FlowEngine` initialization to register the function that processes ticks.
- `scheduleTick(flowId, flowInstanceId)`
    - Enqueues a tick for `(flowId, flowInstanceId)`.
    - At-least-once delivery is required; duplicates are allowed.
- Delivery/lifecycle expectations:
    - Schedulers should start delivering already-queued ticks after application startup.
- Error handling:
    - If the tick handler throws, the scheduler should log and continue delivering future ticks (optionally with backoff). It must not crash permanently.

See `test/DbTickScheduler.kt` for a minimal in-process polling scheduler.

Concurrency scenarios (cheat sheet):

| Scenario                                                        | Can it happen? | If unmitigated, what can go wrong?                                                           | Recommended mitigation                                                                                                        |
|-----------------------------------------------------------------|--------------:|----------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| Two ticks processed concurrently for same instance              | No (if correctly implemented) | Double action execution; double stage advance; inconsistent state                            | Use an atomic `PENDING -> RUNNING` claim using CAS (`tryTransitionStageStatus(...)`) in persistence                           |
| Duplicate `sendEvent` for same instance + event type            | Yes (retries, double-click, at least once external events) | Extra pending event rows; with FlowLite’s flow-definition validation (event used in only one `waitFor(...)`), duplicates are typically harmless but may accumulate | Optionally add dedup/idempotency to `EventStore` if you care about storage growth                                             |
| External writer updates the same row while engine is processing |                                   Yes (GUI, notifications) | Potential lost updates; optimistic lock conflicts                                            | Options in case of JDBC persistence: 1) use optimistic locking (with merge or retries) 2) move engine state to separate table |


#### Practical schema guidance (JDBC)

No external updates (only process updates the row):
- No issues with keeping engine and business fields in one table (one row per flow instance).
- Optimistic locking not necessary
- Actions can return a new state `T` and the persister can save business + engine fields atomically.

External updates exist (GUI / notifications / other services):
- Option A: Single table; External writers update business fields and handle optimistic lock conflicts with proper merge; persister called by engine handles optimistic lock conflicts with proper merge.
- Option B: Separate table for engine and business fields.

### Diagram Generation
- [`source/MermaidGenerator.kt`](`source/MermaidGenerator.kt`) - Converts flow definitions to Mermaid diagrams

### Deferred / Future Enhancements

- Cockpit and Structured history with error details (WIP)
- Distinguish business vs technical errors with tailored retry policies
- Parallelism via coordination of multiple flow instances

## Development Guide

### Build and Test Commands
- `./gradlew build` - Build the entire project
- `./gradlew test` - Run all tests
- `./gradlew clean` - Clean build artifacts
- `./gradlew check` - Run all verification tasks

Note for agent: After changing source code, run `./gradlew test` before returning to user.

### Code Structure

FlowLite uses a **flat directory structure** to keep the codebase simple and organized:

- `source/` - All main source code (flat structure, no subdirectories for main package)
- `test/` - All test code (flat structure)
- Resources are placed directly in source directory alongside code files, not in a separate resources directory

### Development Notes
- Uses Kotlin 2.2 with Java 21 toolchain
- Kotest for testing with BehaviorSpec style and MockK for mocking
- Gradle build system with Maven publishing configuration

### Code Documentation Guidelines
- Keep the code self-explanatory through clear naming and structure
- Favor clear naming over comments
- Comment only non-obvious cases or complex logic
- Keep architectural & usage docs in this README
- Remember to update "Table of Contents" in README when adding new chapter or changing existing
