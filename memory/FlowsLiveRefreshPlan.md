# Flows tab live-refresh plan

## Goal

Keep the Cockpit overview feeling live without making the action-heavy tabs unstable.

The specific target is the `Flows` tab:
- flow counters should update quickly when instances move between `Pending`, `Running`, `Error`, `Completed`, or `Cancelled`,
- per-stage breakdown should refresh without a full page reload,
- long-running badges should stay reasonably fresh,
- operators should still have a manual refresh escape hatch.

## Current state

Today the Cockpit is snapshot-based:
- backend exposes pull endpoints only:
  - `GET /api/flows`
  - `GET /api/instances`
  - `GET /api/errors`
  - `GET /api/instances/{flowId}/{flowInstanceId}/timeline`
- frontend loads data once on mount via `refreshData()` and then refreshes again only after local retry/cancel/change-stage actions,
- the `Flows` tab already renders some values from the `flows` response, but it also derives stage breakdown and long-running badges from the loaded `instances` snapshot.

Important consequence: a live `Flows` tab currently needs both:
- fresh flow summaries, and
- fresh instance snapshot data (or a new dedicated summary endpoint).

## Questions from the backlog

### Should live refresh be limited to the `Flows` tab?

**Yes for phase 1.**

That tab is the best fit because it is an overview/dashboard surface. It benefits from passive updates and does not currently maintain row selections that would be disrupted by auto-refresh.

### Should other tabs also live refresh?

**Not initially.**

`Errors`, `Long Running`, and `Instances` are operator/action tabs:
- users filter, multi-select, open modals, and perform retry/cancel/change-stage actions there,
- automatic list refresh can reorder rows, clear context, or make selected rows disappear while the user is working,
- those tabs benefit more from explicit refresh controls than from passive churn.

### Should there be a global live-update toggle?

**Yes, but scoped to the `Flows` tab in phase 1.**

Recommended behavior:
- show a `Live` toggle only when `Flows` tab is active,
- default it to `ON`,
- subscribe only while that tab is active,
- close the connection when the user switches tabs.

### Should we skip push entirely and just add refresh buttons?

**No.** Add refresh buttons, but not as the only solution.

Manual refresh alone would be the smallest change, but it does not match the monitoring/dashboard use case. The `Flows` tab is the one place where passive updates provide clear value.

## Recommendation

### UX recommendation

1. **Live updates only on `Flows` in phase 1**.
2. **Manual refresh button on every tab**.
3. **`Live` toggle on `Flows` only**, default `ON`.
4. **Show connection state** on `Flows` (`Live`, `Reconnecting`, `Offline`).
5. **Keep other tabs manual-refresh for now**.

This gives the overview tab a live feel while keeping the operator tabs predictable.

## Transport choice

### Options compared

| Option | Pros | Cons | Recommendation |
| --- | --- | --- | --- |
| Manual refresh only | smallest change | not live, easy to forget, weak dashboard UX | not enough alone |
| Short polling | simple, no long-lived connection | wasteful, stale between polls, hard to tune | acceptable fallback only |
| SSE (Server-Sent Events) | one-way push fits this use case, works with current Spring MVC stack, browser auto-reconnect, no extra websocket protocol/config | server-to-client only, not ideal if we later need client messages | **best phase-1 choice** |
| WebSocket | flexible, good for future bidirectional features | more code/config, more moving parts, overkill for one-way invalidation | reserve for a later broader live-cockpit phase |

### Final recommendation

For the current requirement, **prefer SSE over WebSocket**.

Reason:
- the cockpit only needs **server -> browser** invalidation notifications,
- the current project already has Spring MVC on the classpath,
- SSE can be added with much less code and operational complexity than a websocket stack,
- the frontend can still use the same message contract later if we ever replace SSE with WebSocket.

If the team wants to keep the backlog wording as "websockets", the implementation plan can still stay transport-agnostic internally and swap the transport layer later.

## Payload strategy

Do **not** stream full flow/instance state as websocket/SSE payloads in phase 1.

Instead, stream a lightweight invalidation message and let the browser refetch the existing REST endpoints.

Recommended message shape:

```json
{
  "type": "flows-invalidated",
  "flowIds": ["order-confirmation"],
  "occurredAt": "2026-03-08T10:15:30Z"
}
```

Notes:
- `flowIds` is optional but useful for future optimizations/highlighting,
- for phase 1 the client can simply ignore the list and refetch the full `Flows` snapshot,
- invalidation + refetch is safer than trying to maintain client-side partial diffs.

## Backend design

### 1. Introduce a Cockpit live-update publisher

Add a small abstraction, for example:
- `CockpitLiveUpdatePublisher`
- `publishFlowsInvalidated(flowId: String, occurredAt: Instant)`

For phase 1 an **in-memory publisher** is enough because the current public test app / Render deployment is effectively a single JVM instance.

### 2. Publish after persisted history changes

The cleanest trigger point is the history write path, not the UI actions.

Why this is the best place:
- all relevant state changes already become history entries,
- it avoids duplicating publish calls in many engine branches,
- it keeps the broadcaster aligned with persisted state rather than transient in-memory transitions.

Recommended rule:
- publish on `Started`, `StatusChanged`, `StageChanged`, `Cancelled`, `Error`,
- ignore `EventAppended` for now because it does not always produce a visible `Flows` change immediately.

That means manual retry/cancel/change-stage and normal engine progression will all naturally invalidate the `Flows` tab.

### 3. Expose a dedicated live endpoint

Recommended endpoint:
- `GET /api/flows/live`

For SSE this can be implemented with a small dedicated MVC controller/emitter setup.

Behavior:
- send `flows-invalidated` events,
- send heartbeat comments/events every ~15-30 seconds,
- clean up disconnected clients,
- set headers that avoid proxy buffering/caching.

### 4. Keep the existing REST endpoints as source of truth

Do not replace:
- `GET /api/flows`
- `GET /api/instances`

The live channel only says **"something changed"**.
The REST endpoints still provide the actual snapshot.

### 5. Optional optimization after phase 1

If payload size becomes a problem, add one of these:
- `GET /api/instances?bucket=Incomplete`
- or a dedicated `GET /api/flows/overview` that already includes stage breakdown

That optimization should be postponed until there is evidence that refetching the full instance snapshot is too heavy.

## Frontend design

### 1. Split refresh functions by view

Refactor `refreshData()` into narrower loaders, for example:
- `refreshFlowsViewData()` -> `/api/flows` + `/api/instances`
- `refreshErrorsViewData()` -> `/api/errors` + maybe `/api/instances`
- `refreshInstancesViewData()` -> `/api/instances`

Why:
- live `Flows` updates should not refetch `errors` unnecessarily,
- manual refresh buttons become easier to wire per tab,
- later tuning becomes much simpler.

### 2. Add a live subscription hook for `Flows`

Suggested shape:
- subscribe only when `activeView === 'flows' && liveEnabled`,
- on invalidation event, debounce refresh (`250-500ms`),
- if multiple events arrive during one fetch, queue one more refresh after the current one completes.

This matters because one engine tick can append multiple history rows in quick succession.

### 3. Add UI affordances

On the `Flows` tab header:
- `Live` toggle,
- `Refresh now` button,
- small status text/badge:
  - `Live`
  - `Reconnecting`
  - `Offline`
- optional `Last updated at ...`

On other tabs:
- just add `Refresh now`.

### 4. Keep list-modifying tabs stable

Do not auto-refresh these in phase 1:
- `Errors`
- `Long Running`
- `Instances`
- instance details modal/timeline

Reason:
- preserving selections, open modals, and scroll position is more important there than passive freshness.

## Rollout plan

### Phase 0 — Low-risk UX foundation

1. Split `refreshData()` by tab/view.
2. Add manual refresh buttons everywhere.
3. Add per-view `lastUpdatedAt` state.

This part is useful even without server push.

### Phase 1 — Live `Flows` tab via SSE

1. Add backend in-memory live-update publisher.
2. Publish invalidation after relevant persisted history entries.
3. Add `GET /api/flows/live` SSE endpoint.
4. Add frontend `Flows` subscription hook.
5. Add `Live` toggle + connection badge.
6. Debounce invalidation-triggered refreshes.

### Phase 2 — Optional performance tuning

Only if needed:
- add a narrower summary endpoint or incomplete-only bucket,
- use `flowIds` from the event payload to avoid unnecessary full updates,
- coalesce events server-side instead of only in the browser.

### Phase 3 — Broader live cockpit (only if later needed)

If the product later truly needs live operator lists, then revisit:
- selective auto-refresh of `Errors` or `Long Running`,
- preserving list selection across refreshes,
- possible move from SSE to WebSocket if bidirectional control becomes useful.

## Multi-replica note

Phase 1 assumes a single JVM cockpit instance.

If FlowLite later runs with multiple replicas behind one URL, an in-memory broadcaster will only notify clients connected to the same replica that processed the change.

If/when that becomes relevant, upgrade the publisher to a shared mechanism such as:
- DB-backed change polling using `flowlite_history` as the source,
- or an external broker.

That is not necessary for the current public test app / Render deployment.

## Test plan for the future implementation task

### Backend

Add tests for:
- publisher fires on `Started`, `StatusChanged`, `StageChanged`, `Cancelled`, `Error`,
- publisher does not fire on `EventAppended`,
- disconnected emitters/subscribers are cleaned up,
- heartbeat does not break idle connections.

### Frontend / Playwright

Add/adjust Playwright scenarios for:
- `Flows` tab shows live toggle and manual refresh button,
- when a backend state change happens outside the current page interaction, the visible flow card updates automatically,
- turning `Live` off stops automatic updates,
- returning to `Flows` after visiting another tab resynchronizes data,
- live updates do not force navigation away from current filters/bookmarkable tab state.

A small test-only mutation helper may be needed so Playwright can trigger backend changes without relying on the page's own refresh-after-action code path.

## Definition of done for the implementation task

- `Flows` tab updates automatically while open and `Live` is enabled.
- `Errors`, `Long Running`, and `Instances` remain stable and manual-refresh driven.
- Manual refresh is available on every tab.
- Live refresh uses invalidation + refetch rather than fragile delta patches.
- The transport layer remains replaceable, even if phase 1 ships with SSE instead of WebSocket.
