package io.flowlite.test

import io.flowlite.Event
import io.flowlite.Stage
import io.flowlite.eventlessFlow
import io.flowlite.flow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe

private enum class ReceiverStage : Stage {
    Start,
    Wait,
    Removing,
    Informing,
    Done,
}

private enum class ReceiverEvent : Event {
    ConfirmedDigitally,
    ConfirmedPhysically,
}

private data class ReceiverState(val flag: Boolean)

class FlowReceiverDslTest : BehaviorSpec({
    given("receiver-lambda DSL") {
        `when`("building an event-driven flow") {
            val built = flow<ReceiverState, ReceiverStage, ReceiverEvent> {
                stage(ReceiverStage.Start, ::noOp)
                    .stage(ReceiverStage.Wait, block = {
                        onEvent(ReceiverEvent.ConfirmedDigitally) {
                            stage(ReceiverStage.Removing, ::noOp)
                            stage(ReceiverStage.Informing, ::noOp)
                        }
                        onEvent(ReceiverEvent.ConfirmedPhysically) {
                            joinTo(ReceiverStage.Informing)
                        }
                    })
            }

            then("it preserves direct and event transitions") {
                built.initialStage shouldBe ReceiverStage.Start
                val start = requireNotNull(built.stages[ReceiverStage.Start])
                start.nextStage shouldBe ReceiverStage.Wait

                val wait = requireNotNull(built.stages[ReceiverStage.Wait])
                wait.eventHandlers shouldContainKey ReceiverEvent.ConfirmedDigitally
                wait.eventHandlers shouldContainKey ReceiverEvent.ConfirmedPhysically
                wait.eventHandlers[ReceiverEvent.ConfirmedDigitally]?.targetStage shouldBe ReceiverStage.Removing
                wait.eventHandlers[ReceiverEvent.ConfirmedPhysically]?.targetStage shouldBe ReceiverStage.Informing
            }
        }

        `when`("using condition blocks in receiver DSL") {
            val built = flow<ReceiverState, ReceiverStage, ReceiverEvent> {
                condition(
                    predicate = ::isFlagTrue,
                    onTrue = {
                        stage(ReceiverStage.Start)
                            .condition(
                                predicate = ::isFlagTrue,
                                onTrue = { stage(ReceiverStage.Done) },
                                onFalse = { stage(ReceiverStage.Informing) },
                            )
                    },
                    onFalse = { stage(ReceiverStage.Wait) },
                )
            }

            then("it infers condition descriptions and keeps branch targets") {
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

        `when`("building an eventless flow") {
            val built = eventlessFlow<ReceiverState, ReceiverStage> {
                stage(ReceiverStage.Start, ::noOp)
                    .stage(ReceiverStage.Done, ::noOp)
            }

            then("it supports direct transitions without event type") {
                built.initialStage shouldBe ReceiverStage.Start
                val start = requireNotNull(built.stages[ReceiverStage.Start])
                start.nextStage shouldBe ReceiverStage.Done
            }
        }

        `when`("using joinTo on root DSL scope") {
            val built = flow<ReceiverState, ReceiverStage, ReceiverEvent> {
                stage(ReceiverStage.Done)
                joinTo(ReceiverStage.Done)
            }

            then("it sets the initial stage to an existing stage") {
                built.initialStage shouldBe ReceiverStage.Done
                built.stages shouldContainKey ReceiverStage.Done
            }
        }

        `when`("an event branch transitions to a condition") {
            val built = flow<ReceiverState, ReceiverStage, ReceiverEvent> {
                stage(ReceiverStage.Wait, block = {
                    onEvent(ReceiverEvent.ConfirmedDigitally) {
                        condition(
                            predicate = ::isFlagTrue,
                            onTrue = { stage(ReceiverStage.Removing) },
                            onFalse = { stage(ReceiverStage.Informing) },
                        )
                    }
                })
            }

            then("it stores event handler with condition target and inferred description") {
                val wait = requireNotNull(built.stages[ReceiverStage.Wait])
                val handler = requireNotNull(wait.eventHandlers[ReceiverEvent.ConfirmedDigitally])
                val condition = requireNotNull(handler.targetCondition)

                condition.description shouldBe "isFlagTrue"
                condition.trueStage shouldBe ReceiverStage.Removing
                condition.falseStage shouldBe ReceiverStage.Informing
            }
        }
    }
})

private fun noOp(state: ReceiverState): ReceiverState = state

private fun isFlagTrue(state: ReceiverState): Boolean = state.flag
