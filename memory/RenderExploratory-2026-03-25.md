# Render exploratory test report - 2026-03-25

Target:
- https://flowlite-test-instance.onrender.com/index.html

Tooling used:
- Existing repo Playwright Java test dependency and browser bundle.
- No additional MCP/browser tooling was required to start.

Artifacts:
- Screenshot: `memory/render-exploratory-2026-03-25/flows-header.png`
- Screenshot: `memory/render-exploratory-2026-03-25/instances-without-filters.png`
- Captured page text: `memory/render-exploratory-2026-03-25/home-page.txt`
- Captured page text: `memory/render-exploratory-2026-03-25/instances-tab.txt`

What was tested:
- Initial cockpit landing page on the deployed Render instance.
- Header summary values shown before any tab interaction.
- `Instances` tab behavior before any filter/search is applied.
- Basic endpoint timing samples for `/api/flows`, `/api/instances`, and `/api/errors`.

Findings:
1. Header summary bug is visible on the deployed version.
   - The landing page screenshot shows `flows: 0 • instances: 0 • errors: 0` even though `/api/flows` returned non-zero counts at the same time (`activeCount: 923`, `completedCount: 15`).
   - Screenshot: `memory/render-exploratory-2026-03-25/flows-header.png`

2. `Instances` tab still skips the filter-gate UX.
   - Instead of showing the later "Apply filters to view instances" guidance state, the deployed UI immediately renders the full controls/table shell before any filter is applied.
   - Screenshot: `memory/render-exploratory-2026-03-25/instances-without-filters.png`

3. Unfiltered heavy endpoints are still expensive enough to matter on the deployed version.
   - `/api/instances`: `1.296125s`, `630331` bytes
   - `/api/errors`: `3.933635s`, `18582` bytes
   - `/api/flows`: `1.427504s`, `7933` bytes
   - The `/api/errors` timing is notable because the payload is small, so most of the cost is server-side work rather than transfer size.

Notes:
- The deployed Render version is intentionally behind the current loop, so the first two findings are expected regressions relative to local `main`, not new surprises in the freshly changed code.
- The endpoint timing samples are still useful as a baseline for future production-like checks.