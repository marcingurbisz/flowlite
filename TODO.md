## [DONE 2026-04-12.1] tsconfig.app.tsbuildinfo
do czego ten plik? Dodasz go do gitignore czy lepiej zakomitować?

> Agent:
> - Response: `tsconfig.app.tsbuildinfo` to cache incremental compilation TypeScript tworzone przez `tsc -b`. Zawiera metadane o poprzednim buildzie, żeby kolejne przebiegi były szybsze; nie jest to artefakt źródłowy ani nic, co warto przeglądać w review.
> - Response: W tym repo lepiej go ignorować niż commitować. Plik i tak odtwarza się lokalnie po buildzie, a wersjonowanie go generuje tylko szum w diffach i konfliktach.
> - Changes: Dodałem `/cockpit-ui/*.tsbuildinfo` do `.gitignore` i usunąłem `cockpit-ui/tsconfig.app.tsbuildinfo` z indeksu gita, zostawiając lokalny plik roboczy bez zmian.
> - Validation: `git status --short` pokazuje teraz planowaną tylko jednorazową delecję śledzonego pliku wraz ze zmianą `.gitignore`; po commicie plik przestanie wracać jako zmiana repo.

## [DONE 2026-04-12.1] Extension point in IFE/AGENTS.md
I'd like to add something that I call now "extension point" (not sure if this is the best name) to workspace/AGENTS.md (IFE).
This extension point should instruct Agent that given repo may have it's own extension to IFE loop.
In flowlite I'd like to add following extensions:
* Move "[EVERY LOOP] Review your own changes" todo item from this list to the extension
* "Exploratory tests" item from this list to be done for each loop.

> Agent:
> - Response: `Repo loop extensions` is a good enough name here. I added it to the workspace-level `AGENTS.md` and then defined the FlowLite-specific extension in `flowlite/AGENTS.md`.
> - Changes: The base IEF now explicitly allows repo-level loop extensions. FlowLite now declares three concrete extensions: TODO-based final summary, recurring self-review, and recurring exploratory testing unless that item is explicitly marked `[ON HOLD]` in `TODO.md`.
> - Response: I kept the current `Exploratory tests` entry on hold because the TODO still says `[ON HOLD]`; the new extension tells the agent to honor that hold until you reopen it.

>> MG: Move extensions to separate file. See my changes in flowlite/AGENTS.md > Repo IEF loop extensions

> Agent:
> - Response: Done. The base IEF now allows repo extensions to live either directly in `AGENTS.md` or in a separate file explicitly pointed to by `AGENTS.md`.
> - Changes: In FlowLite, I replaced the inline extension block in `AGENTS.md`/`README.md` with a pointer to the dedicated file `docs/repo-ief-loop-extensions.md`, and moved the recurring `Exploratory tests` plus `Review your own changes` rules there.
> - Changes: Mirrored the same extension-file rule in `idea-execution-framework/AGENTS.md`, so the source IEF repo and workspace copy stay aligned.
> - Validation: Documentation-only change; verified the new file location is referenced from FlowLite `AGENTS.md` and that both IEF AGENTS files now describe the separate-file extension model.

## [DONE 2026-04-12.1] What is left in App.tsx?
Maybe move also details modal to separate file?

> Agent:
> - Response: After the earlier tab split, what remained in `App.tsx` was mostly the top-level cockpit container: shared state, URL sync, fetch/mutation handlers, selection logic, and the modal layer. The biggest leftover render block was indeed the instance details modal.
> - Changes: Extracted the instance details modal into `cockpit-ui/src/cockpit/modals/InstanceDetailsModal.tsx`. `App.tsx` now passes the selected instance state, timeline data, and action handlers into that dedicated component while keeping container responsibilities in one place.
> - Response: I intentionally did not split the smaller change-stage and action-confirmation dialogs yet. Those are much shorter and still reasonably tied to the container's mutation workflow, so pulling only the details modal gives a clear readability win without over-fragmenting the file.
> - Validation: `cd cockpit-ui && npm run build` → success.
> - Validation: `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-04-12.1] Implement recommendations from cockpit-scaling-notes.md
... see also my comment in cockpit-scaling-notes.md

> Agent:
> - Response: Implemented the main recommendation: Cockpit summary endpoints no longer rebuild instance state from `flowlite_history` on every request.
> - Changes: Added a new `flowlite_instance_summary` projection table and repository, updated `SpringDataJdbcHistoryStore` to keep that projection in sync on history writes, and switched `CockpitService` to read `/api/flows`, `/api/instances`, and `/api/errors` from the summary projection.
> - Changes: Kept `/timeline` on the history table and added a lazy backfill path so existing deployments with already-populated history can repopulate the new summary table on first Cockpit read.
> - Response: I did not persist `activityStatus`/`cockpitStatus` in this batch. The projection now removes the expensive history window query, but `activityStatus` is still derived in service code from stage definitions. That keeps the first step smaller and lower-risk while still addressing the main bottleneck shape.
> - Validation: `./gradlew test --tests io.flowlite.test.CockpitServiceTest` → BUILD SUCCESSFUL.
> - Validation: `./gradlew test --tests io.flowlite.test.CockpitPlaywrightTest` → BUILD SUCCESSFUL.
> - Validation: `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-04-12.1] Exploratory tests questions
Are you able to find following during exploratory tests?
  * "Loading instances" and then after some moment "Apply filters" on Instances tab - it should be "Apply filters" immediately
    > Agent:
    > - Response: Yes. I confirmed this issue from the actual render path and fixed it in this loop. The problem was that `InstancesView` rendered the loading state before checking whether any filters were applied, so the tab briefly showed `Loading instances…` even though no backend query should run yet.
    > - Changes: The `Instances` tab now prioritizes the `Apply filters` empty state over the loading card when no filters are active.
  * When I go from "Flows" after it displays to data then to Instances and back to Flows then old view flickers for a moment before showing "Loading flows"
    > Agent:
    > - Response: Yes. I confirmed the root cause and fixed it in this loop. `loadingView` was being set only inside the refresh effect, so after switching tabs there was one render where the new tab still showed stale content before the loading card took over.
    > - Changes: Tab/view transitions now set `loadingView` synchronously when the active tab changes, which removes that stale-content flash before `Loading flows` appears.
  * Can you record video from you exploratory tests not only screenshots?
    > Agent:
    > - Response: Yes. The existing Playwright `RecordedPageSession` helper already records videos as part of cockpit browser sessions. This loop's Playwright runs produced artifacts under `build/reports/playwright/videos`.
  * Are you able to analyze video or only screenshots?
    > Agent:
    > - Response: Screenshots are the stronger path with the current toolset because I can inspect them directly. Videos are useful as an artifact and for replay, but I do not have equally direct native frame-by-frame analysis on mp4 files in this environment. If needed, I can still use video for manual review or extract screenshots/frames from it.
  * With Playwright mcp server your tests could be more free - you would not need to prepare whole script upfront and could click and see what happens. Am I right? Worth to add it to your toolkit?
    > Agent:
    > - Response: Yes, that is basically right. A Playwright MCP server would make ad-hoc exploratory interaction more fluid because it supports shorter feedback loops than writing a whole deterministic repo test upfront.
    > - Response: I do think it would be worth adding if you want exploratory testing to be a frequent part of the workflow. The existing Java Playwright harness is still valuable for durable regression coverage, screenshots, and video artifacts, but MCP would improve freeform exploration.

> Whole item agent notes:
> - Changes: Fixed two Cockpit UI polish issues discovered during the exploratory pass: the incorrect `Loading instances…` flash before the `Apply filters` state, and the stale-content flicker before `Loading flows` on tab switches.
> - Validation: `cd cockpit-ui && npm run build` → success.
> - Validation: `./gradlew test --tests io.flowlite.test.CockpitPlaywrightTest` → BUILD SUCCESSFUL.
> - Validation: `./gradlew test` → BUILD SUCCESSFUL.

## [DONE 2026-04-12.1] Consider gating frontend coverage instrumentation behind a dedicated Gradle property or task
The current frontend coverage implementation enables `VITE_COVERAGE=true` in `buildCockpitUi`, so every `./gradlew test` rebuilds an instrumented Cockpit bundle even when the run is focused on backend-only tests. That keeps the setup simple and correct, but it is broader and slower than necessary. A likely next refinement is to enable instrumentation only for Playwright-oriented runs or a dedicated coverage task, while leaving the default backend test loop on the normal production-like frontend bundle.

> MG: What is the difference in between the run with coverage and run without? If it is not much I would not bother adding gradle prop/task for that.

> Agent:
> - Response: I measured the current Cockpit UI production build both ways. Without instrumentation `npm run build` finished in `8.239s`; with `VITE_COVERAGE=true` it finished in `9.785s`. The instrumented bundle also grew from `248.50 kB` to `432.15 kB` before gzip.
> - Response: That is not catastrophic, but it is material enough to justify a flag. The extra ~`1.55s` per frontend build is about a `19%` wall-clock increase, and the larger instrumented artifact is only useful for browser coverage runs.
> - Changes: Added an opt-in Gradle property `-PfrontendCoverage=true` that turns on `VITE_COVERAGE` only for coverage-oriented runs. Default `./gradlew test` now uses the normal Cockpit build; CI enables the property explicitly for the main-branch Sonar run.
> - Validation: Measured `cd cockpit-ui && TIMEFORMAT='elapsed=%R'; time npm run build` and `cd cockpit-ui && TIMEFORMAT='elapsed=%R'; time env VITE_COVERAGE=true npm run build`.

## [DONE 2026-04-12.1] Sonar shows 0 coverage for tsx files

> Agent:
> - Response: Root cause was not missing instrumentation. The merged LCOV file used `SF:src/...` paths, while Sonar scans TSX sources from repo root as `cockpit-ui/src/...`, so Sonar could not match the coverage records back to the files it had indexed.
> - Changes: Updated the frontend coverage merge script to normalize coverage paths to repo-root-relative values before emitting `coverage-final.json` and `lcov.info`. That makes the final LCOV entries align with Sonar's `cockpit-ui/src` source tree.
> - Changes: Documented the new `-PfrontendCoverage=true` workflow in `README.md` and wired the main-branch CI Sonar job to enable frontend instrumentation explicitly.
> - Validation: Verified the merge script on a controlled coverage sample; the generated LCOV now emits `SF:cockpit-ui/src/App.tsx`.
> - Validation: `./gradlew -PfrontendCoverage=true test --tests io.flowlite.test.CockpitPlaywrightTest` stayed green in this container, but Playwright also reported missing host browser libraries and did not produce raw frontend coverage files here, so I could not re-check the fix against a real browser trace in this environment.

## [DONE 2026-04-12.1] Frontend and backend sources together
Moving cockpit-ui/src under source/cockpit. Pros and cons?

> Agent:
> - Response: I would keep them separate in this repo.
> - Response: Main upside of moving `cockpit-ui/src` under `source/cockpit` is locality: one subtree for the whole Cockpit feature, easier side-by-side browsing of router/service/frontend code, and a slightly stronger signal that Cockpit is one product slice rather than two semi-independent projects.
> - Response: The downsides are more important here. Kotlin/Gradle/JVM sources and Vite/TypeScript/browser sources have different toolchains, cache directories, dependency graphs, and packaging assumptions. Mixing them under one source tree makes source-set rules, Sonar scoping, IDE indexing, and future publishing boundaries less clear. It also obscures the fact that the frontend is an independently buildable static app with its own lifecycle.
> - Response: My recommendation is: keep the dedicated top-level `cockpit-ui/` project, but continue tightening naming and docs so the conceptual ownership is obvious. If you ever shrink the frontend into a tiny mostly-static bundle, then revisiting a co-located layout would make more sense.

## [DONE 2026-04-12.1] Change FE from React to HTML+JS
Pros and cons?

> Agent:
> - Response: For the current Cockpit, I would not rewrite from React to plain HTML+JS.
> - Response: The argument for HTML+JS is valid when the UI is tiny, mostly static, or intentionally dependency-light. It would reduce framework/runtime dependencies, lower conceptual overhead for very small screens, and make the shipped artifact feel closer to a simple server-rendered/admin page.
> - Response: That is not the shape of this Cockpit anymore. The app already has tab state, URL synchronization, async data loading, filter forms, optimistic UI transitions, modal workflows, Mermaid rendering, and a growing set of reusable views/components. Rewriting that to manual DOM updates would trade one dependency for a lot of ad-hoc state and rendering code.
> - Response: So the likely outcome of switching to HTML+JS now is not real simplification; it is moving complexity into custom imperative code with weaker reuse and higher regression risk. If you want to reduce frontend stack weight later, a better question would be React-to-lighter-view-layer, not React-to-handwritten DOM from scratch.

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
