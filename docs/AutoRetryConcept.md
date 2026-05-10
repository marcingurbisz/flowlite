# Auto-retry and external-retry concept

## Goal

Keep one runtime `Error` status while making recovery intent explicit and visible.

The implemented model distinguishes three retry triggers:
- `auto-retry` scheduled by the engine,
- `external-retry` initiated outside Cockpit by application-owned code,
- `cockpit-retry` initiated directly from Cockpit.

These are recovery triggers, not separate stage statuses.

## Core decision

We keep one `Error`.

`Error` answers whether the engine failed to progress the current stage.
Retry metadata answers how recovery may happen:
- whether external retry is allowed,
- whether auto retry is active,
- how many attempts already failed,
- which actor or mechanism triggered a retry.

That separation turned out to be enough for both runtime behavior and Cockpit visibility, without inflating `StageStatus`.

## What was implemented

### Failure classification stays application-owned

Flows can now register an optional `FailureClassifier<T>`.

```kotlin
interface FailureClassifier<T : Any> {
    fun classify(
        context: ActionContext,
        stage: Stage,
        state: T,
        error: Exception,
        failedAttemptCount: Int,
    ): FailureHandling
}

data class FailureHandling(
    val autoRetry: AutoRetryPlan? = null,
    val externalRetryAllowed: Boolean = false,
)
```

This keeps retry policy in the application while leaving retry mechanics in FlowLite.

### Engine-owned retry metadata

Retry metadata is stored outside application domain tables.

Current retry metadata includes:
- failing stage,
- failed attempt count,
- `externalRetryAllowed`,
- optional `autoRetryMaxAttempts`,
- optional `nextAutoRetryAt`,
- last error type/message.

This metadata is persisted in a dedicated retry-state store and also copied into `HistoryEntry.Error`, so Cockpit can explain the current error state from history.

### Auto retry reuses delayed ticks

No new scheduler abstraction was introduced.

Auto retry works by:
- keeping the instance in `Error`,
- persisting retry metadata,
- scheduling a delayed auto-retry tick,
- converting that tick back into the normal retry path when due.

This keeps the runtime model small and reuses existing engine machinery.

### Separate retry methods were worth it

Yes, the engine now has two explicit retry entry points:
- `retry(flowId, flowInstanceId)` for Cockpit retry,
- `externalRetry(flowId, flowInstanceId)` for application-owned retry.

That gives truthful history through `RetryTrigger`:
- `Auto`
- `External`
- `Cockpit`

So the answer to the inline question is yes: separate retry methods are useful because they preserve retry provenance in history without adding new statuses.

### Auto-retried failures still stay in logs

The engine still logs the original exception with `log.error(ex)` before persisting retry metadata and scheduling auto retry.

So the answer to the logging remark is also yes: failures remain visible in logs even when recovery is accepted and scheduled.

## Cockpit UX

The implemented Cockpit surface shows the two requested badges next to errored instances:
- `External retry allowed`
- `Auto retry`

Where shown:
- Instances table
- Instance details modal

The instance details modal also shows:
- failed attempt count,
- next auto retry timestamp,
- retry-aware history details.

History now distinguishes retry sources, for example `trigger=External`.

## MVP scope

The MVP now includes:
- auto retry,
- external retry support in the engine,
- Cockpit retry as the universal fallback,
- Cockpit visibility for retry metadata.

The MVP still does not include:
- new test-app UI/API affordances for external retry,
- Cockpit filters dedicated to retry modes,
- authorization semantics for application-owned retry callers.

That matches the requested scope: external retry exists in the engine and history model, but the test app was not changed to expose new external-retry triggers.

## Definition of done

- transient failures can schedule auto retry with backoff,
- auto-retried failures keep a visible error history entry and stay logged,
- external retry can be triggered separately from Cockpit retry,
- history distinguishes `Auto`, `External`, and `Cockpit` retries,
- Cockpit shows retry badges and retry details for errored instances,
- retry metadata lives outside application domain tables,
- tests cover engine behavior, Cockpit service projection, and Playwright UI rendering.