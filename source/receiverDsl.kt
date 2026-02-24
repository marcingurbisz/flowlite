package io.flowlite

import kotlin.reflect.KFunction1

@FlowLiteDsl
class FlowDslScope<T : Any, S : Stage, E : Event>(
    private val flowBuilder: FlowBuilder<T, S, E>,
) {
    private var lastTopLevelStageBuilder: StageBuilder<T, S, E>? = null

    fun stage(stage: S): StageDslScope<T, S, E> {
        val stageBuilder = if (canAutoChainFromLast()) {
            requireNotNull(lastTopLevelStageBuilder).stage(stage)
        } else {
            flowBuilder.stage(stage)
        }
        lastTopLevelStageBuilder = stageBuilder
        return StageDslScope(flowBuilder, stageBuilder)
    }

    fun stage(stage: S, action: KFunction1<T, T?>): StageDslScope<T, S, E> {
        val stageBuilder = if (canAutoChainFromLast()) {
            requireNotNull(lastTopLevelStageBuilder).stage(stage, action)
        } else {
            flowBuilder.stage(stage, action)
        }
        lastTopLevelStageBuilder = stageBuilder
        return StageDslScope(flowBuilder, stageBuilder)
    }

    @JvmName("stageWithBlock")
    fun stage(stage: S, block: StageDslScope<T, S, E>.() -> Unit): StageDslScope<T, S, E> {
        val scoped = stage(stage).apply(block)
        lastTopLevelStageBuilder = scoped.currentStageBuilder()
        return scoped
    }

    fun stage(stage: S, action: KFunction1<T, T?>, block: StageDslScope<T, S, E>.() -> Unit): StageDslScope<T, S, E> {
        val scoped = stage(stage, action).apply(block)
        lastTopLevelStageBuilder = scoped.currentStageBuilder()
        return scoped
    }

    fun condition(
        predicate: (item: T) -> Boolean,
        onTrue: FlowDslScope<T, S, E>.() -> Unit,
        onFalse: FlowDslScope<T, S, E>.() -> Unit,
        description: String = inferConditionDescription(predicate),
    ): FlowDslScope<T, S, E> {
        flowBuilder.condition(
            predicate = predicate,
            description = description,
            onTrue = {
                FlowDslScope(this).apply(onTrue)
            },
            onFalse = {
                FlowDslScope(this).apply(onFalse)
            },
        )
        lastTopLevelStageBuilder = null
        return this
    }

    fun joinTo(targetStage: S): FlowDslScope<T, S, E> {
        flowBuilder.join(targetStage)
        lastTopLevelStageBuilder = null
        return this
    }

    fun build(): Flow<T, S, E> = flowBuilder.build()

    private fun canAutoChainFromLast(): Boolean {
        val previous = lastTopLevelStageBuilder ?: return false
        val definition = previous.stageDefinition
        return definition.nextStage == null &&
            definition.eventHandlers.isEmpty() &&
            definition.conditionHandler == null
    }
}

@FlowLiteDsl
class StageDslScope<T : Any, S : Stage, E : Event>(
    private val flowBuilder: FlowBuilder<T, S, E>,
    private var currentStageBuilder: StageBuilder<T, S, E>,
) {
    fun stage(stage: S): StageDslScope<T, S, E> {
        currentStageBuilder = currentStageBuilder.stage(stage)
        return this
    }

    fun stage(stage: S, action: KFunction1<T, T?>): StageDslScope<T, S, E> {
        currentStageBuilder = currentStageBuilder.stage(stage, action)
        return this
    }

    @JvmName("stageWithBlock")
    fun stage(stage: S, block: StageDslScope<T, S, E>.() -> Unit): StageDslScope<T, S, E> {
        return stage(stage).apply(block)
    }

    fun stage(stage: S, action: KFunction1<T, T?>, block: StageDslScope<T, S, E>.() -> Unit): StageDslScope<T, S, E> {
        return stage(stage, action).apply(block)
    }

    fun onEvent(event: E, block: EventTransitionDslScope<T, S, E>.() -> Unit): StageDslScope<T, S, E> {
        val eventScope = EventTransitionDslScope(currentStageBuilder.waitFor(event))
        eventScope.block()
        return this
    }

    fun condition(
        predicate: (item: T) -> Boolean,
        onTrue: FlowDslScope<T, S, E>.() -> Unit,
        onFalse: FlowDslScope<T, S, E>.() -> Unit,
        description: String = inferConditionDescription(predicate),
    ): FlowDslScope<T, S, E> {
        currentStageBuilder.condition(
            predicate = predicate,
            description = description,
            onTrue = {
                FlowDslScope(this).apply(onTrue)
            },
            onFalse = {
                FlowDslScope(this).apply(onFalse)
            },
        )
        return FlowDslScope(flowBuilder)
    }

    fun joinTo(targetStage: S): FlowDslScope<T, S, E> {
        currentStageBuilder.join(targetStage)
        return FlowDslScope(flowBuilder)
    }

    fun end(): FlowDslScope<T, S, E> {
        currentStageBuilder.end()
        return FlowDslScope(flowBuilder)
    }

    fun build(): Flow<T, S, E> = flowBuilder.build()

    internal fun currentStageBuilder(): StageBuilder<T, S, E> = currentStageBuilder
}

@FlowLiteDsl
class EventTransitionDslScope<T : Any, S : Stage, E : Event>(
    private val eventBuilder: EventBuilder<T, S, E>,
) {
    private var currentStageBuilder: StageBuilder<T, S, E>? = null

    fun stage(stage: S): EventTransitionDslScope<T, S, E> {
        currentStageBuilder =
            if (currentStageBuilder == null) eventBuilder.stage(stage)
            else currentStageBuilder!!.stage(stage)
        return this
    }

    fun stage(stage: S, action: KFunction1<T, T?>): EventTransitionDslScope<T, S, E> {
        currentStageBuilder =
            if (currentStageBuilder == null) eventBuilder.stage(stage, action)
            else currentStageBuilder!!.stage(stage, action)
        return this
    }

    @JvmName("stageWithBlock")
    fun stage(stage: S, block: StageDslScope<T, S, E>.() -> Unit): EventTransitionDslScope<T, S, E> {
        return stage(stage).apply {
            val stageBuilder = requireNotNull(currentStageBuilder)
            StageDslScope(stageBuilder.flowBuilder, stageBuilder).apply(block)
        }
    }

    fun stage(stage: S, action: KFunction1<T, T?>, block: StageDslScope<T, S, E>.() -> Unit): EventTransitionDslScope<T, S, E> {
        stage(stage, action)
        val stageBuilder = requireNotNull(currentStageBuilder)
        StageDslScope(stageBuilder.flowBuilder, stageBuilder).apply(block)
        return this
    }

    fun joinTo(targetStage: S) {
        val current = currentStageBuilder
        if (current == null) {
            eventBuilder.join(targetStage)
        } else {
            current.join(targetStage)
        }
    }

    fun condition(
        predicate: (item: T) -> Boolean,
        onTrue: FlowDslScope<T, S, E>.() -> Unit,
        onFalse: FlowDslScope<T, S, E>.() -> Unit,
        description: String = inferConditionDescription(predicate),
    ) {
        val current = currentStageBuilder
        if (current == null) {
            eventBuilder.condition(
                predicate = predicate,
                description = description,
                onTrue = {
                    FlowDslScope(this).apply(onTrue)
                },
                onFalse = {
                    FlowDslScope(this).apply(onFalse)
                },
            )
        } else {
            current.condition(
                predicate = predicate,
                description = description,
                onTrue = {
                    FlowDslScope(this).apply(onTrue)
                },
                onFalse = {
                    FlowDslScope(this).apply(onFalse)
                },
            )
        }
    }
}

fun <T : Any, S : Stage, E : Event> flow(definition: FlowDslScope<T, S, E>.() -> Unit): Flow<T, S, E> {
    val builder = FlowBuilder<T, S, E>()
    FlowDslScope(builder).apply(definition)
    return builder.build()
}

fun <T : Any, S : Stage> eventlessFlow(definition: FlowDslScope<T, S, NoEvent>.() -> Unit): EventlessFlow<T, S> {
    val builder = EventlessFlowBuilder<T, S>()
    FlowDslScope(builder).apply(definition)
    return builder.build()
}
