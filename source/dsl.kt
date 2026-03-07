package io.flowlite

import java.util.UUID
import kotlin.reflect.KFunction
import kotlin.reflect.KFunction1

interface Stage

interface Event

@DslMarker
annotation class FlowLiteDsl

enum class NoEvent : Event

data class ActionContext(
    val flowId: String,
    val flowInstanceId: UUID,
)

typealias StageAction<T> = (ActionContext, T) -> T?

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
    return inferCallableName(predicate, fallback = "condition")
}

internal fun inferActionName(action: Any): String {
    return inferCallableName(action, fallback = "action")
}

private fun inferCallableName(value: Any, fallback: String): String {
    val kFunction = value as? KFunction<*>
    if (kFunction != null) {
        val name = kFunction.name
        if (name.isNotBlank() && name != "<anonymous>") return name
    }

    val asString = value.toString()
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
    return if (isLikelySynthetic) fallback else candidate
}

@FlowLiteDsl
class FlowDslScope<T : Any, S, E>
internal constructor() where S : Enum<S>, S : Stage, E : Event, E : Enum<E> {
    private val steps = mutableListOf<FlowStep<T, S, E>>()

    fun stage(stage: S) {
        addLinearStep(stage = stage)
    }

    fun stage(stage: S, action: KFunction1<T, T?>) {
        addLinearStep(stage = stage, action = { _, state -> action(state) }, actionName = action.name)
    }

    fun stage(stage: S, action: StageAction<T>) {
        addLinearStep(stage = stage, action = action, actionName = inferActionName(action))
    }

    fun stage(stage: S, waitFor: E) {
        addLinearStep(stage = stage, waitFor = waitFor)
    }

    fun timer(stage: S, action: KFunction1<T, T?>) {
        addLinearStep(stage = stage, action = { _, state -> action(state) }, actionName = action.name)
    }

    fun timer(stage: S, action: StageAction<T>) {
        addLinearStep(stage = stage, action = action, actionName = inferActionName(action))
    }

    fun _if(
        predicate: (item: T) -> Boolean,
        description: String = inferConditionDescription(predicate),
        block: FlowDslScope<T, S, E>.() -> Unit,
    ): ElseCapableIfBuilder<T, S, E> {
        val trueScope = FlowDslScope<T, S, E>()
        trueScope.apply(block)
        val step = MutableIfStep(
            predicate = predicate,
            description = description,
            trueSteps = trueScope.steps.toList(),
        )
        steps += step
        return ElseCapableIfBuilder(step)
    }

    internal fun build(): Flow<T, S, E> = FlowCompiler<T, S, E>().build(steps)

    private fun addLinearStep(
        stage: S,
        action: StageAction<T>? = null,
        actionName: String? = null,
        waitFor: E? = null,
    ) {
        require(action == null || waitFor == null) {
            "Stage $stage cannot define both action and waitFor"
        }
        steps += LinearStep(
            stage = stage,
            action = action,
            actionName = actionName,
            waitFor = waitFor,
        )
    }

    @FlowLiteDsl
    class ElseCapableIfBuilder<T : Any, S, E>
    internal constructor(
        private val step: MutableIfStep<T, S, E>,
    ) where S : Enum<S>, S : Stage, E : Event, E : Enum<E> {
        infix fun _else(block: FlowDslScope<T, S, E>.() -> Unit) {
            require(step.falseSteps == null) { "_if(...) allows only one _else { ... } block" }
            val falseScope = FlowDslScope<T, S, E>()
            falseScope.apply(block)
            step.falseSteps = falseScope.steps.toList()
        }
    }
}

typealias EventlessFlow<T, S> = Flow<T, S, NoEvent>

data class StageDefinition<T : Any, S : Stage, E : Event>(
    val stage: S,
    val action: StageAction<T>? = null,
    val actionName: String? = null,
) {
    val eventHandlers = mutableMapOf<E, EventHandler<T, S, E>>()
    var conditionHandler: ConditionHandler<T, S>? = null
    var nextStage: S? = null
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

internal sealed interface FlowStep<T : Any, S, E>
where S : Enum<S>, S : Stage, E : Event, E : Enum<E>

internal data class LinearStep<T : Any, S, E>(
    val stage: S,
    val action: StageAction<T>?,
    val actionName: String?,
    val waitFor: E?,
) : FlowStep<T, S, E>
where S : Enum<S>, S : Stage, E : Event, E : Enum<E>

internal data class MutableIfStep<T : Any, S, E>(
    val predicate: (item: T) -> Boolean,
    val description: String,
    val trueSteps: List<FlowStep<T, S, E>>,
    var falseSteps: List<FlowStep<T, S, E>>? = null,
) : FlowStep<T, S, E>
where S : Enum<S>, S : Stage, E : Event, E : Enum<E>

internal sealed interface FlowEntry<T : Any, S : Stage>

internal data class StageEntry<T : Any, S : Stage>(
    val stage: S,
) : FlowEntry<T, S>

internal data class ConditionEntry<T : Any, S : Stage>(
    val condition: ConditionHandler<T, S>,
) : FlowEntry<T, S>

internal class FlowCompiler<T : Any, S, E>
where S : Enum<S>, S : Stage, E : Event, E : Enum<E> {
    private val stages = linkedMapOf<S, StageDefinition<T, S, E>>()

    fun build(steps: List<FlowStep<T, S, E>>): Flow<T, S, E> {
        val entry = compileSequence(steps, continuation = null)
        val initialStage = (entry as? StageEntry<T, S>)?.stage
        val initialCondition = (entry as? ConditionEntry<T, S>)?.condition

        validateDefinitions(stages, initialStage, initialCondition)
        return Flow(
            initialStage = initialStage,
            initialCondition = initialCondition,
            stages = stages.toMap(),
        )
    }

    private fun compileSequence(
        steps: List<FlowStep<T, S, E>>,
        continuation: FlowEntry<T, S>?,
    ): FlowEntry<T, S>? {
        var current = continuation
        for (step in steps.asReversed()) {
            current = compileStep(step, current)
        }
        return current
    }

    private fun compileStep(
        step: FlowStep<T, S, E>,
        continuation: FlowEntry<T, S>?,
    ): FlowEntry<T, S> {
        return when (step) {
            is LinearStep -> compileLinear(step, continuation)
            is MutableIfStep -> compileIf(step, continuation)
        }
    }

    private fun compileLinear(
        step: LinearStep<T, S, E>,
        continuation: FlowEntry<T, S>?,
    ): FlowEntry<T, S> {
        val definition = defineStage(step.stage, step.action, step.actionName)

        if (step.waitFor != null) {
            val resolved = requireNotNull(continuation) {
                "Stage ${step.stage} waits for ${step.waitFor} but nothing follows it"
            }
            definition.eventHandlers[step.waitFor] = toEventHandler(step.waitFor, resolved)
        } else {
            attachContinuation(definition, continuation)
        }

        return StageEntry(step.stage)
    }

    private fun compileIf(
        step: MutableIfStep<T, S, E>,
        continuation: FlowEntry<T, S>?,
    ): FlowEntry<T, S> {
        val trueEntry = compileSequence(step.trueSteps, continuation) ?: continuation
        val falseEntry = compileSequence(step.falseSteps.orEmpty(), continuation) ?: continuation

        val resolvedTrue = requireNotNull(trueEntry) {
            "_if(...) true branch must resolve to a stage or condition"
        }
        val resolvedFalse = requireNotNull(falseEntry) {
            "_if(...) false branch must resolve to a stage or condition"
        }

        return ConditionEntry(
            ConditionHandler(
                predicate = step.predicate,
                trueStage = (resolvedTrue as? StageEntry<T, S>)?.stage,
                trueCondition = (resolvedTrue as? ConditionEntry<T, S>)?.condition,
                falseStage = (resolvedFalse as? StageEntry<T, S>)?.stage,
                falseCondition = (resolvedFalse as? ConditionEntry<T, S>)?.condition,
                description = step.description,
            ),
        )
    }

    private fun defineStage(
        stage: S,
        action: StageAction<T>?,
        actionName: String?,
    ): StageDefinition<T, S, E> {
        if (stage in stages) {
            error("Stage $stage already defined - each stage should be defined only once")
        }

        return StageDefinition<T, S, E>(stage, action, actionName).also { definition ->
            stages[stage] = definition
        }
    }

    private fun attachContinuation(
        definition: StageDefinition<T, S, E>,
        continuation: FlowEntry<T, S>?,
    ) {
        when (continuation) {
            null -> return
            is StageEntry -> definition.nextStage = continuation.stage
            is ConditionEntry -> definition.conditionHandler = continuation.condition
        }
    }

    private fun toEventHandler(
        event: E,
        continuation: FlowEntry<T, S>,
    ): EventHandler<T, S, E> {
        return when (continuation) {
            is StageEntry -> EventHandler(event = event, targetStage = continuation.stage, targetCondition = null)
            is ConditionEntry -> EventHandler(event = event, targetStage = null, targetCondition = continuation.condition)
        }
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
