# Interaction Log

Template:
- Date – [Short description of item].
  - Outcome: [What was done].
  - Learning (optional): [What was discovered].

## Entries

- 2026-03-04 – Action context for stage actions.
  - Outcome: Added `ActionContext` support in DSL/runtime, migrated onboarding actions, and validated with `./gradlew test`.
  - Learning: Receiver-style action overloads (`ActionContext.(T) -> T?`) can conflict with existing receiver-lambda DSL APIs and create ambiguity for `::action` references.
- 2026-03-04 – History model deduplication.
  - Outcome: Removed `CockpitHistoryEntryDto` and returned `FlowLiteHistoryRow` directly from cockpit timeline APIs.
- 2026-03-04 – Engine rename.
  - Outcome: Renamed `FlowEngine` to `Engine`, moved source to `source/Engine.kt`, and updated source/tests/docs.
- 2026-03-04 – Cockpit API declarations move.
  - Outcome: Moved cockpit DTO/enum declarations from `source/cockpit/api.kt` into `source/cockpit/service.kt` and deleted `api.kt`.
- 2026-03-04 – Showcase app seeding.
  - Outcome: Added servlet-only demo seeding in `test/testApplication.kt` and verified cockpit activity/API checks.
  - Learning: Demo/background generation should be feature-flagged so shared non-web test contexts are not polluted.
- 2026-03-04 – History query review.
  - Outcome: Replaced duplicated latest-row queries with parameterized `findLatestRows(flowId, types)` and shared type groups.
- 2026-03-04 – Playwright tests in `cockpit-ui`.
  - Outcome: Added Playwright setup/tests, fixed static asset serving, and kept both E2E + Gradle suites green.
  - Learning: Asset routing must point to real Vite output subpaths (like `dist/assets`) to avoid JS/CSS 404 and blank UI.
- 2026-03-04 – Public exposure script.
  - Outcome: Added and validated `tools/exposeTestInstance.sh` for automated localhost.run tunnel bring-up and cleanup.
- 2026-03-04 – Cockpit coverage expansion.
  - Outcome: Added `CockpitServiceTest` coverage for instance aggregation, error grouping, counters/diagrams, and timeline projections.
  - Learning: In Kotest `BehaviorSpec`, fixture setup inside `when` can be reset by cleanup hooks because each `then` is a separate test.
- 2026-03-04 – Query consolidation.
  - Outcome: Replaced three cockpit latest-row reads with single `findLatestRowsPerType` + in-memory projection.
  - Learning: One query can still support latest stage/status/error derivation when SQL ranks by `(flow, instance, type)`.
- 2026-03-04 – DSL infer-name deduplication.
  - Outcome: Extracted shared `inferCallableName(...)` helper and reused it in action/condition description inference.
  - Learning: Shared fallback-aware callable-name inference avoids drift in synthetic-lambda detection rules.
- 2026-03-04 – `CockpitUiStaticConfig` move.
  - Outcome: Moved static config from `test/` to reusable `source/cockpit` and wired it explicitly in test app beans.
  - Learning: Reusable Spring MVC config should be explicitly registered instead of relying on incidental scan boundaries.
- 2026-03-04 – Test naming cleanup.
  - Outcome: Removed `Flow` prefixes from test files/classes (including `FlowReceiverDslTest` → `DslTest`) and revalidated full suite.
  - Learning: Bulk test renames are usually low-risk but still require full-suite verification due to discovery/reporting behavior.
- 2026-03-04 – ActionContext test placement decision.
  - Outcome: Kept `ActionContextTest` as a dedicated contract test file.
  - Learning: Narrow contract checks are easier to diagnose when isolated from broader scenario suites.
- 2026-03-04 – Playwright rewrite to Kotlin.
  - Outcome: Added `CockpitPlaywrightTest` with screenshot-on-failure and always-on video recording; targeted and full suites passed.
  - Learning: Playwright host-library warnings in containers may be non-fatal for Chromium smoke tests, but should be tracked as environment debt.
- 2026-03-04 – Public provider selection.
  - Outcome: Evaluated GitHub/Render/Railway/Fly, selected Render free service, and added `render.yaml` + `Dockerfile` + `.dockerignore`.
  - Learning: GitHub Pages is static-only and cannot host the JVM/Spring app; GitHub remains useful as repo/CI source for external deployment.
