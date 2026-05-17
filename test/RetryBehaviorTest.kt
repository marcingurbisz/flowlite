package io.flowlite.test

import io.flowlite.ActionContext
import io.flowlite.AutoRetryPlan
import io.flowlite.BackoffStrategy
import io.flowlite.Engine
import io.flowlite.Event
import io.flowlite.EventStore
import io.flowlite.FailureClassifier
import io.flowlite.FailureHandling
import io.flowlite.HistoryEntry
import io.flowlite.InstanceData
import io.flowlite.RetryState
import io.flowlite.RetryStateStore
import io.flowlite.RetryTrigger
import io.flowlite.ScheduledTick
import io.flowlite.Stage
import io.flowlite.StageStatus
import io.flowlite.StatePersister
import io.flowlite.TickScheduler
import io.flowlite.eventlessFlow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private enum class RetryStage : Stage { Failing, Done }
private data class RetryStatefulValue(val stage: RetryStage, val attempts: Int = 0)

class RetryBehaviorTest : BehaviorSpec({
    given("auto retry support") {
        val clock = AdjustableClock.systemUTC()
        val tickScheduler = DueTickScheduler(clock)
        val eventStore = InMemoryRetryEventStore()
        val persister = InMemoryRetryStatePersister<RetryStatefulValue>()
        val retryStateStore = InMemoryRetryStateStore()
        val history = RecordingHistoryStore()
        val attempts = AtomicInteger(0)

        fun flakyAction(state: RetryStatefulValue): RetryStatefulValue {
            val currentAttempt = attempts.incrementAndGet()
            if (currentAttempt < 2) throw IOException("temporary-$currentAttempt")
            return state.copy(attempts = currentAttempt)
        }

        val engine = Engine(
            eventStore = eventStore,
            tickScheduler = tickScheduler,
            historyStore = history,
            retryStateStore = retryStateStore,
            clock = clock,
        ).also {
            it.registerFlow(
                flowId = "auto-retry",
                flow = eventlessFlow<RetryStatefulValue, RetryStage> {
                    stage(RetryStage.Failing, ::flakyAction)
                    stage(RetryStage.Done)
                },
                statePersister = persister,
                failureClassifier = object : FailureClassifier<RetryStatefulValue> {
                    override fun classify(
                        context: ActionContext,
                        stage: Stage,
                        state: RetryStatefulValue,
                        error: Exception,
                        failedAttemptCount: Int,
                    ): FailureHandling {
                        return if (error is IOException) {
                            FailureHandling(
                                autoRetry = AutoRetryPlan(
                                    maxAttempts = 3,
                                    backoffStrategy = BackoffStrategy.fixed(java.time.Duration.ofSeconds(5)),
                                ),
                                externalRetryAllowed = true,
                            )
                        } else {
                            FailureHandling()
                        }
                    }
                },
            )
        }

        `when`("the first attempt fails with an auto-retriable error") {
            val id = engine.startInstance("auto-retry", RetryStatefulValue(stage = RetryStage.Failing))
            tickScheduler.drainDue()

            then("it stays in error, stores retry metadata and schedules auto retry") {
                engine.getStatus("auto-retry", id) shouldBe (RetryStage.Failing to StageStatus.Error)
                val retryState = retryStateStore.find(id).shouldNotBeNull()
                retryState.failedAttemptCount shouldBe 1
                retryState.externalRetryAllowed shouldBe true
                retryState.autoRetryActive shouldBe true
                retryState.nextAutoRetryAt.shouldNotBeNull()
                history.entries.map { it.type } shouldContain io.flowlite.HistoryEntryType.Error
                (history.entries.last { it.type == io.flowlite.HistoryEntryType.Error } as HistoryEntry.Error).externalRetryAllowed shouldBe true
            }

            then("it retries automatically after the scheduled delay") {
                clock.advanceBy(java.time.Duration.ofSeconds(5))
                tickScheduler.drainDue()
                engine.getStatus("auto-retry", id) shouldBe (RetryStage.Done to StageStatus.Completed)
                retryStateStore.find(id) shouldBe null
                (history.entries.last { it.type == io.flowlite.HistoryEntryType.Retried } as HistoryEntry.Retried).retryTrigger shouldBe RetryTrigger.Auto
            }
        }
    }

    given("external retry support") {
        val clock = AdjustableClock.systemUTC()
        val tickScheduler = DueTickScheduler(clock)
        val eventStore = InMemoryRetryEventStore()
        val persister = InMemoryRetryStatePersister<RetryStatefulValue>()
        val retryStateStore = InMemoryRetryStateStore()
        val history = RecordingHistoryStore()
        val attempts = AtomicInteger(0)

        fun externallyFixableAction(state: RetryStatefulValue): RetryStatefulValue {
            val currentAttempt = attempts.incrementAndGet()
            if (currentAttempt < 2) throw IllegalStateException("needs-external-fix")
            return state.copy(attempts = currentAttempt)
        }

        val engine = Engine(
            eventStore = eventStore,
            tickScheduler = tickScheduler,
            historyStore = history,
            retryStateStore = retryStateStore,
            clock = clock,
        ).also {
            it.registerFlow(
                flowId = "external-retry",
                flow = eventlessFlow<RetryStatefulValue, RetryStage> {
                    stage(RetryStage.Failing, ::externallyFixableAction)
                    stage(RetryStage.Done)
                },
                statePersister = persister,
                failureClassifier = object : FailureClassifier<RetryStatefulValue> {
                    override fun classify(
                        context: ActionContext,
                        stage: Stage,
                        state: RetryStatefulValue,
                        error: Exception,
                        failedAttemptCount: Int,
                    ): FailureHandling = FailureHandling(externalRetryAllowed = true)
                },
            )
        }

        `when`("the stage fails and external retry is allowed") {
            val id = engine.startInstance("external-retry", RetryStatefulValue(stage = RetryStage.Failing))
            try {
                tickScheduler.drainDue()
            } catch (_: IllegalStateException) {
                // Plain external-retry failures still bubble up in the current processing model.
            }

            then("external retry completes and records a distinct history trigger") {
                engine.getStatus("external-retry", id) shouldBe (RetryStage.Failing to StageStatus.Error)
                retryStateStore.find(id)?.externalRetryAllowed shouldBe true
                engine.externalRetry("external-retry", id)
                tickScheduler.drainDue()
                engine.getStatus("external-retry", id) shouldBe (RetryStage.Done to StageStatus.Completed)
                (history.entries.last { it.type == io.flowlite.HistoryEntryType.Retried } as HistoryEntry.Retried).retryTrigger shouldBe RetryTrigger.External
            }
        }
    }
})

private class DueTickScheduler(private val clock: AdjustableClock) : TickScheduler {
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
        autoRetry: Boolean,
    ) {
        queue.addLast(ScheduledTick(flowId, flowInstanceId, notBefore, targetStage, autoRetry))
    }

    fun drainDue(limit: Int = 1000) {
        val tickHandler = handler ?: error("Tick handler not set")
        var steps = 0
        while (true) {
            val next = queue.firstOrNull() ?: return
            if (next.notBefore.isAfter(clock.instant())) return
            if (steps++ > limit) error("Exceeded tick drain limit ($limit)")
            tickHandler(queue.removeFirst())
        }
    }
}

private class InMemoryRetryStatePersister<T : Any> : StatePersister<T> {
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

private class InMemoryRetryEventStore : EventStore {
    override fun append(flowId: String, flowInstanceId: UUID, event: Event) = Unit
    override fun peek(flowId: String, flowInstanceId: UUID, candidates: Collection<Event>) = null
    override fun delete(eventId: UUID) = false
}

private class InMemoryRetryStateStore : RetryStateStore {
    private val states = mutableMapOf<UUID, RetryState>()

    override fun save(state: RetryState): RetryState {
        states[state.flowInstanceId] = state
        return state
    }

    override fun find(flowInstanceId: UUID): RetryState? = states[flowInstanceId]

    override fun delete(flowInstanceId: UUID) {
        states.remove(flowInstanceId)
    }
}

private class RecordingHistoryStore : io.flowlite.HistoryStore {
    val entries = mutableListOf<HistoryEntry>()

    override fun append(entry: HistoryEntry) {
        entries += entry
    }
}
