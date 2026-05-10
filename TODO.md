## [DONE 2026-05-10.1] On render I have now this log
memory diagnostics config enabled=false requestedIntervalSeconds=60 effectiveIntervalSeconds=60 rawEnabledEnv=null rawIntervalEnv=null

> Agent:
> `rawEnabledEnv=null` and `rawIntervalEnv=null` mean the Render runtime process still does not receive those environment variables.
> Changes: no further runtime code changes were needed for this item because the diagnostics now expose the root cause clearly.
> Validation: reviewed the emitted log and previous local verification that Spring binding works when env vars are actually present.
> Learning: this is now a deployment/configuration issue on Render, not a code-path or property-binding issue in FlowLite.

## [DONE 2026-05-10.1] Concept for "Auto-retry" and "User retriable"
Added few inline remarks to [AutoRetryConcept.md](docs/AutoRetryConcept.md). Please think about them.
Please implement MVP including "external-retry" too, but without any changes in our test app to trigger external retries.
Cover it with tests including playwright.
Please give me screenshots showing how this looks like in cockpit.

> Agent:
> Answered the inline remarks in the implementation and refreshed [docs/AutoRetryConcept.md](docs/AutoRetryConcept.md) to match the shipped model.
> Changes: added `FailureClassifier`/`FailureHandling`/`AutoRetryPlan`, durable retry state plus retry-aware history metadata, `Engine.externalRetry(...)`, Cockpit retry badges/details, and Playwright screenshot capture without adding new external-retry triggers to the test app.
> Validation: `./gradlew test --tests 'io.flowlite.test.RetryBehaviorTest'`; `./gradlew test --tests 'io.flowlite.test.CockpitServiceTest'`; `./gradlew test --tests 'io.flowlite.test.CockpitPlaywrightTest'`.
> Learning: keeping one `Error` status plus separate retry metadata gave enough room for `Auto`, `External`, and `Cockpit` retry distinctions without inflating the runtime status model.

## [DONE 2026-05-10.1] Exploratory tests

> Agent:
> Ran `./gradlew runPerfTestApp` for a large local showcase dataset, verified the app responds with `HTTP/1.1 200` on `/cockpit`, and reviewed the generated Cockpit screenshots for the new retry badges/details.
> Changes: none.
> Validation: `curl -I http://127.0.0.1:8080/cockpit`; visual smoke on Playwright artifacts `build/reports/playwright/screenshots/cockpit-retry-badges-list.png` and `build/reports/playwright/screenshots/cockpit-retry-badges-details.png`.
> Learning: no obvious visual regressions were visible in the retry UI slice; browser automation from the chat browser could not reach container localhost, so the visual smoke used saved Playwright artifacts instead.

## [DONE 2026-05-10.1] Review own changes

> Agent:
> Reviewed engine retry flow, JDBC persistence/history mapping, Cockpit retry rendering, and the new tests for both execution and UI coverage.
> Changes: added a Cockpit fallback to read retry metadata from the latest error history row so the UI stays informative even when a retry-state lookup does not supply data.
> Validation: same focused test suite as above plus screenshot inspection.
> Learning: the error history is a strong secondary source of truth for retry UI, not just an audit trail.

## [FOR HUMAN REVIEW] Retry metadata source of truth

> Agent:
> Cockpit now prefers retry-state rows but falls back to the latest error history row when retry metadata is unavailable from that store. The runtime path writes both, so the UI stays correct.
> If you want a stricter architecture later, we should decide whether to keep both sources intentionally or simplify to one authoritative read model.

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
