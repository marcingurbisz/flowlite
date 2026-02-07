## TODO

WIP: 

* What tests are missing to have good test coverage?
* Move SpringData Jdbc implementations of TickScheduler and EventStore to source
* Integrate with Sonar and Codecov

Next:

* Change io.flowlite.api to io.flowlite
* onTrue/onFalse as methods?
* update to Java 25
* Introduce Flow<T : Any, S : Stage, E : Event> with NoEvent as default
* Implement history of changes
* Integrate Cockpit prototype
* Expose test instance publicly available 
* Optimistic locking based on modified fields?
* Tick using Azure Service Bus emulator? Different persistence? Mongo? Redis?