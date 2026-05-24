---
name: Render incident
about: Investigate a Render service failure
labels: incident, render
title: "[render] <service-name> <event-type> <timestamp>"
---

## Render payload

- incident_id:
- source: render
- service_id:
- service_name:
- event_type:
- failure_reason:
- started_at:
- dashboard_url:
- recent_deploy_id:

## Notes from receiver

- event_id:
- instance_id:
- latest_live_commit:
- free-text summary:

## Agent checklist

- [ ] fetch recent Render events
- [ ] fetch latest live deploy
- [ ] fetch logs around the failure window
- [ ] correlate with recent commits on `main`
- [ ] open investigation PR or fix PR
