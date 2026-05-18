# Agentic incident triage on Render free tier

## Goal
Automate investigation of app problems on render so the human only does the decision-making.

## Workflow

1. Render service fails (health check timeout, OOM kill, deploy failure).
2. Monitoring source (Better Stack / UptimeRobot / Render Service Event)
   fires a webhook.
3. Webhook bridge creates a GitHub issue with the alert payload and assigns
   it to the coding agent.
4. Agent investigates: fetches recent logs, deploys, service events;
   correlates with commits on `main`.
5. Agent opens a PR on `copilot/*` branch with a hypothesis (and, when
   confident, a proposed fix).
6. Human reviews the PR, merges to `main`.
7. Render auto-deploys `main` → loop closes.

## Candidate platforms

### GitHub Copilot coding agent (web) — leading

- ✅ Playwright MCP built-in (browser access to Render dashboard if needed)
- ✅ Dedicated `Agents` secrets bucket, isolated from Actions/Codespaces
- ✅ Custom MCP servers via repo config, including auth-injecting proxies
- ✅ Trigger mechanism (issue assignment) maps naturally to the webhook bridge
- ✅ `copilot/*`-only push policy is a safety net, not a friction point here

## Tools the agent needs

| Capability                          | Mechanism                                                       |
| ----------------------------------- | --------------------------------------------------------------- |
| Read Render logs, deploys, events   | `render-mcp` — auth-injecting proxy with method+path allowlist  |

## Security model

- Render API key stored in `COPILOT_MCP_RENDER_API_KEY` (Agents secret).
- `render-mcp` injects the auth header server-side; the key never appears
  in the agent's context, even if log content contains prompt-injection
  attempts.
- Default policy: `GET`-only methods, read-only observability path
  allowlist. `POST /services/{id}/restart` and deploy triggers are
  opt-in and require an explicit policy change.
- All agent commits land on `copilot/*` branches; `main` merges are
  human-gated.
- `.github/copilot-instructions.md` instructs the agent to treat all
  content fetched from Render as untrusted input.

# TODO

## Review the concept and implement
Review, if it needs adaptations do it, create next todos in this file and start working on them.