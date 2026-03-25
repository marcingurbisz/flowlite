## [DONE 2026-03-25] Increase number of worker threads to 40
Completed changes:
- Raised the default `SpringDataJdbcTickScheduler` worker-thread limit from `20` to `40`.
- Kept the existing constructor override so callers can still tune the scheduler explicitly when needed.

Validation:
- `./gradlew test` → BUILD SUCCESSFUL.

## Cockpit-only activity instances 
What was rationale behind introducing cockpit-only activity instances instead introducing new statuses in StageStatus? Give me pros and cons and your current recommendation.

## Worth to split App it separate files for tabs?
... or you suggest some different split. I do not what to create too much files but >1k lines is probably to much. Or it its ok for you as it is now?

## Further Cockpit scaling follow-ups
* I think `Apply filters to view instances` should be displayed (and no request to for instances) till user select some filter. Also "Clear filters" should cause showing this message.
* Errors and Long Inactive tabs probably should call /api/instances (or dedicated endpoint) with filtering. It takes 1s to get 1k instances and we may have more then 10k on production. I do not think we can make it fast for 10k instances without applying filter to backend request (+ virtual scrolling) or you have other opinion? 
* Consider virtual scrolling for the `Instances` tab. 

## 
* When you go to "Flows" tab you get on the screen:
```
Workflow Engine Monitoring & Management
flows: 2 • instances: 0 • errors: 0
```
I guess we need a separate query for that and not base it on response from /instances

## GWT style
Review all tests and rewrite them to GWT/Setup-Action-Verification style. Now I see cases where setup is done in "then". Best if setup and action is done directly in given and when blocks respectively in straightforward way. In case of problems with how the code from given/when tests are invoked consider InstancePerRoot instead default SingleInstance isolation. As a last resort you may consider moving setup and action to hooks (https://kotest.io/docs/framework/lifecycle-hooks.html) but would really prefer to not use them.

## ShowcaseFlowSeeder.processPendingEvents is quite complex
What about spawning a Thread (virtual) that sleeps for random number fo ms? I think this would remove a lot of code.

## Minor questions and remarks
* CockpitInstanceDto has:
 val status: StageStatus?,
 val activityStatus: CockpitActivityStatus?,
 In what case they are null?
* Update "Tick processing loop" in README.md with handling timer
* Add TOC to README.md, check if all header levels are correct 

## Coverage topic
* FE coverage
  * can we gather coverage for frontend code executed by Playwright tests? Try to implement it.
* BE coverage
  * are we gathering coverage from all tests including Playwright?

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
