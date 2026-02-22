# TODO

## Kotlin DSL with receiver lambdas instead of builder chains

Status (2026-02-22): initial implementation delivered (`flow {}` / `eventlessFlow {}`), tests added, and Order Confirmation migrated.
Next incremental follow-ups:
- Consider reducing overload ambiguity around `stage(..., block = { ... })` by introducing clearer names (e.g. `stageBlock(...)`) or signature tweaks.
- Consider migrating Employee Onboarding flow to receiver DSL after readability review.

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

## Enforce that stages are enums
## Context receivers (or extension function types) for actions

Instead of `(T) -> T?`, actions could receive a richer context:

```kotlin
fun interface StageAction<T> {
    context(ActionContext) // Kotlin context receivers
    fun execute(state: T): T?
}

class ActionContext(
    val flowId: String,
)
```

This avoids the pattern where actions need the `flowInstanceId` but have to dig it out of the state object (as the employee onboarding actions currently do with `requireNotNull(employee.id)`). The context could provide it directly.

## Integrate Cockpit prototype
## Expose test instance publicly available
## Yet more coverage?
## Optimistic locking based on modified fields?
