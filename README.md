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

## TODO

* ✅ Prepare a gradle project for Kotlin for this library
* ✅ Prepare rules files for copilot
* ✅ Come up with Kotlin API (I can use it as a client)
* ✅ Put source code into github
* Full implementation of API
* Diagram generator and flow validator
* Implementation of the engine

## Process Example

### Assumptions

* Rectangle represent action (methods on diagram) with a state change or state change alone: Format: `actionName() STATE_NAME`
* Status change is executed within the same transaction with action
* When action fails state will be marked as error (not shown diagram).
    * It's possible to add retry strategy for each action.
* Arrows represent transitions between states, triggered by action completion (and status change) or events
* Choice nodes represent routing decisions
* Events can trigger action execution and status change. They represent external triggers that change the process state (e.g., `onEvent: SwitchToCashPayment`)
* Terminal states are represented by transitions to *`[]`

### Diagram

```mermaid
stateDiagram-v2
    state if_order_eligibility <<choice>>
    state if_payment_method <<choice>>
    
    [*] --> ORDER_ELIGIBILITY_EVALUATED
    ORDER_CANCELLATION_SENT --> [*]
    ORDER_ELIGIBILITY_EVALUATED: evaluateOrderEligibility() ORDER_ELIGIBILITY_EVALUATED
    ORDER_ELIGIBILITY_EVALUATED --> if_order_eligibility
    if_order_eligibility --> ORDER_CREATED: eligibility = VALID
    if_order_eligibility --> INVALID_ORDER_ELIGIBILITY: eligibility = INVALID
    INVALID_ORDER_ELIGIBILITY --> ORDER_CANCELLATION_SENT: onEvent#58; AcknowledgeError
    ORDER_CREATED: createPizzaOrder() ORDER_CREATED
    ORDER_CREATED --> if_payment_method
    if_payment_method --> CASH_PAYMENT_INITIALIZED: paymentMethod = CASH
    if_payment_method --> ONLINE_PAYMENT_INITIALIZED: paymentMethod = ONLINE
    CASH_PAYMENT_INITIALIZED: initializeCashPayment() CASH_PAYMENT_INITIALIZED
    CASH_PAYMENT_INITIALIZED --> PAYMENT_WAITING
    PAYMENT_WAITING --> ORDER_PREPARATION_STARTED: onEvent#58; PaymentConfirmed
    PAYMENT_WAITING --> ORDER_CANCELLATION_SENT: onEvent#58; Cancel
    ONLINE_PAYMENT_INITIALIZED: initializeOnlinePayment() ONLINE_PAYMENT_INITIALIZED
    ONLINE_PAYMENT_INITIALIZED --> ONLINE_PAYMENT_WAITING
    ONLINE_PAYMENT_WAITING --> CASH_PAYMENT_INITIALIZED: onEvent#58; SwitchToCashPayment
    ONLINE_PAYMENT_WAITING --> ORDER_PREPARATION_STARTED: onEvent#58; PaymentCompleted
    ONLINE_PAYMENT_WAITING --> ORDER_CANCELLATION_SENT: onEvent#58; Cancel 
    ONLINE_PAYMENT_WAITING --> ONLINE_PAYMENT_EXPIRED: onEvent#58; PaymentSessionExpired
    ONLINE_PAYMENT_EXPIRED --> ONLINE_PAYMENT_INITIALIZED: onEvent#58; RetryPayment
    ONLINE_PAYMENT_EXPIRED --> ORDER_CANCELLATION_SENT: onEvent#58; Cancel
    ORDER_PREPARATION_STARTED: startOrderPreparation() ORDER_PREPARATION_STARTED
    ORDER_PREPARATION_STARTED --> ORDER_READY_FOR_DELIVERY
    ORDER_READY_FOR_DELIVERY: markOrderReadyForDelivery() ORDER_READY_FOR_DELIVERY
    ORDER_READY_FOR_DELIVERY --> DELIVERY_INITIALIZED
    
    DELIVERY_INITIALIZED: initializeDelivery() DELIVERY_INITIALIZED
    DELIVERY_INITIALIZED --> DELIVERY_IN_PROGRESS
    DELIVERY_IN_PROGRESS --> ORDER_COMPLETED: onEvent#58; DeliveryCompleted
    DELIVERY_IN_PROGRESS --> ORDER_CANCELLATION_SENT: onEvent#58; DeliveryFailed
    
    ORDER_COMPLETED: completeOrder() ORDER_COMPLETED
    ORDER_CANCELLATION_SENT: sendOrderCancellation() ORDER_CANCELLATION_SENT
    ORDER_COMPLETED --> [*]
```

## Code example

[PizzaOrderFlowTest.kt](test/PizzaOrderFlowTest.kt)
