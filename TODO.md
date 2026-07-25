## [DONE 2026-07-25.1] Check the latest failure on render

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

> Agent: Checked the latest failure and compared it with the preceding failures, the active deploy, access logs, memory diagnostics, and the periodic thread dump.
> Validation: Render reported `server_failed` at `2026-07-25T08:11:01.831306Z` for instance `srv-d6m3tqkhg0os73b3c8o0-r749n`, with `evicted=false` and `HTTP health check failed (timed out after 5 seconds)`. The process started again at about `08:11:48Z`, and Render reported `server_available` at `08:12:16.12866Z`.
> Validation: The active deploy is still `dep-d89hs46k1jcs73f2m7e0`, built from commit `807785b544a4f09580d3f16ffe6082ee0b89f942`; there was no deploy immediately before this failure.
> Learning: The failure is reproducible and age-related. In the 20 most recent samples, most failures happened after roughly five hours of uptime, and recovery normally took one to two minutes. The latest three runtimes before failure were about `5.07h`, `5.38h`, and `5.68h`.
> Learning: Immediately after the latest restart, `/api/flows` normally completed in `2-100ms`. Near the end of the preceding uptime, typical successful responses had degraded to roughly `2-5s`, including `4794ms`, `5056ms`, and `5300ms`; access logs also contain requests that eventually returned `200` only after `130496ms`, `391397ms`, `471399ms`, and `484201ms`.
> Learning: Render continues probing on an approximately five-second cadence while slow server-side requests remain in progress. Once `/api/flows` crosses the five-second health-check limit, timed-out probes overlap with later probes and increase pressure until Render restarts the process. `connection refused` is a consequence of that restart window, not the initiating failure.
> Learning: The best-supported cause is the unbounded showcase workload reaching a resource/contention cliff on the small Render instance. Every five seconds the app adds two flow instances plus their history and work; `/api/flows` repeatedly aggregates the growing `flowlite_instance_summary`, while `DriverManagerDataSource` opens a new H2 connection for each transaction. The pre-failure thread dump showed a showcase virtual thread opening an H2 connection, the seeder blocked on the H2 database monitor, and HTTP executor threads waiting on monitors.
> Learning: Heap pressure likely contributes but there is still no direct OOM evidence. Before failure the JVM had a `123MiB` maximum heap and periodic samples were commonly around `93-104MiB`, with a sampled peak of `117MiB`; RSS stayed around `338MiB`. After restart heap usage dropped to `54MiB` at startup and endpoint latency returned to milliseconds.
> Learning: A focused follow-up should reproduce the cliff with an accelerated fixed-size dataset and separately compare bounded showcase retention, pooled H2 connections, and aggregate-query cost. This investigation does not change `/api/flows` as the health-check path because stressing that endpoint is intentional.

## [DONE 2026-07-25.2] Compare `/api/flows` performance variants
Reproduce the roughly five-hour performance cliff locally with accelerated seeding and isolate the contribution of dataset growth, `DriverManagerDataSource` connection contention, and the two `/api/flows` aggregate queries before choosing a production-test-instance mitigation.

> Human: Ok zrób porównanie 4 wariantów. Zapisz to jako todo w pliku i dopisuj swoje znaleziska. Moze jakies indexy by pomogły? Jesze myslalem o sprawdzeniu użycia h2 file zamiast in memory.
>> Agent: Added the repeatable `benchmarkFlowSummary` Gradle task and `tools/FlowSummaryBenchmark.kt`. It seeds identical deterministic datasets, verifies that every variant returns the same flow/stage projection, measures sequential and concurrent latency, prints H2 plans, and reports approximate heap/file footprint.
>> Changes: Compared four variants on 50,000 and 200,000 `flowlite_instance_summary` rows: current two queries with `DriverManagerDataSource`, current two queries with HikariCP, one combined query with HikariCP, and preaggregated flow/stage counters with the existing indexed dynamic long-running query.
>> Validation: The corrected 200,000-row memory-H2 sequential `p50` results were `281.975ms`, `262.090ms`, `244.800ms`, and `13.002ms` respectively. File-H2 results were `467.020ms`, `483.367ms`, `429.204ms`, and `10.857ms`.
>> Validation: With eight concurrent callers on 200,000 rows, memory-H2 `p50` was `545.678ms`, `592.818ms`, `485.980ms`, and `17.697ms`; file-H2 `p50` was `4088.460ms`, `4003.535ms`, `522.633ms`, and `15.060ms`.
>> Learning: Hikari alone is not the performance fix; once H2 query-result caching was disabled, pooling did not materially reduce scan-dominated latency. The first cached run was explicitly rejected because persistent Hikari connections received an artificial result-cache advantage over new DriverManager connections.
>> Learning: One combined query is a useful lower-complexity improvement, especially because it removes the severe two-query concurrency cliff in file mode. It remains an `O(N)` full aggregation.
>> Learning: Preaggregation is the only tested option that changes the scaling shape. It was about 20 times faster than Hikari plus two queries on memory H2 and about 45 times faster on file H2 sequentially; its transactional write/rebuild/repair cost still needs a separate design and benchmark before implementation.
>> Learning: The tested covering index did not robustly help. The current negative status predicate kept a table scan; rewriting it to a positive `IN` made H2 use the index and modestly helped memory H2, but made file H2 slower. The flow-wide aggregate must still inspect all rows, so the new wide index is not recommended.
>> Learning: H2 file reduced measured heap growth (`112MiB` to a variable `36-55MiB` at 200,000 rows) but produced an `814-818MiB` file and made the current concurrent two-query path much slower. File mode is a possible heap-pressure experiment only when paired with the one-query or preaggregated design, not a standalone latency fix.
>> Changes: Documented the methodology, full results, limitations, and recommendation in `docs/FlowSummaryPerformanceBenchmark.md`; added the benchmark command to `README.md`.
>> Validation: Full benchmark command completed successfully; focused index/footprint reruns completed successfully; `./gradlew test --tests '*CockpitServiceTest'` passed; `git diff --check` passed.

## [DONE 2026-07-25.3] Reassess the Render cliff at the real instance count

> Human: zastanawia mnie jeszcze fakt ze na render w ciagu 5h nie wygeneruj sie 200 tys instancji. Wydaje mi sie ze restart nastepuje gdzies w okolicach 20 tys. BTW: widze ze generacja nowych instancji sie zblokowala teraz na testowej produkcji bo jest 7434 od jakiejs godziny.
>> Agent: Confirmed from the public API that the current dataset is much smaller than the synthetic benchmark and that showcase generation is stalled. At `2026-07-25T20:55Z`, `/api/flows` reported exactly `7,439` summaries and took `3.61s`; a second sample after 30 seconds still reported exactly `7,439`.
>> Validation: The count is consistent with the configured rate: one seed cycle creates two instances every five seconds, so five hours creates about `7,200`, not `200,000`. The three deterministic showcase error records also explain why the observed total is odd: `7,439 = 2 * 3,718 + 3`.
>> Learning: The isolated summary benchmark does not reproduce the Render cliff. At the roughly linear memory-H2 rate measured locally, `7,439` summary rows should be far below the observed multi-second latency. The live app also has concurrent writers, flow/history work, event polling, and a much smaller JVM.
>> Learning: The strongest new code-level hypothesis is leaked showcase event polling. Every employee instance starts two or sometimes three virtual tasks. Each task calls `engine.getStatus()` (a Spring Data/H2 read) every `250ms`; it exits for `Completed` or `Cancelled`, but not for `Error`. Failed instances that never reach the awaited event stage therefore retain polling tasks indefinitely.
>> Learning: The live response contains `1,470` final employee errors. Stage-level counts imply at least roughly `1,900` event tasks can remain before their awaited stage, which alone can generate more than `7,500` H2 reads per second. Another `1,436` employees are waiting at the long IT timer before the first awaited event and can temporarily retain roughly two pollers each. This is sufficient to explain severe H2 monitor contention, a blocked single-thread seeder, and `/api/flows` waiting seconds even though its source table has only about `7.4k` rows.
>> Learning: Private Render logs were unavailable in this environment, so the precise current seeder stack and thread count could not be confirmed. The public count and source inspection strongly support the polling leak, but a fresh thread dump or a focused local reproduction is still needed for direct runtime confirmation.

## [DONE 2026-07-25.3] Review own changes
Review the updated Render diagnosis and distinguish observations from source-based inference.

> Agent: Rechecked the public response, seed-rate arithmetic, stage ordering, event-task lifecycle, and the persistence path behind `engine.getStatus()`.
> Validation: `7,439` remained unchanged across two public samples; `2 * 3,718 + 3` matches the two instances per cycle plus three deterministic records. At least `908` employee errors occurred before the first awaited event and retain two standard pollers, while `116` occurred between the two awaited events and retain one, yielding a conservative lower bound of `1,932` leaked polling tasks.
> Validation: The UI's `7,434` and the API's `7,439` are consistent: the top-level UI total adds `activeCount + errorCount + completedCount`, while `errorCount` intentionally includes only final errors. Five non-final/retriable order errors are omitted from that display total; `notCompletedCount + completedCount` gives the actual summary-row total.
> Learning: The stalled count and multi-second endpoint are direct observations. The event-poller leak is proven by control flow for instances in `Error`, but its contribution to the live stall remains a strong inference until correlated with a current Render thread dump or reproduced locally.

## [FOR HUMAN REVIEW] Fix leaked showcase event pollers and expose seeder failures
Add `Error` as a terminal condition for pending showcase-event tasks, stop polling once an instance has passed an awaited stage, log failures currently swallowed by `seedOnceSafely()`, and add a focused regression/load test before reconsidering summary preaggregation or a database migration as the primary incident fix.

## [DONE 2026-07-25.2] Review own changes
Review the benchmark implementation, measurement methodology, recorded results, and recommendations from this loop.

> Agent: Reviewed commit `fc92df5`, the benchmark SQL/result equivalence checks, concurrency harness, H2 lifecycle, Gradle arguments, and the recorded conclusions.
> Changes: Made numeric output locale-independent, validated all benchmark arguments up front, and added an explicit H2 checkpoint before footprint measurement.
> Validation: Re-ran the 200,000-row H2 file probe after the checkpoint; the live file remained about `814MiB`, confirming that the reported size was not just an unflushed checkpoint artifact.
> Learning: Post-GC file-mode heap readings varied more than the latency results (`36-55MiB`), so the documentation now reports the observed range and keeps the footprint conclusion qualitative. No latency recommendation changed.
> Validation: The review smoke benchmark completed successfully, all four projections remained identical, and `git diff --check` passed.

## [DONE 2026-07-25.1] Review own changes
Review the Render incident analysis and the Render API skill update from this loop.

> Agent: Reviewed commit `b76b0d1` against the raw Render event/deploy data, access-log timings, current deployed source, and the official API shape.
> Validation: `git show --check b76b0d1` passed; recalculating the 20 latest runtimes showed that 13 were between `4.5h` and `6h`, supporting the “most failures after roughly five hours” wording.
> Validation: Re-read the deployed `807785b` versions of `test/testApplication.kt` and `source/springDataJdbc.kt` to confirm that the documented unbounded seeding, `DriverManagerDataSource`, and aggregate-query behavior applies to the active Render build.
> Learning: Kept the conclusion explicitly probabilistic: the evidence supports a data-growth/resource/contention cliff and rules out a deploy-triggered incident, but it does not isolate one query, prove GC as the trigger, or prove an OOM.
> Learning: The Render service reports `autoDeploy=true`, but its newest live deploy is still from `2026-05-24` while `origin/main` has newer commits. This is operationally separate from the recurring timeout mechanism and needs a human decision before changing external deployment configuration.

## [FOR HUMAN REVIEW] Check why Render auto-deploy is stale
The service API reports `autoDeploy=true`, but the newest live deploy is `dep-d89hs46k1jcs73f2m7e0` from `2026-05-24` at commit `807785b`, while the repository's `origin/main` is newer. Confirm whether deployment is intentionally paused or whether the Render/GitHub connection needs repair.

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
