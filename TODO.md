## [REOPEN] Check the latest failure on render

MG: Health check is using /api/flows on purpose - I want to stress the app a little bit. I want to understand how test app behaves under load. How often render is calling this endpoint. Any idea why after some time /api/flows gives connection refused?

> Agent: Reframed the failure explanation around Render health-check behavior instead of trying to undo the `/api/flows` choice.
> Validation: Checked current Render health-check documentation and recent service events/logs.
> Learning: Render says health checks run every few seconds and must succeed within five seconds.
> Learning: Because access logging is not enabled for the probe path, I do not have an exact observed request interval from the app logs themselves; the strongest grounded statement is the Render doc wording: `every few seconds`.
> Learning: `connection refused` is narrower than `EOF`: it means the TCP connection to port `10000` was not accepted at all, which points to the process not listening yet, being restarted, or being temporarily unavailable at the socket level.
> Learning: With `/api/flows` intentionally stressing the service, the most plausible path is: heavy probe/read load or some other instability causes health-check failures, Render treats the instance as unhealthy, and a later probe hits the instance during restart or before the listener is ready, producing `connection refused`.
> Learning: The current evidence still does not support OOM or a simple thread explosion as the first explanation for the sampled failure windows.

MG: Add access logging for test instance endpoints. Maybe using logbook?
MG: After implementing push and deploy the newest version to render.

> Agent: Added application-level HTTP access logging for the Render test instance instead of Tomcat file access logs.
> Changes: Added a small servlet filter in `test/testApplication.kt` that logs method, request target, response status, duration, remote address, and failure marker to normal app logs, added `test/HttpAccessLogFilterTest.kt`, and enabled the feature for Render in `render.yaml`.

MG: Own HttpAccessLogFilterTest is better than logbook?
MG: There was a new incident around 4:30AM. Check it. You now have http logs.

> Agent: Checked the new incident using Render events plus the new access logs.
> Validation: Queried recent Render events, deploys, and logs for `srv-d6m3tqkhg0os73b3c8o0` around `2026-05-25T02:32Z`.
> Learning: The new incident window was `server_failed` at `2026-05-25T02:32:04.683223Z` with reason `HTTP health check failed (timed out after 5 seconds)`, followed by `server_available` at `2026-05-25T02:33:48.355902Z`.
> Learning: The new access logs are visible in Render logs and show `/api/flows` probes roughly every 5 seconds, with occasional near-simultaneous pairs from the same internal source address `10.233.27.88`.
> Learning: Immediately before failure the last successful `/api/flows` responses were still `200`, mostly around `300-400ms`, but one probe at `02:31:48.479Z` already took `3002ms`. The next expected probe around `02:32:03Z` is missing from app logs, which fits the Render-side timeout event at `02:32:04Z`.
> Learning: After recovery the first visible `/api/flows` response at `02:33:44.152Z` still took `4100ms`, very close to the 5-second health-check ceiling, and only then dropped back to sub-`200ms` and double-digit millisecond timings.
> Learning: Periodic memory diagnostics around the same window stayed bounded (`usedMiB=97`, then `115`, then after recovery `60`; `rssMiB=339` before the failure), so this sample still does not look like OOM.
> Learning: Render request logs did not add anything useful here, while the custom app-level access logs did, so for this repo the small `HttpAccessLogFilter` plus its focused test is a better fit than adding Logbook just to answer this failure-analysis question.

MG: Check once gain last failure. Mabye this time came to some idea why we have render failures from time to time.

## [DONE 2026-05-19.3] Auto-retry and externally retriable on gui
On Flows show only "final" errors - non externally retriable and (non autoretry or all retries are done)
On Errors tab add filters - by default only final but you can choose externally retriable errors or the one with active auto-retry.
Make sure that we have all types of errors on our test render instance.

> Agent:
> Done.
> Changes: updated `source/cockpit/service.kt` so flow cards and stage error counters count only final errors, while error rows retain retry metadata for filtering.
> Changes: updated Cockpit UI state in `cockpit-ui/src/App.tsx`, `cockpit-ui/src/cockpit/types.ts`, `cockpit-ui/src/cockpit/locationState.ts`, and `cockpit-ui/src/cockpit/views/ErrorsView.tsx` so the Errors tab defaults to `final` and can switch to `external-retry` or `auto-retry-active`.
> Changes: added deterministic startup seeding in `test/testApplication.kt` so the servlet test app and `runPerfTestApp` always expose one final error, one external-retry error, and one active auto-retry error even when random showcase failures are disabled.
> Changes: wired `errorFilter` through the cockpit instances API (`source/cockpit/router.kt` and `cockpit-ui/src/App.tsx`) so the same retry category filter is available server-side and client-side.
> Validation: `./gradlew test --tests "*CockpitServiceTest"`; `./gradlew test --tests "*ShowcaseErrorCatalogSeederTest"`; `cd cockpit-ui && npm run build`.
> Validation: exploratory on local `SERVER_PORT=18081 ./gradlew runPerfTestApp` confirmed:
> - `/api/instances?bucket=Error&errorFilter=final` returns only the seeded final employee error,
> - `/api/instances?bucket=Error&errorFilter=external-retry` returns the seeded external-retry and active auto-retry order errors,
> - `/api/instances?bucket=Error&errorFilter=auto-retry-active` returns only the seeded auto-retry order error,
> - `/api/flows` reports `employee-onboarding.errorCount=1` and `order-confirmation.errorCount=0`, so retriable order errors are excluded from final flow counters.
> Learning: the original GUI requirement was implemented client-side, but live exploratory exposed that the backend route was not yet forwarding `errorFilter`; wiring that through kept the API and UI semantics aligned.

MG: Are we doing filtering on FE side? Isn't it better use only backend side filtering?
MG: listFlows - now we do more queries and logic then before introducing the change. Can't we change findIncompleteStageBreakdown and still use one query?
MG: .filter { row -> errorFilter == null || row.matchesErrorFilter(errorFilter) } - isn't it better to move to the db query?

> Agent:
> Changes: moved retry-aware error filtering into `findFilteredSummaries(...)` SQL, so `/api/instances` now applies `final` / `external-retry` / `auto-retry-active` server-side and the cockpit UI no longer re-filters retry categories in memory.
> Changes: changed `findFlowSummaryAggregates(...)` and `findIncompleteStageBreakdown()` to count only final errors directly in SQL, and simplified `CockpitService.listFlows()` back to repository aggregates instead of fetching all error rows plus per-stage summary queries.
> Validation: `./gradlew test --tests "*CockpitServiceTest"`; `./gradlew test --tests "*ShowcaseErrorCatalogSeederTest"`; `cd cockpit-ui && npm run build`
> Learning: the retry-category rule belongs in the database predicate because both the Errors API and flow-card counters depend on the same classification; once that predicate lives in SQL, the FE becomes a pure view over already-filtered data and `listFlows()` can stay query-shaped instead of reconstructing counts in Kotlin.

## [FOR HUMAN]
* Review agent-responding-to-failures.md
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

## [ON HOLD] Topics on hold for now
* Check sonar findings and decide if they should be fixed
* Visual testing - comparing screenshots before and after changes? Not sure about it because when agent can
see produced image maybe this be enough for visual inspection and comparing will not be needed?
* Check coverage and suggest modifications/new tests to cover it
* The GWT cleanup showed that the Cockpit Playwright spec now needs a small `RecordedPageSession` helper to keep browser setup/actions in `when` blocks while preserving failure screenshots/videos. If we add more browser scenarios, it may be worth introducing a tiny test DSL/helper layer for `open page -> act -> assert -> close` flows so future specs do not repeat the same session lifecycle/synchronization plumbing.
* Websocket for live refresh
