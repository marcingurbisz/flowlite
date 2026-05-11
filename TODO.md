## [FOR HUMAN]
* Check on GUI:
  retryTrigger?: RetryTrigger | null;
  failedAttemptCount?: number | null;
  autoRetryMaxAttempts?: number | null;
  nextAutoRetryAt?: string | null;
  externalRetryAllowed?: boolean | null;
* Review engine once again

## [REOPEN 2026-05-10.1] On render I have now this log
memory diagnostics config enabled=false requestedIntervalSeconds=60 effectiveIntervalSeconds=60 rawEnabledEnv=null rawIntervalEnv=null

> Agent:
> `rawEnabledEnv=null` and `rawIntervalEnv=null` mean the Render runtime process still does not receive those environment variables.
> Changes: no further runtime code changes were needed for this item because the diagnostics now expose the root cause clearly.
> Validation: reviewed the emitted log and previous local verification that Spring binding works when env vars are actually present.
> Learning: this is now a deployment/configuration issue on Render, not a code-path or property-binding issue in FlowLite.

> MG: So how to solve this? Maybe consult render documentation?

## Why from and to status for Retried?
case 'Retried':
      return [
        event.fromStatus || event.toStatus
          ? `status: ${event.fromStatus ?? '—'} → ${event.toStatus ?? '—'}`
Retry is always from ERROR to some Pending. Is it worth to show to which status?

## Can we register classifier per engine for all flows?

## Why we need to decode target tick stage?

## What about joining with retry state store in sql instead in kotlin?

## Why scheduled tick needs target stage?
We know where the flow is right now so we know what should be the next stage, don't we?
Do we need to decode stage into a tick?

## We have clearRetryState in so many places
Why not to clear it before adding a new entry?

## Access to flowlite from browser automation
> browser automation from the chat browser could not reach container localhost

Why we have this problem? Because flowlite is started in devcontainer? Can we expose the port so it is visible to browser automation?

## Feedback 3
* GUI:
  * what about keeping badges in the same line with Error and using shorter labels AutoRetry and ExternalRetryAllowed?
  * ExternalRetryAllowed -> ExternallyRetryable/ExternalRetryPossible ?
* do we keep all retries in history or only the last one?
* Remove exponential from backoff strategy

## In CockpitPlaywrightTest you are preparing retry data
What about testing it in a way that we start some process which fails, and then we see have the "real" inserted by process execution instead of manipulated data?

## Retry metadata source of truth

> Agent:
> Cockpit now prefers retry-state rows but falls back to the latest error history row when retry metadata is unavailable from that store. The runtime path writes both, so the UI stays correct.
> If you want a stricter architecture later, we should decide whether to keep both sources intentionally or simplify to one authoritative read model.
>> MG: What about adding retry related attributes to FlowLiteInstanceSummaryRow?

## [FOR HUMAN] Review git changes
* Review perf(engine): raise tick worker default 3/25/26, 9:13 AM marcingurbisz
* loop starting from 75abfec728b2184501ae4ebae0c83c07f1e0ba23
* loops starting from
  * docs: TODO update marcingurbisz 3/8/26, 9:12 AM
  * docs: update TODO marcingurbisz 3/7/26, 3:57 PM
  * docs: update TODO marcingurbisz 3/6/26, 7:13 AM

## [ON HOLD] Topics on hold for now
* Check sonar findings and decide if they should be fixed
* Visual testing - comparing screenshots before and after changes? Not sure about it because when agent can
see produced image maybe this be enough for visual inspection and comparing will not be needed?
* Check coverage and suggest modifications/new tests to cover it
* The GWT cleanup showed that the Cockpit Playwright spec now needs a small `RecordedPageSession` helper to keep browser setup/actions in `when` blocks while preserving failure screenshots/videos. If we add more browser scenarios, it may be worth introducing a tiny test DSL/helper layer for `open page -> act -> assert -> close` flows so future specs do not repeat the same session lifecycle/synchronization plumbing.
* Websocket for live refresh
