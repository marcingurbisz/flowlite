## [REOPEN] Feedback to "Implement recommendations from cockpit-scaling-notes.md"

> - Response: I did not persist `activityStatus` in the summary table in this batch. I do not think it is a good next step inside the current generic `SpringDataJdbcHistoryStore`, because that would couple a generic history projection to Cockpit-specific stage semantics and engine flow definitions. If we want to persist it later, I would do that through a cockpit-owned projector instead of pushing Cockpit knowledge into the generic store.
> - Validation: `./gradlew test`

>> MG: But isn't SpringDataJdbcHistoryStore basically only for cockpit? We added it to serve cokpit back than.

## [REOPEN] Playwright MCP or playwright-cli plus skills?
I've installed playwright MCP server. Please use it for exploratory tests. Also check
https://github.com/microsoft/playwright?tab=readme-ov-file#playwright-cli and https://github.com/microsoft/playwright/blob/main/.claude/skills/playwright-dev/SKILL.md and compare what is better. MCP or playwright-cli + skill. 

> Agent:
> - Response: For ad-hoc exploratory testing, Playwright MCP is the better interface when the environment supports it. It gives direct page snapshots, targeted interactions, and quick steering without having to author a script first.
> - Response: `playwright-cli` plus a skill is better for reproducible scripted automation, codegen-heavy flows, and situations where we want the browser execution mode to live entirely inside the repo and CI scripts.
> - Response: For this repo I would use both, but for different jobs: MCP for manual exploratory sessions, checked-in Playwright tests/CLI for repeatable regressions.
> - Changes: I started `./gradlew runPerfTestApp` and attempted the requested exploratory pass through Playwright MCP.
> - Validation: The MCP browser could not start in this dev container because it launched a headed Chrome instance without an available X server / `DISPLAY`. That is an environment blocker in this container, not a Cockpit app failure.
> - Response: In contrast, the repo's existing Java Playwright tests continue to work headlessly here, so CLI/test-driven automation remains the safer fallback in this specific environment.

>> MG:
>> * Playwright MCP cannot use headless blowser?
>> * With playwright-cli you cannot run one command then see what is displayed on page and run next command?

## [REOPEN] What do you think about adding 3 index on status, stage and instance id?
... these will be quite frequent queries to FlowLiteInstanceSummary tables from "Instances" tab.

> Agent:
> - Response: I would not add those three single-column indexes in the current shape.
> - Response: Right now Cockpit loads `flowlite_instance_summary` rows by flow/all and then applies most `Instances` filtering in JVM code. Because the SQL does not predicate on `status`, `stage`, or free-text instance search, those indexes would not materially help the hot path yet.
> - Response: If we later push `Instances` filtering into SQL, then the useful indexes will be composite indexes that match the actual predicates and sort order, not three independent indexes by default.
> - Response: The existing unique key on `(flow_id, flow_instance_id)` and the `(flow_id, updated_at, flow_instance_id)` ordering index are the right base indexes for the current query plan.

>> MG: We shold move whole filterng into SQL not in JVM code.

## listFlows question
What do you think about doing agregation in db query? Or at least moving part of it to db? Or you think it is better load everything to jvm and do in Kotlin?
I think that at least we do not need to load "Complete" and "Canceled" instances to save memory.

## Do we need then("it does not rebuild summaries lazily from history rows") test ?

## given("the IT business-hours timer") - worth to have these tests? We are testing test code.

## [ONHOLD] Frontend and backend sources together
Moving cockpit-ui/src under source/cockpit. Pros and cons?

> Agent:
> - Response: I would keep them separate in this repo.
> - Response: Main upside of moving `cockpit-ui/src` under `source/cockpit` is locality: one subtree for the whole Cockpit feature, easier side-by-side browsing of router/service/frontend code, and a slightly stronger signal that Cockpit is one product slice rather than two semi-independent projects.
> - Response: The downsides are more important here. Kotlin/Gradle/JVM sources and Vite/TypeScript/browser sources have different toolchains, cache directories, dependency graphs, and packaging assumptions. Mixing them under one source tree makes source-set rules, Sonar scoping, IDE indexing, and future publishing boundaries less clear. It also obscures the fact that the frontend is an independently buildable static app with its own lifecycle.
> - Response: My recommendation is: keep the dedicated top-level `cockpit-ui/` project, but continue tightening naming and docs so the conceptual ownership is obvious. If you ever shrink the frontend into a tiny mostly-static bundle, then revisiting a co-located layout would make more sense.

>> MG: Get the point but still I'm not fully convinced :). Can you enumerate what we would need to change in fe and be build system to have the sources together?

> Agent:
> - Response: Yes. If we colocated the frontend sources under `source/cockpit`, the build-system changes would be concrete rather than cosmetic.
> - Response: Gradle source sets: we would need to stop treating the whole `source/` tree as both Kotlin sources and JVM resources, otherwise the frontend source tree would be swept into `processResources`. That means redefining `sourceSets.main.resources` to exclude the frontend subtree or moving JVM resources to a narrower directory.
> - Response: Frontend tool root: Vite, TypeScript, `package.json`, lockfile, and `node_modules` would need either a new root under `source/cockpit` or a top-level package file that points into that subtree. Today the frontend build assumes `cockpit-ui/` is its working directory.
> - Response: Gradle frontend tasks: `installCockpitUiDeps`, `buildCockpitUi`, coverage merge paths, and dist sync paths would all need their working directory and input globs rewritten away from `cockpit-ui/**`.
> - Response: Sonar config: `sonar.sources`, `sonar.tests`, and frontend LCOV path normalization would need to move from `cockpit-ui/src` and `cockpit-ui/tests` to the new colocated paths.
> - Response: Test/resource packaging: the copy-to-test-app step would still need to publish a built static dist into test resources; colocating sources does not remove that packaging boundary.
> - Response: Developer tooling: TS config, Vite config, IDE excludes, and cache ignores would all need to be retargeted so TypeScript artifacts do not pollute the JVM `source/` tree.
> - Response: So it is feasible, but it is a real Gradle/Vite/source-set reshaping task, not just a folder move.

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
  > - Response: Considered but intentionally not implemented in this batch. After the gated `Instances` tab and backend-filtered heavy views, virtual scrolling is no longer the first bottleneck. I still recommend it as a later follow-up if filtered result sets themselves become large in production.
* Visual testing - comparing screenshots before and after changes? Not sure about it because when agent can
see produced image maybe this be enough for visual inspection and comparing will not be needed?
* Check coverage and suggest modifications/new tests to cover it
* There are no logs that show how the /flows /instances processing goes. Now I cannot find out whether the query takes so long or it is processing in JVM code.
* The GWT cleanup showed that the Cockpit Playwright spec now needs a small `RecordedPageSession` helper to keep browser setup/actions in `when` blocks while preserving failure screenshots/videos. If we add more browser scenarios, it may be worth introducing a tiny test DSL/helper layer for `open page -> act -> assert -> close` flows so future specs do not repeat the same session lifecycle/synchronization plumbing.
* Websocket for live refresh
* New/duplicate cockpit but in Kotlin
