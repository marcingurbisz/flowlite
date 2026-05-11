## [FOR HUMAN]
* Check on GUI:
  retryTrigger?: RetryTrigger | null;
  failedAttemptCount?: number | null;
  autoRetryMaxAttempts?: number | null;
  nextAutoRetryAt?: string | null;
  externalRetryAllowed?: boolean | null;
* Review engine once again

## [DONE 2026-05-11.1] Retry feedback follow-up

> Agent:
> Picked this loop item to reconcile the follow-up remarks around retry UX and the engine shape.
> Planned scope: polish Cockpit retry presentation, remove unused retry API surface, answer the engine/design questions inline below, then do the requested exploratory test at the end.
> Changes: kept retry badges inline with `Error`, shortened labels to `AutoRetry` and `ExternallyRetryable`, simplified `Retried` details to the retry trigger, removed unused `BackoffStrategy.exponential(...)`, and answered the follow-up engine/render questions inline below.
> Validation: `./gradlew test --tests 'io.flowlite.test.CockpitPlaywrightTest'`, `./gradlew test --tests 'io.flowlite.test.RetryBehaviorTest'`, `./gradlew test --tests 'io.flowlite.test.CockpitServiceTest'`.
> Learning: the current retry shape is still coherent as `Error` + separate retry metadata; the main remaining trade-off is whether Cockpit should eventually denormalize retry data into the instance summary projection.

## [FOR HUMAN 2026-05-10.1] On render I have now this log
memory diagnostics config enabled=false requestedIntervalSeconds=60 effectiveIntervalSeconds=60 rawEnabledEnv=null rawIntervalEnv=null

> Agent:
> `rawEnabledEnv=null` and `rawIntervalEnv=null` mean the Render runtime process still does not receive those environment variables.
> Changes: no further runtime code changes were needed for this item because the diagnostics now expose the root cause clearly.
> Validation: reviewed the emitted log and previous local verification that Spring binding works when env vars are actually present.
> Learning: this is now a deployment/configuration issue on Render, not a code-path or property-binding issue in FlowLite.

> MG: So how to solve this? Maybe consult render documentation?

> Agent:
> Yes. Render docs confirm two important details:
> 1. Blueprint env vars must exist under the service `envVars` or a linked env group.
> 2. Dashboard env-var changes only affect runtime after a deploy; `Save only` does not redeploy.
> Practical fix: verify the FlowLite service actually defines these vars in Render for the active environment, then trigger `Save and deploy` or `Save, rebuild, and deploy`. If the values are meant to come from `render.yaml`, ensure they are under that service's `envVars` section and that the Blueprint sync was applied to the existing service. If they are secrets defined with `sync: false`, Render will not backfill them on Blueprint updates, so they must be added manually in the Dashboard.
> Additional check: confirmed that [render.yaml](/workspaces/workplace/flowlite/render.yaml) in this repo already defines both `FLOWLITE_DIAGNOSTICS_MEMORY_LOG_ENABLED` and `FLOWLITE_DIAGNOSTICS_MEMORY_LOG_INTERVAL_SECONDS` under the `flowlite-test-instance` service.
> Conclusion: the remaining fix is outside the repo. Most likely the existing Render service has not picked up the Blueprint env vars for the active environment yet, or it needs an explicit sync/redeploy from the Dashboard.

## Why from and to status for Retried?
case 'Retried':
      return [
        event.fromStatus || event.toStatus
          ? `status: ${event.fromStatus ?? '—'} → ${event.toStatus ?? '—'}`
Retry is always from ERROR to some Pending. Is it worth to show to which status?

> Agent:
> I do not think so for Cockpit. For retry history the useful bit is the trigger (`Auto`/`External`/`Cockpit`), not `Error -> PendingEngine|WaitingFor*`.
> Changes: simplified the Cockpit `Retried` history details to show only `trigger=...`.

## Can we register classifier per engine for all flows?

> Agent:
> We can, but I would not add it in this loop. A global default classifier is easy technically, but it weakens the current explicitness: retry policy is usually stage/domain-specific. If we add it later, I would make it an optional engine-level fallback used only when a flow-level classifier is absent.

## Why we need to decode target tick stage?

> Agent:
> Because auto-retry ticks overload the existing `targetStage` field with a prefix marker. The runtime still needs the original stage name to decide whether the delayed tick is stale. `decodeTickTargetStage(...)` is only the small adapter that strips the auto-retry prefix before comparing with the current stage.

## What about joining with retry state store in sql instead in kotlin?

> Agent:
> That is a valid next design if Cockpit query volume justifies it. I kept it out of this loop because the current summary projection stays simpler and we avoid denormalizing retry fields into another table too early. The current Kotlin-side merge is acceptable at this scale; if Cockpit list queries become hot, SQL join or projection denormalization is the next step.

## Why scheduled tick needs target stage?
We know where the flow is right now so we know what should be the next stage, don't we?
Do we need to decode stage into a tick?

> Agent:
> Yes, we still need it. Delayed timer and auto-retry ticks can fire after the instance has already moved elsewhere. Without `targetStage`, a stale delayed tick could wake the instance in the wrong stage and incorrectly re-enter processing. The stage tag is what makes those delayed ticks safe to ignore.

## We have clearRetryState in so many places
Why not to clear it before adding a new entry?

> Agent:
> Because that would lose information needed by the failure path. We want retry metadata to survive until successful progress or an intentional release to a waiting status. Clearing only on successful progression/wait handoff preserves the failed-attempt count for repeated failures in the same stage. I agree the call sites are repetitive; if we revisit this area, the right cleanup is extracting the common successful-exit paths, not clearing earlier.

## Access to flowlite from browser automation
> browser automation from the chat browser could not reach container localhost

Why we have this problem? Because flowlite is started in devcontainer? Can we expose the port so it is visible to browser automation?

> Agent:
> Yes, this is because the chat browser tool is not inside the devcontainer network namespace, so its `localhost` is not the container's `localhost`. The app is reachable from the container itself, but not automatically from that browser tool. The practical fix is port forwarding / exposing the container port to the host-side browser context. I could not do that directly with the current toolset, so exploratory verification used `curl` inside the container plus saved Playwright artifacts.

## Feedback 3
* GUI:
  * what about keeping badges in the same line with Error and using shorter labels AutoRetry and ExternalRetryAllowed?
  * ExternalRetryAllowed -> ExternallyRetryable/ExternalRetryPossible ?
* do we keep all retries in history or only the last one?
* Remove exponential from backoff strategy

> Agent:
> Changes: moved retry badges inline with the `Error` badge, shortened labels to `AutoRetry` and `ExternallyRetryable`, and removed the unused `BackoffStrategy.exponential(...)` helper.
> We keep all retry events in history, not just the last one. That preserves the real operational timeline.

## In CockpitPlaywrightTest you are preparing retry data
What about testing it in a way that we start some process which fails, and then we see have the "real" inserted by process execution instead of manipulated data?

> Agent:
> I agree that would be stronger coverage, but it needs a deterministic failing flow exposed through the test app specifically for UI testing. I did not add that in this loop because your earlier constraint was to avoid changing the test app to trigger external retries. The current seeded Playwright fixture keeps the UI contract deterministic; a real-execution Playwright scenario is a good follow-up once we decide on the dedicated failing test flow.

## Retry metadata source of truth

> Agent:
> Cockpit now prefers retry-state rows but falls back to the latest error history row when retry metadata is unavailable from that store. The runtime path writes both, so the UI stays correct.
> If you want a stricter architecture later, we should decide whether to keep both sources intentionally or simplify to one authoritative read model.
>> MG: What about adding retry related attributes to FlowLiteInstanceSummaryRow?

> Agent:
> That is the strongest next option if we want one Cockpit read model with cheap queries. I did not move there in this loop because it adds more projection/update rules and more denormalized state to maintain. My current preference is:
> 1. keep retry-state as the write model,
> 2. add retry fields to `FlowLiteInstanceSummaryRow` only when Cockpit query pressure proves the extra projection complexity is worth it.

## [DONE 2026-05-11.1] Exploratory test with runPerfTestApp

> Agent:
> Started `./gradlew runPerfTestApp` and checked the local Cockpit surface from inside the container.
> Validation: `curl -I http://127.0.0.1:8080/cockpit` returned `HTTP/1.1 200`; `curl -s http://127.0.0.1:8080/api/flows | head -c 800` returned the expected showcase flow payload with active counts and Mermaid diagram data.
> Learning: the exploratory app serves correctly inside the devcontainer, but host-side browser automation still needs explicit port exposure/forwarding to inspect that instance directly.

## [DONE 2026-05-11.1] Self-review of current loop

> Agent:
> Reviewed the focused diff for `badges.tsx`, `utils.ts`, `CockpitPlaywrightTest.kt`, `persistance.kt`, and this TODO ledger.
> Validation: checked the diff for unnecessary complexity, missing test updates, and mismatches between the UI wording and the assertions.
> Learning: no new in-scope issues found beyond the already documented future consideration of a real-execution retry scenario for Cockpit Playwright coverage.

## [DONE 2026-05-11.2] Self-review of Render item closure

> Agent:
> Reviewed the follow-up change that reclassified the remaining Render diagnostics item from repo work to human-side deployment work.
> Validation: confirmed [render.yaml](/workspaces/workplace/flowlite/render.yaml) already contains the two diagnostics env vars and that no non-`DONE`/non-`FOR HUMAN` items remain in this TODO ledger.
> Learning: the earlier stop was wrong because one item still looked open in the ledger even though the repo-side investigation was already complete.

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
