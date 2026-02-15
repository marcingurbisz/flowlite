package io.flowlite.test

import io.flowlite.Event
import io.flowlite.EventlessFlowBuilder
import io.flowlite.FlowBuilder
import io.flowlite.FlowEngine
import io.flowlite.HistoryEntry
import io.flowlite.HistoryEntryType
import io.flowlite.HistoryStore
import io.flowlite.Stage
import io.flowlite.StageStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

private enum class HistAutoStage : Stage { Start, Done }
private data class HistAutoState(val x: Int = 0)

private enum class HistWaitStage : Stage { Wait, Done }
private enum class HistWaitEvent : Event { Go }
private data class HistWaitState(val x: Int = 0)

private enum class HistThrowStage : Stage { Start, Terminal }
private data class HistThrowState(val x: Int = 0)

class FlowEngineHistoryTest : BehaviorSpec({

    given("history store integration") {
        `when`("a flow auto-transitions and completes") {
            val flow = EventlessFlowBuilder<HistAutoState, HistAutoStage>()
                .stage(HistAutoStage.Start)
                .stage(HistAutoStage.Done)
                .end()
                .build()

            val eventStore = HistoryInMemoryEventStore()
            val tickScheduler = HistoryManualTickScheduler()
            val persister = HistoryInMemoryStatePersister<HistAutoState>()
            val history = CapturingHistoryStore()

            val engine = FlowEngine(eventStore, tickScheduler, history).also {
                it.registerFlow("hist-auto", flow, persister)
            }

            val id = engine.startInstance("hist-auto", HistAutoState())
            tickScheduler.drain()

            then("it emits basic timeline entries") {
                engine.getStatus("hist-auto", id) shouldBe (HistAutoStage.Done to StageStatus.Completed)

                val types = history.entries.map { it.type }
                types.shouldContainAll(
                    HistoryEntryType.InstanceStarted,
                    HistoryEntryType.StatusChanged,
                    HistoryEntryType.StageChanged,
                )

                history.entries.any {
                    it.type == HistoryEntryType.StageChanged &&
                        it.fromStage == HistAutoStage.Start.name &&
                        it.toStage == HistAutoStage.Done.name
                } shouldBe true
                history.entries.any { it.type == HistoryEntryType.StatusChanged && it.toStatus == StageStatus.Completed } shouldBe true
            }
        }

        `when`("a flow waits for an event and none is present") {
            val flow = FlowBuilder<HistWaitState, HistWaitStage, HistWaitEvent>()
                .stage(HistWaitStage.Wait)
                .waitFor(HistWaitEvent.Go)
                .stage(HistWaitStage.Done)
                .end()
                .build()

            val eventStore = HistoryInMemoryEventStore()
            val tickScheduler = HistoryManualTickScheduler()
            val persister = HistoryInMemoryStatePersister<HistWaitState>()
            val history = CapturingHistoryStore()

            val engine = FlowEngine(eventStore, tickScheduler, history).also {
                it.registerFlow("hist-wait", flow, persister)
            }

            val id = engine.startInstance("hist-wait", HistWaitState())
            tickScheduler.drain()

            then("it records releasing RUNNING back to PENDING") {
                engine.getStatus("hist-wait", id) shouldBe (HistWaitStage.Wait to StageStatus.Pending)
                history.entries.any {
                    it.type == HistoryEntryType.StatusChanged &&
                        it.fromStatus == StageStatus.Running &&
                        it.toStatus == StageStatus.Pending
                } shouldBe true
            }
        }

        `when`("the history store throws") {
            val flow = EventlessFlowBuilder<HistThrowState, HistThrowStage>()
                .stage(HistThrowStage.Start)
                .stage(HistThrowStage.Terminal)
                .end()
                .build()

            val eventStore = HistoryInMemoryEventStore()
            val tickScheduler = HistoryManualTickScheduler()
            val persister = HistoryInMemoryStatePersister<HistThrowState>()

            val throwingHistory = object : HistoryStore {
                override fun append(entry: HistoryEntry) {
                    error("history down")
                }
            }

            val engine = FlowEngine(eventStore, tickScheduler, throwingHistory).also {
                it.registerFlow("hist-throw", flow, persister)
            }

            val id = engine.startInstance("hist-throw", HistThrowState())
            tickScheduler.drain()

            then("engine still completes") {
                engine.getStatus("hist-throw", id) shouldBe (HistThrowStage.Terminal to StageStatus.Completed)
            }
        }
    }
})

private class CapturingHistoryStore : HistoryStore {
    val entries = mutableListOf<HistoryEntry>()

    override fun append(entry: HistoryEntry) {
        entries += entry
    }
}

private class HistoryManualTickScheduler : io.flowlite.TickScheduler {
    private var handler: ((String, java.util.UUID) -> Unit)? = null
    private val queue = ArrayDeque<Pair<String, java.util.UUID>>()

    override fun setTickHandler(handler: (String, java.util.UUID) -> Unit) {
        this.handler = handler
    }

    override fun scheduleTick(flowId: String, flowInstanceId: java.util.UUID) {
        queue.addLast(flowId to flowInstanceId)
    }

    fun drain(limit: Int = 1000) {
        val h = handler ?: error("Tick handler not set")
        var steps = 0
        while (queue.isNotEmpty()) {
            if (steps++ > limit) error("Exceeded tick drain limit ($limit)")
            val (flowId, id) = queue.removeFirst()
            h(flowId, id)
        }
    }
}

private class HistoryInMemoryStatePersister<T : Any> : io.flowlite.StatePersister<T> {
    private val data = mutableMapOf<java.util.UUID, io.flowlite.InstanceData<T>>()

    override fun tryTransitionStageStatus(
        flowInstanceId: java.util.UUID,
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

    override fun save(instanceData: io.flowlite.InstanceData<T>): io.flowlite.InstanceData<T> {
        data[instanceData.flowInstanceId] = instanceData
        return instanceData
    }

    override fun load(flowInstanceId: java.util.UUID): io.flowlite.InstanceData<T> =
        data[flowInstanceId] ?: error("Flow instance '$flowInstanceId' not found")
}

private class HistoryInMemoryEventStore : io.flowlite.EventStore {
    private data class Row(val flowId: String, val flowInstanceId: java.util.UUID, val event: Event)

    private val rows = linkedMapOf<java.util.UUID, Row>()

    override fun append(flowId: String, flowInstanceId: java.util.UUID, event: Event) {
        rows[java.util.UUID.randomUUID()] = Row(flowId, flowInstanceId, event)
    }

    override fun peek(
        flowId: String,
        flowInstanceId: java.util.UUID,
        candidates: Collection<Event>,
    ): io.flowlite.StoredEvent? {
        if (candidates.isEmpty()) return null
        val candidateSet = candidates.toSet()
        val match = rows.entries.firstOrNull { (_, row) ->
            row.flowId == flowId && row.flowInstanceId == flowInstanceId && row.event in candidateSet
        } ?: return null
        return io.flowlite.StoredEvent(id = match.key, event = match.value.event)
    }

    override fun delete(eventId: java.util.UUID): Boolean = rows.remove(eventId) != null
}
