## Render instance
Remove keep-render-alive.yml - render instance is now kept alive by betterstack and uptimerobot.com. Update "Public test instance deployment" chapter with this info. Also remove info how to setup render as it is already done.

## Support for large amount of instances
* It looks that Flows tab is using data from /flows and /instances. For big amount of instances this is working slow. Should aggregation of all data for Flows tab be done on backend?
Additional question: In our render flowlite instance in case of 10k instances FE is waiting for data around 8s. With 11k instances it takes around 40s so it seems to me that this time spent mainly in db? Maybe also some index is missing on db side?
* Should "Errors" and "Long running" tabs depend on data from /instances?
* Introduce virtual scrolling for "Instances" tab?
* Worth to display "Apply filters to view instances" like in cockpit-ui/claude-prototype.jsx prototype for "Instances" tab?

## Increase number of worker threads 
... in SpringDataJdbcTickScheduler to 20 by default

## timer() implementation
timer(Delay5Min, actions::delay5Min) - should suspend execution of flow instance for 5min without blocking worker thread. Second argument should probably a function that calculates wake-up time not action.

## "Long Running" tab improvements
Consider below. In case for change write/update tests for changes:
* I think we also need to be able to see instances that are for a long time in Pending status. But I do not want to see pending on event (at least not by default). Maybe worth to introduce additional status WaitingForEvent? 
* Should long time pending be classified as long running or as some other group? Maybe whole tab rename to "Long inactive" or "Long running and inactive"? Show status in the list and add filter for status?
* What do you think about allowing entering period as threshold? E.g. 1h, 1m, 30s, 1h 30m. 

## "Errors" tab changes
Change and write/update tests for changes:
* add "clear filters" button like in cockpit-ui/claude-prototype.jsx
* add select/deselect all for errors in stage group like in cockpit-ui/claude-prototype.jsx

## Show "Incomplete Only" filter 
... when navigated from incomplete link from Flows tab to Instances tab (like in prototype). Add/modify test for it.

## Retry, changes stage, cancel should have additional "Are you sure" modal with summary for the action
Add/modify tests for that.

## Add sending events for instances in some random times 
... so we see sometimes processes waiting for events

## cdn.tailwindcss.com should not be used in production
I guess we should fix it?
(index):64 cdn.tailwindcss.com should not be used in production. To use Tailwind CSS in production, install it as a PostCSS plugin or use the Tailwind CLI: https://tailwindcss.com/docs/installation

## [FOR HUMAN] Review changes git changes

## [DONE 2026-03-08] Split engine schema from test schemas and keep nullability scoped to stage metadata

Completed changes:
- Split the test-app DDL into engine-owned resources (`test/schema/h2.sql`, `test/schema/mssql.sql`) and separate domain-table resources (`test/schema/h2-test-tables.sql`, `test/schema/mssql-test-tables.sql`), then updated `test/testDatabaseSchema.kt` to apply both scripts per dialect.
- Reverted the attempted nullable-business-state refactor in the demo domains/runtime so `OrderConfirmation` and `EmployeeOnboarding` business fields stay non-null in code.
- Limited the `NOT NULL` relaxation in the test domain-table DDL to only `stage` and `stage_status`; all other business columns remain required.

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-08] Concept for "Auto-retry" and "User retriable"
Completed changes:
- Added planning note `memory/AutoRetryConcept.md` describing the current gap, the recommended hybrid design, retry directives, delayed tick scheduling, cockpit implications, and rollout phases.
- Chose the main architectural split: retry mechanics and bookkeeping should live in core FlowLite, while retry/failure classification should stay application-provided.
- Recommended separate engine-owned retry metadata rather than forcing every domain table to grow retry columns.

Validation:
- Design-only task; no runtime code changes were required.

## [DONE 2026-03-08] Update keep render live workflow to execute every 10min between 6:00 and 24:00 Warsaw time every day
Completed changes:
- Updated `.github/workflows/keep-render-alive.yml` to schedule every 10 minutes across the UTC range that covers the Warsaw window and added a Warsaw-time gate so pings only run from 06:00 until midnight local time.
- Updated `README.md` and `AGENTS.md` to describe the new keepalive cadence.

Validation:
- `git diff --check` → no issues.

## [DONE 2026-03-08] Plan for implementing refreshing "Flows" tab using websockets
Completed changes:
- Added planning note `memory/FlowsLiveRefreshPlan.md` covering scope, transport choice, backend/frontend design, rollout, and test strategy.
- Recommended phase-1 live refresh only for the `Flows` tab, with manual refresh buttons on every tab and a `Live` toggle only on `Flows`.
- Recommended invalidation + refetch as the data strategy, with SSE as the preferred phase-1 transport and WebSocket kept as a future transport swap if broader bidirectional live features are later needed.

Validation:
- Design-only task; no runtime code changes were required.

## [DONE 2026-03-08] Cockpit and engine func improvements
Completed changes:
- Added `Escape` handling for the Cockpit modals, made instance-details URLs bookmarkable, and kept the selected instance in sync with URL/back-forward navigation.
- Switched the long-running threshold control from fractional hours to integer minutes so `1` minute is a valid input, and updated Playwright coverage around that workflow.
- Improved instance-details UX with copy buttons, UTC date+time formatting, a clearer history table (`Timestamp` / `Type` / `Stage` / `Details`), and blank stage cells for terminal completion/cancel rows.
- Fixed stale details after retry/cancel/change-stage by deriving the selected instance from the refreshed instance snapshot and reloading its timeline when data changes.
- Added dedicated history event types for manual retry and manual stage change, updated cockpit projections to understand them, and added engine test coverage proving both retry and change-stage enqueue ticks.
- Decision: keep persisted terminal state non-null for now. Clearing it would require a broader breaking refactor of `InstanceData<T : Any>`, persister contracts, and domain schemas, so it is better handled as a separate deliberate change rather than folded into this UI/history batch.

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-08] Flowlite is MIT license - add this info to the project in a way other open source MIT projects do.
Completed changes:
- Added a root `LICENSE` file with the standard MIT license text.
- Added a `License` section to `README.md` and `AGENTS.md` pointing readers to the license file.
- Updated Maven publishing metadata in `build.gradle.kts` to publish the real repository URL, SCM coordinates, and MIT license information.

Validation:
- `./gradlew help` → BUILD SUCCESSFUL.

## [DONE 2026-03-07] More playwright tests
Completed changes:
- Added URL query-state syncing and browser-history support in `cockpit-ui/src/App.tsx` so cockpit tabs and applied filters are bookmarkable and respond to the back button.
- Added stable `data-testid` hooks for flow shortcuts, error filters/actions, long-running filters/actions, instance filters/actions, detail stack traces, and the change-stage modal.
- Added missing cockpit interactions needed by the test plan: clear instance filters and retry-selected support in the long-running view.
- Reworked `test/CockpitPlaywrightTest.kt` to use deterministic seeded fixture data and expanded coverage for flow-definition shortcuts, error empty/filter/action scenarios, long-running scenarios, and instance-filter scenarios.
- Allowed `startTestWebApplication(...)` to disable showcase seeding so Playwright scenarios can run against deterministic data.

Validation:
- `./gradlew test --tests io.flowlite.test.CockpitPlaywrightTest` → BUILD SUCCESSFUL.
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-07] Expose test instance publicly available - part 4
Completed changes:
- Added packaged test-app build tasks in `build.gradle.kts`: copied Cockpit UI dist into test-app classpath resources, added `testAppJar`, `syncTestAppRuntimeLibs`, and `testAppBundle` so the public test app can be launched without Gradle at runtime.
- Updated `CockpitUiStaticConfig` to prefer packaged classpath assets while keeping filesystem fallback for local development.
- Switched `Dockerfile` to a multi-stage build that assembles the packaged app bundle and starts it directly with `java -cp ... io.flowlite.test.TestApplicationMainKt` instead of `./gradlew runTestApp`.
- Updated `README.md` deployment/build guidance to mention the packaged app bundle.

Validation:
- `./gradlew testAppBundle` → BUILD SUCCESSFUL.
- `java -Dserver.port=18080 -cp build/libs/flowlite-0.1.0-SNAPSHOT-test-app.jar:build/test-app-libs/* io.flowlite.test.TestApplicationMainKt` + `curl http://127.0.0.1:18080/api/flows` → OK.
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-07] Fix Render packaged-app Docker build
Completed changes:
- Added a Gradle `usePrebuiltCockpitUi` switch so `installCockpitUiDeps` and `buildCockpitUi` are skipped when a prebuilt `cockpit-ui/dist` is supplied, while `syncCockpitUiDist` still copies the assets into the packaged test app.
- Updated `Dockerfile` to build the Cockpit UI in a dedicated Node stage, copy the resulting `dist` directory into the JDK packaging stage, and run `./gradlew -PusePrebuiltCockpitUi=true testAppBundle`.
- Updated `README.md` to reflect the new Docker build path.

Validation:
- `./gradlew --console=plain -PusePrebuiltCockpitUi=true testAppBundle` → BUILD SUCCESSFUL (`installCockpitUiDeps` and `buildCockpitUi` both SKIPPED).
- `./gradlew test` → BUILD SUCCESSFUL.

## [WAITING FOR RENDER ACCESS] Deploy updated packaged test app to Render
* Apply the current jar-based container changes on Render.
* Verify `/cockpit` and `/api/flows` on the deployed service.

## [WAITING FOR BETTER SPEC] Duplicate copkpit but in Kotlin 
Create a duplicate of cockpit-ui but written in Kotlin (cockpit-ui-kotlin)

## [DONE 2026-03-07] Playwright: use date and timestap in artifact names instead toEpochMilli
Completed changes:
- Updated `test/CockpitPlaywrightTest.kt` so screenshot/video artifact prefixes use a readable UTC `yyyyMMdd-HHmmss-SSS` timestamp instead of raw `toEpochMilli()` values.

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-07] Refactor DSL to procedural style
Completed changes:
- Replaced the old `onEvent` / `condition` / `goTo` builder surface with a procedural compiler in `source/dsl.kt` that supports `stage(..., waitFor = ...)`, `timer(...)`, and `_if { ... } _else { ... }` while reusing the existing runtime graph model.
- Migrated order confirmation and employee onboarding to the new DSL, including the new order event model and the new employee stage/event/action vocabulary.
- Updated the affected DSL/runtime tests, cockpit fixtures, showcase seeding, schema scripts, and generated/manual README docs to match the new builder shape.

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.
- `./gradlew updateReadme` → BUILD SUCCESSFUL.

## [DONE 2026-03-07] Prepare idea for runnig test on mssql too
Completed changes:
- Added the planning note `memory/MssqlTestPlan.md`.
- Chose a staged approach: keep default H2 tests fast, add a separate MSSQL task/job, and use CI service containers first.
- Captured the concrete follow-up changes needed in datasource selection, Gradle tasking, CI wiring, and test-suite scope.

Validation:
- Design-only task; no runtime code changes were required.

## [DONE 2026-03-07] Move tables definitions outside testApplication.kt
Completed changes:
- Moved test-app schema DDL out of `test/testApplication.kt` into dedicated resource scripts: `test/schema/h2.sql` and `test/schema/mssql.sql`.
- Added `test/testDatabaseSchema.kt` with `TestDatabaseDialect` and `initializeTestSchema(...)` so the test app bootstrap only selects a dialect and applies the matching script.
- Kept the current runtime on H2 while preparing a parallel MSSQL schema script for the next database-support task.

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-07] Showcase improvements
Completed changes:
- Added README showcase-mode documentation describing how `ShowcaseFlowSeeder` works, what gets seeded, and which properties control demo behavior.
- Moved `ShowcaseActionBehavior` into `test/testApplication.kt` so showcase bootstrap and showcase-only action behavior live together.
- Added explicit `isShowcaseInstance` state to `EmployeeOnboarding` and its schema, and updated the seeder to use it instead of overloading `isRemoteEmployee`.

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.