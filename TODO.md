## [DONE 2026-05-19.1] Better jcmd or maybe dump via JMX?
```
fun dumpThreads() {
    val server = ManagementFactory.getPlatformMBeanServer()
    val name = ObjectName("com.sun.management:type=DiagnosticCommand")
    val path = "/tmp/td-${System.currentTimeMillis()}.txt"
    
    server.invoke(
        name,
        "threadDumpToFile",
        arrayOf(arrayOf("-format=plain", path)),
        arrayOf(Array<String>::class.java.name)
    )
    
    val file = File(path)
    logger.info("=== THREAD DUMP START (${file.length()} bytes) ===")
    file.readLines().chunked(100).forEachIndexed { i, chunk ->
        logger.info("td chunk=$i\n${chunk.joinToString("\n")}")
    }
    logger.info("=== THREAD DUMP END ===")
    file.delete()
}
```

> Agent:
> Done. `PeriodicThreadDumpLogger` now prefers `com.sun.management:type=DiagnosticCommand` over JMX and calls `threadPrint` directly in-process, with `jcmd <pid> Thread.print` kept only as a fallback if the MBean path fails.
> Validation: ad hoc probe on this JDK confirmed that `DiagnosticCommand.threadPrint` is available and VT-aware; then `./gradlew test --tests "*RuntimeDiagnosticsLoggerTest"` passed after switching the logger to JMX-first with a tested fallback path.
> Learning: the earlier reason for using external `jcmd` was that the simpler in-process APIs (`ThreadMXBean.dumpAllThreads`, `Thread.getAllStackTraces`) missed live virtual-thread coverage. `DiagnosticCommand.threadPrint` is different: it exposes the same VM diagnostic command through JMX, so it keeps VT-aware dumps without spawning a separate process.

MG: Remove jcmd fallback. Not sure if it is worth to keep RuntimeDiagnosticsLoggerTest

> Agent:
> Changes: removed the `jcmd` fallback from `PeriodicThreadDumpLogger`, so runtime diagnostics now rely only on `DiagnosticCommand.threadPrint`; also deleted the leftover command-runner helper and simplified `RuntimeDiagnosticsLoggerTest` to cover the remaining enabled/disabled behavior.
> Validation: `./gradlew test --tests "*RuntimeDiagnosticsLoggerTest"`
> Learning: the JMX-backed `DiagnosticCommand` path is the VT-aware mechanism we actually want here, so keeping the external-process fallback only added code and test surface without a clear benefit.

## [DONE 2026-05-19.1] Review own changes
> Agent:
> Changes: reviewed the diff for the thread-dump logger simplification and removed the now-unused `runCommand` / `CommandExecutionResult` helper left behind by the fallback deletion.
> Validation: `git --no-pager diff -- test/testApplication.kt test/RuntimeDiagnosticsLoggerTest.kt TODO.md`; `./gradlew test --tests "*RuntimeDiagnosticsLoggerTest"`
> Learning: this slice stays small if the logger owns only one dump source; otherwise tests start encoding fallback mechanics instead of the behavior we care about.

## [DONE 2026-05-19.2] Fix tick worker thread churn
> Agent:
> Finding from the live exploratory run: before the fix, failure-heavy `runPerfTestApp` logs reached `flowlite-tick-worker-161` quickly, which strongly suggested that `SpringDataJdbcTickScheduler` was creating new platform threads per task instead of reusing a bounded worker set.
> Changes: replaced the scheduler's `SimpleAsyncTaskExecutor` with a fixed thread pool in `source/springDataJdbc.kt`, keeping the same worker-count knob but making worker threads reusable.
> Validation: `./gradlew test` passed after the scheduler change. A follow-up smoke run of `runPerfTestApp` on port `18081` showed `ThreadPoolExecutor` worker traces and tick-worker names in a much smaller range (`...-11`, `...-15`, `...-28`) under the same forced-failure setup instead of immediately racing upward into the hundreds.
> Learning: the render thread-growth symptom was not only a diagnostics gap. The scheduler executor choice itself was contributing to thread churn.
>> MG: I think we are using virtual threads so there is no problem with creating many new threads. Is this change worth doing?
>
> Agent:
> Yes. The current code only uses a virtual thread for the scheduler poller and stop helper. The actual tick workers are a platform-thread fixed pool in `SpringDataJdbcTickScheduler`, and the previous executor was also not using virtual-thread workers. So the churn was real platform-thread churn, not cheap VT creation.
> Validation: re-read current `source/springDataJdbc.kt` and compared it with the pre-fix revision from `git show 0c8ef80^:source/springDataJdbc.kt`.

MG: Isn't it better to use:
```
SimpleAsyncTaskExecutorBuilder()
        .threadNamePrefix("flowlite-tick-worker-")
        .concurrencyLimit(workerThreads)
        .taskTerminationTimeout(Duration.ofMinutes(5))
        .build()
```
And add spring.threads.virtual.enabled or .virtualThreads(true)

> Agent:
> I would keep the current fixed pool for this scheduler. `spring.threads.virtual.enabled` would not automatically change this code path because the scheduler constructs its own executor manually. Switching this slice to `SimpleAsyncTaskExecutorBuilder().virtualThreads(true)` would change the execution model again from bounded reusable platform workers to per-task virtual threads, which is a different trade-off than the issue we just fixed.
> Learning: the observed bug was platform-thread churn from the previous executor choice. The current fixed pool directly addresses that with predictable worker reuse and stable shutdown semantics. If we later want to evaluate VT workers, that should be a separate benchmarked decision rather than bundled into the churn fix.
> Validation: current implementation in `source/springDataJdbc.kt` still uses a fixed platform-thread pool; targeted and exploratory checks from this loop remain green with that model.

MG: I prefer SimpleAsyncTaskExecutorBuilder with virtual threads

> Agent:
> Changes: switched `SpringDataJdbcTickScheduler` workers from the fixed platform-thread pool to `SimpleAsyncTaskExecutorBuilder` with `virtualThreads(true)`, the existing `workerThreads` concurrency limit, and a 5-minute termination timeout; shutdown now closes the Spring executor instead of managing `ExecutorService` lifecycle manually.
> Validation: `./gradlew test`
> Learning: this keeps the user-preferred VT execution model while still bounding in-flight tick handling through `concurrencyLimit`, so the worker-count knob still controls scheduler pressure even though workers are no longer reused platform threads.

## [DONE 2026-05-19.2] Review own changes
> Agent:
> Changes: reviewed the scheduler diff to confirm the change stayed limited to the worker executor and shutdown path, with no unintended polling/query changes.
> Validation: `git --no-pager diff -- source/springDataJdbc.kt TODO.md`; `./gradlew test`
> Learning: in this slice the main behavioral change is the worker execution model, so keeping batching and poll-loop logic untouched makes the trade-off easier to reason about.

## [REOPEN] Auto-retry and externally retriable on gui
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

## Move seeding to separate file

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
