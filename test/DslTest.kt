package io.flowlite.test

import io.flowlite.Event
import io.flowlite.Stage
import io.flowlite.eventlessFlow
import io.flowlite.flow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import java.time.Instant

private enum class ReceiverStage : Stage {
    Start,
    Wait,
    Removing,
    Informing,
    Done,
}

private enum class ReceiverEvent : Event {
    Confirmed,
}

private data class ReceiverState(val flag: Boolean)

class DslTest : BehaviorSpec({
    given("procedural DSL") {
        `when`("building a flow with waitFor and implicit false-path fallthrough") {
            val built = flow<ReceiverState, ReceiverStage, ReceiverEvent> {
                stage(ReceiverStage.Start, ::noOp)
                stage(ReceiverStage.Wait, waitFor = ReceiverEvent.Confirmed)
                _if(::isFlagTrue) {
                    stage(ReceiverStage.Removing, ::noOp)
                }
                stage(ReceiverStage.Informing, ::noOp)
            }

            then("it lowers the waited event into an event-based condition") {
                built.initialStage shouldBe ReceiverStage.Start
                val start = requireNotNull(built.stages[ReceiverStage.Start])
                start.nextStage shouldBe ReceiverStage.Wait

                val wait = requireNotNull(built.stages[ReceiverStage.Wait])
                wait.eventHandlers shouldContainKey ReceiverEvent.Confirmed

                val condition = requireNotNull(wait.eventHandlers[ReceiverEvent.Confirmed]?.targetCondition)
                condition.description shouldBe "isFlagTrue"
                condition.trueStage shouldBe ReceiverStage.Removing
                condition.falseStage shouldBe ReceiverStage.Informing

                requireNotNull(built.stages[ReceiverStage.Removing]).nextStage shouldBe ReceiverStage.Informing
            }
        }

        `when`("building nested _if / _else branches") {
            val built = eventlessFlow<ReceiverState, ReceiverStage> {
                _if(::isFlagTrue) {
                    stage(ReceiverStage.Start)
                    _if(::isFlagTrue) {
                        stage(ReceiverStage.Done)
                    } _else {
                        stage(ReceiverStage.Informing)
                    }
                } _else {
                    stage(ReceiverStage.Wait)
                }
            }

            then("it lowers nested conditions and infers descriptions") {
                val initialCondition = requireNotNull(built.initialCondition)
                initialCondition.description shouldBe "isFlagTrue"
                initialCondition.trueStage shouldBe ReceiverStage.Start
                initialCondition.falseStage shouldBe ReceiverStage.Wait

                val startCondition = requireNotNull(built.stages[ReceiverStage.Start]?.conditionHandler)
                startCondition.description shouldBe "isFlagTrue"
                startCondition.trueStage shouldBe ReceiverStage.Done
                startCondition.falseStage shouldBe ReceiverStage.Informing
            }
        }

        `when`("building a flow with a timer step") {
            val built = eventlessFlow<ReceiverState, ReceiverStage> {
                timer(ReceiverStage.Wait, ::wakeAtNow)
                stage(ReceiverStage.Done, ::noOp)
            }

            then("it stores timer metadata separately from actions") {
                val wait = requireNotNull(built.stages[ReceiverStage.Wait])
                wait.timerName shouldBe "wakeAtNow"
                wait.action shouldBe null
                wait.nextStage shouldBe ReceiverStage.Done
            }
        }
    }
})

private fun noOp(state: ReceiverState) = state
private fun wakeAtNow(state: ReceiverState): Instant = Instant.EPOCH

private fun isFlagTrue(state: ReceiverState) = state.flag
