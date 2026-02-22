# Decisions

Template:
- Date – [Context/Question] → Decision: [What was decided]. Rationale: [Why]. Impact: [What this affects].

## Entries

- 2026-02-21 – [DSL ergonomics + fail-fast validation] → Decision: allow omitting `condition` description (infer when possible) and validate undefined stage references / empty condition branches at `build()` time. Rationale: improve usability and catch wiring mistakes earlier than runtime. Impact: minor additive API change + stricter build-time validation.
