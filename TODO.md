## [FOR HUMAN]
* Check on GUI:
  retryTrigger?: RetryTrigger | null;
  failedAttemptCount?: number | null;
  autoRetryMaxAttempts?: number | null;
  nextAutoRetryAt?: string | null;
  externalRetryAllowed?: boolean | null;
* Review engine once again
* Review CockpitPlaywrightTest

## [DONE 2026-05-16.2] Why scheduled tick needs target stage?
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

> Agent:
> I agree partially, but with one important correction.
> 1. You are right that an early timer tick is already handled safely. In the current code `isDueTimerTick` requires `!tick.notBefore.isAfter(now)`, so a timer tick that arrives before its due time does not advance the instance. The engine recalculates/schedules and returns to `WaitingForTimer`.
> 2. The main timer risk is not "too early while still in the same timer stage". The real risk is "late after the instance has already left that timer stage". That is exactly what `targetStage` protects against.
> 3. For ordinary immediate ticks I mostly agree with you: they do not carry `targetStage`, and duplicate delivery is usually benign because claim/status checks and event-store semantics make them idempotent enough.
> 4. The other important delayed case is auto-retry. Auto-retry ticks are not generic duplicates; they are delayed wake-ups tied to the stage that failed. If that stage is no longer current, the stale auto-retry tick must be ignored for the same causal reason as stale timer ticks.
> So the refined answer is: yes, timer-stage early wake-up is already guarded by `notBefore`; no, that does not remove the need for `targetStage`, because `targetStage` is guarding against delayed wake-ups arriving after the instance has moved on, and that matters for timer ticks and auto-retry ticks specifically.
> Validation: re-reviewed `processTick(...)` and the timer branch in `processTickLoop(...)`; the due-time check protects against early wake-up, while the `currentStage != tick.targetStage` and stale auto-retry checks protect against wake-ups that belong to an older stage context.
> Learning: `notBefore` answers "is it time yet?" while `targetStage` answers "is this wake-up still for the current stage?". We need both.
>> MG: We are adding targetStage to ticks when in timer stage or when retrying after Exception. In case of complete targetStage removal the only problematic case I can think of is a timer or autoretry tick that comes when process is in error state on another stage. In other case processing this tick is not a problem. In case of process in ERROR state will it advance if timer tick comes? I guess only autoretry tick can advance it.

> Agent:
> Yes, your latest refinement is mostly right, and the key distinction is exactly timer tick vs auto-retry tick while current status is `Error`.
> 1. A stale plain timer tick will not advance an instance that is currently `Error` on another stage. After the stale-stage guard, `processTick(...)` enters the `StageStatus.Error` branch, `tick.autoRetry` is false, so the engine only logs and returns.
> 2. A stale auto-retry tick is the real advancing case. Without `targetStage`, that same `StageStatus.Error` branch would call `tryAutoRetry(...)`, which can reset the current error stage back to its waiting status and enqueue fresh work, even though the delayed tick belonged to an older stage failure.
> 3. For non-error current states I would still not call stale timer ticks universally harmless; they degrade into generic duplicate wake-ups. Many will be benign, but they also stop being causally tied to the timer stage that scheduled them, which is exactly the contract `targetStage` is preserving.
> So the narrowest concrete answer is: yes, if we remove `targetStage`, the sharpest incorrect advance is stale auto-retry against a different current `Error` stage; and no, a plain timer tick will not advance an instance that is already `Error`.
> Validation: re-reviewed the `tick.targetStage` guard and the `StageStatus.Error` branch in `processTick(...)`; only `tick.autoRetry` can pass from `Error` into `tryAutoRetry(...)`.
> Learning: `targetStage` is doing two jobs at once for delayed ticks: protecting timer causality in general and blocking the one truly dangerous cross-stage advance path, which is stale auto-retry into the wrong current error stage.

## [DONE 2026-05-16.2] Retry metadata source of truth

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

> Agent:
> I think that is the better direction.
> If `FlowLiteInstanceSummaryRow` is an intentional internal read model, then moving the current retry snapshot there is cleaner than duplicating the same current-state fields in both `FLOWLITE_RETRY_STATE` and `FLOWLITE_INSTANCE_SUMMARY`.
> My preferred shape would be:
> 1. keep the `RetryStateStore` interface,
> 2. back it with retry columns on `FlowLiteInstanceSummaryRow`,
> 3. remove the separate `FlowLiteRetryStateRow` table once the migration is done,
> 4. let Cockpit read retry metadata directly from summary without fallback logic.
> Main caution: choose one owner for writing those retry fields. Right now summary is updated through `SpringDataJdbcHistoryStore.append(...)`, while retry snapshot writes happen through `RetryStateStore`. If we move retry state into summary, we should avoid a muddled "two writers, same columns" design. I would keep the `RetryStateStore` abstraction as the engine-facing owner of current retry snapshot and make it persist into summary row fields.
> Validation: reviewed `RetryStateStore`, `FlowLiteRetryStateRow`, `FlowLiteInstanceSummaryRow`, and the current `SpringDataJdbcHistoryStore` / `SpringDataJdbcRetryStateStore` split. The duplication today is structural, not accidental.
> Learning: once summary is accepted as the internal read model, duplicating current retry state in a second table buys little. The cleaner trade-off is one summary-backed current snapshot plus append-only history.
>> MG: Go for it. No migration needed. We do not have any production instances for flowlite. Is FlowLiteInstanceSummary the best name? Is adding a Row to the name a good pattern?

> Agent:
> Done.
> Changes: moved retry snapshot fields into `FlowLiteInstanceSummaryRow`, switched `SpringDataJdbcRetryStateStore` to persist into summary rows, removed the separate `FLOWLITE_RETRY_STATE` schema/table, and simplified Cockpit to read retry metadata directly from summary instead of using retry-store/history fallback.
> Naming: I would keep `FlowLiteInstanceSummaryRow` for now. `FlowLiteInstanceSummary` is a reasonable concept name for the internal read model, and the `Row` suffix is still useful here because this Kotlin type is also the Spring Data JDBC table-mapped record. If we later split "projection concept" from "JDBC record", then `FlowLiteInstanceSummary` plus a separate `...Row` type would make sense, but that would be extra churn today.
> Validation: `./gradlew test` passed after the change. The run emitted a Playwright host dependency warning in this container, but the Gradle build still completed successfully.
> Learning: the clean boundary is now append-only history plus one summary-backed current snapshot. Keeping `RetryStateStore` as the engine-facing abstraction still works well even though the physical storage moved into summary.

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
