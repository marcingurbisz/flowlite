## [DONE 2026-05-17.1] Thread dump on render

Engine hangs after hours of operation (no business logs, only memory diagnostics). RSS stable (~344 MiB) — not OOM. Thread count grows from ~30 → ~125 during hang. Render eventually restarts on health check RST.

1. **Hourly VT-aware thread dump to logs** — dump should include Virtual Threads 
2. **Add pinned-thread tracing**

> Agent:
> Done.
> Changes: added a separate `PeriodicThreadDumpLogger` in `test/testApplication.kt`, wired it as a bean behind `flowlite.diagnostics.thread-dump-*` properties, and enabled it in `render.yaml` with an hourly interval. The logger uses `jcmd <pid> Thread.print` against the current JVM because the in-process management APIs available here do not include live virtual threads in their dumps.
> Changes: enabled pinned-thread tracing on Render via `JDK_JAVA_OPTIONS=-Djdk.tracePinnedThreads=full` and log the effective tracing mode plus raw env at startup so the deployment logs show whether tracing was really activated.
> Validation: `./gradlew test` passed. Added `RuntimeDiagnosticsLoggerTest` for the new command path. The build still emits the known Playwright host dependency warning in this container, but the Gradle test task succeeds.
> Learning: `ThreadMXBean.dumpAllThreads(...)` and `Thread.getAllStackTraces()` are not sufficient for VT-aware diagnostics in this JDK; self-`jcmd` is the practical path when we want the dump in app logs.

## [DONE 2026-05-17.1] Exploratory tests for autoretry and external retry
> Agent:
> Tested on local `runPerfTestApp` started with `--server.port=18080 --flowlite.showcase.initial-seed-count=20 --flowlite.showcase.repeat-seeding-enabled=false --flowlite.showcase.action-failure-rate=1.0 --flowlite.showcase.max-action-delay-ms=0 --flowlite.showcase.max-event-delay-ms=1000` so showcase order-confirmation instances deterministically fail and expose retry behavior quickly.
> Observed: Cockpit `/api/instances?flowId=order-confirmation&bucket=Error` returned 20 error instances with `retryInfo.externalRetryAllowed=true`. Live timelines showed the expected `Error -> Retried(Auto) -> Error` sequence, and after the retry budget was exhausted the instances stayed in `Error` with `nextAutoRetryAt=null`.
> Observed: manual retry through the Cockpit endpoint `POST /api/instances/{flowId}/{flowInstanceId}/retry` returned `204`, added `Retried` with `retryTrigger=Cockpit` to history, and the instance re-entered processing before failing again under the forced-failure setup.
> Limitation: true `Engine.externalRetry(...)` is not exposed through the test app HTTP surface, so that exact trigger could not be exercised as a live exploratory step without adding a dedicated endpoint/debug hook. Existing automated coverage for that path remains in `RetryBehaviorTest`.
> Findings: no functional regression found in the live autoretry/manual-retry behavior under this setup.

## [DONE 2026-05-17.1] Fix tick worker thread churn
> Agent:
> Finding from the live exploratory run: before the fix, failure-heavy `runPerfTestApp` logs reached `flowlite-tick-worker-161` quickly, which strongly suggested that `SpringDataJdbcTickScheduler` was creating new platform threads per task instead of reusing a bounded worker set.
> Changes: replaced the scheduler's `SimpleAsyncTaskExecutor` with a fixed thread pool in `source/springDataJdbc.kt`, keeping the same worker-count knob but making worker threads reusable.
> Validation: `./gradlew test` passed after the scheduler change. A follow-up smoke run of `runPerfTestApp` on port `18081` showed `ThreadPoolExecutor` worker traces and tick-worker names in a much smaller range (`...-11`, `...-15`, `...-28`) under the same forced-failure setup instead of immediately racing upward into the hundreds.
> Learning: the render thread-growth symptom was not only a diagnostics gap. The scheduler executor choice itself was contributing to thread churn.

## [FOR HUMAN]
* Check on GUI:
  retryTrigger?: RetryTrigger | null;
  failedAttemptCount?: number | null;
  autoRetryMaxAttempts?: number | null;
  nextAutoRetryAt?: string | null;
  externalRetryAllowed?: boolean | null;
* Review engine once again
* Review CockpitPlaywrightTest

## [FOR HUMAN] Review git changes
* Review perf(engine): raise tick worker default 3/25/26, 9:13 AM marcingurbisz
* loop starting from 75abfec728b2184501ae4ebae0c83c07f1e0ba23
* loops starting from
  * docs: TODO update marcingurbisz 3/8/26, 9:12 AM
  * docs: update TODO marcingurbisz 3/7/26, 3:57 PM
  * docs: update TODO marcingurbisz 3/6/26, 7:13 AM

## [DONE 2026-05-17.1] Self-review of current loop
> Agent:
> Reviewed: render diagnostics bean wiring, the `jcmd`-based VT dump path, render env changes for pinned-thread tracing, the retry exploratory notes, and the scheduler executor fix in `SpringDataJdbcTickScheduler`.
> Result: no additional local defects found in the current loop changes. The main residual risk is still environmental: browser-backed exploratory coverage remains limited in this container by missing Playwright host libraries, so the retry exploration used live HTTP/API probing instead of full browser automation.
> Validation reviewed: `./gradlew test`; two live `runPerfTestApp` probes with forced showcase failures; final TODO scan shows no remaining `REOPEN` or `IN PROGRESS` headings.
> Learning: the loop started as pure observability work, but the live retry probe exposed a concrete scheduler issue that was worth fixing immediately. That kept the investigation grounded in executable evidence instead of only adding more diagnostics.

## [ON HOLD] Topics on hold for now
* Check sonar findings and decide if they should be fixed
* Visual testing - comparing screenshots before and after changes? Not sure about it because when agent can
see produced image maybe this be enough for visual inspection and comparing will not be needed?
* Check coverage and suggest modifications/new tests to cover it
* The GWT cleanup showed that the Cockpit Playwright spec now needs a small `RecordedPageSession` helper to keep browser setup/actions in `when` blocks while preserving failure screenshots/videos. If we add more browser scenarios, it may be worth introducing a tiny test DSL/helper layer for `open page -> act -> assert -> close` flows so future specs do not repeat the same session lifecycle/synchronization plumbing.
* Websocket for live refresh
