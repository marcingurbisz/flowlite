package io.flowlite.api

import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * Represents a stage within a workflow. Implementations should be enums to provide a finite set of possible stages.
 */
interface Stage

/**
 * Represents an event that can trigger state transitions in a workflow. Implementations should be enums to provide a
 * finite set of possible events.
 */
interface Event

/**
 * Exception thrown when a duplicate stage or event is added to a flow.
 */
class FlowDefinitionException(message: String) : RuntimeException(message)

/**
 * Runtime status of a single active stage for a flow instance.
 */
enum class StageStatus {
    PENDING,
    RUNNING,
    COMPLETED, // used only for terminal stages
    ERROR,
}

/**
 * Persisted view of a flow instance.
 */
data class ProcessData<T : Any>(
    val flowInstanceId: UUID,
    val state: T,
    val stage: Stage,
    val stageStatus: StageStatus,
)

/**
 * Interface for persisting the state of a workflow instance.
 *
 * @param T The type of the state object.
 */
interface StatePersister<T : Any> {
    /**
     * Create or update the domain row and engine columns atomically.
     * Returns refreshed data on success.
     */
    fun save(processData: ProcessData<T>): ProcessData<T>

    /** Load current process data; throws if the process does not exist. */
    fun load(flowInstanceId: UUID): ProcessData<T>
}

/**
 * Pluggable store for pending events. Default implementation is in-memory; applications can provide
 * persistent implementations (e.g., Spring Data JDBC) without changing the engine.
 */
interface EventStore {
    fun append(flowId: String, flowInstanceId: UUID, event: Event)
    fun peek(flowId: String, flowInstanceId: UUID, candidates: Collection<Event>): StoredEvent?
    fun delete(eventId: UUID): Boolean
}

data class StoredEvent(
    val id: UUID,
    val event: Event,
)

interface TickScheduler {
    fun setTickHandler(handler: (String, UUID) -> Unit)
    fun scheduleTick(flowId: String, flowInstanceId: UUID)
}

data class Flow<T : Any>(
    val initialStage: Stage?,
    val initialCondition: ConditionHandler<T>?,
    val stages: Map<Stage, StageDefinition<T>>,
) {
    init {
        require((initialStage != null) xor (initialCondition != null)) {
            "Flow must have either an initial stage or an initial condition, but not both"
        }
    }
}

class FlowBuilder<T : Any> {

    internal val stages = mutableMapOf<Stage, StageDefinition<T>>()
    internal var initialStage: Stage? = null
    internal var initialCondition: ConditionHandler<T>? = null

    fun stage(stage: Stage, action: ((item: T) -> T?)? = null): StageBuilder<T> {
        return internalStage(stage, action)
    }

    fun join(targetStage: Stage) { initialStage = targetStage }

    internal fun internalStage(stage: Stage, action: ((item: T) -> T?)? = null): StageBuilder<T> {
        if (initialStage == null && initialCondition == null) {
            initialStage = stage
        }
        
        val stageDefinition = StageDefinition<T>(stage, action)
        addStage(stage, stageDefinition)
        return StageBuilder(this, stageDefinition)
    }
    
    fun condition(
        predicate: (item: T) -> Boolean,
        onTrue: FlowBuilder<T>.() -> Unit,
        onFalse: FlowBuilder<T>.() -> Unit,
        description: String
    ): FlowBuilder<T> {
        initialCondition = createConditionHandler(predicate, onTrue, onFalse, description)
        return this
    }

    internal fun addStage(stage: Stage, definition: StageDefinition<T>) {
        if (stage in stages) {
            throw FlowDefinitionException("Stage $stage already defined - each stage should be defined only once")
        }
        stages[stage] = definition
    }

    internal fun createConditionHandler(
        predicate: (item: T) -> Boolean,
        onTrue: FlowBuilder<T>.() -> Unit,
        onFalse: FlowBuilder<T>.() -> Unit,
        description: String
    ): ConditionHandler<T> {
        // Create both branch builders
        val trueBranch = FlowBuilder<T>().apply(onTrue)
        val falseBranch = FlowBuilder<T>().apply(onFalse)
        
        // Collect all stage definitions from both branches
        trueBranch.stages.forEach { (stage, definition) ->
            addStage(stage, definition)
        }
        falseBranch.stages.forEach { (stage, definition) ->
            addStage(stage, definition)
        }
        
        return ConditionHandler(
            predicate,
            trueBranch.initialStage,
            trueBranch.initialCondition,
            falseBranch.initialStage,
            falseBranch.initialCondition,
            description
        )
    }

    /**
     * Builds and returns an immutable flow.
     */
    fun build(): Flow<T> {
        validateDefinitions()
        return Flow(initialStage, initialCondition, stages.toMap())
    }

    private fun validateDefinitions() {
        val eventToStage = mutableMapOf<Event, Stage>()
        stages.values.forEach { def ->
            if (def.action != null && def.eventHandlers.isNotEmpty()) {
                throw FlowDefinitionException("Stage ${def.stage} cannot declare both an action and event handlers")
            }
            if (def.eventHandlers.isNotEmpty() && (def.nextStage != null || def.conditionHandler != null)) {
                throw FlowDefinitionException("Stage ${def.stage} cannot mix event handlers with direct or conditional transitions")
            }

            def.eventHandlers.keys.forEach { event ->
                val existing = eventToStage.putIfAbsent(event, def.stage)
                if (existing != null && existing != def.stage) {
                    throw FlowDefinitionException(
                        "Event $event is used in multiple waitFor declarations ($existing and ${def.stage}). " +
                            "Reusing the same event type in different parts of a flow is not supported; " +
                            "model repeated occurrences as distinct event types or include event identity/deduplication in your EventStore."
                    )
                }
            }
        }
    }

}

/**
 * Types of transitions between stages.
 */
enum class TransitionType {
    DIRECT,    // Direct stage-to-stage transition
    EVENT,     // Event-based transition
    CONDITION  // Conditional branching
}

data class StageDefinition<T : Any>(
    val stage: Stage,
    val action: ((item: T) -> T?)? = null,
) {
    val eventHandlers = mutableMapOf<Event, EventHandler<T>>()
    var conditionHandler: ConditionHandler<T>? = null
    var nextStage: Stage? = null
    
    fun hasConflictingTransitions(newTransitionType: TransitionType): Boolean {
        return when (newTransitionType) {
            TransitionType.DIRECT -> eventHandlers.isNotEmpty() || conditionHandler != null
            TransitionType.EVENT -> nextStage != null || conditionHandler != null
            TransitionType.CONDITION -> nextStage != null || eventHandlers.isNotEmpty()
        }
    }

    fun getExistingTransitions(): String {
        val transitions = mutableListOf<String>()
        if (nextStage != null) transitions.add("nextStage")
        if (eventHandlers.isNotEmpty()) transitions.add("eventHandlers")
        if (conditionHandler != null) transitions.add("conditionHandler")
        return transitions.joinToString(", ")
    }
}

data class ConditionHandler<T : Any>(
    val predicate: (item: T) -> Boolean,
    val trueStage: Stage?,
    val trueCondition: ConditionHandler<T>?,
    val falseStage: Stage?,
    val falseCondition: ConditionHandler<T>?,
    val description: String,
)

data class EventHandler<T : Any>(
    val event: Event,
    val targetStage: Stage?,
    val targetCondition: ConditionHandler<T>?,
)

class StageBuilder<T : Any>(
    val flowBuilder: FlowBuilder<T>,
    val stageDefinition: StageDefinition<T>,
) {
    fun stage(stage: Stage, action: ((item: T) -> T?)? = null): StageBuilder<T> {
        if (stageDefinition.hasConflictingTransitions(TransitionType.DIRECT)) {
            throw FlowDefinitionException("Stage ${stageDefinition.stage} already has transitions defined: ${stageDefinition.getExistingTransitions()}. Use only one of: stage(), onEvent(), or condition().")
        }
        stageDefinition.nextStage = stage
        return flowBuilder.internalStage(stage, action)
    }

    fun waitFor(event: Event): EventBuilder<T> = EventBuilder(this, event)

    fun condition(
        predicate: (item: T) -> Boolean,
        onTrue: FlowBuilder<T>.() -> Unit,
        onFalse: FlowBuilder<T>.() -> Unit,
        description: String
    ): FlowBuilder<T> {
        if (stageDefinition.hasConflictingTransitions(TransitionType.CONDITION)) {
            throw FlowDefinitionException("Stage ${stageDefinition.stage} already has transitions defined: ${stageDefinition.getExistingTransitions()}. Use only one of: stage(), onEvent(), or condition().")
        }
        stageDefinition.conditionHandler = flowBuilder.createConditionHandler(predicate, onTrue, onFalse, description)

        return flowBuilder
    }
    
    fun join(targetStage: Stage): FlowBuilder<T> {
        if (stageDefinition.hasConflictingTransitions(TransitionType.DIRECT)) {
            throw FlowDefinitionException("Stage ${stageDefinition.stage} already has transitions defined: ${stageDefinition.getExistingTransitions()}. Use only one of: stage(), onEvent(), or condition().")
        }
        stageDefinition.nextStage = targetStage
        return flowBuilder
    }

    fun end(): FlowBuilder<T> = flowBuilder
}

class EventBuilder<T : Any>(
    private val stageBuilder: StageBuilder<T>,
    private val event: Event
) {
    fun stage(stage: Stage, action: ((item: T) -> T?)? = null): StageBuilder<T> {
        if (stageBuilder.stageDefinition.hasConflictingTransitions(TransitionType.EVENT)) {
            throw FlowDefinitionException("Stage ${stageBuilder.stageDefinition.stage} already has transitions defined: ${stageBuilder.stageDefinition.getExistingTransitions()}. Use only one of: stage(), onEvent(), or condition().")
        }

        val targetStageBuilder = stageBuilder.flowBuilder.internalStage(stage, action)
        stageBuilder.stageDefinition.eventHandlers[event] = EventHandler(event, stage, null)
        
        return targetStageBuilder
    }

    fun join(targetStage: Stage) {
        stageBuilder.stageDefinition.eventHandlers[event] = EventHandler(event, targetStage, null)
    }

    fun condition(
        predicate: (item: T) -> Boolean,
        onTrue: FlowBuilder<T>.() -> Unit,
        onFalse: FlowBuilder<T>.() -> Unit,
        description: String
    ): FlowBuilder<T> {
        stageBuilder.stageDefinition.eventHandlers[event] = EventHandler(event, null,
            stageBuilder.flowBuilder.createConditionHandler(predicate, onTrue, onFalse, description))
        return stageBuilder.flowBuilder
    }

}

class FlowEngine(
    private val eventStore: EventStore,
    private val tickScheduler: TickScheduler,
) {
    init {
        tickScheduler.setTickHandler(::processTick)
    }
    private val flows = mutableMapOf<String, Flow<Any>>()
    private val persisters = mutableMapOf<String, StatePersister<Any>>()

    fun <T : Any> registerFlow(
        flowId: String,
        flow: Flow<T>,
        statePersister: StatePersister<T>,
    ) {
        flows[flowId] = flow as Flow<Any>
        persisters[flowId] = statePersister as StatePersister<Any>
    }

    fun <T : Any> startProcess(flowId: String, initialState: T): UUID {
        val flowInstanceId = UUID.randomUUID()
        val flow = requireNotNull(flows[flowId]) { "Flow '$flowId' not registered" }
        val persister = requireNotNull(persisters[flowId]) { "Persister for flow '$flowId' not registered" }
        val initialStage = resolveInitialStage(flow as Flow<T>, initialState)
        val pd = ProcessData(
            flowInstanceId = flowInstanceId,
            state = initialState,
            stage = initialStage,
            stageStatus = StageStatus.PENDING,
        )
        persister.save(pd as ProcessData<Any>)
        enqueueTick(flowId, flowInstanceId)
        return flowInstanceId
    }

    fun startProcess(flowId: String, flowInstanceId: UUID): UUID {
        requireNotNull(flows[flowId]) { "Flow '$flowId' not registered" }
        val persister = requireNotNull(persisters[flowId]) { "Persister for flow '$flowId' not registered" }
        val current = persister.load(flowInstanceId)
        if (current.stageStatus == StageStatus.COMPLETED) return flowInstanceId
        enqueueTick(flowId, flowInstanceId)
        return flowInstanceId
    }

    fun sendEvent(flowId: String, flowInstanceId: UUID, event: Event) {
        requireNotNull(flows[flowId]) { "Flow '$flowId' not registered" }
        requireNotNull(persisters[flowId]) { "Persister for flow '$flowId' not registered" }
        eventStore.append(flowId, flowInstanceId, event)
        enqueueTick(flowId, flowInstanceId)
    }

    fun retry(flowId: String, flowInstanceId: UUID) {
        val persister = requireNotNull(persisters[flowId]) { "Persister for flow '$flowId' not registered" }
        val current = persister.load(flowInstanceId)
        if (current.stageStatus != StageStatus.ERROR) {
            error("Cannot retry $flowId/$flowInstanceId because status is ${current.stageStatus}")
        }
        val reset = current.copy(stageStatus = StageStatus.PENDING)
        persister.save(reset)
        enqueueTick(flowId, flowInstanceId)
    }

    fun getStatus(flowId: String, flowInstanceId: UUID): Pair<Stage, StageStatus>? {
        val persister = persisters[flowId] ?: return null
        val pd = persister.load(flowInstanceId)
        return pd.stage to pd.stageStatus
    }

    // --- Internal processing ---

    private fun <T : Any> resolveInitialStage(flow: Flow<T>, state: T): Stage {
        flow.initialStage?.let { return it }
        val cond = requireNotNull(flow.initialCondition) { "Flow must have initial stage or condition" }
        return resolveConditionInitialStage(cond, state)
            ?: throw FlowDefinitionException("Initial condition did not resolve to a stage")
    }

    private fun <T : Any> resolveConditionInitialStage(condition: ConditionHandler<T>, state: T): Stage? {
        val branchTrue = condition.predicate(state)
        val stage = if (branchTrue) condition.trueStage else condition.falseStage
        val nested = if (branchTrue) condition.trueCondition else condition.falseCondition
        return stage ?: nested?.let { resolveConditionInitialStage(it, state) }
    }

    private fun enqueueTick(flowId: String, flowInstanceId: UUID) {
        tickScheduler.scheduleTick(flowId, flowInstanceId)
    }

    private fun processTick(flowId: String, flowInstanceId: UUID) {
        val flow = flows[flowId] ?: return
        val persister = persisters[flowId] ?: return

        val pd = persister.load(flowInstanceId)
        processTickLoop(flowId, flow, persister, pd)
    }

    private fun processTickLoop(flowId: String, flow: Flow<Any>, persister: StatePersister<Any>, initial: ProcessData<Any>) {
        var pd = initial
        val flowInstanceId = pd.flowInstanceId
        while (true) {
            when (pd.stageStatus) {
                StageStatus.ERROR -> {
                    log("$flowId/$flowInstanceId is in ERROR at stage ${pd.stage}; awaiting retry")
                    return
                }
                StageStatus.RUNNING -> {
                    log("$flowId/$flowInstanceId currently RUNNING at stage ${pd.stage}; skipping processing loop")
                    return
                }
                StageStatus.COMPLETED -> {
                    log("$flowId/$flowInstanceId already COMPLETED")
                    return
                }
                StageStatus.PENDING -> {
                    log("Processing loop for $flowId/$flowInstanceId at stage ${pd.stage}")
                    val def = flow.stages[pd.stage]
                        ?: throw FlowDefinitionException("No definition for stage ${pd.stage}")

                    try {
                        if (def.eventHandlers.isNotEmpty()) {
                            val next = tryConsumeEventAndAdvance(flowId, def, pd, persister, flowInstanceId)
                            if (next != null) {
                                log("Event consumed for ${pd.stage}; advancing")
                                pd = next
                                continue
                            }
                            return
                        }

                        if (def.action != null) {
                            val updated = runActionAndPersistNext(def, pd, persister)
                            if (updated.stageStatus == StageStatus.COMPLETED) {
                                log("Stage ${def.stage} completed after action")
                                return
                            }
                            if (updated.stage != pd.stage) {
                                log("Action advanced ${pd.stage} -> ${updated.stage}")
                                pd = updated
                                continue
                            }
                            return
                        }

                        def.conditionHandler?.let { cond ->
                            val from = pd.stage
                            val target = resolveConditionInitialStage(cond, pd.state)
                                ?: throw FlowDefinitionException("Condition did not resolve to a stage from ${pd.stage}")
                            val next = pd.copy(stage = target, stageStatus = StageStatus.PENDING)
                            pd = persister.save(next)
                            log("Condition transition $from -> $target")
                            continue
                        }

                        def.nextStage?.let { ns ->
                            val from = pd.stage
                            val next = pd.copy(stage = ns, stageStatus = StageStatus.PENDING)
                            pd = persister.save(next)
                            log("Automatic transition $from -> $ns")
                            continue
                        }

                        if (def.isTerminal()) {
                            val completed = pd.copy(stageStatus = StageStatus.COMPLETED)
                            persister.save(completed)
                            log("Stage ${pd.stage} marked COMPLETED")
                            return
                        }

                        error("Stage ${pd.stage} has no transitions but is not terminal")
                    } catch (ex: Throwable) {
                        handleFailure(flowId, flowInstanceId, pd, persister, ex)
                        throw ex
                    }
                }
            }
        }
    }

    private fun tryConsumeEventAndAdvance(
        flowId: String,
        def: StageDefinition<Any>,
        pd: ProcessData<Any>,
        persister: StatePersister<Any>,
        flowInstanceId: UUID,
    ): ProcessData<Any>? {
        val stored = eventStore.peek(flowId, flowInstanceId, def.eventHandlers.keys) ?: return null
        val handler = def.eventHandlers[stored.event] ?: return null
        val targetStage = handler.targetStage ?: handler.targetCondition?.let { ch ->
            resolveConditionInitialStage(ch, pd.state)
        }
        ?: throw FlowDefinitionException("Event handler did not resolve to a stage from ${pd.stage}")
        val next = pd.copy(stage = targetStage, stageStatus = StageStatus.PENDING)
        val saved = persister.save(next)
        eventStore.delete(stored.id)
        return saved
    }

    /**
     * Execute action with RUNNING flip and persist the outcome.
     */
    private fun runActionAndPersistNext(
        def: StageDefinition<Any>,
        pd: ProcessData<Any>,
        persister: StatePersister<Any>,
    ): ProcessData<Any> {
        val action = requireNotNull(def.action) { "runActionAndPersistNext called without an action" }

        val running = pd.copy(stageStatus = StageStatus.RUNNING)
        val current = persister.save(running)

        val result = action(current.state)
        val newState = result ?: current.state

        if (def.isTerminal()) {
            val completed = current.copy(state = newState, stageStatus = StageStatus.COMPLETED)
            return persister.save(completed)
        }

        val nextStage: Stage = def.conditionHandler
            ?.let { cond ->
                resolveConditionInitialStage(cond, newState)
                    ?: throw FlowDefinitionException("Condition did not resolve to a stage from ${pd.stage}")
            }
            ?: def.nextStage
            ?: error("Non-terminal stage ${pd.stage} has an action but no nextStage/condition")

        val next = current.copy(state = newState, stage = nextStage, stageStatus = StageStatus.PENDING)
        return persister.save(next)
    }

    private fun handleFailure(
        flowId: String,
        flowInstanceId: UUID,
        pd: ProcessData<Any>,
        persister: StatePersister<Any>,
        ex: Throwable,
    ) {
        log("Failure in $flowId/$flowInstanceId at stage ${pd.stage}: ${ex.message}")
        val errored = pd.copy(stageStatus = StageStatus.ERROR)
        try {
            persister.save(errored)
        } catch (_: Throwable) {
            // best-effort
        }
    }

    private fun StageDefinition<*>.isTerminal(): Boolean =
        nextStage == null && conditionHandler == null && eventHandlers.isEmpty()

    private fun log(message: String) {
        logger.info(message)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(FlowEngine::class.java)
    }
}