## [DONE 2026-03-16] Clarify TODO/LOG template in shared AGENTS
Completed changes:
- Updated the shared IEF guidance in `/workspaces/workplace/AGENTS.md` so `TODO.md` is the primary per-item execution log in `TODO.md` mode.
- Added an explicit rule to avoid duplicating the same per-item summary in both `TODO.md` and `memory/LOG.md`; `memory/LOG.md` is now framed as a place for cross-item learnings and durable repo notes.
- Expanded the recommended TODO item shape so it supports optional inline discussion / review notes in addition to completed changes, validation, and learning.

Validation:
- `git diff --check` → no issues.

Learning:
- The repo still benefits from a separate memory file, but it should capture reusable facts rather than restating every completed TODO item.

## Timer questions
* Due-time polling alone is not enough once operators can manually change stages while old delayed ticks are still queued - what you mean by that. I think duplicate ticks are not dangerous so I think we do not need special handling for manually state changes.
* val existingTimer = timerStore.load(flowId, flowInstanceId, stageKey) - why we need to consider existing timers? Is it the case when tick comes but it is yet not the time for execution, right?
* dedicated `flowlite_timer` table to keep delayed timer state durable - taking comment above into account maybe we can remove this new table and just have tick due time?
* InMemoryTimerStore - we should not have such in persistence.kt. If needed move to test sources.

## [DONE 2026-03-16] Simplify delayed timer persistence model
Completed changes:
- Removed the separate `TimerStore` / `flowlite_timer` persistence layer and made delayed timer wake-ups durable via the existing due-time tick rows only.
- Extended `TickScheduler` with a scheduled-tick lookup so timer stages can detect an already-planned wake-up and avoid rescheduling it when duplicate immediate ticks arrive before the timer is due.
- Kept only stage-based stale-tick guarding: delayed ticks are ignored if the instance is no longer on the target stage, but same-stage revisits are intentionally treated as acceptable duplicate-tick behavior.
- Removed the production `InMemoryTimerStore` implementation entirely.

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-16] README.md updates
Completed changes:
- Added the public Render test-instance URL to both `README.md` and `AGENTS.md`.
- Clarified that Better Stack and UptimeRobot keep the Render service awake by sending external pings and that Render would otherwise suspend it after about 15 minutes without traffic.

Validation:
- `git diff --check` → no issues.

## [DONE 2026-03-16] Render instance
Completed changes:
- Removed `.github/workflows/keep-render-alive.yml` because the Render test instance is now kept alive externally by Better Stack and UptimeRobot.
- Updated the `Public test instance deployment` chapter in `README.md` and `AGENTS.md` to reflect the current operational state instead of repeating the original Render setup steps.
- Removed the old GitHub keepalive variable/workflow instructions from the docs.

Validation:
- `git diff --check` → no issues.

## [DONE 2026-03-16] Support for large amount of instances
Completed changes:
- Moved Flows-tab active-stage breakdown and per-flow long-running counts to the backend `/api/flows` response so the Flows view no longer has to derive them from the full instance list.
- Changed Cockpit frontend refresh behavior so the Flows tab does not fetch `/api/instances` or `/api/errors`; those datasets are now loaded only on tabs that actually use them.
- Refactored cockpit summary projection to reuse one history-summary projection per endpoint and added `idx_flowlite_history_summary` to the engine schema to support the latest-row query pattern.

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.

## Further Cockpit scaling follow-ups
* Measure the current Render behavior again at 10k-11k instances after the Flows-tab fetch fan-out reduction.
* Decide whether `Errors` and `Long Running` should get dedicated backend endpoints instead of relying on `/api/instances`.
  > MG: Do Errors and Long Runnings trigger do calls to /api/instances with some filtering or request all instances? If all that for sure is something we do not want to do
* Consider virtual scrolling for the `Instances` tab. 
  > MG: We can wait with that but we should do item below.
* Consider showing `Apply filters to view instances` guidance like in `cockpit-ui/claude-prototype.jsx`.

## [DONE 2026-03-16] Increase number of worker threads
Completed changes:
- Increased the default `SpringDataJdbcTickScheduler` worker-thread limit from `4` to `20`.
- Kept the existing constructor override so callers can still tune the value explicitly if needed.

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-16] timer() implementation
Completed changes:
- Reworked `timer(...)` so it now accepts a wake-up calculator returning `Instant` and schedules delayed ticks instead of running a stage action immediately.
- Extended the Spring Data JDBC tick scheduler with due-time support so delayed timer wake-ups survive restarts using the existing tick table.
- Updated the employee onboarding example so timer stages compute wake-up times instead of mutating state, and added clock-controlled tests that advance through both delayed onboarding timers without blocking worker threads.
- Updated README and AGENTS so the documented timer contract matches the new runtime semantics.

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.
- `./gradlew updateReadme` → BUILD SUCCESSFUL.

## [DONE 2026-03-16] Long Running tab improvements
Completed changes:
- Kept core `StageStatus` unchanged and added Cockpit-only activity classification for active instances: `Running`, `Pending`, `WaitingForTimer`, and `WaitingForEvent`, derived from registered stage definitions.
- Changed the long-running view text to `Long Inactive`, included long-pending timer/plain-pending instances, and excluded `WaitingForEvent` by default while adding an explicit activity filter.
- Replaced the numeric minute threshold with human-readable duration input such as `30s`, `1m`, and `1h 30m`, and updated the flow-card counts to use the same long-inactive definition.
- Updated Cockpit service tests and Playwright coverage for the new pending classification, default filtering, and human-readable thresholds.

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-16] "Errors" tab changes
Completed changes:
- Added an `Errors` tab `Clear Filters` action that resets the flow, stage, and message filters back to their default state.
- Added per-group `Select All` and `Deselect All` controls so operators can bulk-select every error instance in the visible flow/stage group.
- Extended Playwright coverage to verify both the filter reset behavior and the group-level selection flow.

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-16] Show "Incomplete Only" filter
Completed changes:
- Exposed the existing `Incomplete Only` state as a visible checkbox in the `Instances` tab filter bar instead of keeping it as a hidden flow-shortcut-only state.
- Kept the Flows-tab incomplete shortcut behavior so navigating from the flow card opens `Instances` with the checkbox already enabled.
- Updated Playwright coverage to verify both the checked shortcut state and that `Clear Filters` resets it.

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-16] Retry, changes stage, cancel should have additional "Are you sure" modal with summary for the action
Completed changes:
- Added a shared cockpit confirmation modal for retry, cancel, and change-stage actions, including a short action summary plus the affected instance list.
- Kept change-stage as a two-step flow: choose the target stage first, then confirm the summarized action before it is sent.
- Updated Playwright coverage so bulk error actions, long-inactive retries, and detail-modal actions all pass through the new confirmation step.

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-16] Add sending events for instances in some random times
Completed changes:
- Changed the showcase seeder so demo events are no longer sent immediately at instance creation; each event now waits for the instance to reach its matching wait stage first.
- Added random showcase event delays after those wait stages so the public demo surfaces real `Waiting for event` instances without changing the underlying flow definitions.
- Documented the new `flowlite.showcase.max-event-delay-ms` property and added test coverage for delayed order confirmations in showcase mode.

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.

## cdn.tailwindcss.com should not be used in production
I guess we should fix it?
(index):64 cdn.tailwindcss.com should not be used in production. To use Tailwind CSS in production, install it as a PostCSS plugin or use the Tailwind CLI: https://tailwindcss.com/docs/installation

## Move mssql.sql and h2.sql
... to source/schema

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
