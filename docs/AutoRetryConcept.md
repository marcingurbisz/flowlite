# Auto-retry and user-retriable concept

## Goal

Introduce a retry model that covers:
1. failures that should be retried automatically,
2. failures that user can retry (outside engine with dedicated UI that client of flowlite builds for this purpose),
3. failures that can only be retried via Cockpit,

There are failures that can be 2 and 3 and the same time. Every failure can be retried from cockpit.

> MG: Who is the user in 2 is not clear. I wonder if we can somehow improve that.

## Recommendation

Use a **hybrid design**:
- **core engine owns retry orchestration and persistence mechanics**, because delayed retries, due-time scheduling, status handling, and cockpit visibility are engine concerns,
- **applications provide the failure classification policy**, because only the application knows whether an exception is transient, business-fixable, or fatal.

So the answer to “core engine or extension?” is:
- **mechanics in core**,
- **policy in extension/application code**.

## Proposed model

## 1. Introduce explicit failure directives

Instead of treating every exception the same, a stage/action failure should resolve to a directive such as:

```kotlin
sealed interface FailureDirective {
    data class AutoRetry(
        val delay: Duration,
        val maxAttempts: Int,
        val backoff: BackoffStrategy = BackoffStrategy.fixed(delay),
    ) : FailureDirective

    data class UserRetriable(
    ) : FailureDirective

    data class Fatal(
    ) : FailureDirective
}
```

Important point: the action still throws, but the engine asks a classifier what the thrown exception means.

## 2. Add a failure classifier hook

Engine level classifier:
```kotlin
interface FailureClassifier<T : Any> {
    fun classify(
        context: ActionContext,
        stage: Stage,
        state: T,
        error: Exception,
        attempt: Int,
    ): FailureDirective
}
```

In future maybe flow level classifier but not for now.

## 3. Persist retry metadata separately from domain state

Do **not** require every application domain row to grow custom retry columns.

Recommended direction:
- keep `stage` and `stageStatus` where they already are,
- persist retry metadata in an engine-owned table or engine-owned persistence structure.

Suggested fields:
- `flow_id`
- `flow_instance_id`
- `attempt_count`
- `last_error_type`
- `last_error_message`
- `last_error_stack_trace`
- `failure_disposition` (`AUTO_RETRY`, `USER_RETRIABLE`, `FATAL`)
- `next_retry_at` (nullable)
- `max_attempts` (nullable)
- `last_failed_at`

Why separate storage is recommended:
- avoids breaking every application schema,
- keeps FlowLite mechanics reusable,
- makes Cockpit projection much easier.

## 4. Extend tick scheduling with due-time support

> MG: We have already WaitingForTimer that support due at ticks.

## 5. Keep the stage status model simple

Avoid exploding `StageStatus` into many retry-specific statuses.

Recommended approach:
- keep `Pending`, `Running`, `Completed`, `Cancelled`, `Error`,
- use retry metadata to explain what kind of `Error` it is.

Cockpit can then present:
- `Error / auto retry at 12:05 UTC`
- `Error / user retriable`
- `Error / fatal`

This keeps the engine state machine smaller while still exposing richer behavior.

> MG: Would you keep one Error even when you would design the engine from scratch?

## Definition of done for a future implementation task

- transient failures can retry automatically with backoff,
- user-retriable failures stay visible and actionable,
- fatal failures are clearly distinguished,
- retry metadata is persisted without forcing domain-schema sprawl,
- timelines explain why a retry happened or why it stopped.
