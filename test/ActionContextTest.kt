package io.flowlite.test

import io.flowlite.ActionContext
import io.flowlite.Event
import io.flowlite.Engine
import io.flowlite.InstanceData
import io.flowlite.ScheduledTick
import io.flowlite.Stage
import io.flowlite.StageStatus
import io.flowlite.StatePersister
import io.flowlite.StoredEvent
import io.flowlite.TickScheduler
import io.flowlite.eventlessFlow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

private enum class ActionContextStage : Stage {
    Start,
    Done,
}

private data class ActionContextState(
    val seenFlowInstanceId: UUID? = null,
)

private class ContextAwareActions {
    fun captureFlowInstanceId(context: ActionContext, state: ActionContextState): ActionContextState {
        return state.copy(seenFlowInstanceId = context.flowInstanceId)
    }
}

class ActionContextTest : BehaviorSpec({
    given("a stage action defined with ActionContext receiver") {
        val flowId = "context-aware-action"
        val actions = ContextAwareActions()

        val flow = eventlessFlow<ActionContextState, ActionContextStage> {
            stage(ActionContextStage.Start, actions::captureFlowInstanceId)
            stage(ActionContextStage.Done)
        }

        val eventStore = ContextInMemoryEventStore()
        val tickScheduler = ContextManualTickScheduler()
        val persister = ContextInMemoryStatePersister<ActionContextState>()
        val engine = Engine(eventStore, tickScheduler).also {
            it.registerFlow(flowId, flow, persister)
        }

        `when`("processing ticks") {
            val flowInstanceId = engine.startInstance(flowId, ActionContextState())
            tickScheduler.drain()

            then("it exposes the active flow instance id inside action context") {
                val saved = persister.load(flowInstanceId)
                saved.stage shouldBe ActionContextStage.Done
                saved.stageStatus shouldBe StageStatus.Completed
                saved.state.seenFlowInstanceId shouldBe flowInstanceId
            }
        }
    }
})

private class ContextManualTickScheduler : TickScheduler {
    private var handler: ((ScheduledTick) -> Unit)? = null
    private val queue = ArrayDeque<ScheduledTick>()

    override fun setTickHandler(handler: (ScheduledTick) -> Unit) {
        this.handler = handler
    }

    override fun scheduleTick(
        flowId: String,
        flowInstanceId: UUID,
        notBefore: Instant,
        targetStage: String?,
        timerToken: UUID?,
    ) {
        queue.addLast(ScheduledTick(flowId, flowInstanceId, notBefore, targetStage, timerToken))
    }

    fun drain(limit: Int = 1000) {
        val h = handler ?: error("Tick handler not set")
        var steps = 0
        while (queue.isNotEmpty()) {
            if (steps++ > limit) error("Exceeded tick drain limit ($limit)")
            h(queue.removeFirst())
        }
    }
}

private class ContextInMemoryStatePersister<T : Any> : StatePersister<T> {
    private val data = mutableMapOf<UUID, InstanceData<T>>()

    override fun tryTransitionStageStatus(
        flowInstanceId: UUID,
        expectedStage: Stage,
        expectedStageStatus: StageStatus,
        newStageStatus: StageStatus,
    ): Boolean {
        val current = data[flowInstanceId] ?: return false
        if (current.stage != expectedStage) return false
        if (current.stageStatus != expectedStageStatus) return false
        data[flowInstanceId] = current.copy(stageStatus = newStageStatus)
        return true
    }

    override fun save(instanceData: InstanceData<T>): InstanceData<T> {
        data[instanceData.flowInstanceId] = instanceData
        return instanceData
    }

    override fun load(flowInstanceId: UUID) =
        data[flowInstanceId] ?: error("Flow instance '$flowInstanceId' not found")
}

private class ContextInMemoryEventStore : io.flowlite.EventStore {
    private data class Row(
        val flowId: String,
        val flowInstanceId: UUID,
        val event: Event,
    )

    private val rows = linkedMapOf<UUID, Row>()

    override fun append(flowId: String, flowInstanceId: UUID, event: Event) {
        rows[UUID.randomUUID()] = Row(flowId, flowInstanceId, event)
    }

    override fun peek(flowId: String, flowInstanceId: UUID, candidates: Collection<Event>): StoredEvent? {
        if (candidates.isEmpty()) return null
        val candidateSet = candidates.toSet()
        val match = rows.entries.firstOrNull { (_, row) ->
            row.flowId == flowId && row.flowInstanceId == flowInstanceId && row.event in candidateSet
        } ?: return null
        return StoredEvent(id = match.key, event = match.value.event)
    }

    override fun delete(eventId: UUID) = rows.remove(eventId) != null
}