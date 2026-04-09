# Cockpit scaling notes

## Current bottleneck

Cockpit summary endpoints currently rebuild instance summaries from `flowlite_history` on every request:

- `/api/flows` -> `CockpitService.listFlows()` -> `loadInstanceSummaries(null)`
- `/api/instances` -> `CockpitService.listInstances()` -> `loadInstanceSummaries(flowId)`
- `/api/errors` -> `CockpitService.listErrorGroups()` -> `loadInstanceSummaries(flowId)`

The expensive part is `FlowLiteHistoryRepository.findLatestRowsPerType(...)`, which uses a window-function query over `flowlite_history` and then leaves additional grouping/filtering to JVM code.

Current indexes already help somewhat:

- `idx_flowlite_history_instance(flow_id, flow_instance_id, occurred_at)`
- `idx_flowlite_history_summary(flow_id, flow_instance_id, type, occurred_at)`

Those indexes do not remove the core cost: Cockpit summary endpoints still derive the latest state by scanning history partitions and then filter more in memory.

## Recommendation

For current and larger datasets, the right medium-term direction is a dedicated read-model table for cockpit summaries, for example `flowlite_instance_summary`.

Suggested columns:

- `flow_id`
- `flow_instance_id`
- `stage`
- `status`
- `last_error_message`
- `updated_at`

`activityStatus` should stay computed at read time from stage definitions, because it is a projection rather than durable engine state.

With that shape:

- `/api/flows`, `/api/instances`, and `/api/errors` query the summary table
- `/timeline` remains the only cockpit endpoint that queries `flowlite_history`
- filtering by flow/stage/status/error can move to SQL instead of JVM post-filtering

## Status vocabulary

`Pending scheduler` is not the best label for the current semantics.

Today:

- `WaitingForTimer` already means the instance is waiting on a timer-backed wake-up
- `WaitingForEvent` already means the instance is waiting on an external event
- plain `Pending` means the instance is actionable/runnable, not specifically scheduler-waiting

If a future projection needs a more explicit label than `Pending`, a name closer to `Pending engine` or `Runnable` would fit better than `Pending scheduler`.

## Practical conclusion

- Small index/query tuning may still be worth trying as a temporary mitigation, but it will not change the asymptotic shape enough for Cockpit-heavy usage.
- If Cockpit becomes a serious operator surface, introducing a summary/read-model table is the cleaner fix.