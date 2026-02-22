package io.flowlite

@FlowLiteDsl
class FlowDslScope<T : Any, S : Stage, E : Event>(
    private val flowBuilder: FlowBuilder<T, S, E>,
) {
    fun stage(stage: S, action: ((item: T) -> T?)? = null): StageDslScope<T, S, E> {
        val stageBuilder = flowBuilder.stage(stage, action)
        return StageDslScope(flowBuilder, stageBuilder)
    }

    fun stage(stage: S, action: ((item: T) -> T?)? = null, block: StageDslScope<T, S, E>.() -> Unit): StageDslScope<T, S, E> {
        val stageScope = stage(stage, action)
        stageScope.block()
        return stageScope
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
        return this
    }

    fun joinTo(targetStage: S): FlowDslScope<T, S, E> {
        flowBuilder.join(targetStage)
        return this
    }

    fun build(): Flow<T, S, E> = flowBuilder.build()
}

@FlowLiteDsl
class StageDslScope<T : Any, S : Stage, E : Event>(
    private val flowBuilder: FlowBuilder<T, S, E>,
    private var currentStageBuilder: StageBuilder<T, S, E>,
) {
    fun stage(stage: S, action: ((item: T) -> T?)? = null): StageDslScope<T, S, E> {
        currentStageBuilder = currentStageBuilder.stage(stage, action)
        return this
    }

    fun stage(stage: S, action: ((item: T) -> T?)? = null, block: StageDslScope<T, S, E>.() -> Unit): StageDslScope<T, S, E> {
        stage(stage, action)
        block()
        return this
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
}

@FlowLiteDsl
class EventTransitionDslScope<T : Any, S : Stage, E : Event>(
    private val eventBuilder: EventBuilder<T, S, E>,
) {
    private var currentStageBuilder: StageBuilder<T, S, E>? = null

    fun stage(stage: S, action: ((item: T) -> T?)? = null): EventTransitionDslScope<T, S, E> {
        currentStageBuilder =
            if (currentStageBuilder == null) eventBuilder.stage(stage, action)
            else currentStageBuilder!!.stage(stage, action)
        return this
    }

    fun stage(stage: S, action: ((item: T) -> T?)? = null, block: StageDslScope<T, S, E>.() -> Unit): EventTransitionDslScope<T, S, E> {
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
