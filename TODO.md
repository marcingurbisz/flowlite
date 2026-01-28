## TODO
Agent:

* The action function initializeOrderConfirmation on line 210 sets the stage field in the returned copy: confirmation.copy(stage = InitializingConfirmation, ...). However, the engine manages the stage field separately in ProcessData and updates it when advancing to the next stage. Action functions should only modify domain-specific fields and should not set the stage field, as this could lead to confusion about which component controls stage transitions.
* Split flowApi.kt into api and implementation files
* Replace list of methods and components with link to files containing them
* add logs to api method invocation. Use kotlin logging everywhere.
* What tests are missing to have good test coverage? 
* Integrate test coverage measuring tools


* Tick using Azure Service Bus emulator
* Send for review to guys
* Full implementation of engine with working example
* onTrue/onFalse as methods?
* Cockpit UI
  * History of changes
* optimistic locking like in walternate?