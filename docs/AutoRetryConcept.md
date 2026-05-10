# Auto-retry and external-retry concept

## Goal

Introduce a retry model that distinguishes between:
1. failures that FlowLite should retry automatically,
2. failures that an application-specific actor may retry outside Cockpit,
3. failures that should stay as plain `Error` and only be recoverable from Cockpit.

Cockpit retry remains the universal fallback. The extra question is whether a failure also gets automatic retry behavior and/or an application-owned retry path.

## Terminology

The earlier phrase "user-retriable" is too vague. The actor might be:
- an end user in a product UI,
- an internal operator in an application-specific admin UI,
- another application endpoint that becomes available only after some business fix.

For the concept itself, `external-retry` is the clearer term:
- `external-retry` means retry initiated outside FlowLite Cockpit through application-owned UI/API,
- `cockpit-retry` means retry initiated directly from Cockpit,
- `auto-retry` means retry scheduled by the engine itself.

## What Needed Tightening

The previous draft had the right direction but needed a few corrections.

### 1. It used an outdated status model

FlowLite no longer has a generic `Pending` status. The current engine state model is:
- `PendingEngine`
- `WaitingForEvent`
- `WaitingForTimer`
- `Running`
- `Error`
- `Completed`
- `Cancelled`

The retry concept should build on that model instead of reintroducing older state names.

### 2. It mixed execution state with recovery policy

Retryability is not the same thing as execution state.

`StageStatus` should keep describing where the engine is operationally. Retry policy should describe what to do after a failure. Those are related, but they are not the same dimension.

### 3. It treated scheduler support as missing

FlowLite already supports delayed work through scheduled ticks and `WaitingForTimer`. Auto-retry should reuse that mechanism instead of inventing another scheduling concept.

### 4. It did not define a realistic MVP boundary

The previous version described a fairly complete target model, but it did not separate:
- what is required to get value quickly,
- what should wait for a later iteration.

That matters here because FlowLite already has manual retry through Cockpit. The smallest valuable step is therefore auto-retry, not the whole final feature set.

### 5. It risked duplicating too much error data

History already stores detailed error information, including stack trace. A retry-specific store should keep only the current retry state needed by engine logic and Cockpit summary. It should not become a second history table.

## Recommendation

Keep the design hybrid:
- FlowLite core owns retry mechanics.
- Applications own retry policy.

That split still looks correct after reviewing the current engine code.

Why mechanics belong in core:
- delayed retry scheduling is an engine concern,
- attempt counting is an engine concern,
- visibility in Cockpit is an engine/read-model concern,
- transition back from `Error` to a runnable waiting status is an engine concern.

Why policy belongs in the application:
- only the application knows whether a failure is transient,
- only the application knows whether a failure can be fixed externally,
- only the application knows whether retrying is safe for a particular exception and stage.

## Keep One `Error`

Yes, even from scratch I would still keep a single `Error` status.

Reason:
- `Error` says the engine failed to progress the current stage.
- Auto-retry vs external-retry vs cockpit-only retry says how recovery may happen.

Those are different concerns. If we turn them into different stage statuses, the state machine becomes noisier and leaks recovery policy into places that should only care about execution lifecycle.

So the recommended model is:
- keep one `Error`,
- store retry metadata separately,
- let Cockpit render richer labels from the metadata.

Examples in Cockpit:
- `Error / auto retry at 12:05 UTC`
- `Error / external retry allowed`
- `Error / cockpit only`

## Target Model

### 1. Introduce a failure classification hook

The engine should not hardcode which exceptions are transient.

```kotlin
interface FailureClassifier<T : Any> {
    fun classify(
        context: ActionContext,
        stage: Stage,
        state: T,
        error: Exception,
        failedAttemptCount: Int,
    ): FailureDirective
}
```

`failedAttemptCount` should mean attempts for the current failing stage since the last successful progress, not total lifetime retries across the whole instance.

### 2. Use directives that describe recovery policy, not engine state

```kotlin
sealed interface FailureDirective {
    data class AutoRetry(
        val delay: Duration,
        val maxAttempts: Int,
        val backoff: BackoffStrategy = BackoffStrategy.fixed(delay),
        val onExhausted: ExhaustedAutoRetryPolicy = ExhaustedAutoRetryPolicy.CockpitOnly,
    ) : FailureDirective

    data object ExternalRetry : FailureDirective

    data object CockpitOnly : FailureDirective
}

enum class ExhaustedAutoRetryPolicy {
    ExternalRetry,
    CockpitOnly,
}
```

This is clearer than `Fatal` for the current FlowLite model.

Why:
- FlowLite already allows Cockpit retry for any failed instance.
- So a failure is rarely truly "fatal" in the sense of "cannot ever be retried".
- The meaningful distinction is whether recovery is automatic, externally initiated, or only available from Cockpit.

### 3. Persist retry metadata in an engine-owned store

Do not force every application persistence model to grow retry columns.

Recommended engine-owned state per `(flowId, flowInstanceId)`:
- `attempt_count`
- `next_retry_at`
- `last_failed_at`
- `retry_mode` (`AUTO_RETRY`, `EXTERNAL_RETRY`, `COCKPIT_ONLY`)
- `max_attempts`
- `last_error_type`

Notes:
- `last_error_message` can live here if Cockpit needs cheap access.
- full stack traces should stay in history, not be duplicated here.
- this state should be reset when the instance successfully progresses beyond the failed stage.

### 4. Reuse the existing delayed tick mechanism

No new scheduler abstraction is needed.

For auto-retry:
- keep the instance in `Error`,
- persist retry metadata with `next_retry_at`,
- schedule a delayed tick for that instant,
- when the delayed tick fires, transition through the same runtime path as manual `retry(...)`.

This aligns with FlowLite as it exists today.

### 5. Avoid rethrowing once the engine has accepted an auto-retry plan

This is an important operational detail.

Today, a stage exception is persisted as `Error`, recorded in history, and then rethrown. That is fine for plain failure handling, but it is noisy for accepted auto-retry.

If a failure is classified as `AutoRetry`, the engine should:
- persist the failure and retry plan,
- schedule the delayed retry,
- finish the current tick cleanly without rethrowing the exception.

Otherwise the scheduler layer will log the same transient failure as a hard tick failure even though the engine deliberately accepted it and scheduled recovery.

## Recommended MVP

The MVP should be intentionally smaller than the full target model.

### Scope

Implement only:
- `AutoRetry`
- existing Cockpit retry fallback

Do not implement yet:
- external/application-owned retry endpoints,
- special Cockpit filters for retry modes,
- a broad matrix of retry policies.

### Why this is the right MVP

FlowLite already has Cockpit retry. That means the first missing capability is automatic retry of transient failures.

Adding external-retry in the same batch would require:
- additional public API design,
- authorization and actor semantics in the client application,
- more Cockpit/read-model language,
- more questions than value for the first increment.

### MVP implementation steps

1. Add an optional `FailureClassifier` registration per flow.
2. Add a small engine-owned retry metadata store/table.
3. In the action/timer failure path, classify the exception.
4. If directive is `AutoRetry` and attempts remain:
   - persist `Error`,
   - persist retry metadata,
   - schedule delayed tick,
   - do not rethrow.
5. If attempts are exhausted or there is no classifier:
   - keep the current `Error` behavior,
   - leave recovery to existing Cockpit retry.
6. Extend Cockpit summary/details to show:
   - current retry attempt count,
   - next retry time when scheduled,
   - whether auto-retry is still active.
7. Reset retry metadata after successful progress from the failed stage.

### MVP behavior example

Example policy:
- `SocketTimeoutException` in stage `SendContractForSigning` -> auto retry up to 3 times with exponential backoff,
- anything else -> plain `Error` recoverable from Cockpit.

From a user perspective:
- transient failures recover automatically,
- exhausted transient failures end as normal `Error`,
- operators can still use Cockpit retry exactly as today.

## Later Extension: External Retry

After MVP, FlowLite can add explicit `ExternalRetry` support.

That phase would mean:
- classifier may mark a failure as externally retriable,
- retry metadata exposes that fact to Cockpit/API,
- client applications may build their own retry UI/API on top,
- Cockpit still keeps manual retry as the universal escape hatch.

This later phase should be designed only after the MVP proves that the engine-owned retry metadata and auto-retry flow feel right operationally.

## Definition of Done for the MVP

- transient failures can be auto-retried with backoff,
- failed auto-retries stop after configured attempt limit,
- exhausted retries remain visible as normal `Error`,
- Cockpit retry still works for exhausted or non-auto-retriable failures,
- retry metadata is stored outside application domain tables,
- Cockpit can show whether an error is waiting for automatic retry and when it will happen,
- history continues to explain the original error and later retry actions.