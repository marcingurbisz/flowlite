# Render Skill

Use this when investigating one Render failure window.

## Inputs

- `service_id`
- `service_name`
- failure timestamp or a narrow failure window

## Steps

1. Fetch recent Render events for the service and record raw event types.
2. Fetch the latest live deploy and record deploy id plus commit SHA.
3. Fetch app logs around the failure window.
4. Keep raw failure reasons from Render instead of translating them to custom categories.
5. Record the instance id when Render provides it.

## Heuristics

- `connection refused` usually means the web process was unavailable or restarting when Render probed it.
- `EOF` or `connection reset by peer` means the connection was closed before a complete response. That is a transport symptom, not a root cause by itself.
- Stable memory and thread diagnostics shortly before failure argue against OOM or runaway thread growth for that specific window.
- FlowLite currently exposes `/api/flows` as the observed health path. That path goes through Cockpit summary queries, so a health failure there does not prove the whole app is dead; it may mean the Cockpit read path is unavailable or too slow.
- Showcase simulated action failures are expected in the public test instance and create noisy stack traces. Do not confuse them with infrastructure root cause.

## Output

Return a short evidence block with:

- event type and timestamp
- raw failure reason
- instance id when present
- latest live deploy id and commit SHA
- one cautious hypothesis and its main uncertainty
