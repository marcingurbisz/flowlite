# Auto-retry and user-retriable concept

## Goal

Introduce a retry model that can distinguish between:
- failures that should be retried automatically,
- failures that should be retried manually by a user from Cockpit,
- failures that should stop immediately as non-retriable/fatal.

The design should improve Cockpit visibility without pushing business-specific retry rules into the UI layer.

## Current state

Today FlowLite has one generic failure path:
- any exception during action processing moves the instance to `Error`,
- the engine stops processing,
- the only recovery path is manual `retry(...)`.

This is intentionally simple, but it mixes very different failure classes into one bucket:
- transient technical failures (`HTTP 503`, timeout, deadlock, temporary downstream outage),
- user-fixable/business failures (missing data, waiting for correction, external mismatch),
- fatal programming/configuration failures.

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
        val code: String? = null,
        val message: String? = null,
        val allowChangeStage: Boolean = true,
    ) : FailureDirective

    data class Fatal(
        val code: String? = null,
        val message: String? = null,
    ) : FailureDirective
}
```

Important point: the action still throws, but the engine asks a classifier what the thrown exception means.

## 2. Add a failure classifier hook

Recommended configuration point:
- flow-level default classifier,
- optional stage-level override.

Example shape:

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

Why this is better than encoding retry behavior directly into actions:
- actions stay business-focused,
- retry policy remains centralized,
- Cockpit can rely on consistent semantics.

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

Auto-retry needs delayed execution, not immediate requeue only.

Recommended engine enhancement:
- extend tick scheduling to support `notBefore` / `scheduledAt`.

Conceptually:

```kotlin
interface TickScheduler {
    fun setTickHandler(handler: (String, UUID) -> Unit)
    fun scheduleTick(flowId: String, flowInstanceId: UUID, notBefore: Instant = Instant.now())
}
```

For Spring Data JDBC this likely means extending the tick table with a due-time column and polling only due rows.

This is better than building a second retry-specific scheduler because:
- retries are still just “work to do later”,
- the tick mechanism already owns wake-up semantics.

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

## Cockpit implications

Cockpit should show more than just `Error`.

Recommended additions:
- error/retry disposition badge:
  - `Auto retry`
  - `User retriable`
  - `Fatal`
- attempt counter (`attempt 2/5`)
- next retry time/countdown for auto-retry
- explicit user actions only when allowed:
  - `Retry now`
  - `Change stage`
  - maybe `Disable auto retry` later if needed

For the `Errors` tab this means grouping/filtering should eventually support retry disposition.

## Recommended behavior rules

### Auto-retry

Use for transient technical failures.

Examples:
- HTTP `429` / `503`
- connection timeout
- deadlock / optimistic-lock temporary conflict

Behavior:
- increment attempt counter,
- classify as `AUTO_RETRY`,
- store `next_retry_at`,
- record history entry,
- schedule delayed tick,
- keep visible in Cockpit as an error that is already being retried automatically.

When max attempts are exhausted:
- either transition to `UserRetriable`, or
- transition to `Fatal`,
- this choice should be classifier-controlled.

### User-retriable

Use when human intervention may fix the problem.

Examples:
- missing document uploaded by a user,
- mismatched data in an external system,
- stage should be retried only after a support agent checks something.

Behavior:
- stay in `Error`,
- expose retry/change-stage actions in Cockpit,
- do not reschedule automatically.

### Fatal

Use when retry is pointless or dangerous.

Examples:
- programming bug,
- invalid flow definition,
- missing required configuration.

Behavior:
- stay in `Error`,
- optionally hide `Retry now` from Cockpit,
- emphasize that operator escalation is required.

## History model recommendation

Add explicit history entries rather than overloading generic `Error` only.

Useful future entries:
- `AutoRetryScheduled`
- `AutoRetryAttempted`
- `AutoRetryExhausted`
- `UserRetriableMarked`
- `FatalMarked`

This keeps Cockpit timelines explainable.

## Suggested rollout

### Phase 1 — concept + storage foundation

1. Decide retry metadata storage shape.
2. Extend tick scheduling with due-time support.
3. Add history entry types for retry orchestration events.

### Phase 2 — engine policy hooks

1. Add failure-classifier API.
2. Support `AutoRetry`, `UserRetriable`, and `Fatal` directives.
3. Keep current manual `retry(...)` API as-is for user retriable cases.

### Phase 3 — cockpit visibility

1. Show retry disposition/attempts/next retry time.
2. Filter errors by retry disposition.
3. Expose only the actions allowed by the directive.

## Decision summary

- **Do not** implement auto-retry entirely in app-specific actions.
- **Do not** force every app schema to own retry metadata.
- **Do** let the application classify failures.
- **Do** let the core engine own delayed scheduling, retry bookkeeping, and cockpit-facing semantics.

## Definition of done for a future implementation task

- transient failures can retry automatically with backoff,
- user-retriable failures stay visible and actionable in Cockpit,
- fatal failures are clearly distinguished,
- retry metadata is persisted without forcing domain-schema sprawl,
- timelines explain why a retry happened or why it stopped.
