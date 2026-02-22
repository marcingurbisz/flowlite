# Interaction Log

Template:
- Date – [What was done]. Outcome: [Result].

## Entries

- 2026-02-21 – Added optional `condition` description inference and strengthened DSL validation to fail fast on undefined stage references / empty condition branches; added tests. Outcome: cleaner DSL usage and earlier errors; test suite passes.

- 2026-02-22 – Added explicit tests for condition description inference (function ref vs lambda fallback), refactored Employee Onboarding flow to use named predicates so `description` can be omitted without hurting diagrams, and regenerated README FlowDoc blocks. Outcome: inference covered and showcased in real example; build passes.

- 2026-02-22 – Added MermaidGenerator test coverage for inferred condition descriptions. Outcome: ensures inferred names propagate into diagram node ids and edge labels.

- 2026-02-22 – Added `@FlowLiteDsl` (`@DslMarker`) to builder classes to reduce accidental outer-scope calls in nested DSL lambdas. Outcome: safer DSL usage without changing runtime behavior; tests pass.
