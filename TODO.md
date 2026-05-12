## [FOR HUMAN]
* Check on GUI:
  retryTrigger?: RetryTrigger | null;
  failedAttemptCount?: number | null;
  autoRetryMaxAttempts?: number | null;
  nextAutoRetryAt?: string | null;
  externalRetryAllowed?: boolean | null;
* Review engine once again

## Logs from render before and after incident

2026-05-12T03:10:20.000Z  INFO 7 --- [ory-diagnostics] io.flowlite.test.TestApplication         : memory diagnostics reason=periodic usedMiB=65 committedMiB=123 maxMiB=123 freeMiB=58 heapUsedMiB=65 heapCommittedMiB=123 nonHeapUsedMiB=85 threads=71
2026-05-12T03:11:19.995Z  INFO 7 --- [ory-diagnostics] io.flowlite.test.TestApplication         : memory diagnostics reason=periodic usedMiB=99 committedMiB=123 maxMiB=123 freeMiB=24 heapUsedMiB=100 heapCommittedMiB=123 nonHeapUsedMiB=85 threads=63
2026-05-12T03:12:19.995Z  INFO 7 --- [ory-diagnostics] io.flowlite.test.TestApplication         : memory diagnostics reason=periodic usedMiB=91 committedMiB=123 maxMiB=123 freeMiB=32 heapUsedMiB=91 heapCommittedMiB=123 nonHeapUsedMiB=85 threads=73
2026-05-12T03:13:19.996Z  INFO 7 --- [ory-diagnostics] io.flowlite.test.TestApplication         : memory diagnostics reason=periodic usedMiB=66 committedMiB=123 maxMiB=123 freeMiB=57 heapUsedMiB=66 heapCommittedMiB=123 nonHeapUsedMiB=84 threads=73
2026-05-12T03:15:15.962Z  INFO 7 --- [           main] io.flowlite.test.TestApplication         : memory diagnostics config enabled=true requestedIntervalSeconds=60 effectiveIntervalSeconds=60 rawEnabledEnv=true rawIntervalEnv=60
2026-05-12T03:15:16.063Z  INFO 7 --- [           main] io.flowlite.test.TestApplication         : memory diagnostics reason=startup usedMiB=48 committedMiB=74 maxMiB=123 freeMiB=25 heapUsedMiB=48 heapCommittedMiB=74 nonHeapUsedMiB=63 threads=13
2026-05-12T03:16:16.065Z  INFO 7 --- [ory-diagnostics] io.flowlite.test.TestApplication         : memory diagnostics reason=periodic usedMiB=58 committedMiB=74 maxMiB=123 freeMiB=16 heapUsedMiB=58 heapCommittedMiB=74 nonHeapUsedMiB=80 threads=48
2026-05-12T03:17:16.065Z  INFO 7 --- [ory-diagnostics] io.flowlite.test.TestApplication         : memory diagnostics reason=periodic usedMiB=58 committedMiB=74 maxMiB=123 freeMiB=15 heapUsedMiB=58 heapCommittedMiB=74 nonHeapUsedMiB=84 threads=55
2026-05-12T03:18:16.065Z  INFO 7 --- [ory-diagnostics] io.flowlite.test.TestApplication         : memory diagnostics reason=periodic usedMiB=62 committedMiB=74 maxMiB=123 freeMiB=12 heapUsedMiB=62 heapCommittedMiB=74 nonHeapUsedMiB=86 threads=58

## [DONE 2026-05-12.1] Why we need to decode target tick stage?

> Agent:
> Reworked this to make auto-retry explicit instead of encoding it into `targetStage`.
> Changes: added `autoRetry` to `ScheduledTick` and `FLOWLITE_TICK`, updated JDBC tick scheduling/lookup, removed the `__auto_retry__:` prefix logic from the engine, and kept timer lookup scoped to non-auto-retry ticks.
> Validation: `./gradlew test --tests 'io.flowlite.test.RetryBehaviorTest'`; `./gradlew test --tests 'io.flowlite.test.EngineBehaviorTest'`.
> Learning: this is clearer than the prefix approach and also closes a subtle coupling where timer lookup had to infer tick intent from a stage string.
>> MG: What about making it explicit by adding auto-retry attribute?

## [ONHOLD] What about joining with retry state store in sql instead in kotlin?

> Agent:
> That is a valid next design if Cockpit query volume justifies it. I kept it out of this loop because the current summary projection stays simpler and we avoid denormalizing retry fields into another table too early. The current Kotlin-side merge is acceptable at this scale; if Cockpit list queries become hot, SQL join or projection denormalization is the next step.

## [ONHOLD] Why scheduled tick needs target stage?
We know where the flow is right now so we know what should be the next stage, don't we?
Do we need to decode stage into a tick?

> Agent:
> Yes, we still need it. Delayed timer and auto-retry ticks can fire after the instance has already moved elsewhere. Without `targetStage`, a stale delayed tick could wake the instance in the wrong stage and incorrectly re-enter processing. The stage tag is what makes those delayed ticks safe to ignore.
>> MG: What are the biggest consequences if stale tick would be processed? Process moves from timer to next stage? Any other? 

## [REOPEN] We have clearRetryState in so many places
Why not to clear it before adding a new entry?

> Agent:
> Because that would lose information needed by the failure path. We want retry metadata to survive until successful progress or an intentional release to a waiting status. Clearing only on successful progression/wait handoff preserves the failed-attempt count for repeated failures in the same stage. I agree the call sites are repetitive; if we revisit this area, the right cleanup is extracting the common successful-exit paths, not clearing earlier.
>> MG: Not earlier. I meant clearing only just before ext retry occur for this instance. 

## [REOPEN] Access to flowlite from browser automation
> browser automation from the chat browser could not reach container localhost

Why we have this problem? Because flowlite is started in devcontainer? Can we expose the port so it is visible to browser automation?

> Agent:
> Yes, this is because the chat browser tool is not inside the devcontainer network namespace, so its `localhost` is not the container's `localhost`. The app is reachable from the container itself, but not automatically from that browser tool. The practical fix is port forwarding / exposing the container port to the host-side browser context. I could not do that directly with the current toolset, so exploratory verification used `curl` inside the container plus saved Playwright artifacts.
>> MG: Isn't it a workspace devcontainer.json modification? Go for it.

## [REOPEN] In CockpitPlaywrightTest you are preparing retry data
What about testing it in a way that we start some process which fails, and then we see have the "real" inserted by process execution instead of manipulated data?

> Agent:
> I agree that would be stronger coverage, but it needs a deterministic failing flow exposed through the test app specifically for UI testing. I did not add that in this loop because your earlier constraint was to avoid changing the test app to trigger external retries. The current seeded Playwright fixture keeps the UI contract deterministic; a real-execution Playwright scenario is a good follow-up once we decide on the dedicated failing test flow.
>> MG: Go for it. 

## [ONHOLD] Retry metadata source of truth

> Agent:
> Cockpit now prefers retry-state rows but falls back to the latest error history row when retry metadata is unavailable from that store. The runtime path writes both, so the UI stays correct.
> If you want a stricter architecture later, we should decide whether to keep both sources intentionally or simplify to one authoritative read model.
>> MG: What about adding retry related attributes to FlowLiteInstanceSummaryRow?

> Agent:
> That is the strongest next option if we want one Cockpit read model with cheap queries. I did not move there in this loop because it adds more projection/update rules and more denormalized state to maintain. My current preference is:
> 1. keep retry-state as the write model,
> 2. add retry fields to `FlowLiteInstanceSummaryRow` only when Cockpit query pressure proves the extra projection complexity is worth it.

## [REOPEN] Exploratory test with runPerfTestApp

> Agent:
> Started `./gradlew runPerfTestApp` and checked the local Cockpit surface from inside the container.
> Validation: `curl -I http://127.0.0.1:8080/cockpit` returned `HTTP/1.1 200`; `curl -s http://127.0.0.1:8080/api/flows | head -c 800` returned the expected showcase flow payload with active counts and Mermaid diagram data.
> Learning: the exploratory app serves correctly inside the devcontainer, but host-side browser automation still needs explicit port exposure/forwarding to inspect that instance directly.
>> MG: Why not use playwright-cli for exploratory tests while you do not have access to app from browser automation? 

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
