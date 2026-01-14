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
     * Return true on success, false on optimistic conflict (no write applied).
     */
    fun save(processData: ProcessData<T>): Boolean

    /** Load current process data or null if not found. */
    fun load(flowInstanceId: UUID): ProcessData<T>?
}

/**
 * Pluggable store for pending events. Default implementation is in-memory; applications can provide
 * persistent implementations (e.g., Spring Data JDBC) without changing the engine.
 */
interface EventStore {
    fun append(flowId: String, flowInstanceId: UUID, event: Event)
    fun poll(flowId: String, flowInstanceId: UUID, candidates: Collection<Event>): Event?
}

interface TickScheduler {
    fun schedule(flowId: String, flowInstanceId: UUID, dispatch: () -> Unit)
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
        stages.values.forEach { def ->
            if (def.action != null && def.eventHandlers.isNotEmpty()) {
                throw FlowDefinitionException("Stage ${def.stage} cannot declare both an action and event handlers")
            }
            if (def.eventHandlers.isNotEmpty() && (def.nextStage != null || def.conditionHandler != null)) {
                throw FlowDefinitionException("Stage ${def.stage} cannot mix event handlers with direct or conditional transitions")
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
            ?: throw IllegalArgumentException("Process '$flowInstanceId' for flow '$flowId' not found")
        if (current.stageStatus == StageStatus.COMPLETED) return flowInstanceId
        enqueueTick(flowId, flowInstanceId)
        return flowInstanceId
    }

    fun sendEvent(flowId: String, flowInstanceId: UUID, event: Event) {
        eventStore.append(flowId, flowInstanceId, event)
        enqueueTick(flowId, flowInstanceId)
    }

    fun retry(flowId: String, flowInstanceId: UUID) {
        enqueueTick(flowId, flowInstanceId)
    }

    fun getStatus(flowId: String, flowInstanceId: UUID): Pair<Stage, StageStatus>? {
        val persister = persisters[flowId] ?: return null
        val pd = persister.load(flowInstanceId) ?: return null
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
        tickScheduler.schedule(flowId, flowInstanceId) { processTick(flowId, flowInstanceId) }
    }

    private fun processTick(flowId: String, flowInstanceId: UUID) {
        val flow = flows[flowId] ?: return
        val persister = persisters[flowId] ?: return

        processTickTyped(flowId, flow, persister, flowInstanceId)
    }

    private tailrec fun processTickTyped(flowId: String, flow: Flow<Any>, persister: StatePersister<Any>, flowInstanceId: UUID) {
        val pd = persister.load(flowInstanceId) ?: return

        when (pd.stageStatus) {
            StageStatus.ERROR -> {
                log("$flowId/$flowInstanceId is in ERROR at stage ${pd.stage}; awaiting retry")
                return
            }
            StageStatus.RUNNING -> {
                log("$flowId/$flowInstanceId currently RUNNING at stage ${pd.stage}; skipping tick")
                return
            }
            StageStatus.COMPLETED -> {
                log("$flowId/$flowInstanceId already COMPLETED")
                return
            }
            StageStatus.PENDING -> {
                log("Tick $flowId/$flowInstanceId at stage ${pd.stage}")
                val def = flow.stages[pd.stage]
                    ?: throw FlowDefinitionException("No definition for stage ${pd.stage}")

                if (def.eventHandlers.isNotEmpty()) {
                    val consumed = tryConsumeEventAndAdvance(flowId, def, pd, persister, flowInstanceId)
                    if (consumed) {
                        log("Event consumed for ${pd.stage}; advancing")
                        processTickTyped(flowId, flow, persister, flowInstanceId)
                    }
                    return
                }

                if (def.action != null) {
                    val updated = runActionAndPersistNext(def, pd, persister, markTerminal = def.isTerminal()) ?: return
                    if (updated.stageStatus == StageStatus.COMPLETED) {
                        log("Stage ${def.stage} completed after action")
                        return
                    }
                    if (updated.stage != pd.stage) {
                        log("Action advanced ${pd.stage} -> ${updated.stage}")
                        processTickTyped(flowId, flow, persister, flowInstanceId)
                    }
                    return
                }

                def.conditionHandler?.let { cond ->
                    val target = resolveConditionInitialStage(cond, pd.state)
                        ?: throw FlowDefinitionException("Condition did not resolve to a stage from ${pd.stage}")
                    val next = pd.copy(stage = target, stageStatus = StageStatus.PENDING)
                    if (!persister.save(next)) return
                    log("Condition transition ${pd.stage} -> $target")
                    processTickTyped(flowId, flow, persister, flowInstanceId)
                    return
                }

                def.nextStage?.let { ns ->
                    val next = pd.copy(stage = ns, stageStatus = StageStatus.PENDING)
                    if (!persister.save(next)) return
                    log("Automatic transition ${pd.stage} -> $ns")
                    processTickTyped(flowId, flow, persister, flowInstanceId)
                    return
                }

                val completed = pd.copy(stageStatus = StageStatus.COMPLETED)
                persister.save(completed)
                log("Stage ${pd.stage} marked COMPLETED")
                return
            }
        }
    }

    private fun tryConsumeEventAndAdvance(
        flowId: String,
        def: StageDefinition<Any>,
        pd: ProcessData<Any>,
        persister: StatePersister<Any>,
        flowInstanceId: UUID,
    ): Boolean {
        val matching = eventStore.poll(flowId, flowInstanceId, def.eventHandlers.keys) ?: return false
        val handler = def.eventHandlers[matching] ?: return false
        val targetStage = handler.targetStage ?: handler.targetCondition?.let { ch ->
            resolveConditionInitialStage(ch, pd.state)
        }
        ?: throw FlowDefinitionException("Event handler did not resolve to a stage from ${pd.stage}")
        val next = pd.copy(stage = targetStage, stageStatus = StageStatus.PENDING)
        return persister.save(next)
    }

    /**
     * Execute action with RUNNING flip and persist the outcome.
     */
    private fun runActionAndPersistNext(
        def: StageDefinition<Any>,
        pd: ProcessData<Any>,
        persister: StatePersister<Any>,
        markTerminal: Boolean = false,
    ): ProcessData<Any>? {
        // Flip to RUNNING
        val running = pd.copy(stageStatus = StageStatus.RUNNING)
        if (!persister.save(running)) return null

        val action = def.action
            ?: return advanceAfterAction(null, def, pd, persister, markTerminal)

        val result: Any?
        try {
            result = action(pd.state)
        } catch (ex: Throwable) {
            log("Action on stage ${def.stage} failed: ${ex.message}")
            val errored = pd.copy(stageStatus = StageStatus.ERROR)
            persister.save(errored)
            return null
        }

        return advanceAfterAction(result, def, pd, persister, markTerminal)
    }

    private fun advanceAfterAction(
        result: Any?,
        def: StageDefinition<Any>,
        pd: ProcessData<Any>,
        persister: StatePersister<Any>,
        markTerminal: Boolean,
    ): ProcessData<Any>? {
        // Determine next stage
        val nextStage: Stage? = when {
            markTerminal -> null
            def.conditionHandler != null -> {
                val target = resolveConditionInitialStage(def.conditionHandler as ConditionHandler<Any>, (result ?: pd.state))
                    ?: throw FlowDefinitionException("Condition did not resolve to a stage from ${pd.stage}")
                target
            }
            def.eventHandlers.isNotEmpty() -> null // wait for event
            def.nextStage != null -> def.nextStage
            else -> null
        }

        // Compute new state and status
        return if (markTerminal) {
            val newState = (result ?: pd.state)
            val completed = pd.copy(state = newState, stageStatus = StageStatus.COMPLETED)
            if (persister.save(completed)) completed else null
        } else if (nextStage != null) {
            val newState = (result ?: pd.state)
            val next = pd.copy(state = newState, stage = nextStage, stageStatus = StageStatus.PENDING)
            // If result == null (stage-only), on conflict we should retry the save once with reloaded state
            val saved = persister.save(next)
            if (!saved && result == null) {
                val fresh = persister.load(pd.flowInstanceId) ?: return null
                val retried = fresh.copy(stage = nextStage, stageStatus = StageStatus.PENDING)
                return if (persister.save(retried)) retried else null
            }
            if (saved) next else null
        } else {
            // Remain in same stage
            val newState = (result ?: pd.state)
            val waiting = pd.copy(state = newState, stageStatus = StageStatus.PENDING)
            if (persister.save(waiting)) waiting else null
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