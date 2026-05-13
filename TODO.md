## [FOR HUMAN]
* Check on GUI:
  retryTrigger?: RetryTrigger | null;
  failedAttemptCount?: number | null;
  autoRetryMaxAttempts?: number | null;
  nextAutoRetryAt?: string | null;
  externalRetryAllowed?: boolean | null;
* Review engine once again
* Review CockpitPlaywrightTest
* Review ONHOLD and decide

## Logs from render before and after incident
Does the logs below show memory problems before render restarts the app?
If not what can be the other problem that is causing our render instance to stop responding after few/dozen of hours?
2026-05-12T03:10:20.000Z  INFO 7 --- [ory-diagnostics] io.flowlite.test.TestApplication         : memory diagnostics reason=periodic usedMiB=65 committedMiB=123 maxMiB=123 freeMiB=58 heapUsedMiB=65 heapCommittedMiB=123 nonHeapUsedMiB=85 threads=71
2026-05-12T03:11:19.995Z  INFO 7 --- [ory-diagnostics] io.flowlite.test.TestApplication         : memory diagnostics reason=periodic usedMiB=99 committedMiB=123 maxMiB=123 freeMiB=24 heapUsedMiB=100 heapCommittedMiB=123 nonHeapUsedMiB=85 threads=63
2026-05-12T03:12:19.995Z  INFO 7 --- [ory-diagnostics] io.flowlite.test.TestApplication         : memory diagnostics reason=periodic usedMiB=91 committedMiB=123 maxMiB=123 freeMiB=32 heapUsedMiB=91 heapCommittedMiB=123 nonHeapUsedMiB=85 threads=73
2026-05-12T03:13:19.996Z  INFO 7 --- [ory-diagnostics] io.flowlite.test.TestApplication         : memory diagnostics reason=periodic usedMiB=66 committedMiB=123 maxMiB=123 freeMiB=57 heapUsedMiB=66 heapCommittedMiB=123 nonHeapUsedMiB=84 threads=73
2026-05-12T03:15:15.962Z  INFO 7 --- [           main] io.flowlite.test.TestApplication         : memory diagnostics config enabled=true requestedIntervalSeconds=60 effectiveIntervalSeconds=60 rawEnabledEnv=true rawIntervalEnv=60
2026-05-12T03:15:16.063Z  INFO 7 --- [           main] io.flowlite.test.TestApplication         : memory diagnostics reason=startup usedMiB=48 committedMiB=74 maxMiB=123 freeMiB=25 heapUsedMiB=48 heapCommittedMiB=74 nonHeapUsedMiB=63 threads=13
2026-05-12T03:16:16.065Z  INFO 7 --- [ory-diagnostics] io.flowlite.test.TestApplication         : memory diagnostics reason=periodic usedMiB=58 committedMiB=74 maxMiB=123 freeMiB=16 heapUsedMiB=58 heapCommittedMiB=74 nonHeapUsedMiB=80 threads=48
2026-05-12T03:17:16.065Z  INFO 7 --- [ory-diagnostics] io.flowlite.test.TestApplication         : memory diagnostics reason=periodic usedMiB=58 committedMiB=74 maxMiB=123 freeMiB=15 heapUsedMiB=58 heapCommittedMiB=74 nonHeapUsedMiB=84 threads=55
2026-05-12T03:18:16.065Z  INFO 7 --- [ory-diagnostics] io.flowlite.test.TestApplication         : memory diagnostics reason=periodic usedMiB=62 committedMiB=74 maxMiB=123 freeMiB=12 heapUsedMiB=62 heapCommittedMiB=74 nonHeapUsedMiB=86 threads=58

## [DONE 2026-05-13.1] We have clearRetryState in so many places
Why not to clear it before adding a new entry?

> Agent:
> Because that would lose information needed by the failure path. We want retry metadata to survive until successful progress or an intentional release to a waiting status. Clearing only on successful progression/wait handoff preserves the failed-attempt count for repeated failures in the same stage. I agree the call sites are repetitive; if we revisit this area, the right cleanup is extracting the common successful-exit paths, not clearing earlier.
>> MG: Not earlier. I meant clearing only just before ext retry occur for this instance. 

> Agent:
> Still no. The current `RetryStateStore` is a snapshot of the current failure/retry situation, not a history table. If we kept rows until explicit external retry only, then successful normal progress, successful auto-retry, timer waits, and completed instances would keep stale retry rows long after the failure was gone.
> Consequences: the store would stop meaning "instance is currently retry-related" and start meaning "instance once failed", which is not the contract used by the engine. It would also retain obsolete `nextAutoRetryAt` / `externalRetryAllowed` metadata until an unrelated later external retry happens, or forever if it never happens.
> Validation: reviewed the current engine paths that clear retry state on normal progress/wait handoff and the Cockpit read path that treats retry state as current error metadata.
> Learning: if we want fewer `clearRetryState(...)` call sites, the right refactor is centralizing successful-exit paths, not delaying cleanup until external retry.
>> MG: Ok. Try to centralize successful-exit path.

> Agent:
> Changes: extracted `releaseRunning(...)`, `completeRunning(...)`, and `advanceStage(...)` in `Engine` so successful exit paths share the retry-state cleanup and history writes instead of repeating the same sequence inline.
> Validation: `./gradlew test --tests 'io.flowlite.test.EngineBehaviorTest'`; `./gradlew test --tests 'io.flowlite.test.RetryBehaviorTest'`
> Learning: the duplication was mostly in three shapes only: release to waiting status, complete from `Running`, and advance to next stage. Pulling exactly those shapes out reduces call sites without changing retry timing semantics.

## [DONE 2026-05-13.1] Access to flowlite from browser automation
> Agent:
> Done at workspace level: added `forwardPorts: [8080]` to [devcontainer.json](/workspaces/workplace/.devcontainer/devcontainer.json).
> Validation: this is a workspace-side change, not part of the `flowlite` git repo, so it is not included in the FlowLite commit history. It should take effect after reopening/rebuilding the devcontainer.
> Learning: the browser-access fix belongs to the workspace container config, not to FlowLite runtime code.
>> MG: VS Code has been restarted. Check if you can use browser automation to reach the app.

> Agent:
> Changes: verified the reopened workspace setup against a live `runPerfTestApp` instance using browser automation, not only container-local `curl`.
> Validation: `curl -I http://127.0.0.1:8080/cockpit` returned `HTTP/1.1 200`; browser automation opened `http://127.0.0.1:8080/cockpit?tab=flows`, rendered the Cockpit shell, and loaded live flow data (`flows: 2`, `instances: 1200`) with a screenshot.
> Learning: after the VS Code restart, forwarded port access is good enough for browser automation against the Cockpit running inside the devcontainer.

## [DONE 2026-05-13.1] Exploratory test with runPerfTestApp

> Agent:
> Started `./gradlew runPerfTestApp` and checked the local Cockpit surface from inside the container.
> Validation: `curl -I http://127.0.0.1:8080/cockpit` returned `HTTP/1.1 200`; `curl -s http://127.0.0.1:8080/api/flows | head -c 800` returned the expected showcase flow payload with active counts and Mermaid diagram data.
> Learning: the exploratory app serves correctly inside the devcontainer, but host-side browser automation still needs explicit port exposure/forwarding to inspect that instance directly.
>> MG: Why not use playwright-cli for exploratory tests while you do not have access to app from browser automation? 

> Agent:
> Done. Used Playwright CLI from inside the container after installing the local Chromium browser.
> Validation: `npx playwright screenshot http://127.0.0.1:8080/cockpit ../build/reports/playwright/screenshots/exploratory-perf-cockpit-cli.png` completed successfully and produced the exploratory screenshot in `build/reports/playwright/screenshots/exploratory-perf-cockpit-cli.png`.
> Learning: Playwright CLI is a good fallback for exploratory visual checks even when the chat browser cannot reach the container directly.
>> MG: Do you prefer to use browser automation instead of Playwright MCP or CLI to do exploratory tests? What are pros and cons of each?. Add you're learnings into [ief-loop-extensions.md](ief-loop-extensions.md)

> Agent:
> Answer: yes, when the live app is reachable I prefer browser automation first, Playwright-style scripted automation second, and CLI third.
> Pros/cons: browser automation is fastest for interactive exploration and seeing the real rendered state, but it is weaker for repeatable long flows and low-level diagnostics; scripted Playwright automation is better for reproducibility, waits, and deeper debugging, but costs more setup; CLI is the lightest fallback for smoke checks and screenshots from inside the container, but it gives the least observability and weakest interaction model.
> Changes: added an explicit exploratory tool preference order and fallback rule to `ief-loop-extensions.md`.
> Validation: reviewed the updated loop-extension text against the just-completed browser-access check and earlier CLI fallback path so the rule matches how the repo was actually exercised.
> Learning: the right default is not one universal tool but a preference ladder based on reachability and diagnostic depth.

## [ONHOLD] What about joining with retry state store in sql instead in kotlin?

> Agent:
> That is a valid next design if Cockpit query volume justifies it. I kept it out of this loop because the current summary projection stays simpler and we avoid denormalizing retry fields into another table too early. The current Kotlin-side merge is acceptable at this scale; if Cockpit list queries become hot, SQL join or projection denormalization is the next step.

## [ONHOLD] Why scheduled tick needs target stage?
We know where the flow is right now so we know what should be the next stage, don't we?
Do we need to decode stage into a tick?

> Agent:
> Yes, we still need it. Delayed timer and auto-retry ticks can fire after the instance has already moved elsewhere. Without `targetStage`, a stale delayed tick could wake the instance in the wrong stage and incorrectly re-enter processing. The stage tag is what makes those delayed ticks safe to ignore.
>> MG: What are the biggest consequences if stale tick would be processed? Process moves from timer to next stage? Any other?

## [ONHOLD] Retry metadata source of truth

> Agent:
> Cockpit now prefers retry-state rows but falls back to the latest error history row when retry metadata is unavailable from that store. The runtime path writes both, so the UI stays correct.
> If you want a stricter architecture later, we should decide whether to keep both sources intentionally or simplify to one authoritative read model.
>> MG: What about adding retry related attributes to FlowLiteInstanceSummaryRow?

> Agent:
> That is the strongest next option if we want one Cockpit read model with cheap queries. I did not move there in this loop because it adds more projection/update rules and more denormalized state to maintain. My current preference is:
> 1. keep retry-state as the write model,
> 2. add retry fields to `FlowLiteInstanceSummaryRow` only when Cockpit query pressure proves the extra projection complexity is worth it.

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
