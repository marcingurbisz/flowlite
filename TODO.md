## [DONE 2026-04-15.1] Aligning CockpitStatus and StageStatus
What do you think about using in StageStatus the same statuses as we have now in CockpitStatus, effectively removing the need to have separate CockpitStatus? Consider that we do not have yet any clients and we are in alpha phase. No need for backward compatibility.
What are pros, cons and your recommendation.
Related topic: I see setCockpitStatusResolver - this introduce kind of circular dependency between engine and history store, right? I do not like that.

> Agent: Picking this item in loop `2026-04-15.1` together with the linked Cockpit follow-ups below, because the status-model design, summary projection, long-inactive behavior, and worker defaults are all part of the same Cockpit/runtime batch.

> Agent:
> - Response: I would still keep `StageStatus` and `CockpitStatus` separate. `StageStatus` is the engine control model (`Pending`, `Running`, `Error`, `Completed`, `Cancelled`) and is used directly in claim/retry/cancel/manual-stage transitions. `CockpitStatus` is an observability/read-model split of the non-running branch of that state machine.
> - Response: The main upside of merging them would be one enum, one persisted status column, no `cockpit_status` projection, and no resolver hook between engine bootstrap and summary updates.
> - Response: The main downside is that engine semantics would become stage-definition-aware at the core runtime layer. `WaitingForTimer`, `WaitingForEvent`, and `PendingEngine` are not just extra labels; they require the engine to know whether a stage is timer-driven or event-driven whenever it sets or compares status. That would spread flow-definition knowledge into claim/release logic, retry paths, status history, persistence contracts, and tests.
> - Response: So yes, `setCockpitStatusResolver(...)` is a circular-style hookup and I do not like the shape either, but I do not think collapsing the enums is the best fix for it. My recommendation is: keep separate engine and Cockpit statuses, and later remove the setter by introducing a dedicated flow-definition catalog/classifier bean or a Cockpit-owned projector instead of pushing that wiring through `SpringDataJdbcHistoryStore`.
> - Validation: Re-checked current `Engine`, `StageStatus`, `SpringDataJdbcHistoryStore`, and Cockpit status-classification flow while answering this item.

## [DONE 2026-04-15.1] Is /api/errors and listErrorGroups in use? Looks to me that we can remove it

> Agent: Investigating this in loop `2026-04-15.1` as part of the same Cockpit batch.

> Agent:
> - Response: They were no longer used by the UI. The `Errors` tab already had the raw `/api/instances?bucket=error` rows it needed, and the browser-network check after the change confirmed the page now loads with only `/api/flows` and `/api/instances?bucket=error`.
> - Changes: Removed backend `/api/errors`, `CockpitService.listErrorGroups(...)`, the unused summary aggregation query/data class behind it, and the corresponding backend tests.
> - Validation: `./gradlew test`
> - Validation: Browser-network inspection of `http://127.0.0.1:8080/cockpit?tab=errors` showed only `/api/flows?longRunningThresholdSeconds=3600` and `/api/instances?bucket=error`.

## [DONE 2026-04-15.1] Index question
create index if not exists idx_flowlite_instance_summary_updated on flowlite_instance_summary(flow_id, updated_at, flow_instance_id);
create index if not exists idx_flowlite_instance_summary_status_stage on flowlite_instance_summary(flow_id, cockpit_status, stage, updated_at, flow_instance_id);
create index if not exists idx_flowlite_instance_summary_cockpit_status on flowlite_instance_summary(flow_id, cockpit_status, updated_at, flow_instance_id);
Are we always providing flow_id when we query cockpit_status or updated_at?

> Agent: Reviewing the current Cockpit query shapes for this in loop `2026-04-15.1`.

> Agent:
> - Response: No. We do not always provide `flow_id` for the important `cockpit_status` / `updated_at` queries. Examples: global long-inactive requests, global error requests, and filtered instances searches can all run across all flows.
> - Changes: Dropped `idx_flowlite_instance_summary_updated`, which did not match a current query shape, and changed `idx_flowlite_instance_summary_cockpit_status` to lead with `cockpit_status, updated_at` instead of `flow_id`. I kept `idx_flowlite_instance_summary_status_stage` flow-first because flow+stage drill-downs do use that shape.
> - Validation: Re-checked current `findFilteredSummaries(...)`, `findFlowSummaryAggregates(...)`, and live Cockpit request patterns during the exploratory pass.

## [DONE 2026-04-15.1] Default values in FlowLiteInstanceSummaryRow
Why we have default values in:
```
@Table("FLOWLITE_INSTANCE_SUMMARY")
data class FlowLiteInstanceSummaryRow(
    val status: String = StageStatus.Pending.name,
    val cockpitStatus: String = "PendingEngine",
)
```

> Agent: Reviewing this together with the summary-row/index questions in loop `2026-04-15.1`.

> Agent:
> - Response: They were only there to seed a brand-new summary row before the first history entry was applied. That is implicit and weaker than necessary now that the columns are non-null.
> - Changes: Removed the constructor defaults and made the initial summary-row creation pass `status = Pending` and `cockpitStatus = PendingEngine` explicitly at the append site.
> - Validation: `./gradlew test`

## [DONE 2026-04-15.1] Clicking on long inactive in Flow tab do not update status filter on Long Inactive tab
... so when you changed it manually before you do not get "Running + pending engine"

> Agent: Reproducing and fixing this in loop `2026-04-15.1`.

> Agent:
> - Changes: Reset `longRunningStatusFilter` back to `default` when the `Flows` tab shortcut opens the `Long Inactive` tab.
> - Validation: Added Playwright coverage that first bookmarks a non-default long-inactive filter, then uses the flow shortcut and asserts the filter resets to `default`.
> - Validation: `./gradlew test`

## [DONE 2026-04-15.1] Local flowlite app process used for exploratory tests with 7k instances was consuming 25% cpu
This was without any real use of the app. Background processing (e.g. tick scheduler) should not use that much I think. Please check that next time you run Exploratory.

> Agent: Will include this explicitly in the requested exploratory pass for loop `2026-04-15.1`.

> Agent:
> - Findings: On the updated `runPerfTestApp` process with ~7k seeded instances, the Java process was still very hot during active background processing: sampled at about `1184%` CPU with `125` native threads. That was not an idle system, though; the showcase app was actively progressing thousands of instances, generating random delays/failures/events, and logging heavily the whole time.
> - Response: So I do not read the earlier `25%` observation as evidence of idle scheduler overhead by itself. Under this showcase workload the app is busy by design. If we want a true background-idle measurement, we should run a quieter scenario with seeding finished, low/no action failure, and much less per-stage logging.


## [DONE 2026-04-15.1] Many Pending engine inactive for a long time on render
Right now 361 instances raging for few sec to ~ 30m 17s
I wonder if this is because of too little worker threads or because ticks are lost. I think the first one because I see that these waiting ~30min are finally picked up.
Please increase default value for workers from 40 to 60.

> Agent: Investigating this and applying the requested worker-default change in loop `2026-04-15.1`.

> Agent:
> - Response: The current data still points much more toward worker saturation than lost ticks. On the updated perf app, repeated `Running` samples were `57`, `55`, `55`, which tracks closely with the new default of `60` worker threads. At the same time, `PendingEngine` long-inactive rows remained very large under the showcase load, which is consistent with backlog rather than dropped wakeups.
> - Changes: Raised the default `SpringDataJdbcTickScheduler` worker count from `40` to `60`.
> - Validation: `./gradlew test`
> - Validation: Exploratory curl checks on the updated perf app showed `Running` about mid-to-high 50s, `PendingEngine` with `5662` rows above `1s`, and `5391` rows above `30m` while the system was still actively processing the same ~7k seeded workload.

## [DONE 2026-04-15.1] Exploratory tests
> Agent:
> - Ran the updated local `runPerfTestApp --args='--flowlite.showcase.initial-seed-count=3500 --flowlite.showcase.repeat-seeding-enabled=false'` and checked live Cockpit APIs and browser request patterns.
> - Findings: `Errors` now loads with only two API calls (`/api/flows`, `/api/instances?bucket=error`). `Instances` with no filters makes no API calls, and a filtered instance lookup makes only `/api/instances?...`.
> - Findings: On the updated app, `/api/instances?bucket=active&cockpitStatus=Running&longInactiveThresholdSeconds=1` returned `58` rows in about `242ms`, `/api/instances?bucket=active&cockpitStatus=WaitingForEvent&longInactiveThresholdSeconds=1` returned `987` rows in about `404ms`, `/api/instances?bucket=active&cockpitStatus=PendingEngine&longInactiveThresholdSeconds=1` returned `5662` rows in about `232ms`, and `/api/instances?bucket=active&cockpitStatus=default&longInactiveThresholdSeconds=1` returned `5718` rows in about `192ms`.
> - Findings: Repeated warm `/api/flows` timings for `longRunningThresholdSeconds=1` and `3600` were both around `45-60ms` in this run.

## [DONE 2026-04-15.1] Self-review of loop changes
> Agent:
> - Reviewed the loop diff for accidental API leftovers, missing tests around the long-inactive shortcut, and unnecessary backend surface area.
> - Result: removing `/api/errors` and its aggregation path was the main simplification opportunity, and I added coverage for the long-inactive shortcut reset after applying that fix. I did not identify a separate new issue that needs a `[FOR HUMAN REVIEW]` follow-up item from this loop.
> - Validation: `./gradlew test`

## [ONHOLD] Frontend and backend sources together
Moving cockpit-ui/src under source/cockpit. Pros and cons?

> Agent:
> I would keep them separate in this repo.
> Main upside of moving `cockpit-ui/src` under `source/cockpit` is locality: one subtree for the whole Cockpit feature, easier side-by-side browsing of router/service/frontend code, and a slightly stronger signal that Cockpit is one product slice rather than two semi-independent projects.
> The downsides are more important here. Kotlin/Gradle/JVM sources and Vite/TypeScript/browser sources have different toolchains, cache directories, dependency graphs, and packaging assumptions. Mixing them under one source tree makes source-set rules, Sonar scoping, IDE indexing, and future publishing boundaries less clear. It also obscures the fact that the frontend is an independently buildable static app with its own lifecycle.
> My recommendation is: keep the dedicated top-level `cockpit-ui/` project, but continue tightening naming and docs so the conceptual ownership is obvious. If you ever shrink the frontend into a tiny mostly-static bundle, then revisiting a co-located layout would make more sense.

>> MG: Get the point but still I'm not fully convinced :). Can you enumerate what we would need to change in fe and be build system to have the sources together?

> Agent:
> Yes. If we colocated the frontend sources under `source/cockpit`, the build-system changes would be concrete rather than cosmetic.
> Gradle source sets: we would need to stop treating the whole `source/` tree as both Kotlin sources and JVM resources, otherwise the frontend source tree would be swept into `processResources`. That means redefining `sourceSets.main.resources` to exclude the frontend subtree or moving JVM resources to a narrower directory.
> Frontend tool root: Vite, TypeScript, `package.json`, lockfile, and `node_modules` would need either a new root under `source/cockpit` or a top-level package file that points into that subtree. Today the frontend build assumes `cockpit-ui/` is its working directory.
> Gradle frontend tasks: `installCockpitUiDeps`, `buildCockpitUi`, coverage merge paths, and dist sync paths would all need their working directory and input globs rewritten away from `cockpit-ui/**`.
> Sonar config: `sonar.sources`, `sonar.tests`, and frontend LCOV path normalization would need to move from `cockpit-ui/src` and `cockpit-ui/tests` to the new colocated paths.
> Test/resource packaging: the copy-to-test-app step would still need to publish a built static dist into test resources; colocating sources does not remove that packaging boundary.
> Developer tooling: TS config, Vite config, IDE excludes, and cache ignores would all need to be retargeted so TypeScript artifacts do not pollute the JVM `source/` tree.
> So it is feasible, but it is a real Gradle/Vite/source-set reshaping task, not just a folder move.

## [FOR HUMAN] review for improvements
* Research: How Peter and Simon are handling that (prompts, workflows)

## [FOR HUMAN] Review git changes
* Review perf(engine): raise tick worker default 3/25/26, 9:13 AM marcingurbisz
* loop starting from 75abfec728b2184501ae4ebae0c83c07f1e0ba23
* loops starting from
  * docs: TODO update marcingurbisz 3/8/26, 9:12 AM
  * docs: update TODO marcingurbisz 3/7/26, 3:57 PM
  * docs: update TODO marcingurbisz 3/6/26, 7:13 AM

## [ON HOLD] Topics on hold for now
* Check sonar findings and decide if they should be fixed
* Concept for "Auto-retry" and "User retriable"
* Long Inactive tab
  * Default filter should be "Running and Pending scheduler"
* Think about making CockpitStatus and engine Status the same
* Consider virtual scrolling for the `Instances` tab.
  > Agent:
  > Considered but intentionally not implemented in this batch. After the gated `Instances` tab and backend-filtered heavy views, virtual scrolling is no longer the first bottleneck. I still recommend it as a later follow-up if filtered result sets themselves become large in production.
* Visual testing - comparing screenshots before and after changes? Not sure about it because when agent can
see produced image maybe this be enough for visual inspection and comparing will not be needed?
* Check coverage and suggest modifications/new tests to cover it
* There are no logs that show how the /flows /instances processing goes. Now I cannot find out whether the query takes so long or it is processing in JVM code.
* The GWT cleanup showed that the Cockpit Playwright spec now needs a small `RecordedPageSession` helper to keep browser setup/actions in `when` blocks while preserving failure screenshots/videos. If we add more browser scenarios, it may be worth introducing a tiny test DSL/helper layer for `open page -> act -> assert -> close` flows so future specs do not repeat the same session lifecycle/synchronization plumbing.
* Websocket for live refresh
* New/duplicate cockpit but in Kotlin
