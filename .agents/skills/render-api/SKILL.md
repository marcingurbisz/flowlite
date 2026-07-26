---
name: render-api
description: "Use when investigating Render API behavior, Render webhooks, service events, deploys, health-check failures, or Render log retrieval for FlowLite."
---

# Render API Skill

Use this skill only for things learned about working with Render itself.

- Prefer raw Render event types and raw failure reasons over custom categories.
- Render webhooks are intentionally thin; for richer data, fetch the full event with `GET /v1/events/{eventId}`.
- Render health checks run every few seconds and fail if the service does not respond successfully within five seconds.
- `connection refused` means the port was not accepting the TCP connection; `EOF` or `connection reset by peer` means the connection was accepted and then closed before a complete response.
- Render logs API returns objects under a `logs` array with `message`, `timestamp`, and `labels`.
- List service events with `GET /v1/services/{serviceId}/events`; each list item wraps the event under `event` and carries its pagination cursor separately under `cursor`.
- Retrieve workspace notification settings with `GET /v1/notification-settings/owners/{ownerId}` and check service-specific overrides with `GET /v1/notification-settings/overrides?ownerId=...&serviceId=...`.
- Notification settings confirm what should be sent but the public API does not expose email delivery history; distinguish a missing configured notification from proof that an email was or was not delivered.
- Improve this skill whenever you learn something new about the Render API or discover that one of these notes was wrong.

If you need to "expand" your render-api skill use the references below, but normally you should not need to read them. 
References:
- https://render.com/docs/cli
- https://github.com/render-oss/skills
- https://api-docs.render.com/reference/list-logs
- https://api-docs.render.com/reference/retrieve-event
