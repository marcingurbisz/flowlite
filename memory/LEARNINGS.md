# Learnings

Template:
- Date – [What happened/was tried] → Learning: [What we discovered]. Application: [How this changes our approach].

## Entries

- 2026-02-21 – [Condition description inference] → Learning: Kotlin function references stringify predictably enough to infer a readable predicate name, but lambdas often stringify to synthetic names; fallback to a stable default like `condition`. Application: keep `description` optional for ergonomics, but preserve explicit `description` for diagram readability.
