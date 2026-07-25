# Render Webhook Receiver

Thin standalone receiver for Render incident webhooks.

## Why this module exists

- It is separate from the FlowLite app process.
- It is small enough to host on a free public endpoint.
- It creates or deduplicates GitHub issues for Render `server_failed` incidents.
- It can also accept a Pipedream-posted email payload and turn it into the same kind of GitHub incident issue.

## Recommended free hosting

Cloudflare Workers Free is the recommended target for this module.

Why:
- public `*.workers.dev` endpoint
- secrets support
- free tier with `100,000` requests per day
- simple deploy path with Wrangler

## Local development

1. `cd render-webhook-receiver`
2. `npm install`
3. create `.dev.vars` from `.dev.vars.example`
4. `npm run dev`

## Deploy

1. `cd render-webhook-receiver`
2. `npm install`
3. `npx wrangler login`
4. `npx wrangler secret put RENDER_WEBHOOK_SECRET`
5. `npx wrangler secret put GITHUB_TOKEN`
6. `npx wrangler secret put PIPEDREAM_SHARED_SECRET`
7. optionally set `RENDER_API_KEY`
8. optionally override default vars for `GITHUB_REPO_OWNER` or `GITHUB_REPO_NAME`, and override `GITHUB_ISSUE_ASSIGNEE` if `copilot-swe-agent[bot]` is not a valid assignee in the target repo
9. keep `ALLOW_INSECURE_TEST_WEBHOOKS=false` for real deployments; only flip it for short-lived manual smoke tests
10. `npm run deploy`

The worker will be published under a `*.workers.dev` URL.
Point the Render webhook to `POST /render/webhook` on that URL.

`RENDER_API_KEY` is optional. It is used only to enrich the incident issue with extra Render data such as full event details, dashboard URL, and latest live deploy commit. Issue creation itself still works without it.

## Pipedream email ingress

If Render webhooks are unavailable on the current plan, Pipedream can be used as the ingress path instead.

Recommended shape:

1. forward the alert email into Pipedream
2. in Pipedream, send `POST /pipedream/email` to the Worker
3. include header `x-pipedream-secret: <same value as PIPEDREAM_SHARED_SECRET>`
4. send a JSON body with at least these fields:

```json
{
	"messageId": "<stable message id>",
	"subject": "<email subject>",
	"from": "notifications@render.com",
	"date": "2026-05-25T04:30:00+02:00",
	"text": "<plain text body>"
}
```

The Worker deduplicates on `messageId` and creates a GitHub issue with labels `incident`, `email`, and `render`.
