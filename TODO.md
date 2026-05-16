## [FOR HUMAN]
* Check on GUI:
  retryTrigger?: RetryTrigger | null;
  failedAttemptCount?: number | null;
  autoRetryMaxAttempts?: number | null;
  nextAutoRetryAt?: string | null;
  externalRetryAllowed?: boolean | null;
* Review engine once again
* Review CockpitPlaywrightTest

## [DONE 2026-05-16.1] Render restarts investigation
* Revert setting FLOWLITE_TICK_WORKER_THREADS to 8
* From Claude: Render instance dies after hours with `read: connection reset by peer` on health check. TCP RST mid-response = process killed (likely container OOM-kill on free tier 512 MiB, since JVM heap maxed at 123 MiB rules out JVM-level OOM). Free tier has no memory metrics, so we must log RSS ourselves. Extend memory diagnostics logger — add `rssMiB`, `directMiB`, `mappedMiB`

> Agent:
> Changes: removed the Render-specific `FLOWLITE_TICK_WORKER_THREADS=8` override and restored the test app scheduler wiring to the library default. Extended `PeriodicMemoryLogger` to emit `rssMiB`, `directMiB`, and `mappedMiB` in addition to the existing JVM heap/non-heap metrics.
> Validation: `./gradlew test --tests 'io.flowlite.test.CockpitServiceTest'`
> Learning: the previous worker-thread mitigation and the new OOM-kill hypothesis point in different directions, so the safer move is to revert the concurrency override and increase observability first. `rssMiB` should help distinguish JVM-managed memory from whole-process memory growth on Render.

## [DONE 2026-05-16.1] Exploratory test with runPerfTestApp

> Agent:
> Answer: yes, when the live app is reachable I prefer browser automation first, Playwright-style scripted automation second, and CLI third.
> Pros/cons: browser automation is fastest for interactive exploration and seeing the real rendered state, but it is weaker for repeatable long flows and low-level diagnostics; scripted Playwright automation is better for reproducibility, waits, and deeper debugging, but costs more setup; CLI is the lightest fallback for smoke checks and screenshots from inside the container, but it gives the least observability and weakest interaction model.
> Changes: added an explicit exploratory tool preference order and fallback rule to `ief-loop-extensions.md`.
> Validation: reviewed the updated loop-extension text against the just-completed browser-access check and earlier CLI fallback path so the rule matches how the repo was actually exercised.
> Learning: the right default is not one universal tool but a preference ladder based on reachability and diagnostic depth.
>> MG: Where would you put playwright MCP?

> Agent:
> Between browser automation and Playwright CLI.
> Reasoning: MCP is closer to scripted Playwright automation than to one-shot CLI usage. It is stronger than CLI when you need multi-step control, waits, or repeatability, but still less direct than live browser automation for quick visual exploration.
> Updated order: browser automation first, Playwright MCP second, Playwright CLI third.
> Changes: refined the exploratory-testing rule in `ief-loop-extensions.md` so MCP and CLI no longer share the same fallback bucket.
> Validation: reviewed the updated rule against the kinds of exploratory work we already did here: live Cockpit inspection fits browser automation, richer scripted repro would fit MCP, and single-screenshot fallback fits CLI.
> Learning: MCP is best treated as the structured fallback for interactive automation, while CLI remains the lightweight smoke-check tool.

## [REOPEN] Why scheduled tick needs target stage?
We know where the flow is right now so we know what should be the next stage, don't we?
Do we need to decode stage into a tick?

> Agent:
> Yes, we still need it. Delayed timer and auto-retry ticks can fire after the instance has already moved elsewhere. Without `targetStage`, a stale delayed tick could wake the instance in the wrong stage and incorrectly re-enter processing. The stage tag is what makes those delayed ticks safe to ignore.
>> MG: What are the biggest consequences if stale tick would be processed? Process moves from timer to next stage? Any other?

> Agent:
> Biggest consequences are broader than just `timer -> next stage`.
> 1. A stale timer tick can wake a completely different current stage and let the engine claim `Running`, so the instance may progress even though the business condition that originally scheduled the tick is no longer relevant.
> 2. That can consume pending mailbox events from the current stage earlier than intended, because once the stale tick enters `processTickLoop`, the engine behaves as if the current stage was legitimately woken.
> 3. It can execute current-stage actions and their side effects under the wrong trigger, which is worse than a harmless duplicate tick because the action may call external systems.
> 4. It can clear current retry metadata on successful progress paths, even though the tick belonged to an older stage/timer context.
> 5. It can enqueue new delayed work from the wrong point in the flow, so one stale tick can fan out into more incorrect ticks/history rows instead of being a single no-op mistake.
> Validation: reviewed `processTick(...)` and `processTickLoop(...)` in `Engine`; once a tick passes the stale-stage guard, the runtime treats it as a legitimate wake-up and may claim, consume events, run actions, advance stage, clear retry state, and record history.
> Learning: `targetStage` is not just a convenience for logs; it is the guard that keeps delayed wake-ups causally tied to the stage that originally scheduled them.
>> MG: The only problem I can see is moving out of timer stage to early. For other type of stages I do not think that processing stale tick will cause any problem. Do you agree? Actually looking at the code I think we are also protected from progressing from timer even if tick will come earlier.

## [REOPEN] Retry metadata source of truth

> Agent:
> Cockpit now prefers retry-state rows but falls back to the latest error history row when retry metadata is unavailable from that store. The runtime path writes both, so the UI stays correct.
> If you want a stricter architecture later, we should decide whether to keep both sources intentionally or simplify to one authoritative read model.
>> MG: What about adding retry related attributes to FlowLiteInstanceSummaryRow?

> Agent:
> That is the strongest next option if we want one Cockpit read model with cheap queries. I did not move there in this loop because it adds more projection/update rules and more denormalized state to maintain. My current preference is:
> 1. keep retry-state as the write model,
> 2. add retry fields to `FlowLiteInstanceSummaryRow` only when Cockpit query pressure proves the extra projection complexity is worth it.

> Agent:
> More concrete answer: yes, adding retry attributes to `FlowLiteInstanceSummaryRow` is the cleanest way to make Cockpit read from one table, but only if we are ready to treat summary projection as an intentional read model rather than a minimal status cache.
> Cost: today `FlowLiteInstanceSummaryRow.apply(...)` updates stage, status, cockpitStatus, and last error message only. If we move retry metadata there, we must also project and clear `retryTrigger`, `failedAttemptCount`, `autoRetryMaxAttempts`, `nextAutoRetryAt`, and `externalRetryAllowed` on error, retry, successful progress, manual stage change, completion, and cancellation paths. That is doable, but it is more state to keep correct.
> Benefit: Cockpit list/details queries become simpler and cheaper, and the current fallback from retry-state to latest error history can be removed because the summary row itself becomes the Cockpit source of truth.
> Current decision: still keep this `ONHOLD`. I would promote it only when we decide Cockpit read simplicity is more important than keeping retry projection logic minimal.
> Validation: reviewed `SpringDataJdbcHistoryStore.append(...)`, `HistoryEntry.toSummaryProjectionUpdate()`, and `FlowLiteInstanceSummaryRow.apply(...)`; the current summary projection is intentionally narrow, so retry fields would be a deliberate expansion of that model rather than a small tweak.
> Learning: this is not mainly a storage decision; it is a read-model ownership decision. Once retry fields land in summary, that projection should be considered the primary Cockpit contract, not just a cache of a few status columns.
>> MG: Yes. FlowLiteInstanceSummaryRow is definitely for me internal read model not a minimal status cache. What do you think about moving FlowLiteRetryStateRow fields into FlowLiteInstanceSummaryRow instead duplicating them?

## [FOR HUMAN] Review git changes
* Review perf(engine): raise tick worker default 3/25/26, 9:13 AM marcingurbisz
* loop starting from 75abfec728b2184501ae4ebae0c83c07f1e0ba23
* loops starting from
  * docs: TODO update marcingurbisz 3/8/26, 9:12 AM
  * docs: update TODO marcingurbisz 3/7/26, 3:57 PM
  * docs: update TODO marcingurbisz 3/6/26, 7:13 AM

## [ON HOLD] Topics on hold for now
* Check sonar findings and decide if they should be fixed
* Visual testing - comparing screenshots before and after changes? Not sure about it because when agent can
see produced image maybe this be enough for visual inspection and comparing will not be needed?
* Check coverage and suggest modifications/new tests to cover it
* The GWT cleanup showed that the Cockpit Playwright spec now needs a small `RecordedPageSession` helper to keep browser setup/actions in `when` blocks while preserving failure screenshots/videos. If we add more browser scenarios, it may be worth introducing a tiny test DSL/helper layer for `open page -> act -> assert -> close` flows so future specs do not repeat the same session lifecycle/synchronization plumbing.
* Websocket for live refresh
