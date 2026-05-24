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
- Improve this skill whenever you learn something new about the Render API or discover that one of these notes was wrong.

References:
- https://render.com/docs/cli
- https://github.com/render-oss/skills
- https://api-docs.render.com/reference/list-logs
- https://api-docs.render.com/reference/retrieve-event
