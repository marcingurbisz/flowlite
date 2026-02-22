# TODO

## 1. Kotlin DSL with receiver lambdas instead of builder chains

The current API mixes method chaining with `apply` blocks (like in the order confirmation flow). A dedicated Kotlin DSL using `@DslMarker` and lambda-with-receiver could feel more natural:

```kotlin
val flow = flow<OrderConfirmation, OrderConfirmationStage, OrderConfirmationEvent> {
    stage(InitializingConfirmation, ::initializeOrderConfirmation)
    stage(WaitingForConfirmation) {
        onEvent(ConfirmedDigitally) {
            stage(RemovingFromConfirmationQueue, ::removeFromConfirmationQueue)
            stage(InformingCustomer, ::informCustomer)
        }
        onEvent(ConfirmedPhysically) {
            joinTo(InformingCustomer)
        }
    }
}
```

The nesting makes it visually clear which events belong to which stage. The `@DslMarker` annotation prevents accidentally calling outer-scope builders from inner lambdas (which is a real footgun with the current `apply` approach — nothing stops you from calling `stage()` on the wrong builder).

## 2. Sealed types instead of marker interfaces for Stage/Event

Right now `Stage` and `Event` are empty interfaces, and the README says "should be enums." You could enforce this more strongly and get exhaustive `when` checking:

```kotlin
// User defines:
sealed class OrderStage : Stage {
    data object Initializing : OrderStage()
    data object WaitingForConfirmation : OrderStage()
    data object Removing : OrderStage()
    data object Informing : OrderStage()
}
```

With sealed classes/interfaces, the compiler guarantees exhaustiveness in `when` expressions. This matters if you ever add APIs where users need to handle stages (e.g., custom persistence mapping). Enums work too, but sealed hierarchies allow carrying per-stage data if needed later (e.g., a stage that carries a retry count or timeout configuration).

## 7. Context receivers (or extension function types) for actions

Instead of `(T) -> T?`, actions could receive a richer context:

```kotlin
fun interface StageAction<T> {
    context(ActionContext) // Kotlin context receivers
    fun execute(state: T): T?
}

class ActionContext(
    val flowId: String,
    val flowInstanceId: UUID,
    val logger: Logger,
    // potentially: emit sub-events, schedule timers, etc.
)
```

This avoids the pattern where actions need the `flowInstanceId` but have to dig it out of the state object (as the employee onboarding actions currently do with `requireNotNull(employee.id)`). The context could provide it directly.

##
- Integrate Cockpit prototype
- Expose test instance publicly available
- Yet more coverage?
- Optimistic locking based on modified fields?
