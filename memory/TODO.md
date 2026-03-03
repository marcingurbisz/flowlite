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

## Use FlowLiteHistoryRow instead CockpitHistoryEntryDto which is just duplicate

## move cockpit/api to service
 
## Showcase application
Prepare showcase application (or find a better name). It should be startTestWebApplication with a logic that creates one order confirm. and one employee onbording flow instance on start and then every 5s so we can see cockpit in action.
Check if cockpit is working fine. Do you have tools to test it? If not do you know what tools I can give you so you can verify how cockpit is working including visual inspection?

## Review queries in history repository
Can we simplify queries by some design change?

## Implement playwright tests

## Expose test instance publicly available

## Yet more coverage?

## Optimistic locking based on modified fields?
