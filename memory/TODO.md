## [DONE 2026-03-04] Action context for stage actions
Implemented `ActionContext(flowId, flowInstanceId)` and action support with signature `(context: ActionContext, state: T) -> T?` while keeping existing state-only `::action` references.

Completed changes:
- Added action-context support in DSL and runtime invocation.
- Updated Mermaid rendering to preserve action names.
- Migrated `EmployeeOnboardingActions` to use `context.flowInstanceId` instead of reading id from domain state.
- Added `FlowActionContextTest` and verified with full suite (`./gradlew test`).

## [DONE 2026-03-04] Use FlowLiteHistoryRow instead CockpitHistoryEntryDto which is just duplicate
Completed changes:
- Removed `CockpitHistoryEntryDto` from `source/cockpit/api.kt`.
- Updated `CockpitService.timeline(...)` to return `List<FlowLiteHistoryRow>` directly.
- Verified with `./gradlew test` (BUILD SUCCESSFUL).

## [DONE 2026-03-04] rename FlowEngine to Engine
Completed changes:
- Renamed core class `FlowEngine` to `Engine` and moved source file to `source/Engine.kt`.
- Updated all source and test usages to the new class name.
- Updated references in `README.md` and `AGENTS.md`.
- Verified with `./gradlew test` (BUILD SUCCESSFUL).


## [DONE 2026-03-04] move cockpit/api.kt to service
Completed changes:
- Moved `CockpitFlowDto`, `CockpitInstanceDto`, `CockpitErrorGroupDto`, and `CockpitInstanceBucket` into `source/cockpit/service.kt`.
- Deleted `source/cockpit/api.kt`.
- Verified with `./gradlew test` (BUILD SUCCESSFUL).
 
## [DONE 2026-03-04] Showcase application
Completed changes:
- Added servlet-only `ShowcaseFlowSeeder` in `test/testApplication.kt`.
- `startTestWebApplication()` now enables `flowlite.showcase.enabled=true` and seeds one order-confirmation + one employee-onboarding instance on startup and every 5 seconds.
- Seeder also sends demo events so instances progress through stages (good cockpit activity).

Validation:
- `./gradlew test` passed.
- `./gradlew runTestApp` runtime logs confirm periodic seeding.
- Cockpit API smoke check: `/api/flows` and `/api/instances` returned data.
- Visual inspection tooling confirmed via Simple Browser (`http://localhost:8080/cockpit`).

## [DONE 2026-03-04] Review queries in history repository
Completed changes:
- Replaced three duplicated latest-row repository queries with one parameterized method: `findLatestRows(flowId, types)`.
- Centralized stage/status/error type-group definitions in `cockpit/service.kt` and reused the single query.
- Verified behavior with `./gradlew test` (BUILD SUCCESSFUL).

## [DONE 2026-03-04] Implement playwright tests
Completed changes:
- Added Playwright setup in `cockpit-ui` (`@playwright/test`, `playwright.config.ts`, and `tests/cockpit.spec.ts`).
- Added `test:e2e` and `test:e2e:headed` scripts to `cockpit-ui/package.json`.
- Fixed static cockpit asset serving in `test/CockpitUiStaticConfig.kt` so E2E can render the app.
- Added Playwright artifacts to `.gitignore`.

Validation:
- `cd cockpit-ui && npm run test:e2e` → `2 passed`.
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-04] Expose test instance publicly available
Completed changes:
- Added automation script `tools/exposeTestInstance.sh` to start `runTestApp`, wait for readiness, open localhost.run tunnel, and clean up on exit.
- Set executable bit on script (`chmod +x`).

Validation:
- Script dry-run with timeout successfully started app, reached readiness (`/api/flows`), and produced public tunnel URL (`https://2babf788d33bf1.lhr.life`).
- Confirmed cleanup behavior: port `8080` was free after timeout/termination.

## [DONE 2026-03-04] Yet more coverage?
Completed changes:
- Added `test/CockpitServiceTest.kt` with targeted coverage for cockpit aggregation logic:
	- `listInstances(...)` sorting + bucket filtering (`Active`, `Error`, `Completed`) + `flowId` filter.
	- `listErrorGroups(...)` grouping/sorting behavior.
	- `listFlows()` per-flow counters and diagram generation checks.
	- `timeline(...)` projection behavior.

Validation:
- Focused run: `./gradlew test --tests io.flowlite.test.CockpitServiceTest` → BUILD SUCCESSFUL.
- Full run: `./gradlew test` → BUILD SUCCESSFUL.
- Coverage snapshot after tests: total line coverage `76%` and `io.flowlite.cockpit` line coverage `68%`.

## [DONE 2026-03-04] Why we query for all this separatelly?
Completed changes:
- Reworked cockpit instance summary loading to use one repository call instead of three.
- Added `FlowLiteHistoryRepository.findLatestRowsPerType(flowId, types)` that ranks latest rows per `(flow_id, flow_instance_id, type)`.
- Updated `CockpitService.listInstances(...)` to group once-fetched rows by instance key and derive latest stage/status/error rows in memory.

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-04] Remove duplication between inferActionName and inferConditionDescription
Completed changes:
- Introduced shared helper `inferCallableName(value, fallback)` in `source/dsl.kt`.
- Reused the helper from both `inferConditionDescription(...)` and `inferActionName(...)`.

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-04] CockpitUiStaticConfig - move under source/cockpit? This may be usefull for library clients I guess.
Completed changes:
- Moved `CockpitUiStaticConfig` from `test/` to `source/cockpit/CockpitUiStaticConfig.kt`.
- Kept behavior unchanged (`/cockpit`, root files, and `/assets/**` mappings).
- Wired the reusable config bean from `test/testApplication.kt`.

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-04] Remove Flow as perfix in test files/classes. Also FlowReceiverDslTest -> DslTest
Completed changes:
- Renamed flow-prefixed test files/classes:
	- `FlowDslValidationTest` -> `DslValidationTest`
	- `FlowActionContextTest` -> `ActionContextTest`
	- `FlowEngineBehaviorTest` -> `EngineBehaviorTest`
	- `FlowEngineErrorHandlingTest` -> `EngineErrorHandlingTest`
	- `FlowEngineHistoryTest` -> `EngineHistoryTest`
	- `FlowReceiverDslTest` -> `DslTest`

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-04] Worth to keep ActionContextTest as separate file? Don't they fit to ActionContextTest or other file?
Decision:
- Keep `ActionContextTest` as a separate file.

Rationale:
- It validates one focused contract (`ActionContext` propagation into stage actions) independent of broader engine behavior.
- Keeping it isolated avoids mixing low-level contract checks into larger scenario suites and keeps failure diagnosis faster.

## [DONE 2026-03-04] Rewrite playwright test to Kotlin
Completed changes:
- Added Kotlin Playwright E2E test: `test/CockpitPlaywrightTest.kt`.
- Added test dependency: `com.microsoft.playwright:playwright:1.58.0`.
- Implemented screenshot capture on failure (`build/reports/playwright/screenshots`).
- Enabled always-on video recording via Playwright context (`build/reports/playwright/videos`).

Validation:
- `./gradlew test --tests io.flowlite.test.CockpitPlaywrightTest` → BUILD SUCCESSFUL.
- `./gradlew test` → BUILD SUCCESSFUL.
- Note: Playwright host dependency warnings were emitted in this container, but test execution succeeded.

## [DONE 2026-03-04] Expose test instance publicly available - part 2
Completed changes:
- Selected **Render free web service** as the default provider for a public test instance.
- Added deploy blueprint: `render.yaml`.
- Added container runtime definition: `Dockerfile`.
- Added `.dockerignore` for faster/smaller image builds.
- Documented provider decision and quick-start deployment steps in `README.md`.

Decision summary:
- GitHub Pages is static-only, so it is **not** suitable for hosting this JVM/Spring service.
- GitHub is still a good option for repository hosting and CI/CD trigger (Render deploy from GitHub repo).
- Render free tier is suitable for demo exposure with expected idle sleep/cold-start trade-off.

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

## Improve showcase mode
In showcase mode flow instances started by ShowcaseFlowSeeder should have random action execution time (from near zero to lets say one minute?). From time to time some flow action should fail.