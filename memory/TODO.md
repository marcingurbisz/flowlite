## Update keep render live workflow to execute every 10min between 6:00 and 24:00 Warsaw time every day

## Plan for implementing refreshing "Flows" tab using websockets
Prepare plan document explaining the technical details. I think we should do live refresh only on Flows tab, what do you think?
Any other tab that could benefit from that? Or maybe live update toggle for everything? Or maybe better just add refresh buttons?

## Cockpit and engine func improvements
For every change implement new or adjust existing playwright test. Func changes/fixes:
* Closing modals with esc
* Modals (Instance details) should be bookmarkable
* It should be possible to set 1min to long running treshold. In playwright make sure that you can see some long running instance.
* Do we set state to null when instance complete or is cancelled? I think we should. What do you think?
* Event history is not clear now. What about changing event history in following way:
  * add date not only time
  * for stage changes in StageChanged and Started event the new stage name is the most important information but now is less visible
  * maybe type of event in separate column?
  * When completed stage should be empty/null
* Add copy button/thing next to instance id on list and details
* Details are not refreshing after clicking on cancel/change stage/retry
* I think it is better to have dedicated event type for manual change of stage. Now this is represented as two events in history (StageChanged and StatusChanged). So either replace these to with one event for manual change or add additional event. Similar for retry.
* Check if after state change and retry we enque tick

## [IN PREPARATON] Concept for "Auto-retry" and "User retriable"

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

## [DONE 2026-03-06] Failed on step Test + Coverage
See https://github.com/marcingurbisz/flowlite/actions/runs/22705767837/job/65832358931

Completed changes:
- Identified clean-checkout failure mode: `cockpit-ui/dist` was not versioned and not built before `CockpitPlaywrightTest`.
- Added Gradle task `installCockpitUiDeps` (`npm ci`) and `buildCockpitUi` (`npm run build`) in `build.gradle.kts`.
- Wired `tasks.test` to depend on `buildCockpitUi`, making CI/local test runs deterministic.

Validation:
- `./gradlew test --tests io.flowlite.test.CockpitPlaywrightTest` → BUILD SUCCESSFUL.
- `./gradlew test jacocoTestReport` → BUILD SUCCESSFUL.

## [DONE 2026-03-06] Why we need both private val distPath: Path = Paths.get("cockpit-ui", "dist") ?

Decision:
- Keep both derived locations in `CockpitUiStaticConfig`: `distLocation` for HTML/root files and `distAssetsLocation` for `/assets/**`.

Rationale:
- A simplification attempt that mapped `/assets/**` to `distLocation` caused `CockpitPlaywrightTest` to fail (UI heading not rendered due missing static asset resolution).
- `distAssetsLocation` is therefore required for correct `/assets/**` serving in current setup.

Validation:
- `./gradlew test --tests io.flowlite.test.CockpitPlaywrightTest` → BUILD SUCCESSFUL (after reverting simplification).

## [DONE 2026-03-06] Playwright test improvements
Completed changes:
- Improved Kotlin Playwright artifacts in `test/CockpitPlaywrightTest.kt`: screenshots and videos now use `<test-name>-<timestamp>` naming.
- Added stable `data-testid` selectors in `cockpit-ui/src/App.tsx` for cockpit title, tabs, flow cards/actions, instances search/rows, instance details, and flow diagram modal.
- Expanded Kotlin Playwright coverage with new scenarios:
	- open/close flow diagram modal from a flow card,
	- jump from flow card to instances view with pre-filled search.
- Added scenario planning document: `memory/PlaywrightTestScenarios.md`.
- Removed legacy TypeScript Playwright setup:
	- deleted `cockpit-ui/playwright.config.ts`,
	- deleted `cockpit-ui/tests/cockpit.spec.ts`,
	- removed Playwright scripts/dependency from `cockpit-ui/package.json` and refreshed lockfile.

Validation:
- `./gradlew test --tests io.flowlite.test.CockpitPlaywrightTest` → BUILD SUCCESSFUL.
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-06] Expose test instance publicly available - part 3
Completed changes:
- Added scheduled Render keepalive workflow: `.github/workflows/keep-render-alive.yml` (uses repository variable `FLOWLITE_RENDER_URL`).
- Updated `README.md` deployment chapter to pure step-by-step instructions (removed provider-comparison narrative).
- Removed obsolete local tunnel script: `tools/exposeTestInstance.sh`.

Decision:
- Keep runtime command on `./gradlew runTestApp` for now; moving to a Spring Boot jar is deferred because current public test app is test-source-set based and has no dedicated production-style bootJar pipeline yet.

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-06] Improve showcase mode
Completed changes:
- Added showcase behavior helper `test/showcaseActionBehavior.kt` that applies randomized action delay (`0..maxDelayMs`) and probabilistic failures for showcase-marked instances.
- Wired showcase config in `ShowcaseFlowSeeder` with new properties:
	- `flowlite.showcase.max-action-delay-ms` (default `60000`),
	- `flowlite.showcase.action-failure-rate` (default `0.1`).
- Marked showcase-seeded employee instances via `isRemoteEmployee = true` and applied behavior in all employee onboarding actions.
- Applied showcase behavior in order-confirmation actions for `orderNumber` prefixed with `SHOW-`.

Validation:
- `./gradlew test --tests io.flowlite.test.CockpitPlaywrightTest` → BUILD SUCCESSFUL.
- `./gradlew test` → BUILD SUCCESSFUL.