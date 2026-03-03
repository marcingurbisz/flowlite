package io.flowlite

import kotlin.reflect.KFunction
import kotlin.reflect.KFunction1

interface Stage

interface Event

@DslMarker
annotation class FlowLiteDsl

enum class NoEvent : Event

internal enum class ScopeKind {
    Root,
    Branch,
}

data class Flow<T : Any, S : Stage, E : Event>(
    internal val initialStage: S?,
    internal val initialCondition: ConditionHandler<T, S>?,
    internal val stages: Map<S, StageDefinition<T, S, E>>,
) {
    init {
        require((initialStage != null) xor (initialCondition != null)) {
            "Flow must have either an initial stage or an initial condition, but not both"
        }
    }
}

internal fun inferConditionDescription(predicate: Any): String {
    val kFunction = predicate as? KFunction<*>
    if (kFunction != null) {
        val name = kFunction.name
        if (name.isNotBlank() && name != "<anonymous>") return name
    }

    val asString = predicate.toString()
    val rawName = when {
        asString.startsWith("fun ") -> asString.substringAfter("fun ").substringBefore("(")
        else -> asString.substringBefore("(").substringBefore("$")
    }
    val candidate = rawName.substringAfterLast(".")
    val isLikelySynthetic = candidate.isBlank() ||
        candidate.startsWith("Function") ||
        asString.contains("$") ||
        asString.contains("lambda", ignoreCase = true) ||
        asString.contains("anonymous", ignoreCase = true)
    return if (isLikelySynthetic) "condition" else candidate
}

@FlowLiteDsl
class FlowDslScope<T : Any, S, E>
internal constructor(
    private val state: MutableFlowDefinition<T, S, E>,
    private var currentStageDefinition: StageDefinition<T, S, E>? = null,
    private val scopeKind: ScopeKind = ScopeKind.Root,
) where S : Enum<S>, S : Stage, E : Event, E : Enum<E> {
    private var flatEventContext: FlatEventContext<T, S, E>? = null
    private var stageBlockSource: StageDefinition<T, S, E>? = null

    constructor() : this(MutableFlowDefinition())

    fun stage(stage: S) {
        stageInternal(stage, null)
    }

    fun stage(stage: S, action: KFunction1<T, T?>) {
        stageInternal(stage, action as ((T) -> T?))
    }

    @JvmName("stageWithBlock")
    fun stage(stage: S, block: FlowDslScope<T, S, E>.() -> Unit) {
        stage(stage)
        val source = requireNotNull(currentStageDefinition)
        stageBlockSource = source
        try {
            block()
        } finally {
            stageBlockSource = null
            flatEventContext = null
            currentStageDefinition = source
        }
    }

    fun stage(stage: S, action: KFunction1<T, T?>, block: FlowDslScope<T, S, E>.() -> Unit) {
        stage(stage, action)
        val source = requireNotNull(currentStageDefinition)
        stageBlockSource = source
        try {
            block()
        } finally {
            stageBlockSource = null
            flatEventContext = null
            currentStageDefinition = source
        }
    }

    private fun stageInternal(stage: S, action: ((item: T) -> T?)?) {
        val flat = flatEventContext
        val definition = if (flat == null) {
            val current = currentStageDefinition
            if (current?.nextStage != null) {
                error(
                    "Stage ${current.stage} already has a direct transition defined. " +
                        "A goTo(...) ends the current branch; define subsequent stages inside another branch."
                )
            }
            val linkFrom = current?.takeIf { isStageClean(it) }
            state.defineStage(stage, action, linkFrom)
        } else {
            val branchDefinition = state.applyEventStage(flat, stage, action)
            flatEventContext = flat.copy(currentBranchDefinition = branchDefinition)
            branchDefinition
        }

        currentStageDefinition = definition
    }

    fun onEvent(event: E) {
        startEventContext(event)
    }

    private fun startEventContext(event: E) {
        val flat = flatEventContext
        require(flat == null || flat.currentBranchDefinition != null) {
            "onEvent(...) cannot be the first statement inside { ... }. " +
                "Start the block with stage(...), goTo(...), or condition(...)."
        }
        val source = stageBlockSource ?: requireNotNull(currentStageDefinition) {
            "onEvent($event) requires a previously defined stage"
        }
        flatEventContext = FlatEventContext(source, event, null)
    }

    fun onEvent(event: E, block: EventBranchDslScope<T, S, E>.() -> Unit) {
        startEventContext(event)
        val source = requireNotNull(flatEventContext).sourceStageDefinition
        try {
            EventBranchDslScope(this).apply(block)
        } finally {
            flatEventContext = null
            currentStageDefinition = source
        }
    }

    fun condition(
        predicate: (item: T) -> Boolean,
        description: String = inferConditionDescription(predicate),
        block: ConditionBranchDslScope<T, S, E>.() -> Unit,
    ) {
        val branches = ConditionBranchDslScope<T, S, E>().apply(block)
        val onTrue = requireNotNull(branches.trueBranch) {
            "condition(...) requires onTrue { ... } branch"
        }
        val onFalse = requireNotNull(branches.falseBranch) {
            "condition(...) requires onFalse { ... } branch"
        }

        val flat = flatEventContext
        if (flat != null) {
            state.applyEventCondition(flat, predicate, onTrue, onFalse, description)
            flatEventContext = null
            currentStageDefinition = flat.sourceStageDefinition
            return
        }

        val current = currentStageDefinition
        if (current != null) {
            ensureCanUseTransition(current, TransitionType.Condition)
            current.conditionHandler = state.createConditionHandler(predicate, onTrue, onFalse, description)
            return
        }

        state.initialCondition = state.createConditionHandler(predicate, onTrue, onFalse, description)
        state.initialStage = null
    }

    fun goTo(targetStage: S) {
        if (scopeKind == ScopeKind.Root) {
            error("goTo($targetStage) is not allowed in top-level flow scope. Use stage(...) / onEvent(...) / condition(...) to model root transitions.")
        }

        val flat = flatEventContext
        if (flat != null) {
            state.applyEventJoin(flat, targetStage)
            flatEventContext = null
            currentStageDefinition = flat.sourceStageDefinition
            return
        }

        val current = currentStageDefinition
        if (current != null) {
            ensureCanUseTransition(current, TransitionType.Direct)
            current.nextStage = targetStage
            return
        }

        state.initialStage = targetStage
        state.initialCondition = null
    }

    internal fun goToFromEventBranch(targetStage: S) {
        val flat = requireNotNull(flatEventContext) {
            "goTo($targetStage) in event branch requires an active onEvent(...) context"
        }
        state.applyEventJoin(flat, targetStage)
        flatEventContext = null
        currentStageDefinition = flat.sourceStageDefinition
    }

    fun build() = state.build()
}

@FlowLiteDsl
class EventBranchDslScope<T : Any, S, E>
internal constructor(
    private val flowScope: FlowDslScope<T, S, E>,
) where S : Enum<S>, S : Stage, E : Event, E : Enum<E> {
    fun stage(stage: S) {
        flowScope.stage(stage)
    }

    fun stage(stage: S, action: KFunction1<T, T?>) {
        flowScope.stage(stage, action)
    }

    fun stage(stage: S, block: EventBranchDslScope<T, S, E>.() -> Unit) {
        flowScope.stage(stage) { EventBranchDslScope(this).apply(block) }
    }

    fun stage(stage: S, action: KFunction1<T, T?>, block: EventBranchDslScope<T, S, E>.() -> Unit) {
        flowScope.stage(stage, action) { EventBranchDslScope(this).apply(block) }
    }

    fun onEvent(event: E) {
        flowScope.onEvent(event)
    }

    fun onEvent(event: E, block: EventBranchDslScope<T, S, E>.() -> Unit) {
        flowScope.onEvent(event, block)
    }

    fun condition(
        predicate: (item: T) -> Boolean,
        description: String = inferConditionDescription(predicate),
        block: ConditionBranchDslScope<T, S, E>.() -> Unit,
    ) {
        flowScope.condition(predicate, description, block)
    }

    fun goTo(targetStage: S) {
        flowScope.goToFromEventBranch(targetStage)
    }
}

@FlowLiteDsl
class ConditionBranchDslScope<T : Any, S, E> where S : Enum<S>, S : Stage, E : Event, E : Enum<E> {
    internal var trueBranch: (FlowDslScope<T, S, E>.() -> Unit)? = null
    internal var falseBranch: (FlowDslScope<T, S, E>.() -> Unit)? = null

    fun onTrue(block: FlowDslScope<T, S, E>.() -> Unit) {
        require(trueBranch == null) { "condition(...) allows only one onTrue { ... } branch" }
        trueBranch = block
    }

    fun onFalse(block: FlowDslScope<T, S, E>.() -> Unit) {
        require(falseBranch == null) { "condition(...) allows only one onFalse { ... } branch" }
        falseBranch = block
    }
}

typealias EventlessFlow<T, S> = Flow<T, S, NoEvent>

enum class TransitionType {
    Direct,
    Event,
    Condition,
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

internal data class FlatEventContext<T : Any, S, E>(
    val sourceStageDefinition: StageDefinition<T, S, E>,
    val event: E,
    val currentBranchDefinition: StageDefinition<T, S, E>?,
) where S : Enum<S>, S : Stage, E : Event, E : Enum<E>

internal class MutableFlowDefinition<T : Any, S, E> where S : Enum<S>, S : Stage, E : Event, E : Enum<E> {
    private val stages = mutableMapOf<S, StageDefinition<T, S, E>>()
    var initialStage: S? = null
    var initialCondition: ConditionHandler<T, S>? = null

    fun defineStage(
        stage: S,
        action: ((item: T) -> T?)?,
        linkFrom: StageDefinition<T, S, E>?,
    ): StageDefinition<T, S, E> {
        linkFrom?.let {
            ensureCanUseTransition(it, TransitionType.Direct)
            it.nextStage = stage
        }

        if (initialStage == null && initialCondition == null) {
            initialStage = stage
        }

        if (stage in stages) {
            error("Stage $stage already defined - each stage should be defined only once")
        }

        return StageDefinition<T, S, E>(stage, action).also {
            stages[stage] = it
        }
    }

    fun applyEventStage(
        flat: FlatEventContext<T, S, E>,
        stage: S,
        action: ((item: T) -> T?)?,
    ): StageDefinition<T, S, E> {
        if (flat.currentBranchDefinition == null) {
            ensureCanUseTransition(flat.sourceStageDefinition, TransitionType.Event)
            val target = defineStage(stage, action, null)
            flat.sourceStageDefinition.eventHandlers[flat.event] = EventHandler(flat.event, stage, null)
            return target
        }

        return defineStage(stage, action, flat.currentBranchDefinition)
    }

    fun applyEventJoin(flat: FlatEventContext<T, S, E>, targetStage: S) {
        val current = flat.currentBranchDefinition
        if (current == null) {
            ensureCanUseTransition(flat.sourceStageDefinition, TransitionType.Event)
            flat.sourceStageDefinition.eventHandlers[flat.event] = EventHandler(flat.event, targetStage, null)
            return
        }

        ensureCanUseTransition(current, TransitionType.Direct)
        current.nextStage = targetStage
    }

    fun applyEventCondition(
        flat: FlatEventContext<T, S, E>,
        predicate: (item: T) -> Boolean,
        onTrue: FlowDslScope<T, S, E>.() -> Unit,
        onFalse: FlowDslScope<T, S, E>.() -> Unit,
        description: String,
    ) {
        val current = flat.currentBranchDefinition
        if (current == null) {
            ensureCanUseTransition(flat.sourceStageDefinition, TransitionType.Event)
            flat.sourceStageDefinition.eventHandlers[flat.event] = EventHandler(
                event = flat.event,
                targetStage = null,
                targetCondition = createConditionHandler(predicate, onTrue, onFalse, description),
            )
            return
        }

        ensureCanUseTransition(current, TransitionType.Condition)
        current.conditionHandler = createConditionHandler(predicate, onTrue, onFalse, description)
    }

    fun createConditionHandler(
        predicate: (item: T) -> Boolean,
        onTrue: FlowDslScope<T, S, E>.() -> Unit,
        onFalse: FlowDslScope<T, S, E>.() -> Unit,
        description: String,
    ): ConditionHandler<T, S> {
        val trueBranch = MutableFlowDefinition<T, S, E>()
        val falseBranch = MutableFlowDefinition<T, S, E>()

        FlowDslScope(trueBranch, scopeKind = ScopeKind.Branch).apply(onTrue)
        FlowDslScope(falseBranch, scopeKind = ScopeKind.Branch).apply(onFalse)

        trueBranch.stages.forEach { (stage, definition) ->
            if (stage in stages) {
                error("Stage $stage already defined - each stage should be defined only once")
            }
            stages[stage] = definition
        }
        falseBranch.stages.forEach { (stage, definition) ->
            if (stage in stages) {
                error("Stage $stage already defined - each stage should be defined only once")
            }
            stages[stage] = definition
        }

        return ConditionHandler(
            predicate = predicate,
            trueStage = trueBranch.initialStage,
            trueCondition = trueBranch.initialCondition,
            falseStage = falseBranch.initialStage,
            falseCondition = falseBranch.initialCondition,
            description = description,
        )
    }

    internal fun build(): Flow<T, S, E> {
        validateDefinitions(stages, initialStage, initialCondition)
        return Flow(initialStage, initialCondition, stages.toMap())
    }
}

private fun <T : Any, S, E> validateDefinitions(
    stages: Map<S, StageDefinition<T, S, E>>,
    initialStage: S?,
    initialCondition: ConditionHandler<T, S>?,
) where S : Enum<S>, S : Stage, E : Event, E : Enum<E> {
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
                    "Event $event is used in multiple onEvent declarations ($existing and ${def.stage}). " +
                        "Reusing the same event type in different parts of a flow is not supported; " +
                        "model repeated occurrences as distinct event types or include event identity/deduplication in your EventStore."
                )
            }
        }
    }

    initialStage?.let { stage ->
        if (stage !in stages) error("Initial stage $stage is not defined in the flow")
    }
    initialCondition?.let { validateConditionResolvesToDefinedStages(it, stages, "initialCondition") }

    stages.values.forEach { def ->
        def.nextStage?.let { next ->
            if (next !in stages) error("Stage ${def.stage} references undefined nextStage $next")
        }

        def.conditionHandler?.let { ch ->
            validateConditionResolvesToDefinedStages(ch, stages, "condition from stage ${def.stage}")
        }

        def.eventHandlers.forEach { (_, handler) ->
            handler.targetStage?.let { target ->
                if (target !in stages) error("Stage ${def.stage} has event transition to undefined stage $target")
            }
            handler.targetCondition?.let { ch ->
                validateConditionResolvesToDefinedStages(ch, stages, "event-condition from stage ${def.stage}")
            }
        }
    }
}

private fun <T : Any, S : Stage> validateConditionResolvesToDefinedStages(
    condition: ConditionHandler<T, S>,
    stages: Map<S, StageDefinition<T, S, out Event>>,
    origin: String,
) {
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

private fun <T : Any, S, E> ensureCanUseTransition(
    definition: StageDefinition<T, S, E>,
    transitionType: TransitionType,
) where S : Enum<S>, S : Stage, E : Event, E : Enum<E> {
    if (definition.hasConflictingTransitions(transitionType)) {
        error(
            "Stage ${definition.stage} already has transitions defined: ${definition.getExistingTransitions()}. " +
                "Use only one of: stage(), onEvent(), or condition()."
        )
    }
}

private fun <T : Any, S, E> isStageClean(definition: StageDefinition<T, S, E>): Boolean
where S : Enum<S>, S : Stage, E : Event, E : Enum<E> {
    return definition.nextStage == null && definition.eventHandlers.isEmpty() && definition.conditionHandler == null
}

fun <T : Any, S, E> flow(definition: FlowDslScope<T, S, E>.() -> Unit): Flow<T, S, E>
    where S : Enum<S>, S : Stage, E : Event, E : Enum<E> {
    val scope = FlowDslScope<T, S, E>()
    scope.apply(definition)
    return scope.build()
}

fun <T : Any, S> eventlessFlow(definition: FlowDslScope<T, S, NoEvent>.() -> Unit): EventlessFlow<T, S>
    where S : Enum<S>, S : Stage {
    val scope = FlowDslScope<T, S, NoEvent>()
    scope.apply(definition)
    return scope.build()
}
