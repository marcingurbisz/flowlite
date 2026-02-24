# Learnings

Template:
- Date – [What happened/was tried] → Learning: [What we discovered]. Application: [How this changes our approach].

## Entries

- 2026-02-21 – [Condition description inference] → Learning: Kotlin function references stringify predictably enough to infer a readable predicate name, but lambdas often stringify to synthetic names; fallback to a stable default like `condition`. Application: keep `description` optional for ergonomics, but preserve explicit `description` for diagram readability.

- 2026-02-22 – [Kotlin overload ambiguity in DSL] → Learning: overloading receiver DSL methods with multiple functional second parameters leads to ambiguous trailing-lambda calls. Application: keep one lambda-based shorthand overload and represent action overloads with `KFunction1` to preserve ergonomic `stage(...) { ... }` syntax.