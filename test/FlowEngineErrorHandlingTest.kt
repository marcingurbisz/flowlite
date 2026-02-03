package io.flowlite.test

import io.flowlite.api.*
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private enum class ErrorFlowStage : Stage { Failing, Done }

private data class ErrorFlowState(
    val stage: ErrorFlowStage,
    val stageStatus: StageStatus = StageStatus.PENDING,
    val attempts: Int = 0,
)

class FlowEngineErrorHandlingTest : BehaviorSpec({

    given("a flow with an action that fails once") {
        val flowId = "error-handling"

        val attempts = AtomicInteger(0)

        fun flakyAction(s: ErrorFlowState): ErrorFlowState {
            val n = attempts.incrementAndGet()
            if (n == 1) error("boom")
            return s.copy(attempts = n)
        }

        val flow = FlowBuilder<ErrorFlowState>()
            .stage(ErrorFlowStage.Failing, ::flakyAction)
            .stage(ErrorFlowStage.Done)
            .end()
            .build()

        val eventStore = InMemoryEventStore()
        val tickScheduler = ManualTickScheduler()
        val persister = InMemoryStatePersister<ErrorFlowState>()

        val engine = FlowEngine(eventStore = eventStore, tickScheduler = tickScheduler).also {
            it.registerFlow(flowId, flow, persister)
        }

        `when`("processing the first tick") {
            val flowInstanceId = engine.startInstance(flowId, ErrorFlowState(stage = ErrorFlowStage.Failing))
            shouldThrow<IllegalStateException> {
                tickScheduler.drain()
            }

            then("it moves the stage to ERROR") {
                engine.getStatus(flowId, flowInstanceId) shouldBe (ErrorFlowStage.Failing to StageStatus.ERROR)
            }

            then("retry enqueues and eventually completes when the action succeeds") {
                engine.retry(flowId, flowInstanceId)
                tickScheduler.drain()
                engine.getStatus(flowId, flowInstanceId) shouldBe (ErrorFlowStage.Done to StageStatus.COMPLETED)
            }
        }
    }
})

private class ManualTickScheduler : TickScheduler {
    private var handler: ((String, UUID) -> Unit)? = null
    private val queue = ArrayDeque<Pair<String, UUID>>()

    override fun setTickHandler(handler: (String, UUID) -> Unit) {
        this.handler = handler
    }

    override fun scheduleTick(flowId: String, flowInstanceId: UUID) {
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

private class InMemoryStatePersister<T : Any> : StatePersister<T> {
    private val data = mutableMapOf<UUID, ProcessData<T>>()

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

    override fun save(processData: ProcessData<T>): ProcessData<T> {
        data[processData.flowInstanceId] = processData
        return processData
    }

    override fun load(flowInstanceId: UUID): ProcessData<T> =
        data[flowInstanceId] ?: error("Process '$flowInstanceId' not found")
}

private class InMemoryEventStore : EventStore {
    private data class Row(val flowId: String, val flowInstanceId: UUID, val event: Event)

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

    override fun delete(eventId: UUID): Boolean = rows.remove(eventId) != null
}
