## [DONE 2026-04-09] Problem with forgetting items from the beginning at the end of the loop
In the last loop you had many todos (some bigger) and at the end of the loop you haven't remember the one from begging - you have reported only last ones in the chat (probably because of compaction/summarization of your context). This is not a big deal now but if at the end of the loop I would like you to do some task for all done items then it could be a problem. I wonder how we can improve that? Maybe some additional instruction for you in workspace/AGENTS.md for you to look at the TODO file and report all the things done in this loop based on this file? Maybe introduce IFE loop number next to done e.g. [Done 2026-03-25.1] so if there are more then one loop during the day you will not mix todo items? Any other ideas?

> Agent:
> - Response: Yes, the right fix is to make the final loop summary come from `TODO.md` rather than from chat memory. I also agree with introducing a per-loop label when multiple loops can happen on the same day.
> - Response: I implemented this in AGENTS rather than trying to rely on ad-hoc prompting. The base IEF now says the active TODO file is the authoritative loop ledger and that the final summary must come from a fresh TODO rescan. The repo-level FlowLite extension now adds a preferred loop-label format `YYYY-MM-DD.N` for future loops.
> - Response: I intentionally did not retroactively rename old TODO items in this batch. The value is in the rule going forward, not in churn across history.

## [DONE 2026-04-09] Perf tests app
Worth to add a flag that instead of seeding it will create a >1k instances so we can test perf issues immediately without the need to wait for instance number to accumulate since start? The benefits of this would be that you could do exploratory tests on local instance with big number of data without waiting on deployment and accumulation of instances on Render instance.

> Agent:
> - Response: Yes, this is worth having. The lowest-friction shape is to keep the existing showcase app but make its startup batch configurable, then expose a dedicated `runPerfTestApp` task with sensible perf-oriented defaults.
> - Changes: Added two showcase properties: `flowlite.showcase.initial-seed-count` for the immediate startup batch and `flowlite.showcase.repeat-seeding-enabled` to turn the 5-second trickle on/off. Added `./gradlew runPerfTestApp`, which starts the servlet app with `600` startup seed cycles, producing `1200` showcase instances immediately while disabling random delays, failures, and repeat seeding.
> - Changes: Updated `TestApplicationMain` so Spring args can be passed through directly, documented the new task/properties in `README.md`, and added test coverage for the larger startup batch in `ShowcaseFlowSeederTest`.
> - Validation: `./gradlew test --tests io.flowlite.test.ShowcaseFlowSeederTest` → BUILD SUCCESSFUL.
> - Validation: `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-04-09] Extension point in IFE/AGENTS.md
I'd like to add something that I call now "extension point" (not sure if this is the best name) to workspace/AGENTS.md (IFE).
This extension point should instruct Agent that given repo may have it's own extension to IFE loop.
In flowlite I'd like to add following extensions:
* Move "[EVERY LOOP] Review your own changes" todo item from this list to the extension
* "Exploratory tests" item from this list to be done for each loop.

> Agent:
> - Response: `Repo loop extensions` is a good enough name here. I added it to the workspace-level `AGENTS.md` and then defined the FlowLite-specific extension in `flowlite/AGENTS.md`.
> - Changes: The base IEF now explicitly allows repo-level loop extensions. FlowLite now declares three concrete extensions: TODO-based final summary, recurring self-review, and recurring exploratory testing unless that item is explicitly marked `[ON HOLD]` in `TODO.md`.
> - Response: I kept the current `Exploratory tests` entry on hold because the TODO still says `[ON HOLD]`; the new extension tells the agent to honor that hold until you reopen it.

## [DONE 2026-04-09] Worth to split App it separate files for tabs?
... or you suggest some different split. I do not what to create too much files but >1k lines is probably to much. Or it its ok for you as it is now?

> Agent:
> - Response: At the current size (`cockpit-ui/src/App.tsx` is ~1679 lines) I do think a split is worth it. The main issue is not only line count, but that one file currently mixes DTO/types, URL/query-string state handling, fetch/update actions, selection/filter logic, modal flows, Mermaid rendering, and all four tab render trees.
> - Response: I would not split aggressively into dozens of tiny files. My recommendation is a small structural split around real responsibilities.
> - Response: Recommended minimum split: keep one top-level container component that owns shared state and routing, then extract one file per major tab (`FlowsTab`, `ErrorsTab`, `LongInactiveTab`, `InstancesTab`), plus one shared file for reusable UI pieces such as badges/modals/formatters, and one file for cockpit DTO/types + URL-state helpers.
> - Response: Pros of that split: each tab becomes much easier to read/test/change in isolation; tab-specific regressions are less likely to create merge conflicts across the whole file; the current repeated table/filter/action patterns become easier to see and simplify.
> - Response: Cons: some props plumbing will increase unless we also extract a small state/actions hook; if done carelessly, it can create too many near-empty wrapper files and make navigation worse instead of better.
> - Response: Current recommendation: yes, split it, but do it as a medium refactor rather than a “one file per component” cleanup. I would target roughly 6-8 files total for the cockpit UI, not dozens.
> - Validation: Design-only item; validated against the current `App.tsx` structure and size.

>> MG: Go for it

> Agent:
> - Changes: Split the Cockpit UI into a medium-granularity structure instead of many tiny files. `App.tsx` now owns state, effects, fetches, and modals, while tab render trees and shared UI/helpers moved into dedicated modules under `cockpit-ui/src/cockpit/`.
> - Changes: Added extracted files for cockpit types, URL/location state handling, generic formatting/history helpers, shared badges, Mermaid rendering, and one file per major tab view (`FlowsView`, `ErrorsView`, `LongRunningView`, `InstancesView`).
> - Response: This keeps the main container readable without over-fragmenting the UI. The split ended up close to the previously recommended 6-8 file range, with shared responsibilities grouped by concern instead of one-file-per-small-component.
> - Validation: `cd cockpit-ui && npm run build` → success.
> - Validation: `./gradlew test` → BUILD SUCCESSFUL.

## Further Cockpit scaling follow-ups
* All FE calls end up with calling findLatestRowsPerType which I guess takes most of the processing time (few sec) when we have 1k or more instances. Can we do some optimizations of this query or add some indexes? Is this a right approach?
What about creating a dedicated table that would keep latest state as regards the stage, status, timestamp for given instance and thanks to that have very fast queries. What do you think?
Also in this table we could have status for cockpit (CockpitStatus) which will include "Pending event", "Pending timer" and "Pending scheduler" (is pending scheduler a good name?)
* In what cases instances are in "Pending scheduler"/Pending (Pending)?
* Only timeline should query history table and should filter rows in db and not as we have it now read everything to memory and filter there.

## Frontend improvements
* When I select stage on instance list and want to copy it instance details open. Can we prevent it and open only when click without selecting text for copy? Or there some other UX pattern for that?
* When I switch between tabs old list (from other tab) is presented first and then it is replaced by new data from backend and there is no loading indicator and queries can run for few seconds now.

## [ON HOLD] Topics on hold for now
* Concept for "Auto-retry" and "User retriable"
* Long Inactive tab
  * Default filter should be "Running and Pending scheduler"
* Think about making CockpitStatus and engine Status the same
* Consider virtual scrolling for the `Instances` tab.
  > Agent:
  > - Response: Considered but intentionally not implemented in this batch. After the gated `Instances` tab and backend-filtered heavy views, virtual scrolling is no longer the first bottleneck. I still recommend it as a later follow-up if filtered result sets themselves become large in production.
* Visual testing - comparing screenshots before and after changes? Not sure about it because when agent can
see produced image maybe this be enough for visual inspection and comparing will not be needed?
* Check coverage and suggest modifications/new tests to cover it
* There are no logs that show how the /flows /instances processing goes. Now I cannot find out whether the query takes so long or it is processing in JVM code.
* The GWT cleanup showed that the Cockpit Playwright spec now needs a small `RecordedPageSession` helper to keep browser setup/actions in `when` blocks while preserving failure screenshots/videos. If we add more browser scenarios, it may be worth introducing a tiny test DSL/helper layer for `open page -> act -> assert -> close` flows so future specs do not repeat the same session lifecycle/synchronization plumbing.
* Websocket for live refresh
* New/duplicate copkpit but in Kotlin

## [REOPEN] Coverage topic
* FE coverage
  * can we gather coverage for frontend code executed by Playwright tests? Try to implement it.
  > Agent:
  > - Response: Yes. The Cockpit UI test build is now Istanbul-instrumented when Gradle runs Playwright-backed tests, and `CockpitPlaywrightTest` persists `globalThis.__coverage__` snapshots from browser sessions into raw JSON artifacts that are merged into HTML, LCOV, and JSON reports after the test task.
  > - Changes: Added Vite Istanbul instrumentation, a frontend coverage merge/report script, Gradle wiring to enable instrumentation and publish the merged report, and Playwright-side snapshot export from recorded browser sessions.
  > - Validation: `./gradlew test --tests io.flowlite.test.CockpitPlaywrightTest --rerun-tasks` → BUILD SUCCESSFUL, frontend coverage merged from 13 snapshot files.

>> MG: I do not see anything under build/reports/playwright/frontend-coverage/. Also github build did not publish the results for FE code to Sonar.

## [ON HOLD / MOVED TO AGENTS 2026-04-09] Exploratory tests
I'd like you to do exploratory tests using our test Flowlite instance on Render. Search for bugs and performance issues. Please document what you have tested and do the screenshots documenting the bugs. Are you able to do it right away or you need some additional tooling e.g. playwright installed in container or playwright MCP? Let me know do you need and I will give it to you :).
Note: the version currently deployed on Render is the one before this loop.

> Agent:
> - Response: I was able to do it right away with the tooling already present in the container. The integrated browser tool was not enough for DOM inspection, but the repo's existing Playwright Java stack worked for remote browser automation and screenshot capture.
> - Changes: Added the exploratory note `memory/RenderExploratory-2026-03-25.md` and stored screenshots under `memory/render-exploratory-2026-03-25/`.
> - Findings: Confirmed two visible regressions on the deployed pre-loop version: the landing header shows `flows: 0 • instances: 0 • errors: 0` on first load, and the `Instances` tab still skips the later filter-gate UX and renders the table shell immediately.
> - Findings: Collected endpoint timing samples as a performance baseline: `/api/instances` `1.296125s` for `630331` bytes, `/api/errors` `3.933635s` for `18582` bytes, `/api/flows` `1.427504s` for `7933` bytes.
> - Validation: Reached the deployed Render instance, captured screenshots and page text via a temporary Playwright probe, then removed the probe from the repo after collecting the artifacts.

>> MG: What was this Playwright probe? I will ask you to do Exploratory tests quite often so probably worth to keep the code that helps you do that in repo.
>>> Agent:
>>> Response: The probe was a temporary one-off Playwright test used only to drive the deployed Render UI and dump screenshots/text artifacts. I agree it should become durable repo tooling instead of staying disposable. The FlowLite repo extension now treats exploratory testing as recurring loop work, so the next time this item is reopened I should add reusable exploratory helper code rather than another throwaway probe.

## [MOVED TO AGENTS 2026-04-09] Review your own changes
Please review your own changes from this loop, looking of potential improvements/simplifications both in the code and sounding concepts. Report any finding as a new todo item with [FOR HUMAN REVIEW] marker.

> Agent:
> - Response: Reviewed the recent cockpit/coverage changes. I did not find a correctness issue that needs immediate code rollback, but I did find one worthwhile follow-up and recorded it below.

> Agent:
> - Response: This recurring step is now defined in `flowlite/AGENTS.md` as a repo loop extension, so it no longer needs to stay in the main actionable queue.

## Consider gating frontend coverage instrumentation behind a dedicated Gradle property or task
The current frontend coverage implementation enables `VITE_COVERAGE=true` in `buildCockpitUi`, so every `./gradlew test` rebuilds an instrumented Cockpit bundle even when the run is focused on backend-only tests. That keeps the setup simple and correct, but it is broader and slower than necessary. A likely next refinement is to enable instrumentation only for Playwright-oriented runs or a dedicated coverage task, while leaving the default backend test loop on the normal production-like frontend bundle.

> MG: What is the difference in between the run with coverage and run without? If it is not much I would not bother adding gradle prop/task for that.

## [FOR HUMAN] review for improvements
* Research: How Peter and Simon are handling that (prompts, workflows)

## [FOR HUMAN] Review git changes
* loop starting from 75abfec728b2184501ae4ebae0c83c07f1e0ba23
* loops starting from
  * docs: TODO update marcingurbisz 3/8/26, 9:12 AM
  * docs: update TODO marcingurbisz 3/7/26, 3:57 PM
  * docs: update TODO marcingurbisz 3/6/26, 7:13 AM