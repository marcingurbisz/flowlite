import { describe, expect, it, vi } from "vitest";
import worker, { sign, verifyRequest } from "../src/index";

const fixedNow = 1_700_000_000_000;

describe("verifyRequest", () => {
  it("accepts a matching Standard Webhooks signature", async () => {
    vi.spyOn(Date, "now").mockReturnValue(fixedNow);
    const body = JSON.stringify({
      type: "server_failed",
      timestamp: "2026-05-24T05:33:54Z",
      data: { id: "evt-1", serviceId: "srv-1", serviceName: "flowlite-test-instance" },
    });
    const timestamp = String(Math.floor(fixedNow / 1000));
    const signature = await sign("evt-1", timestamp, body, "secret");
    const request = new Request("https://example.com/render/webhook", {
      method: "POST",
      headers: {
        "webhook-id": "evt-1",
        "webhook-timestamp": timestamp,
        "webhook-signature": `v1,${signature}`,
      },
      body,
    });

    await expect(verifyRequest(request, body, "secret")).resolves.toEqual({ ok: true });
  });

  it("rejects an invalid signature", async () => {
    vi.spyOn(Date, "now").mockReturnValue(fixedNow);
    const body = "{}";
    const request = new Request("https://example.com/render/webhook", {
      method: "POST",
      headers: {
        "webhook-id": "evt-1",
        "webhook-timestamp": String(Math.floor(fixedNow / 1000)),
        "webhook-signature": "v1,wrong",
      },
      body,
    });

    await expect(verifyRequest(request, body, "secret")).resolves.toEqual({ ok: false, reason: "invalid webhook signature" });
  });
});

describe("worker fetch", () => {
  it("creates an issue for a new server_failed event", async () => {
    vi.spyOn(Date, "now").mockReturnValue(fixedNow);
    const body = JSON.stringify({
      type: "server_failed",
      timestamp: "2026-05-24T05:33:54Z",
      data: { id: "evt-2", serviceId: "srv-1", serviceName: "flowlite-test-instance" },
    });
    const timestamp = String(Math.floor(fixedNow / 1000));
    const signature = await sign("evt-2", timestamp, body, "secret");

    const fetchMock = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input.toString();
      if (url.includes("/issues?")) {
        return new Response(JSON.stringify([]), { status: 200 });
      }
      if (url.endsWith("/events/evt-2")) {
        return new Response(JSON.stringify({
          id: "evt-2",
          timestamp: "2026-05-24T05:33:54Z",
          serviceId: "srv-1",
          type: "server_failed",
          details: {
            instanceID: "srv-1-xyz",
            reason: { unhealthy: 'Get "http://10.0.0.1:10000/api/flows": EOF' },
          },
        }), { status: 200 });
      }
      if (url.endsWith("/services/srv-1/deploys")) {
        return new Response(JSON.stringify([{ status: "live", deploy: { id: "dep-1" }, commit: { id: "abc123" } }]), { status: 200 });
      }
      if (url.endsWith("/services/srv-1")) {
        return new Response(JSON.stringify({ dashboardUrl: "https://dashboard.render.com/web/srv-1" }), { status: 200 });
      }
      if (url.endsWith("/issues") && init?.method === "POST") {
        return new Response(JSON.stringify({ number: 21, html_url: "https://github.com/marcingurbisz/flowlite/issues/21" }), { status: 201 });
      }
      throw new Error(`Unexpected fetch call: ${url}`);
    });

    vi.stubGlobal("fetch", fetchMock);

    const response = await worker.fetch(
      new Request("https://receiver.example/render/webhook", {
        method: "POST",
        headers: {
          "webhook-id": "evt-2",
          "webhook-timestamp": timestamp,
          "webhook-signature": `v1,${signature}`,
        },
        body,
      }),
      {
        RENDER_WEBHOOK_SECRET: "secret",
        RENDER_API_KEY: "render-token",
        GITHUB_TOKEN: "github-token",
        GITHUB_REPO_OWNER: "marcingurbisz",
        GITHUB_REPO_NAME: "flowlite",
        GITHUB_ISSUE_ASSIGNEE: "copilot",
      },
    );

    expect(response.status).toBe(202);
    await expect(response.json()).resolves.toEqual({
      status: "created",
      issueNumber: 21,
      issueUrl: "https://github.com/marcingurbisz/flowlite/issues/21",
    });
  });
});
