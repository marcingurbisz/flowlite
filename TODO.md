## [DONE 2026-03-25] Increase number of worker threads to 40
Completed changes:
- Raised the default `SpringDataJdbcTickScheduler` worker-thread limit from `20` to `40`.
- Kept the existing constructor override so callers can still tune the scheduler explicitly when needed.

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-25] Cockpit-only activity instances
What was rationale behind introducing cockpit-only activity instances instead introducing new statuses in StageStatus? Give me pros and cons and your current recommendation.

> Agent:
> - Response: The rationale for keeping `CockpitActivityStatus` separate was to avoid turning `StageStatus` into a mixed engine+UI enum. `StageStatus` currently expresses engine/runtime ownership states (`Pending`, `Running`, `Completed`, `Cancelled`, `Error`) that drive persistence transitions and engine behavior. `WaitingForTimer` and `WaitingForEvent` are more like Cockpit interpretations of why a `Pending` instance is inactive, based on stage-definition metadata.
> - Response: Pros of keeping it Cockpit-only: preserves a smaller engine state model; avoids widening persistence/API semantics for every FlowLite consumer; keeps room for multiple future projections of `Pending` without committing them as durable engine contract; avoids migration churn in domain schemas/history/tests just for operator-facing categorization.
> - Response: Cons of keeping it Cockpit-only: some duplication of interpretation logic outside the engine; the meaning is derived rather than persisted, so other clients would need the same projection if they want the same distinction; the nullability split (`status` vs `activityStatus`) is slightly less direct than a single richer enum.
> - Response: Pros of extending `StageStatus`: one canonical status vocabulary; easier for external consumers to read without re-deriving; potentially simpler Cockpit code if the engine persisted those distinctions directly.
> - Response: Cons of extending `StageStatus`: it would blur “engine control state” with “reason for pending”; `WaitingForTimer` / `WaitingForEvent` are not independent lifecycle states because both are still `Pending` from the engine’s claim/retry/cancel perspective; it would force broader persistence/history/API/test changes and make future engine evolution more rigid.
> - Response: Current recommendation: keep `StageStatus` as-is and keep `CockpitActivityStatus` as a projection. If we later see multiple non-Cockpit consumers needing the same distinction, we should extract a shared engine-level read model / query DTO rather than widening the core runtime enum prematurely.
> - Validation: Design-only item; validated against current engine state handling in `persistance.kt` and Cockpit projection logic in `cockpit/service.kt`.

## [DONE 2026-03-25] Worth to split App it separate files for tabs?
... or you suggest some different split. I do not what to create too much files but >1k lines is probably to much. Or it its ok for you as it is now?

> Agent:
> - Response: At the current size (`cockpit-ui/src/App.tsx` is ~1679 lines) I do think a split is worth it. The main issue is not only line count, but that one file currently mixes DTO/types, URL/query-string state handling, fetch/update actions, selection/filter logic, modal flows, Mermaid rendering, and all four tab render trees.
> - Response: I would not split aggressively into dozens of tiny files. My recommendation is a small structural split around real responsibilities.
> - Response: Recommended minimum split: keep one top-level container component that owns shared state and routing, then extract one file per major tab (`FlowsTab`, `ErrorsTab`, `LongInactiveTab`, `InstancesTab`), plus one shared file for reusable UI pieces such as badges/modals/formatters, and one file for cockpit DTO/types + URL-state helpers.
> - Response: Pros of that split: each tab becomes much easier to read/test/change in isolation; tab-specific regressions are less likely to create merge conflicts across the whole file; the current repeated table/filter/action patterns become easier to see and simplify.
> - Response: Cons: some props plumbing will increase unless we also extract a small state/actions hook; if done carelessly, it can create too many near-empty wrapper files and make navigation worse instead of better.
> - Response: Current recommendation: yes, split it, but do it as a medium refactor rather than a “one file per component” cleanup. I would target roughly 6-8 files total for the cockpit UI, not dozens.
> - Validation: Design-only item; validated against the current `App.tsx` structure and size.

## [DONE 2026-03-25] Further Cockpit scaling follow-ups
* I think `Apply filters to view instances` should be displayed (and no request to for instances) till user select some filter. Also "Clear filters" should cause showing this message.
  > Agent:
  > - Changes: Implemented the gated `Instances` tab flow so it does not request `/api/instances` until a search/filter is applied, and `Clear Filters` now returns the tab to the guidance state.
  > - Validation: Covered by updated Playwright instances-tab assertions and `./gradlew test`.
* Errors and Long Inactive tabs probably should call /api/instances (or dedicated endpoint) with filtering. It takes 1s to get 1k instances and we may have more then 10k on production. I do not think we can make it fast for 10k instances without applying filter to backend request (+ virtual scrolling) or you have other opinion? 
  > Agent:
  > - Changes: Extended `/api/instances` and `/api/errors` with server-side filters and switched the `Errors` and `Long Inactive` tabs to fetch only the rows/groups required by the active filters instead of pulling the whole instance dataset.
  > - Response: I agree with the backend-filtering direction. For 10k+ instances, loading everything client-side first is the wrong shape even before virtual scrolling enters the picture.
  > - Validation: Added backend filter coverage in `CockpitServiceTest` and revalidated the cockpit Playwright suite via `./gradlew test`.
* Consider virtual scrolling for the `Instances` tab. 
  > Agent:
  > - Response: Considered but intentionally not implemented in this batch. After the gated `Instances` tab and backend-filtered heavy views, virtual scrolling is no longer the first bottleneck. I still recommend it as a later follow-up if filtered result sets themselves become large in production.

> Whole item agent notes:
> - Changes: Added a single-instance cockpit endpoint so the details modal can refresh independently of the current tab dataset, which avoids detail-modal regressions after filtered refreshes.
> - Validation: `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-25] Flows tab header counts
* When you go to "Flows" tab you get on the screen:
```
Workflow Engine Monitoring & Management
flows: 2 • instances: 0 • errors: 0
```
I guess we need a separate query for that and not base it on response from /instances
  > Agent:
  > - Response: Fixed without adding another endpoint. The header now derives `instances` and `errors` from `/api/flows`, which already carries per-flow active/error/completed totals.
  > - Validation: Included in the same cockpit refresh-path validation via `./gradlew test`.

## [DONE 2026-03-25] GWT style
Review all tests and rewrite them to GWT/Setup-Action-Verification style. Now I see cases where setup is done in "then". Best if setup and action is done directly in given and when blocks respectively in straightforward way. In case of problems with how the code from given/when tests are invoked consider InstancePerRoot instead default SingleInstance isolation. As a last resort you may consider moving setup and action to hooks (https://kotest.io/docs/framework/lifecycle-hooks.html) but would really prefer to not use them.

Completed changes:
- Reworked the main remaining GWT offenders so setup and action moved out of `then` blocks in `DslValidationTest`, `EngineErrorHandlingTest`, `ShowcaseFlowSeederTest`, `OrderConfirmationTest`, `CockpitServiceTest`, and `EmployeeOnboardingFlowTest`.
- Refactored `CockpitPlaywrightTest` as well, introducing an explicit recorded-page session helper so Playwright setup/actions can stay in `when` blocks while `then` stays assertion-focused without losing screenshot/video artifacts on failures.
- Kept the existing spec style and avoided switching the suite to hooks or broader isolation changes because the targeted refactors were sufficient.

Validation:
- `./gradlew test --tests io.flowlite.test.CockpitPlaywrightTest` → BUILD SUCCESSFUL.
- `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-25] ShowcaseFlowSeeder.processPendingEvents is quite complex
What about spawning a Thread (virtual) that sleeps for random number fo ms? I think this would remove a lot of code.

> Agent:
> - Response: Yes, for showcase-only code that is a better trade-off than the shared pending-event sweep. I still kept a small polling loop per queued event, because we cannot sleep the random delay until the instance has actually reached the matching wait stage, but each queued showcase event now owns that logic directly instead of being revisited by a global map sweep.
> - Changes: Replaced the global `pendingEvents` sweep in `ShowcaseFlowSeeder` with one virtual-thread task per queued showcase event. Each task waits until the instance reaches the configured pending wait stage, sleeps for the randomized showcase delay, re-checks the stage, sends the event if still valid, and exits. Shutdown now cancels the active tasks directly.
> - Validation: `./gradlew test --tests io.flowlite.test.ShowcaseFlowSeederTest` → BUILD SUCCESSFUL.
> - Validation: `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-03-25] Minor questions and remarks
* CockpitInstanceDto has:
 val status: StageStatus?,
 val activityStatus: CockpitActivityStatus?,
 In what case they are null?
  > Agent:
  > - Response: In the current Cockpit projection, `status` and `stage` are reconstructed from the latest history rows per instance, not read from the domain tables directly. They are nullable mainly as defensive tolerance for sparse or irregular history, not because healthy current runtime instances are expected to omit them.
  > - Response: `status` is `null` when the latest summary rows for an instance contain no status-bearing history row (`Started`, `StatusChanged`, `Retried`, `ManualStageChanged`, `Cancelled`, `Error`). That would typically mean partial/legacy/manual history data rather than a normal engine-produced active instance.
  > - Response: `activityStatus` is `null` whenever `status` is `null`, and also for non-active lifecycle states (`Completed`, `Cancelled`, `Error`). It is only populated for active states: `Running`, `Pending`, `WaitingForTimer`, and `WaitingForEvent`.
  > - Response: So for normal current Cockpit usage, `activityStatus == null` mainly means “this row is not currently active”, while `status == null` means “history did not provide enough information to classify the lifecycle state reliably”.
* Update "Tick processing loop" in README.md with handling timer
  > Agent:
  > - Changes: Updated the README runtime model section so the tick-processing loop now explains how timer stages schedule delayed wake-up ticks, release back to `Pending`, and continue once the timer becomes due.
* Add TOC to README.md, check if all header levels are correct 
  > Agent:
  > - Changes: Added a top-level README table of contents and fixed the obvious header-level mismatch by promoting `Special notes for agent` to a top-level section instead of nesting it under `License`.

> Whole item agent notes:
> - Validation: Documentation/design-only item after the previously green `./gradlew test` run; no runtime code changed in this batch.

## Coverage topic
* FE coverage
  * can we gather coverage for frontend code executed by Playwright tests? Try to implement it.
* BE coverage
  * are we gathering coverage from all tests including Playwright?

## [FOR HUMAN REVIEW] Optimize cockpit single-instance summary lookup if needed
The new `GET /api/instances/{flowId}/{flowInstanceId}` detail endpoint currently uses `CockpitService.instance(...)`, which reuses `loadInstanceSummaries(flowId)` and therefore rebuilds summaries for the whole flow to serve one details-modal request. That is acceptable for now, but if detail-modal traffic becomes hot we should replace it with a direct repository projection keyed by `(flowId, flowInstanceId)`.

## [FOR HUMAN REVIEW] Consider a tiny Playwright scenario DSL for Cockpit tests
The GWT cleanup showed that the Cockpit Playwright spec now needs a small `RecordedPageSession` helper to keep browser setup/actions in `when` blocks while preserving failure screenshots/videos. If we add more browser scenarios, it may be worth introducing a tiny test DSL/helper layer for `open page -> act -> assert -> close` flows so future specs do not repeat the same session lifecycle/synchronization plumbing.

## Exploratory tests
I'd like you to do exploratory tests using our test Flowlite instance on Render. Search for bugs and performance issues. Please document what you have tested and do the screenshots documenting the bugs. Are you able to do it right away or you need some additional tooling e.g. playwright installed in container or playwright MCP? Let me know do you need and I will give it to you :).
Note: the version currently deployed on Render is the one before this loop.

## [EVERY LOOP] Review your own changes
Please review your own changes from this loop, looking of potential improvements/simplifications both in the code and sounding concepts. Report any finding as a new todo item with [FOR HUMAN REVIEW] marker.

## [FOR HUMAN] Introduce concept of periodic tasks
Executed every loop or every x number of loops?

## [FOR HUMAN] review for improvements
* Research: How Peter and Simon are handling that (prompts, workflows)

## [FOR HUMAN] Review changes git changes

## [DONE 2026-03-08] Concept for "Auto-retry" and "User retriable"
Completed changes:
- Added planning note `memory/AutoRetryConcept.md` describing the current gap, the recommended hybrid design, retry directives, delayed tick scheduling, cockpit implications, and rollout phases.
- Chose the main architectural split: retry mechanics and bookkeeping should live in core FlowLite, while retry/failure classification should stay application-provided.
- Recommended separate engine-owned retry metadata rather than forcing every domain table to grow retry columns.

Validation:
- Design-only task; no runtime code changes were required.

## [DONE 2026-03-08] Plan for implementing refreshing "Flows" tab using websockets
Completed changes:
- Added planning note `memory/FlowsLiveRefreshPlan.md` covering scope, transport choice, backend/frontend design, rollout, and test strategy.
- Recommended phase-1 live refresh only for the `Flows` tab, with manual refresh buttons on every tab and a `Live` toggle only on `Flows`.
- Recommended invalidation + refetch as the data strategy, with SSE as the preferred phase-1 transport and WebSocket kept as a future transport swap if broader bidirectional live features are later needed.

Validation:
- Design-only task; no runtime code changes were required.

## [WAITING FOR BETTER SPEC] Duplicate copkpit but in Kotlin 
Create a duplicate of cockpit-ui but written in Kotlin (cockpit-ui-kotlin)

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
