# Playwright Test Scenarios

## Purpose
Define stable, high-value Cockpit UI scenarios for Kotlin Playwright E2E coverage.

## Implemented in `test/CockpitPlaywrightTest.kt`
- Smoke: cockpit loads, flow cards are visible, Instances tab opens, instance details modal opens.
- Flow diagram: opening `View Diagram` for `order-confirmation` shows and closes the diagram modal.
- Flow-to-instances navigation: `incomplete` shortcut from flow card switches to Instances with pre-filled flow search.

## Candidate next scenarios
- Error handling actions: select error instance(s), trigger retry, verify status/timeline updates.
- Change stage modal: open modal, choose stage, confirm transition, verify stage reflects in details.
- Long-running view: set threshold/filter and verify only matching running instances are shown.
- Timeline detail expansion: expand stack trace rows for error events and verify payload visibility.

## Selector policy
- Prefer `data-testid` selectors for assertions and interactions.
- Use role/text selectors only when semantic and stable enough.
- Keep `data-testid` values descriptive and deterministic (for example: `flow-view-diagram-order-confirmation`).
