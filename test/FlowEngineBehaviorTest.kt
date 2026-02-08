package io.flowlite.test

import io.flowlite.Event
import io.flowlite.Flow
import io.flowlite.FlowBuilder
import io.flowlite.FlowEngine
import io.flowlite.InstanceData
import io.flowlite.Stage
import io.flowlite.StageStatus
import io.flowlite.StatePersister
import io.flowlite.StoredEvent
import io.flowlite.TickScheduler
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

private enum class EngineStage : Stage {
    Start,
    Wait,
    Conditional,
    TrueStage,
    FalseStage,
    Done,
    Terminal,
    Undefined,
}

private enum class EngineEvent : Event { Go }
private data class EngineState(val flag: Boolean)

class FlowEngineBehaviorTest : BehaviorSpec({
    given("flow engine tick handling") {
        `when`("the flow consists of a terminal stage without action") {
            val eventStore = InMemoryEventStore()
            val tickScheduler = ManualTickScheduler()
            val persister = InMemoryStatePersister<EngineState>()
            val engine = FlowEngine(eventStore, tickScheduler).also {
                it.registerFlow("terminal-no-action", terminalNoActionFlow(), persister)
            }

            val id = engine.startInstance("terminal-no-action", EngineState(flag = false))
            tickScheduler.drain()

            then("it marks the instance as COMPLETED") {
                engine.getStatus("terminal-no-action", id) shouldBe (EngineStage.Terminal to StageStatus.COMPLETED)
            }
        }

        `when`("a stage has an automatic transition") {
            val eventStore = InMemoryEventStore()
            val tickScheduler = ManualTickScheduler()
            val persister = InMemoryStatePersister<EngineState>()
            val engine = FlowEngine(eventStore, tickScheduler).also {
                it.registerFlow("auto-transition", automaticTransitionFlow(), persister)
            }

            val id = engine.startInstance("auto-transition", EngineState(flag = false))
            tickScheduler.drain()

            then("it follows nextStage and completes") {
                engine.getStatus("auto-transition", id) shouldBe (EngineStage.Done to StageStatus.COMPLETED)
            }
        }

        `when`("a stage has a condition-only transition (no action)") {
            val eventStore = InMemoryEventStore()
            val tickScheduler = ManualTickScheduler()
            val persister = InMemoryStatePersister<EngineState>()
            val engine = FlowEngine(eventStore, tickScheduler).also {
                it.registerFlow("condition-only", conditionOnlyFlow(), persister)
            }

            val id = engine.startInstance("condition-only", EngineState(flag = true))
            tickScheduler.drain()

            then("it evaluates the condition and completes") {
                engine.getStatus("condition-only", id) shouldBe (EngineStage.TrueStage to StageStatus.COMPLETED)
            }
        }

        `when`("tick is delivered for RUNNING instance") {
            val eventStore = InMemoryEventStore()
            val tickScheduler = ManualTickScheduler()
            val persister = InMemoryStatePersister<EngineState>()
            val engine = FlowEngine(eventStore, tickScheduler).also {
                it.registerFlow("running-flow", terminalFlow(), persister)
            }

            val id = UUID.randomUUID()
            persister.save(
                InstanceData(
                    flowInstanceId = id,
                    state = EngineState(flag = false),
                    stage = EngineStage.Start,
                    stageStatus = StageStatus.RUNNING,
                ),
            )

            tickScheduler.scheduleTick("running-flow", id)
            tickScheduler.drain()

            then("it does not change status") {
                engine.getStatus("running-flow", id) shouldBe (EngineStage.Start to StageStatus.RUNNING)
            }
        }

        `when`("the claim fails due to concurrent ownership") {
            val eventStore = InMemoryEventStore()
            val tickScheduler = ManualTickScheduler()
            val persister = ClaimRejectingPersister(InMemoryStatePersister<EngineState>())
            val engine = FlowEngine(eventStore, tickScheduler).also {
                it.registerFlow("claim-flow", terminalFlow(), persister)
            }

            val id = UUID.randomUUID()
            persister.save(
                InstanceData(
                    flowInstanceId = id,
                    state = EngineState(flag = false),
                    stage = EngineStage.Start,
                    stageStatus = StageStatus.PENDING,
                ),
            )

            tickScheduler.scheduleTick("claim-flow", id)
            tickScheduler.drain()

            then("it leaves the instance untouched") {
                engine.getStatus("claim-flow", id) shouldBe (EngineStage.Start to StageStatus.PENDING)
            }
        }

        `when`("an event triggers a conditional transition") {
            val eventStore = InMemoryEventStore()
            val tickScheduler = ManualTickScheduler()
            val persister = InMemoryStatePersister<EngineState>()
            val engine = FlowEngine(eventStore, tickScheduler).also {
                it.registerFlow("event-condition-flow", eventConditionFlow(), persister)
            }

            val id = engine.startInstance("event-condition-flow", EngineState(flag = true))
            eventStore.append("event-condition-flow", id, EngineEvent.Go)
            tickScheduler.drain()

            then("it resolves the condition and completes") {
                engine.getStatus("event-condition-flow", id) shouldBe (EngineStage.TrueStage to StageStatus.COMPLETED)
            }
        }

        `when`("no matching event is present") {
            val eventStore = InMemoryEventStore()
            val tickScheduler = ManualTickScheduler()
            val persister = InMemoryStatePersister<EngineState>()
            val engine = FlowEngine(eventStore, tickScheduler).also {
                it.registerFlow("wait-flow", waitingFlow(), persister)
            }

            val id = engine.startInstance("wait-flow", EngineState(flag = false))
            tickScheduler.drain()

            then("it releases the RUNNING claim and stays pending") {
                engine.getStatus("wait-flow", id) shouldBe (EngineStage.Wait to StageStatus.PENDING)
            }
        }

        `when`("an event joins to a stage that has a condition-only transition") {
            val eventStore = InMemoryEventStore()
            val tickScheduler = ManualTickScheduler()
            val persister = InMemoryStatePersister<EngineState>()
            val engine = FlowEngine(eventStore, tickScheduler).also {
                it.registerFlow("event-join-condition", eventJoinToConditionOnlyFlow(), persister)
            }

            val id = engine.startInstance("event-join-condition", EngineState(flag = false))
            engine.sendEvent("event-join-condition", id, EngineEvent.Go)
            tickScheduler.drain()

            then("it consumes the event, evaluates the condition, and completes") {
                engine.getStatus("event-join-condition", id) shouldBe (EngineStage.FalseStage to StageStatus.COMPLETED)
            }
        }

        `when`("processTickLoop encounters an undefined stage") {
            val eventStore = InMemoryEventStore()
            val tickScheduler = ManualTickScheduler()
            val persister = InMemoryStatePersister<EngineState>()
            val engine = FlowEngine(eventStore, tickScheduler).also {
                it.registerFlow("undefined-stage", terminalNoActionFlow(), persister)
            }

            val id = UUID.randomUUID()
            persister.save(
                InstanceData(
                    flowInstanceId = id,
                    state = EngineState(flag = false),
                    stage = EngineStage.Undefined,
                    stageStatus = StageStatus.PENDING,
                ),
            )

            shouldThrow<IllegalStateException> {
                tickScheduler.scheduleTick("undefined-stage", id)
                tickScheduler.drain()
            }

            then("it marks the instance as ERROR") {
                engine.getStatus("undefined-stage", id) shouldBe (EngineStage.Undefined to StageStatus.ERROR)
            }
        }

        `when`("retry is called when status is not ERROR") {
            val eventStore = InMemoryEventStore()
            val tickScheduler = ManualTickScheduler()
            val persister = InMemoryStatePersister<EngineState>()
            val engine = FlowEngine(eventStore, tickScheduler).also {
                it.registerFlow("retry-not-error", terminalNoActionFlow(), persister)
            }

            val id = engine.startInstance("retry-not-error", EngineState(flag = false))

            then("it throws") {
                shouldThrow<IllegalStateException> {
                    engine.retry("retry-not-error", id)
                }
            }
        }

        `when`("calling sendEvent and startInstance for an unregistered flow") {
            val eventStore = InMemoryEventStore()
            val tickScheduler = ManualTickScheduler()
            val engine = FlowEngine(eventStore, tickScheduler)
            val id = UUID.randomUUID()

            then("they throw") {
                shouldThrow<IllegalArgumentException> {
                    engine.sendEvent("missing-flow", id, EngineEvent.Go)
                }
                shouldThrow<IllegalArgumentException> {
                    engine.startInstance("missing-flow", EngineState(flag = false))
                }
            }
        }

        `when`("calling sendEvent and startInstance when persister is missing for a flowId") {
            val flowId = "missing-persister"
            val eventStore = InMemoryEventStore()
            val tickScheduler = ManualTickScheduler()
            val persister = InMemoryStatePersister<EngineState>()
            val engine = FlowEngine(eventStore, tickScheduler).also {
                it.registerFlow(flowId, terminalNoActionFlow(), persister)
            }

            removePersisterRegistration(engine, flowId)
            val id = UUID.randomUUID()

            then("they throw") {
                shouldThrow<IllegalArgumentException> {
                    engine.sendEvent(flowId, id, EngineEvent.Go)
                }
                shouldThrow<IllegalArgumentException> {
                    engine.startInstance(flowId, EngineState(flag = false))
                }
            }
        }

        `when`("getting status for an unregistered flow") {
            val eventStore = InMemoryEventStore()
            val tickScheduler = ManualTickScheduler()
            val engine = FlowEngine(eventStore, tickScheduler)
            val id = UUID.randomUUID()

            then("it throws") {
                shouldThrow<IllegalArgumentException> {
                    engine.getStatus("missing-flow", id)
                }
            }
        }
    }
}) {
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

        override fun load(flowInstanceId: UUID): InstanceData<T> =
            data[flowInstanceId] ?: error("Flow instance '$flowInstanceId' not found")
    }

    private class ClaimRejectingPersister<T : Any>(
        private val delegate: InMemoryStatePersister<T>,
    ) : StatePersister<T> by delegate {
        override fun tryTransitionStageStatus(
            flowInstanceId: UUID,
            expectedStage: Stage,
            expectedStageStatus: StageStatus,
            newStageStatus: StageStatus,
        ): Boolean = false
    }

    private class InMemoryEventStore : io.flowlite.EventStore {
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

    private companion object {
        fun terminalFlow(): Flow<EngineState> = FlowBuilder<EngineState>()
            .stage(EngineStage.Start)
            .end()
            .build()

        fun eventConditionFlow(): Flow<EngineState> = FlowBuilder<EngineState>()
            .stage(EngineStage.Wait)
            .waitFor(EngineEvent.Go)
            .condition(
                predicate = { it.flag },
                description = "flag",
                onTrue = { stage(EngineStage.TrueStage) },
                onFalse = { stage(EngineStage.FalseStage) },
            )
            .build()

        fun waitingFlow(): Flow<EngineState> = FlowBuilder<EngineState>()
            .stage(EngineStage.Wait)
            .waitFor(EngineEvent.Go)
            .stage(EngineStage.Done)
            .end()
            .build()

        fun terminalNoActionFlow(): Flow<EngineState> = FlowBuilder<EngineState>()
            .stage(EngineStage.Terminal)
            .end()
            .build()

        fun automaticTransitionFlow(): Flow<EngineState> = FlowBuilder<EngineState>()
            .stage(EngineStage.Start)
            .stage(EngineStage.Done)
            .end()
            .build()

        fun conditionOnlyFlow(): Flow<EngineState> = FlowBuilder<EngineState>()
            .stage(EngineStage.Start)
            .condition(
                predicate = { it.flag },
                description = "flag",
                onTrue = { stage(EngineStage.TrueStage) },
                onFalse = { stage(EngineStage.FalseStage) },
            )
            .build()

        fun eventJoinToConditionOnlyFlow(): Flow<EngineState> {
            val b = FlowBuilder<EngineState>()

            b.stage(EngineStage.Wait)
                .waitFor(EngineEvent.Go)
                .join(EngineStage.Conditional)

            b.stage(EngineStage.Conditional)
                .condition(
                    predicate = { it.flag },
                    description = "flag",
                    onTrue = { stage(EngineStage.TrueStage) },
                    onFalse = { stage(EngineStage.FalseStage) },
                )

            return b.build()
        }

        fun removePersisterRegistration(engine: FlowEngine, flowId: String) {
            val field = engine.javaClass.getDeclaredField("persisters").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val map = field.get(engine) as MutableMap<String, Any>
            map.remove(flowId)
        }
    }
}

