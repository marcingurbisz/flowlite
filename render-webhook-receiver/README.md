# Render Webhook Receiver

Thin standalone receiver for Render incident webhooks.

## Why this module exists

- It is separate from the FlowLite app process.
- It is small enough to host on a free public endpoint.
- It creates or deduplicates GitHub issues for Render `server_failed` incidents.

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
6. optionally set `RENDER_API_KEY`
7. optionally override default vars for `GITHUB_REPO_OWNER` or `GITHUB_REPO_NAME`, and set `GITHUB_ISSUE_ASSIGNEE` only if that GitHub login is a valid assignee in the target repo
8. keep `ALLOW_INSECURE_TEST_WEBHOOKS=false` for real deployments; only flip it for short-lived manual smoke tests
9. `npm run deploy`

The worker will be published under a `*.workers.dev` URL.
Point the Render webhook to `POST /render/webhook` on that URL.

`RENDER_API_KEY` is optional. It is used only to enrich the incident issue with extra Render data such as full event details, dashboard URL, and latest live deploy commit. Issue creation itself still works without it.
