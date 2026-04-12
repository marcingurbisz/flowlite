## Retry warning
> Retry on non-error rows requeues the current stage by sending the instance back through the same stage entry point.
I think this message shown on retry is not clear. Maybe just "Retry restarts the processing of the current stage." ?

## Flows tab
See: ![alt text](image.png). Feedback to it:
* 178 long inactive is red like errors. This is misleading
* long inactive should only consider Running and "Pending engine"
* Maybe worth to add "errors" next to "long inactive" and "incomplete"?
* is incomplete best name? active? or simply "all" because on Flow tab we always show only incomplete and not canceled?

## By default use timezone of the browser by default not UTC for timestamps on GUI

## Worth to add number of instances returned by backend
... somewhere on "Long inactive" and "Instances" tab?

## Many instances waiting on WaitForITBusinessHours on Render instance
... for more then 2h. Seems like some bug in our mechanism for sending events for seeded processes?

## Feedback to "Implement recommendations from cockpit-scaling-notes.md"
> added a lazy backfill path so existing deployments with already-populated history can repopulate the new summary table on first Cockpit read

>> MG: That's not needed. Remove it.

> I did not persist `activityStatus`/`cockpitStatus` in this batch. The projection now removes the expensive history window query, but `activityStatus` is still derived in service code from stage definitions. That keeps the first step smaller and lower-risk while still addressing the main bottleneck shape.

>> MG: If you agree that this is a good change go for it

## Playwright MCP or playwright-cli plus skills?
I've installed playwright MCP server. Please use it for exploratory tests. Also check
https://github.com/microsoft/playwright?tab=readme-ov-file#playwright-cli and https://github.com/microsoft/playwright/blob/main/.claude/skills/playwright-dev/SKILL.md and compare what is better. MCP or playwright-cli + skill. 

## [REOPEN] Sonar shows 0 coverage for tsx files

> Agent:
> - Response: Root cause was not missing instrumentation. The merged LCOV file used `SF:src/...` paths, while Sonar scans TSX sources from repo root as `cockpit-ui/src/...`, so Sonar could not match the coverage records back to the files it had indexed.
> - Changes: Updated the frontend coverage merge script to normalize coverage paths to repo-root-relative values before emitting `coverage-final.json` and `lcov.info`. That makes the final LCOV entries align with Sonar's `cockpit-ui/src` source tree.
> - Changes: Documented the new `-PfrontendCoverage=true` workflow in `README.md` and wired the main-branch CI Sonar job to enable frontend instrumentation explicitly.
> - Validation: Verified the merge script on a controlled coverage sample; the generated LCOV now emits `SF:cockpit-ui/src/App.tsx`.
> - Validation: `./gradlew -PfrontendCoverage=true test --tests io.flowlite.test.CockpitPlaywrightTest` stayed green in this container, but Playwright also reported missing host browser libraries and did not produce raw frontend coverage files here, so I could not re-check the fix against a real browser trace in this environment.

>> MG: Sonar still shows 0 coverage after recent changes. Check yourself if need here https://sonarcloud.io/component_measures?id=marcingurbisz_flowlite&metric=coverage&view=list

## Question to queries
```
interface FlowLiteInstanceSummaryRepository : CrudRepository<FlowLiteInstanceSummaryRow, UUID> {
    @Query(
        """
        select *
        from flowlite_instance_summary
        where (:flowId is null or flow_id = :flowId)
        order by flow_id asc, updated_at desc, flow_instance_id asc
        """,
    )
    fun findAllSummaries(flowId: String?): List<FlowLiteInstanceSummaryRow>

    @Query(
        """
        select *
        from flowlite_instance_summary
        where flow_id = :flowId and flow_instance_id = :flowInstanceId
        limit 1
        """,
    )
    fun findSummary(flowId: String, flowInstanceId: UUID): FlowLiteInstanceSummaryRow?
}
```

Why :flowId is null and limit 1? I think flowId should never be null and there should always be only one row for flowInstanceId.

## What do you think about adding 3 index on status, stage and instance id?
... these will be quite frequent queries to FlowLiteInstanceSummary tables from "Instances" tab.

## [REOPEN] Frontend and backend sources together
Moving cockpit-ui/src under source/cockpit. Pros and cons?

> Agent:
> - Response: I would keep them separate in this repo.
> - Response: Main upside of moving `cockpit-ui/src` under `source/cockpit` is locality: one subtree for the whole Cockpit feature, easier side-by-side browsing of router/service/frontend code, and a slightly stronger signal that Cockpit is one product slice rather than two semi-independent projects.
> - Response: The downsides are more important here. Kotlin/Gradle/JVM sources and Vite/TypeScript/browser sources have different toolchains, cache directories, dependency graphs, and packaging assumptions. Mixing them under one source tree makes source-set rules, Sonar scoping, IDE indexing, and future publishing boundaries less clear. It also obscures the fact that the frontend is an independently buildable static app with its own lifecycle.
> - Response: My recommendation is: keep the dedicated top-level `cockpit-ui/` project, but continue tightening naming and docs so the conceptual ownership is obvious. If you ever shrink the frontend into a tiny mostly-static bundle, then revisiting a co-located layout would make more sense.

>> MG: Get the point but still I'm not fully convinced :). Can you enumerate what we would need to change in fe and be build system to have the sources together?

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
