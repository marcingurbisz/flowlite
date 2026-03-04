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

## Remove Flow as perfix in test files/classes. Also FlowReceiverDslTest -> DslTest

## Worth to keep FlowActionContextTest as separate file? Don't they fit to FlowActionContextTest or other file?

## Rewrite playwright test to Kotlin
Add taking screenshots on error + recording video (always)

## Expose test instance publicly available - part 2
By exposing test instance I meant finding some provider that we can use to deploy and expose (not serve it from localhost). I'd like to be free (or very cheap). Is github an option here?