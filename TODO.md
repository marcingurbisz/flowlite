## Aligning CockpitStatus and StageStatus
What do you think about using in StageStatus the same statuses as we have now in CockpitStatus, effectively removing the need to have separate CockpitStatus? Consider that we do not have yet any clients and we are in alpha phase. No need for backward compatibility.
What are pros, cons and your recommendation.
Related topic: I see setCockpitStatusResolver - this introduce kind of circular dependency between engine and history store, right? I do not like that.

## Is /api/errors and listErrorGroups in use? Looks to me that we can remove it

## Index question
create index if not exists idx_flowlite_instance_summary_updated on flowlite_instance_summary(flow_id, updated_at, flow_instance_id);
create index if not exists idx_flowlite_instance_summary_status_stage on flowlite_instance_summary(flow_id, cockpit_status, stage, updated_at, flow_instance_id);
create index if not exists idx_flowlite_instance_summary_cockpit_status on flowlite_instance_summary(flow_id, cockpit_status, updated_at, flow_instance_id);
Are we always providing flow_id when we query cockpit_status or updated_at?

## Default values in FlowLiteInstanceSummaryRow
Why we have default values in:
```
@Table("FLOWLITE_INSTANCE_SUMMARY")
data class FlowLiteInstanceSummaryRow(
    val status: String = StageStatus.Pending.name,
    val cockpitStatus: String = "PendingEngine",
)
```

## Clicking on long inactive in Flow tab do not update status filter on Long Inactive tab
... so when you changed it manually before you do not get "Running + pending engine"

## Local flowlite app process used for exploratory tests with 7k instances was consuming 25% cpu
This was without any real use of the app. Background processing (e.g. tick scheduler) should not use that much I think. Please check that next time you run Exploratory.


## Many Pending engine inactive for a long time on render
Right now 361 instances raging for few sec to ~ 30m 17s
I wonder if this is because of too little worker threads or because ticks are lost. I think the first one because I see that these waiting ~30min are finally picked up.
Please increase default value for workers from 40 to 60.

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
