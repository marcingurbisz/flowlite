## [DONE 2026-05-08.1] How to you assess "Aligning CockpitStatus and StageStatus" now
... once you did it in f353837c. Do you still think it's better to have these 2 statuses separate?
Do you still agree with what you wrote in "[DONE 2026-04-16.2] Aligning CockpitStatus and StageStatus" ?

> Agent: Picking this item first in loop `2026-05-08.1` because it is the design checkpoint for the changes already landed in `f353837c`.

> Agent:
> - Response: After doing the refactor, I no longer think keeping the two statuses separate would be better for this codebase as it exists today. The unified model removed the awkward resolver hook, removed duplicated semantics in the read model, and made the persisted/runtime state more honest for Cockpit.
> - Response: I still agree with the core warning from `2026-04-16.2`: the merge does increase engine blast radius and it does make engine code stage-definition-aware in a way it was not before. That warning was correct. What changed is that we now know the refactor is still tractable and the resulting design is simpler overall than the previous resolver-based split.
> - Response: So my updated position is: the old recommendation was reasonable before implementation, but after implementation and validation I would keep the unified model and iterate on it instead of going back to separate `CockpitStatus` and `StageStatus`.
> - Validation: Re-checked the post-`f353837c` engine, summary projection, Cockpit service, and tests before answering.

## Feedback to f353837c
* typealias CockpitStatus = StageStatus - remove it and use StageStatus instead
* Remove StageStatus.Pending

## Can wy simplify FlowLiteInstanceSummaryRow.apply()?
This big whens do not look nice and I wonder if we can have better design here.

## I do not see diagnostic logs on render
Are you sure that FLOWLITE_SHOWCASE_INITIAL_SEED_COUNT is available to spring boot?

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
