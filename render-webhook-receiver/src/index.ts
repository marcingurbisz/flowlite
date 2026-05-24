export interface Env {
  RENDER_WEBHOOK_SECRET: string;
  RENDER_API_KEY?: string;
  RENDER_API_BASE_URL?: string;
  GITHUB_TOKEN: string;
  GITHUB_API_BASE_URL?: string;
  GITHUB_REPO_OWNER: string;
  GITHUB_REPO_NAME: string;
  GITHUB_ISSUE_ASSIGNEE?: string;
  RENDER_WEBHOOK_EVENT_TYPES?: string;
}

interface RenderWebhookPayload {
  type: string;
  timestamp: string;
  data?: {
    id?: string;
    serviceId?: string;
    serviceName?: string;
  };
}

interface RenderEventResponse {
  id: string;
  timestamp: string;
  serviceId: string;
  type: string;
  details?: {
    instanceId?: string;
    instanceID?: string;
    reason?: {
      unhealthy?: string;
    };
  };
}

interface RenderDeployResponse {
  id?: string;
  status?: string;
  deploy?: { id?: string };
  commit?: { id?: string };
}

interface GithubIssueSummary {
  number: number;
  html_url?: string;
  body?: string;
}

interface CreatedResponse {
  status: "created" | "duplicate" | "ignored" | "rejected";
  issueNumber?: number;
  issueUrl?: string;
  reason?: string;
}

const textEncoder = new TextEncoder();

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") {
      return json({ status: "ok" }, 200);
    }
    if (request.method !== "POST" || url.pathname !== "/render/webhook") {
      return json({ status: "rejected", reason: "not found" }, 404);
    }

    const rawBody = await request.text();
    const verification = await verifyRequest(request, rawBody, env.RENDER_WEBHOOK_SECRET);
    if (!verification.ok) {
      return json({ status: "rejected", reason: verification.reason }, 401);
    }

    const payload = parsePayload(rawBody);
    if (!payload.ok) {
      return json({ status: "rejected", reason: payload.reason }, 400);
    }

    const allowedEventTypes = new Set(
      (env.RENDER_WEBHOOK_EVENT_TYPES ?? "server_failed")
        .split(",")
        .map((value) => value.trim())
        .filter(Boolean),
    );
    if (!allowedEventTypes.has(payload.value.type)) {
      return json({ status: "ignored", reason: `event type '${payload.value.type}' not enabled` }, 202);
    }

    const eventId = payload.value.data?.id ?? "";
    const serviceId = payload.value.data?.serviceId ?? "";
    const serviceName = payload.value.data?.serviceName ?? "";
    const startedAt = payload.value.timestamp ?? "";
    if (!eventId || !serviceId || !serviceName || !startedAt) {
      return json({ status: "rejected", reason: "missing required Render webhook fields" }, 400);
    }

    const existingIssue = await findExistingIssue(eventId, env);
    if (existingIssue) {
      return json({ status: "duplicate", issueNumber: existingIssue.number }, 200);
    }

    const eventDetails = await fetchRenderEvent(eventId, env);
    const latestDeploy = await fetchLatestLiveDeploy(serviceId, env);
    const dashboardUrl = await fetchDashboardUrl(serviceId, env) ?? `https://dashboard.render.com/web/${serviceId}`;
    const body = buildIssueBody({
      incidentId: eventId,
      serviceId,
      serviceName,
      eventType: payload.value.type,
      failureReason: eventDetails?.details?.reason?.unhealthy ?? "-",
      startedAt,
      dashboardUrl,
      recentDeployId: latestDeploy?.deploy?.id ?? latestDeploy?.id ?? "-",
      eventId,
      instanceId: eventDetails?.details?.instanceId ?? eventDetails?.details?.instanceID ?? "-",
      latestLiveCommit: latestDeploy?.commit?.id ?? "-",
    });

    const createdIssue = await createIssue(
      {
        title: `[render] ${serviceName} ${payload.value.type} ${startedAt}`,
        body,
        labels: ["incident", "render"],
        assignees: env.GITHUB_ISSUE_ASSIGNEE ? [env.GITHUB_ISSUE_ASSIGNEE] : [],
      },
      env,
    );

    return json({ status: "created", issueNumber: createdIssue.number, issueUrl: createdIssue.html_url }, 202);
  },
};

export async function verifyRequest(request: Request, rawBody: string, secret: string): Promise<{ ok: true } | { ok: false; reason: string }> {
  const webhookId = request.headers.get("webhook-id");
  const webhookTimestamp = request.headers.get("webhook-timestamp");
  const webhookSignature = request.headers.get("webhook-signature");
  if (!webhookId || !webhookTimestamp || !webhookSignature) {
    return { ok: false, reason: "missing webhook headers" };
  }

  const timestampSeconds = Number.parseInt(webhookTimestamp, 10);
  if (!Number.isFinite(timestampSeconds)) {
    return { ok: false, reason: "invalid webhook timestamp" };
  }
  const ageSeconds = Math.abs(Math.floor(Date.now() / 1000) - timestampSeconds);
  if (ageSeconds > 300) {
    return { ok: false, reason: "webhook timestamp outside tolerance" };
  }

  const expectedSignature = await sign(webhookId, webhookTimestamp, rawBody, secret);
  const providedSignatures = webhookSignature
    .split(" ")
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map((entry) => entry.split(",", 2))
    .filter((parts) => parts.length === 2 && parts[0] === "v1")
    .map((parts) => parts[1]);

  const expectedBytes = textEncoder.encode(expectedSignature);
  const matched = providedSignatures.some((candidate) => constantTimeEqual(expectedBytes, textEncoder.encode(candidate)));
  return matched ? { ok: true } : { ok: false, reason: "invalid webhook signature" };
}

export async function sign(id: string, timestamp: string, rawBody: string, secret: string): Promise<string> {
  const keyBytes = decodeSecret(secret);
  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    keyBytes,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const payload = textEncoder.encode(`${id}.${timestamp}.${rawBody}`);
  const signature = await crypto.subtle.sign("HMAC", cryptoKey, payload);
  return base64Encode(new Uint8Array(signature));
}

function decodeSecret(secret: string): Uint8Array {
  const normalized = secret.startsWith("whsec_") ? secret.slice("whsec_".length) : secret;
  try {
    return base64Decode(normalized);
  } catch {
    return textEncoder.encode(secret);
  }
}

function parsePayload(rawBody: string): { ok: true; value: RenderWebhookPayload } | { ok: false; reason: string } {
  try {
    return { ok: true, value: JSON.parse(rawBody) as RenderWebhookPayload };
  } catch {
    return { ok: false, reason: "invalid JSON body" };
  }
}

async function fetchRenderEvent(eventId: string, env: Env): Promise<RenderEventResponse | null> {
  if (!env.RENDER_API_KEY) return null;
  return fetchJson<RenderEventResponse>(`${env.RENDER_API_BASE_URL ?? "https://api.render.com/v1"}/events/${eventId}`, {
    headers: renderHeaders(env.RENDER_API_KEY),
  });
}

async function fetchDashboardUrl(serviceId: string, env: Env): Promise<string | null> {
  if (!env.RENDER_API_KEY) return null;
  const service = await fetchJson<{ dashboardUrl?: string }>(`${env.RENDER_API_BASE_URL ?? "https://api.render.com/v1"}/services/${serviceId}`, {
    headers: renderHeaders(env.RENDER_API_KEY),
  });
  return service.dashboardUrl ?? null;
}

async function fetchLatestLiveDeploy(serviceId: string, env: Env): Promise<RenderDeployResponse | null> {
  if (!env.RENDER_API_KEY) return null;
  const response = await fetchJson<RenderDeployResponse[] | { deploys?: RenderDeployResponse[] }>(`${env.RENDER_API_BASE_URL ?? "https://api.render.com/v1"}/services/${serviceId}/deploys`, {
    headers: renderHeaders(env.RENDER_API_KEY),
  });
  const deploys = Array.isArray(response) ? response : response.deploys ?? [];
  return deploys.find((deploy) => deploy.status === "live") ?? null;
}

async function findExistingIssue(eventId: string, env: Env): Promise<GithubIssueSummary | null> {
  const labels = encodeURIComponent("incident,render");
  const issues = await fetchJson<GithubIssueSummary[]>(`${githubRepoBaseUrl(env)}/issues?state=open&labels=${labels}&per_page=100`, {
    headers: githubHeaders(env.GITHUB_TOKEN),
  });
  return issues.find((issue) => (issue.body ?? "").includes(`- event_id: ${eventId}`)) ?? null;
}

async function createIssue(
  input: { title: string; body: string; labels: string[]; assignees: string[] },
  env: Env,
): Promise<GithubIssueSummary> {
  return fetchJson<GithubIssueSummary>(`${githubRepoBaseUrl(env)}/issues`, {
    method: "POST",
    headers: {
      ...githubHeaders(env.GITHUB_TOKEN),
      "Content-Type": "application/json",
    },
    body: JSON.stringify(input),
  });
}

function buildIssueBody(input: {
  incidentId: string;
  serviceId: string;
  serviceName: string;
  eventType: string;
  failureReason: string;
  startedAt: string;
  dashboardUrl: string;
  recentDeployId: string;
  eventId: string;
  instanceId: string;
  latestLiveCommit: string;
}): string {
  return [
    "## Render payload",
    "",
    `- incident_id: ${input.incidentId}`,
    "- source: render",
    `- service_id: ${input.serviceId}`,
    `- service_name: ${input.serviceName}`,
    `- event_type: ${input.eventType}`,
    `- failure_reason: ${input.failureReason}`,
    `- started_at: ${input.startedAt}`,
    `- dashboard_url: ${input.dashboardUrl}`,
    `- recent_deploy_id: ${input.recentDeployId}`,
    "",
    "## Notes from receiver",
    "",
    `- event_id: ${input.eventId}`,
    `- instance_id: ${input.instanceId}`,
    `- latest_live_commit: ${input.latestLiveCommit}`,
    `- free-text summary: Render reported ${input.eventType} for ${input.serviceName} with reason '${input.failureReason}'.`,
    "",
    "## Agent checklist",
    "",
    "- [ ] fetch recent Render events",
    "- [ ] fetch latest live deploy",
    "- [ ] fetch logs around the failure window",
    "- [ ] correlate with recent commits on `main`",
    "- [ ] open investigation PR or fix PR",
  ].join("\n");
}

async function fetchJson<T>(url: string, init: RequestInit): Promise<T> {
  const response = await fetch(url, init);
  if (!response.ok) {
    const body = await response.text();
    throw new Error(`HTTP ${response.status} for ${url}: ${body}`);
  }
  return (await response.json()) as T;
}

function renderHeaders(apiKey: string): HeadersInit {
  return {
    Authorization: `Bearer ${apiKey}`,
    Accept: "application/json",
  };
}

function githubHeaders(token: string): HeadersInit {
  return {
    Authorization: `Bearer ${token}`,
    Accept: "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
  };
}

function githubRepoBaseUrl(env: Env): string {
  return `${env.GITHUB_API_BASE_URL ?? "https://api.github.com"}/repos/${env.GITHUB_REPO_OWNER}/${env.GITHUB_REPO_NAME}`;
}

function json(body: CreatedResponse | { status: string }, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json",
    },
  });
}

function constantTimeEqual(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  let diff = 0;
  for (let index = 0; index < left.length; index += 1) {
    diff |= left[index] ^ right[index];
  }
  return diff === 0;
}

function base64Encode(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary);
}

function base64Decode(value: string): Uint8Array {
  const binary = atob(value);
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}
