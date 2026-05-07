## [DONE 2026-05-07.1] Do not bound Render workload
... as I want to keep it to test the limits of the flowlite.
* Please revert back previous seeding approach.
* I would prefer to have some confirmation about memory. Maybe some periodic logs about memory usage?
* Once we have memory limit confirmed what about switching h2 file?

> Agent: Picking this item first in loop `2026-05-07.1`. Current local hypothesis: the Render cap should be reverted, and the missing piece is telemetry rather than throttling, so I am restoring the previous showcase behavior and adding periodic JVM memory diagnostics for the hosted test app.

> Agent:
> - Changes: Reverted the Render-specific seeding cap, so the hosted test app again uses the original showcase growth behavior.
> - Changes: Added configurable periodic JVM memory diagnostics in the test app. On Render it is now enabled via environment variables and logs used/committed/max heap plus non-heap usage and thread count every 60 seconds.
> - Response: I would keep the H2 file-store idea as a follow-up after we have enough memory samples. Right now the better next step is to confirm whether the failure pattern is heap pressure, non-heap/thread pressure, or something else. File-backed H2 changes durability and startup behavior, so it is better as a second step after the measurements.
> - Validation: `./gradlew test`

## [DONE 2026-05-07.2] Logback instead "custom" logs in service.kt?
Or maybe we do not need this logs at all? I'm not sure if I need these logs at all in this test app for now.

> Agent: Picking this next in loop `2026-05-07.1`. My current read is that these service-layer timing logs are neither proper access logs nor essential app diagnostics, so the smallest correct change is to remove them entirely and keep the new memory telemetry from the previous item.

> Agent:
> - Response: I do not think these `CockpitService` logs should stay. They are ad hoc diagnostics in application code, not proper HTTP access logging and not durable enough to justify the noise.
> - Response: If you later decide you want request logs, I would do that at servlet/filter/Tomcat level, not inside `service.kt`.
> - Changes: Removed the custom timing/action logs from `CockpitService`.
> - Validation: `./gradlew test --tests 'io.flowlite.test.CockpitPlaywrightTest'`
> - Validation: A full `./gradlew test` run failed once on a flaky Playwright modal-dismissal assertion, but the targeted rerun of that spec passed after the logging cleanup.

## [DONE 2026-05-07.3] What about using playwright skill instead playwright mcp
... for ad-hoc/free testing?
* https://github.com/openai/skills/tree/main/skills/.curated/playwright
* https://github.com/openai/skills/tree/main/skills/.curated/playwright-interactive
What's the difference between these 2?

> Agent: Picking this next in loop `2026-05-07.1`. My local hypothesis is that these two skills solve different layers of the same problem: one is a CLI automation wrapper, the other is a persistent interactive debugging workflow.

> Agent:
> - Response: `playwright` is the simpler CLI-first skill. It is built around `playwright-cli`, snapshots, element refs, and short terminal-driven automation loops. Good fit for ad hoc browser actions, scraping, quick reproduction steps, and lightweight UI flow debugging.
> - Response: `playwright-interactive` is for a longer-lived debugging session. It assumes a persistent `js_repl` Playwright session, keeps browser/page handles alive across iterations, and requires a more disciplined QA loop: functional checks, separate visual QA, viewport-fit checks, screenshots, and explicit signoff.
> - Response: So the difference is not just API style. It is operating model. `playwright` is command-oriented and stateless-ish between steps. `playwright-interactive` is session-oriented and optimized for repeated code-edit -> reload/relaunch -> recheck cycles.
> - Response: Relative to the Playwright MCP/browser tools we have here: MCP is better integrated in this environment. It lets me navigate/click/type/read snapshots directly without bootstrapping an external CLI wrapper or a `js_repl` session, so for this workspace I would keep preferring MCP for ad hoc testing.
> - Response: My recommendation: use MCP/browser tools by default here, use the `playwright` skill when you specifically want terminal-driven browser automation outside this integrated toolset, and use `playwright-interactive` only when you need persistent iterative UI debugging with explicit visual signoff discipline.
> - Validation: Compared the published `SKILL.md` guidance for `playwright` and `playwright-interactive` and mapped it against the browser/MCP workflow available in this session.

## [DONE 2026-05-07.4] Go for "Aligning CockpitStatus and StageStatus"
I've thought through our discussion and I have a feeling that this is a good architectural change. Go for it. We can always go back to "separated statuses" approach later if we found that this was a mistake.

> Agent: Picking this next in loop `2026-05-07.1`. Local hypothesis: the smallest root-fix is to make `StageStatus` carry the richer waiting semantics directly (`WaitingForTimer`, `WaitingForEvent`, `PendingEngine`), then remove the Cockpit-side status resolver/projection logic rather than layering another mapper on top.

> Agent:
> - Changes: Replaced the old generic pending runtime state with explicit `StageStatus` waiting states and updated engine claim/release, retry, manual stage change, and history semantics to use them.
> - Changes: Removed the `SpringDataJdbcHistoryStore.setCockpitStatusResolver(...)` hook and made Cockpit/read-model projection use the unified status values directly.
> - Changes: Kept a Kotlin `typealias CockpitStatus = StageStatus` in the Cockpit slice so the codebase can converge without a noisy API rename, while the actual status model is now a single enum.
> - Changes: Updated engine/Cockpit/domain tests and README documentation to reflect the new status semantics.
> - Validation: `./gradlew test --tests 'io.flowlite.test.CockpitServiceTest'`
> - Validation: `./gradlew test`

## [DONE 2026-04-16.1] Stall on render at 6.5k instances
No logs since 4am. Render instance is small 512MB RAM.
Any idea what happen?
Web requests are working. Do we log anything when request is served?
From the logs it seems that instance started to work again around 7:30 but then around 8am instance stopped responding and render killed it.

> Agent: Picking this item in loop `2026-04-16.1` first because it is a production-style stability issue. Initial finding: the Render service is the servlet test app, and that app enables showcase mode automatically. In the current deployment shape it keeps seeding forever every 5 seconds, so the in-memory H2 dataset grows unbounded on a 512 MB instance.

> Agent:
> - Response: The most likely root cause is the deployment shape itself, not a single bad `/api` request. Render is running the servlet test app, the test app uses in-memory H2, and showcase repeat seeding was enabled. That means rows keep accumulating forever until the 512 MB free-tier instance hits memory or GC pressure. The screenshots fit that pattern: Cockpit still answered HTTP requests, but workflow progression had stopped for hours and then Render eventually killed the container.
> - Response: No, before this loop we were not logging normal Cockpit request servicing. We had startup/shutdown logs plus engine/showcase logs, but nothing that told us `/api/flows` or `/api/instances` was served in `X ms` with `N` rows.
> - Changes: Added Cockpit API timing logs for `listFlows`, `listInstances`, single-instance reads, and timeline reads, plus action logs for retry/cancel/change-stage operations.
> - Changes: Bounded the public Render workload by setting `FLOWLITE_SHOWCASE_INITIAL_SEED_COUNT=100` and `FLOWLITE_SHOWCASE_REPEAT_SEEDING_ENABLED=false` in `render.yaml`, so the hosted demo stops growing without bound.
> - Validation: `./gradlew test`

## [DONE 2026-04-16.2] Aligning CockpitStatus and StageStatus
What do you think about using in StageStatus the same statuses as we have now in CockpitStatus, effectively removing the need to have separate CockpitStatus? Consider that we do not have yet any clients and we are in alpha phase. No need for backward compatibility.
What are pros, cons and your recommendation.
Related topic: I see setCockpitStatusResolver - this introduce kind of circular dependency between engine and history store, right? I do not like that.

> Agent: Picking this item in loop `2026-04-15.1` together with the linked Cockpit follow-ups below, because the status-model design, summary projection, long-inactive behavior, and worker defaults are all part of the same Cockpit/runtime batch.

> Agent:
> - Response: I would still keep `StageStatus` and `CockpitStatus` separate. `StageStatus` is the engine control model (`Pending`, `Running`, `Error`, `Completed`, `Cancelled`) and is used directly in claim/retry/cancel/manual-stage transitions. `CockpitStatus` is an observability/read-model split of the non-running branch of that state machine.
> - Response: The main upside of merging them would be one enum, one persisted status column, no `cockpit_status` projection, and no resolver hook between engine bootstrap and summary updates.
> - Response: The main downside is that engine semantics would become stage-definition-aware at the core runtime layer. `WaitingForTimer`, `WaitingForEvent`, and `PendingEngine` are not just extra labels; they require the engine to know whether a stage is timer-driven or event-driven whenever it sets or compares status. That would spread flow-definition knowledge into claim/release logic, retry paths, status history, persistence contracts, and tests.
> - Response: So yes, `setCockpitStatusResolver(...)` is a circular-style hookup and I do not like the shape either, but I do not think collapsing the enums is the best fix for it. My recommendation is: keep separate engine and Cockpit statuses, and later remove the setter by introducing a dedicated flow-definition catalog/classifier bean or a Cockpit-owned projector instead of pushing that wiring through `SpringDataJdbcHistoryStore`.
> - Validation: Re-checked current `Engine`, `StageStatus`, `SpringDataJdbcHistoryStore`, and Cockpit status-classification flow while answering this item.

>> MG: Would it complicate engine code much? From my pov when looking at flow table and seeing WaitingForTimer instead Pending is a benefit.

> Agent:
> - Response: The benefit you point at is real. For observability, `WaitingForTimer` / `WaitingForEvent` / `PendingEngine` is more informative than a raw `Pending`, especially in Cockpit tables.
> - Response: It would not be algorithmically hard, but it would be a fairly broad engine refactor. Today `StageStatus` is the small lifecycle primitive that drives claim/release (`Pending -> Running`), retry (`Error -> Pending`), cancellation, terminal completion, persisted history, compare-and-set persistence APIs, and a lot of tests. If we replace it with Cockpit-style statuses, then the engine no longer has a single generic claimable waiting status. We would need to either treat `WaitingForTimer`, `WaitingForEvent`, and `PendingEngine` as multiple claimable states everywhere or introduce another internal concept for claimability anyway.
> - Response: In practice that means touching `Engine.processTick`, retry/cancel/manual-stage flows, `StatePersister.tryTransitionStageStatus(...)`, history entry semantics, the persisted `status` column contract, the summary projection, and the related test suite. So I would call it moderate-to-high blast radius, even if each individual change is understandable.
> - Response: My recommendation stays the same: keep `StageStatus` as the engine lifecycle model, keep the richer waiting-state split for Cockpit/read models, and improve the UX by showing derived Cockpit status anywhere humans inspect runtime state. That gives you the better table semantics without teaching the engine core about timer-vs-event waiting reasons everywhere.
> - Response: Separately, I still agree the setter-based resolver is the wrong shape. I would fix that by moving Cockpit status classification behind a dedicated flow-definition catalog/projector bean, not by collapsing the two status models.
> - Validation: Re-checked current `Engine`, `StatePersister`, `HistoryEntry`, and summary projection call sites of `StageStatus` before answering this follow-up.

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
