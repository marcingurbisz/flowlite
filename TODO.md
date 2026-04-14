## [REOPEN] Feedback to "Implement recommendations from cockpit-scaling-notes.md"

> I did not persist `activityStatus` in the summary table in this batch. I do not think it is a good next step inside the current generic `SpringDataJdbcHistoryStore`, because that would couple a generic history projection to Cockpit-specific stage semantics and engine flow definitions. If we want to persist it later, I would do that through a cockpit-owned projector instead of pushing Cockpit knowledge into the generic store.
> Validation: `./gradlew test`

>> MG: But isn't SpringDataJdbcHistoryStore basically only for cockpit? We added it to serve cockpit back than.

> Agent:
> - Response: Yes. After looking at the current shape again, I agree with that framing. In practice this summary projection is now Cockpit-oriented enough that persisting `activityStatus` there is justified.
> - Changes: Added persisted `activity_status` to `flowlite_instance_summary` and wired `SpringDataJdbcHistoryStore` so `Engine` sets an `activityStatus` resolver after flow registration.
> - Changes: Added a one-time refresh of existing summary rows with `NULL activity_status`, derived from their current `(flowId, stage, status)` tuple, so the new SQL filters do not miss legacy rows.
> - Changes: `CockpitService.listInstances(...)` now filters through SQL against the summary table instead of loading all rows and filtering in JVM code.
> - Validation: `./gradlew test`

>> MG:
* What do you think about renaming CockpitActivityStatus to CockpitStatus and keep there all cockpit statues - all statuses + forked Pending into WaitingForTimer, WaitingForEvent and PendingEngine?
* activityStatus -> cockpitStatus ? Isn't it a better name?
* Remove "Added a one-time refresh of existing summary rows with `NULL activity_status`,". Why not to make make this column non nullable together with status column?
* derived from their current `(flowId, stage, status)` tuple - what flowId is needed for?
* sets an `activityStatus` resolver after flow registration - why we need resolver per flow? This should be the same for all flows.

> Agent:
> - Response: I would keep `CockpitActivityStatus` and `activityStatus` for now. The current field models only the Cockpit-specific split of active work, while `status` still carries the engine lifecycle. Renaming it to `CockpitStatus`/`cockpitStatus` becomes cleaner only if we actually collapse those two concepts into one Cockpit-facing status model.
> - Response: I would also keep `status` and `activity_status` nullable in the schema for now. For newly written rows they are populated, but making them `NOT NULL` safely would require an explicit data migration path for all legacy summaries rather than only the startup refresh for derived activity values.
> - Response: `flowId` is needed during derivation because `Pending` must be refined by the owning flow definition. The same stage name is not guaranteed to mean the same thing across flows, so we need the flow to know whether the current stage is a timer stage, an event wait stage, or plain engine-pending work.
> - Response: The resolver is not really "per flow" in registration count. It is one resolver that receives `flowId` and uses that to look up the relevant stage definitions. The dependency on flow-specific stage metadata is real even though the classification rule itself is shared.
> - Validation: Re-checked the current `CockpitService` / `SpringDataJdbcHistoryStore` flow after the SQL-filtering/activity-status changes and kept the model unchanged in this loop.

>> MG: 
* Let's collapse these two concepts into one Cockpit-facing status model. Rename CockpitActivityStatus to CockpitStatus and keep there all cockpit statues - all engine statuses with Pending split into WaitingForTimer, WaitingForEvent and PendingEngine
* `NOT NULL` safely would require an explicit data migration path for all legacy summaries rather - we do not have legacy summaries

## Slow UI when 6.5k instances
* /flows?longRunningThresholdSeconds=1 is considerably slower than /flows?longRunningThresholdSeconds=3600. Why?
* /instances?flowId=employee-onboarding&stage=UpdateDepartmentAssignment&bucket=error - takes ages
* Why Error tab triggers 3 requests /flows /instances and /errors?
* Why Instances tab calls both flows/ and /instances ?
* Right now on render there are only 15 instances in status Running. Usually I was seeing 40 (as many as threads). There are pending engine instances ready to be processed. Any idea why?

# Remove CockpitUiStaticConfigTest?
Worth to keep it? Regression is not that important and we are starting whole app in this test so it is quite heavy.

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
