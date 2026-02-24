# Interaction Log

Template:
- Date – [What was done]. Outcome: [Result].

## Entries

- 2026-02-21 – Added optional `condition` description inference and strengthened DSL validation to fail fast on undefined stage references / empty condition branches; added tests. Outcome: cleaner DSL usage and earlier errors; test suite passes.

- 2026-02-22 – Added explicit tests for condition description inference (function ref vs lambda fallback), refactored Employee Onboarding flow to use named predicates so `description` can be omitted without hurting diagrams, and regenerated README FlowDoc blocks. Outcome: inference covered and showcased in real example; build passes.

- 2026-02-22 – Added MermaidGenerator test coverage for inferred condition descriptions. Outcome: ensures inferred names propagate into diagram node ids and edge labels.

- 2026-02-22 – Added `@FlowLiteDsl` (`@DslMarker`) to builder classes to reduce accidental outer-scope calls in nested DSL lambdas. Outcome: safer DSL usage without changing runtime behavior; tests pass.

- 2026-02-22 – IEF loop (x=3) cycle 1/3: implemented shorthand support for receiver DSL stage blocks and resolved JVM/overload issues. Outcome: `stage(StageX) { ... }` works without `block =`.

- 2026-02-22 – IEF loop (x=3) cycle 2/3: migrated shorthand call sites (Order Confirmation + receiver DSL tests) and fixed sequencing regression by chaining stage transitions. Outcome: behavior preserved and syntax simplified.

- 2026-02-22 – IEF loop (x=3) cycle 3/3: added shorthand regression coverage, ran `./gradlew test updateReadme`, and refreshed docs. Outcome: full build green and README examples regenerated.

- 2026-02-22 – Integrated Cockpit prototype as a runnable test-only Spring Boot app (`./gradlew runTestApp`) with REST endpoints + a minimal static UI backed by `FLOWLITE_HISTORY`. Outcome: observability prototype available; test suite remains green.

- 2026-02-22 – Implemented the full cockpit prototype from `flowlite-cockpit.jsx` as a React + TypeScript app in `cockpit-ui/` (all views, filters, bulk actions, history modal, Mermaid rendering). Outcome: rich UI available with typed code and successful frontend build.

- 2026-02-23 – Refactored Cockpit to source-level publishable API/router (`CockpitApi`, functional `CockpitRouter`) and served cockpit UI from test app Tomcat (removed separate prototype application/controller, switched to `runTestApp`, rewired frontend to real `/api` endpoints and real history event types). Outcome: architecture aligned with target model and build/compile checks pass.

- 2026-02-24 – Completed Cockpit follow-ups: consolidated cockpit logic into source `cockpit/service.kt`, removed test-side cockpit API adapter/query/catalog classes, added history repository latest-row queries, switched router to service directly, added `Cancelled` status support in backend/frontend, and fixed DSL flow regressions in `OrderConfirmation`/receiver tests. Outcome: `./gradlew test` green and cockpit frontend build passes.
