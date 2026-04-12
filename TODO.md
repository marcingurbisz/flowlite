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

## What is left in App.tsx?
Maybe move also details modal to separate file?

## Implement recommendations from cockpit-scaling-notes.md
... see also my comment in cockpit-scaling-notes.md

## Exploratory tests questions
Are you able to find following during exploratory tests?
  * "Loading instances" and then after some moment "Apply filters" on Instances tab - it should be "Apply filters" immediately
  * When I go from "Flows" after it displays to data then to Instances and back to Flows then old view flickers for a moment before showing "Loading flows"
  * Can you record video from you exploratory tests not only screenshots?
  * Are you able to analyze video or only screenshots?
  * With Playwright mcp server your tests could be more free - you would not need to prepare whole script upfront and could click and see what happens. Am I right? Worth to add it to your toolkit?

## Consider gating frontend coverage instrumentation behind a dedicated Gradle property or task
The current frontend coverage implementation enables `VITE_COVERAGE=true` in `buildCockpitUi`, so every `./gradlew test` rebuilds an instrumented Cockpit bundle even when the run is focused on backend-only tests. That keeps the setup simple and correct, but it is broader and slower than necessary. A likely next refinement is to enable instrumentation only for Playwright-oriented runs or a dedicated coverage task, while leaving the default backend test loop on the normal production-like frontend bundle.

> MG: What is the difference in between the run with coverage and run without? If it is not much I would not bother adding gradle prop/task for that.

## Sonar shows 0 coverage for tsx files

## Frontend and backend sources together
Moving cockpit-ui/src under source/cockpit. Pros and cons?

## Change FE from React to HTML+JS
Pros and cons?

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
