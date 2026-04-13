## [DONE 2026-04-13.1] [REOPEN] Feedback to "Implement recommendations from cockpit-scaling-notes.md"

> I did not persist `activityStatus` in the summary table in this batch. I do not think it is a good next step inside the current generic `SpringDataJdbcHistoryStore`, because that would couple a generic history projection to Cockpit-specific stage semantics and engine flow definitions. If we want to persist it later, I would do that through a cockpit-owned projector instead of pushing Cockpit knowledge into the generic store.
> Validation: `./gradlew test`

>> MG: But isn't SpringDataJdbcHistoryStore basically only for cockpit? We added it to serve cockpit back than.

> Agent:
> - Response: Yes. After looking at the current shape again, I agree with that framing. In practice this summary projection is now Cockpit-oriented enough that persisting `activityStatus` there is justified.
> - Changes: Added persisted `activity_status` to `flowlite_instance_summary` and wired `SpringDataJdbcHistoryStore` so `Engine` sets an `activityStatus` resolver after flow registration.
> - Changes: Added a one-time refresh of existing summary rows with `NULL activity_status`, derived from their current `(flowId, stage, status)` tuple, so the new SQL filters do not miss legacy rows.
> - Changes: `CockpitService.listInstances(...)` now filters through SQL against the summary table instead of loading all rows and filtering in JVM code.
> - Validation: `./gradlew test`

## [DONE 2026-04-13.1] [REOPEN] Playwright MCP or playwright-cli plus skills?
I've installed playwright MCP server. Please use it for exploratory tests. Also check
https://github.com/microsoft/playwright?tab=readme-ov-file#playwright-cli and https://github.com/microsoft/playwright/blob/main/.claude/skills/playwright-dev/SKILL.md and compare what is better. MCP or playwright-cli + skill. 

> Agent:
> For ad-hoc exploratory testing, Playwright MCP is the better interface when the environment supports it. It gives direct page snapshots, targeted interactions, and quick steering without having to author a script first.
> `playwright-cli` plus a skill is better for reproducible scripted automation, codegen-heavy flows, and situations where we want the browser execution mode to live entirely inside the repo and CI scripts.
> For this repo I would use both, but for different jobs: MCP for manual exploratory sessions, checked-in Playwright tests/CLI for repeatable regressions.
> Changes: I started `./gradlew runPerfTestApp` and attempted the requested exploratory pass through Playwright MCP.
> Validation: The MCP browser could not start in this dev container because it launched a headed Chrome instance without an available X server / `DISPLAY`. That is an environment blocker in this container, not a Cockpit app failure.
> Response: In contrast, the repo's existing Java Playwright tests continue to work headlessly here, so CLI/test-driven automation remains the safer fallback in this specific environment.

>> MG:
>> * Playwright MCP cannot use headless browser?
>> * With playwright-cli you cannot run one command then see what is displayed on page and run next command?

> Agent:
> - Response: Playwright itself supports both headed and headless execution. MCP can also be wired that way in principle, but in this specific VS Code MCP integration available here I do not have a headless toggle and the server launched headed Chrome, which is why it failed without `DISPLAY`.
> - Response: `playwright-cli` is not limited to one-shot scripts. The Playwright docs explicitly show step-by-step command usage like `playwright-cli open ...`, then `playwright-cli type ...`, `playwright-cli press Enter`, `playwright-cli screenshot`, and a `playwright-cli show` dashboard for live session monitoring.
> - Response: One important nuance: the `.claude` `playwright-dev` skill you linked is a maintainer/development guide for adding Playwright APIs/MCP tools/CLI commands inside the Playwright repo itself. It is not the thing that gives end users interactive browser control.

## [DONE 2026-04-13.1] [REOPEN] What do you think about adding 3 index on status, stage and instance id?
... these will be quite frequent queries to FlowLiteInstanceSummary tables from "Instances" tab.

> Agent:
> I would not add those three single-column indexes in the current shape.
> Right now Cockpit loads `flowlite_instance_summary` rows by flow/all and then applies most `Instances` filtering in JVM code. Because the SQL does not predicate on `status`, `stage`, or free-text instance search, those indexes would not materially help the hot path yet.
> If we later push `Instances` filtering into SQL, then the useful indexes will be composite indexes that match the actual predicates and sort order, not three independent indexes by default.
> The existing unique key on `(flow_id, flow_instance_id)` and the `(flow_id, updated_at, flow_instance_id)` ordering index are the right base indexes for the current query plan.

>> MG: I think we should move whole filtering into SQL not in JVM code.

> Agent:
> - Response: Agreed, and I implemented that direction in this batch.
> - Changes: `Instances` filtering now goes through SQL on `flowlite_instance_summary`.
> - Response: I still did not add three standalone indexes. Instead I added composite indexes that match the new query shapes better: one around `(flow_id, status, stage, updated_at, flow_instance_id)` and one around `(flow_id, activity_status, updated_at, flow_instance_id)`.
> - Response: A standalone `instance id` index still does not buy us much because exact per-instance lookups are already covered by the unique `(flow_id, flow_instance_id)` key.

## [DONE 2026-04-13.1] listFlows question
What do you think about doing agregation in db query? Or at least moving part of it to db? Or you think it is better load everything to jvm and do in Kotlin?
I think that at least we do not need to load "Complete" and "Canceled" instances to save memory.

> Agent:
> - Response: I agree with moving this at least partially to DB.
> - Changes: `listFlows(...)` now gets per-flow counts and stage breakdown through SQL aggregation queries instead of materializing all summary rows in JVM and grouping there.
> - Response: This also addresses the specific memory point: we no longer load completed/cancelled instance rows as individual JVM objects just to compute flow counters and stage breakdowns.
> - Validation: `./gradlew test`

## [DONE 2026-04-13.1] Do we need then("it does not rebuild summaries lazily from history rows") test ?

> Agent:
> - Response: I do not think this test is worth keeping anymore. It was guarding a removed migration/backfill path rather than current behavior that is central to FlowLite.
> - Changes: Removed that test in this batch.

## [DONE 2026-04-13.1] given("the IT business-hours timer") - worth to have these tests? We are testing test code.

> Agent:
> - Response: Agreed. Those assertions were testing showcase/test-domain helper behavior rather than the FlowLite engine contract, so I do not think they are worth keeping in the main suite.
> - Changes: Removed those timer tests in this batch.

> Whole item agent notes:
> Changes: Persisted `activityStatus`, refreshed legacy rows missing it, moved `Instances` filtering into SQL, moved `listFlows` aggregation into SQL, added composite summary indexes for the new query shapes, and aligned the frontend long-inactive helper with the backend default semantics.
> Validation: `./gradlew test`

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
