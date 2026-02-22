package io.flowlite

/**
 * Represents a stage within a workflow. Implementations should be enums to provide a finite set of possible stages.
 */
interface Stage

/**
 * Represents an event that can trigger state transitions in a workflow. Implementations should be enums to provide a
 * finite set of possible events.
 */
interface Event

object NoEvent : Event

data class Flow<T : Any, S : Stage, E : Event>(
    val initialStage: S?,
    val initialCondition: ConditionHandler<T, S>?,
    val stages: Map<S, StageDefinition<T, S, E>>,
) {
    init {
        require((initialStage != null) xor (initialCondition != null)) {
            "Flow must have either an initial stage or an initial condition, but not both"
        }
    }
}

internal fun inferConditionDescription(predicate: Any): String {
    val asString = predicate.toString()
    val rawName = when {
        asString.startsWith("fun ") -> asString.substringAfter("fun ").substringBefore("(")
        else -> asString.substringBefore("(").substringBefore("$")
    }
    val candidate = rawName.substringAfterLast(".")
    val isLikelySynthetic = candidate.isBlank() ||
        candidate.startsWith("Function") ||
        candidate.contains("Lambda") ||
        candidate.contains("$$")
    return if (isLikelySynthetic) "condition" else candidate
}

class FlowBuilder<T : Any, S : Stage, E : Event> {

    internal val stages = mutableMapOf<S, StageDefinition<T, S, E>>()
    internal var initialStage: S? = null
    internal var initialCondition: ConditionHandler<T, S>? = null

    fun stage(stage: S, action: ((item: T) -> T?)? = null): StageBuilder<T, S, E> {
        return internalStage(stage, action)
    }

    fun join(targetStage: S) { initialStage = targetStage }

    internal fun internalStage(stage: S, action: ((item: T) -> T?)? = null): StageBuilder<T, S, E> {
        if (initialStage == null && initialCondition == null) {
            initialStage = stage
        }

        val stageDefinition = StageDefinition<T, S, E>(stage, action)
        addStage(stage, stageDefinition)
        return StageBuilder(this, stageDefinition)
    }

    fun condition(
        predicate: (item: T) -> Boolean,
        onTrue: FlowBuilder<T, S, E>.() -> Unit,
        onFalse: FlowBuilder<T, S, E>.() -> Unit,
        description: String = inferConditionDescription(predicate)
    ): FlowBuilder<T, S, E> {
        initialCondition = createConditionHandler(predicate, onTrue, onFalse, description)
        return this
    }

    internal fun addStage(stage: S, definition: StageDefinition<T, S, E>) {
        if (stage in stages) {
            error("Stage $stage already defined - each stage should be defined only once")
        }
        stages[stage] = definition
    }

    internal fun createConditionHandler(
        predicate: (item: T) -> Boolean,
        onTrue: FlowBuilder<T, S, E>.() -> Unit,
        onFalse: FlowBuilder<T, S, E>.() -> Unit,
        description: String
    ): ConditionHandler<T, S> {
        // Create both branch builders
        val trueBranch = FlowBuilder<T, S, E>().apply(onTrue)
        val falseBranch = FlowBuilder<T, S, E>().apply(onFalse)

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
    fun build(): Flow<T, S, E> {
        validateDefinitions()
        return Flow(initialStage, initialCondition, stages.toMap())
    }

    private fun validateDefinitions() {
        val eventToStage = mutableMapOf<E, S>()
        stages.values.forEach { def ->
            if (def.action != null && def.eventHandlers.isNotEmpty()) {
                error("Stage ${def.stage} cannot declare both an action and event handlers")
            }
            if (def.eventHandlers.isNotEmpty() && (def.nextStage != null || def.conditionHandler != null)) {
                error("Stage ${def.stage} cannot mix event handlers with direct or conditional transitions")
            }

            def.eventHandlers.keys.forEach { event ->
                val existing = eventToStage.putIfAbsent(event, def.stage)
                if (existing != null && existing != def.stage) {
                    error(
                        "Event $event is used in multiple waitFor declarations ($existing and ${def.stage}). " +
                            "Reusing the same event type in different parts of a flow is not supported; " +
                            "model repeated occurrences as distinct event types or include event identity/deduplication in your EventStore."
                    )
                }
            }
        }

        // Validate that all referenced stages exist.
        initialStage?.let { stage ->
            if (stage !in stages) error("Initial stage $stage is not defined in the flow")
        }
        initialCondition?.let { validateConditionResolvesToDefinedStages(it, "initialCondition") }

        stages.values.forEach { def ->
            def.nextStage?.let { next ->
                if (next !in stages) error("Stage ${def.stage} references undefined nextStage $next")
            }

            def.conditionHandler?.let { ch ->
                validateConditionResolvesToDefinedStages(ch, "condition from stage ${def.stage}")
            }

            def.eventHandlers.forEach { (_, handler) ->
                handler.targetStage?.let { target ->
                    if (target !in stages) error("Stage ${def.stage} has event transition to undefined stage $target")
                }
                handler.targetCondition?.let { ch ->
                    validateConditionResolvesToDefinedStages(ch, "event-condition from stage ${def.stage}")
                }
            }
        }
    }

    private fun validateConditionResolvesToDefinedStages(condition: ConditionHandler<T, S>, origin: String) {
        val referenced = mutableSetOf<S>()
        val visited = mutableSetOf<ConditionHandler<T, S>>()

        fun walk(ch: ConditionHandler<T, S>) {
            if (!visited.add(ch)) return

            val trueHasTarget = ch.trueStage != null || ch.trueCondition != null
            val falseHasTarget = ch.falseStage != null || ch.falseCondition != null
            if (!trueHasTarget || !falseHasTarget) {
                error("Condition ($origin) must resolve to a stage on both branches (description='${ch.description}')")
            }

            ch.trueStage?.let { referenced.add(it) }
            ch.falseStage?.let { referenced.add(it) }
            ch.trueCondition?.let { walk(it) }
            ch.falseCondition?.let { walk(it) }
        }

        walk(condition)
        referenced.forEach { stage ->
            if (stage !in stages) error("Condition ($origin) references undefined stage $stage")
        }
    }

}

typealias EventlessFlow<T, S> = Flow<T, S, NoEvent>

typealias EventlessFlowBuilder<T, S> = FlowBuilder<T, S, NoEvent>

/**
 * Types of transitions between stages.
 */
enum class TransitionType {
    Direct,    // Direct stage-to-stage transition
    Event,     // Event-based transition
    Condition  // Conditional branching
}

data class StageDefinition<T : Any, S : Stage, E : Event>(
    val stage: S,
    val action: ((item: T) -> T?)? = null,
) {
    val eventHandlers = mutableMapOf<E, EventHandler<T, S, E>>()
    var conditionHandler: ConditionHandler<T, S>? = null
    var nextStage: S? = null

    fun hasConflictingTransitions(newTransitionType: TransitionType): Boolean {
        return when (newTransitionType) {
            TransitionType.Direct -> eventHandlers.isNotEmpty() || conditionHandler != null
            TransitionType.Event -> nextStage != null || conditionHandler != null
            TransitionType.Condition -> nextStage != null || eventHandlers.isNotEmpty()
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

data class ConditionHandler<T : Any, S : Stage>(
    val predicate: (item: T) -> Boolean,
    val trueStage: S?,
    val trueCondition: ConditionHandler<T, S>?,
    val falseStage: S?,
    val falseCondition: ConditionHandler<T, S>?,
    val description: String,
)

data class EventHandler<T : Any, S : Stage, E : Event>(
    val event: E,
    val targetStage: S?,
    val targetCondition: ConditionHandler<T, S>?,
)

class StageBuilder<T : Any, S : Stage, E : Event>(
    val flowBuilder: FlowBuilder<T, S, E>,
    val stageDefinition: StageDefinition<T, S, E>,
) {
    fun stage(stage: S, action: ((item: T) -> T?)? = null): StageBuilder<T, S, E> {
        if (stageDefinition.hasConflictingTransitions(TransitionType.Direct)) {
            error("Stage ${stageDefinition.stage} already has transitions defined: ${stageDefinition.getExistingTransitions()}. Use only one of: stage(), onEvent(), or condition().")
        }
        stageDefinition.nextStage = stage
        return flowBuilder.internalStage(stage, action)
    }

    fun waitFor(event: E): EventBuilder<T, S, E> = EventBuilder(this, event)

    fun condition(
        predicate: (item: T) -> Boolean,
        onTrue: FlowBuilder<T, S, E>.() -> Unit,
        onFalse: FlowBuilder<T, S, E>.() -> Unit,
        description: String = inferConditionDescription(predicate)
    ): FlowBuilder<T, S, E> {
        if (stageDefinition.hasConflictingTransitions(TransitionType.Condition)) {
            error("Stage ${stageDefinition.stage} already has transitions defined: ${stageDefinition.getExistingTransitions()}. Use only one of: stage(), onEvent(), or condition().")
        }
        stageDefinition.conditionHandler = flowBuilder.createConditionHandler(predicate, onTrue, onFalse, description)

        return flowBuilder
    }

    fun join(targetStage: S): FlowBuilder<T, S, E> {
        if (stageDefinition.hasConflictingTransitions(TransitionType.Direct)) {
            error("Stage ${stageDefinition.stage} already has transitions defined: ${stageDefinition.getExistingTransitions()}. Use only one of: stage(), onEvent(), or condition().")
        }
        stageDefinition.nextStage = targetStage
        return flowBuilder
    }

    fun end(): FlowBuilder<T, S, E> = flowBuilder
}

class EventBuilder<T : Any, S : Stage, E : Event>(
    private val stageBuilder: StageBuilder<T, S, E>,
    private val event: E
) {
    fun stage(stage: S, action: ((item: T) -> T?)? = null): StageBuilder<T, S, E> {
        if (stageBuilder.stageDefinition.hasConflictingTransitions(TransitionType.Event)) {
            error("Stage ${stageBuilder.stageDefinition.stage} already has transitions defined: ${stageBuilder.stageDefinition.getExistingTransitions()}. Use only one of: stage(), onEvent(), or condition().")
        }

        val targetStageBuilder = stageBuilder.flowBuilder.internalStage(stage, action)
        stageBuilder.stageDefinition.eventHandlers[event] = EventHandler(event, stage, null)

        return targetStageBuilder
    }

    fun join(targetStage: S) {
        stageBuilder.stageDefinition.eventHandlers[event] = EventHandler(event, targetStage, null)
    }

    fun condition(
        predicate: (item: T) -> Boolean,
        onTrue: FlowBuilder<T, S, E>.() -> Unit,
        onFalse: FlowBuilder<T, S, E>.() -> Unit,
        description: String = inferConditionDescription(predicate)
    ): FlowBuilder<T, S, E> {
        stageBuilder.stageDefinition.eventHandlers[event] = EventHandler(event, null,
            stageBuilder.flowBuilder.createConditionHandler(predicate, onTrue, onFalse, description))
        return stageBuilder.flowBuilder
    }

}
