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

## Expose test instance publicly available - part 4
We run out of 512MB during build on render. I think it is better to start app in docker without gradle using springboot jar. This probalby shoudl include packing cockpit-ui into jar and serving it from there.

## [IN PROGRESS] Expose test instance publicly available - part 4
* use only dist in CockpitUiStaticConfig
* Deploy to render

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