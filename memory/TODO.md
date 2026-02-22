# TODO

## WIP
- API ergonomics: condition description can be inferred; DSL now validates undefined joins/empty condition branches (done).

## Next
- Integrate Cockpit prototype
- Expose test instance publicly available
- Yet more coverage?
- Optimistic locking based on modified fields?
- Tick using Azure Service Bus emulator? Different persistence? Mongo? Redis?
- Consider validating/whitelisting `sendEvent` events against the registered flow definition to avoid silently accumulating never-consumed events.
