package io.flowlite.test

import io.flowlite.Event
import io.flowlite.FlowBuilder
import io.flowlite.Stage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private enum class DslStage : Stage { Start, Next, Other }
private enum class DslEvent : Event { Go }
private data class DslState(val value: Int = 0)

class FlowDslValidationTest : BehaviorSpec({
    given("flow DSL validation") {
        `when`("building a flow with no initial stage or condition") {
            then("it fails") {
                shouldThrow<IllegalArgumentException> {
                    FlowBuilder<DslState, DslStage, DslEvent>().build()
                }
            }
        }

        `when`("a stage declares both an action and event handlers") {
            then("it fails validation") {
                val builder = FlowBuilder<DslState, DslStage, DslEvent>()
                builder.stage(DslStage.Start, ::identityAction)
                    .waitFor(DslEvent.Go)
                    .join(DslStage.Next)

                val ex = shouldThrow<IllegalStateException> {
                    builder.build()
                }
                requireNotNull(ex.message) shouldContain "cannot declare both an action and event handlers"
            }
        }

        `when`("an event is reused in multiple waitFor declarations") {
            then("it fails validation") {
                val builder = FlowBuilder<DslState, DslStage, DslEvent>()
                builder.stage(DslStage.Start)
                    .waitFor(DslEvent.Go)
                    .join(DslStage.Next)
                builder.stage(DslStage.Other)
                    .waitFor(DslEvent.Go)
                    .join(DslStage.Next)

                val ex = shouldThrow<IllegalStateException> {
                    builder.build()
                }
                requireNotNull(ex.message) shouldContain "Event"
            }
        }

        `when`("a direct transition is added after event handlers") {
            then("it fails immediately") {
                val builder = FlowBuilder<DslState, DslStage, DslEvent>()
                val stageBuilder = builder.stage(DslStage.Start)
                stageBuilder.waitFor(DslEvent.Go).join(DslStage.Next)

                val ex = shouldThrow<IllegalStateException> {
                    stageBuilder.stage(DslStage.Other)
                }
                requireNotNull(ex.message) shouldContain "already has transitions defined"
            }
        }

        `when`("a join references an undefined stage") {
            then("it fails validation") {
                val builder = FlowBuilder<DslState, DslStage, DslEvent>()
                builder.stage(DslStage.Start).join(DslStage.Next)

                val ex = shouldThrow<IllegalStateException> {
                    builder.build()
                }
                requireNotNull(ex.message) shouldContain "undefined"
            }
        }

        `when`("a condition branch does not resolve to a stage") {
            then("it fails validation") {
                val builder = FlowBuilder<DslState, DslStage, DslEvent>()
                builder.condition(
                    predicate = { it.value > 0 },
                    description = "value > 0",
                    onTrue = { /* missing stage/join */ },
                    onFalse = { stage(DslStage.Start) },
                )

                val ex = shouldThrow<IllegalStateException> {
                    builder.build()
                }
                requireNotNull(ex.message) shouldContain "must resolve to a stage"
            }
        }

        `when`("condition description is omitted") {
            then("it infers a name for function references") {
                val flow = FlowBuilder<DslState, DslStage, DslEvent>()
                    .condition(
                        predicate = ::isPositive,
                        onTrue = { stage(DslStage.Start) },
                        onFalse = { stage(DslStage.Next) },
                    )
                    .build()

                requireNotNull(flow.initialCondition).description shouldBe "isPositive"
            }

            then("it falls back to a stable default for lambdas") {
                val flow = FlowBuilder<DslState, DslStage, DslEvent>()
                    .condition(
                        predicate = { it.value > 0 },
                        onTrue = { stage(DslStage.Start) },
                        onFalse = { stage(DslStage.Next) },
                    )
                    .build()

                requireNotNull(flow.initialCondition).description shouldBe "condition"
            }
        }
    }
})

private fun identityAction(state: DslState): DslState = state

private fun isPositive(state: DslState): Boolean = state.value > 0
