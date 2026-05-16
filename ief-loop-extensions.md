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
- Prefer Playwright MCP after browser automation when you still want step-by-step scripted interaction, reproducible waits, or richer automation control, but the direct browser-automation workflow is unavailable or less practical for the task.
- Prefer Playwright CLI after MCP when you only need a quick smoke check or screenshot and do not need a richer interactive or scripted session.
- Record in `TODO.md` which tool was used and why, especially when you had to fall back from browser automation to CLI.

## [EVERY LOOP] Review your own changes

Review your own changes from the current loop, looking for potential improvements, simplifications missing tests in both the code and the surrounding concepts.
Report your work as todo item itself explaining what you have reviewed. Report any finding as a separate TODO item with the `[FOR HUMAN REVIEW]` marker.