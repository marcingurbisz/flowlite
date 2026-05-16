# FlowLite repo IEF loop extensions

These rules extend the base IEF loop described in the workspace-level `AGENTS.md`.

# TODO

Below the tasks that extend each loop.

## [WHEN REQUESTED] Exploratory tests

Do exploratory tests using our local `runPerfTestApp`.
Search for bugs, visual and performance issues.
Shortly document what you have tested as an item in `TODO.md`.
If you find issues, add them as TODO items in `TODO.md` and fix them immediately when they are in scope for the current loop.
Tool preference order for exploratory tests:
- Prefer browser automation against the live app. It gives the fastest interactive feedback, real rendered UI state, and lets you inspect the current page while still taking screenshots.
- Prefer Playwright CLI or MCP as a fallback when the app is reachable only from inside the container or when you need a quick smoke check or screenshot without a longer interactive session.
- Record in `TODO.md` which tool was used and why, especially when you had to fall back from browser automation to CLI.

## [EVERY LOOP] Review your own changes

Review your own changes from the current loop, looking for potential improvements, simplifications missing tests in both the code and the surrounding concepts.
Report your work as todo item itself explaining what you have reviewed. Report any finding as a separate TODO item with the `[FOR HUMAN REVIEW]` marker.