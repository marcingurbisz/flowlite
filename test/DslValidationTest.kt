package io.flowlite.test

import io.flowlite.Event
import io.flowlite.Stage
import io.flowlite.flow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private enum class DslStage : Stage { Start, Next, Other }
private enum class DslEvent : Event { Go }
private data class DslState(val value: Int = 0)

class DslValidationTest : BehaviorSpec({
    given("flow DSL validation") {
        `when`("building a flow with no initial stage or condition") {
            val exception = shouldThrow<IllegalArgumentException> {
                flow<DslState, DslStage, DslEvent> {}
            }

            then("it fails") {
                requireNotNull(exception.message) shouldContain "initial"
            }
        }

        `when`("a stage is defined more than once") {
            val ex = shouldThrow<IllegalStateException> {
                flow<DslState, DslStage, DslEvent> {
                    stage(DslStage.Start)
                    stage(DslStage.Start)
                }
            }

            then("it fails validation") {
                requireNotNull(ex.message) shouldContain "already defined - each stage should be defined only once"
            }
        }

        `when`("a wait stage has no continuation") {
            val ex = shouldThrow<IllegalArgumentException> {
                flow<DslState, DslStage, DslEvent> {
                    stage(DslStage.Start, waitFor = DslEvent.Go)
                }
            }

            then("it fails validation") {
                requireNotNull(ex.message) shouldContain "nothing follows it"
            }
        }

        `when`("an _if has no false continuation") {
            val ex = shouldThrow<IllegalArgumentException> {
                flow<DslState, DslStage, DslEvent> {
                    _if(::isPositive) {
                        stage(DslStage.Start)
                    }
                }
            }

            then("it fails validation") {
                requireNotNull(ex.message) shouldContain "false branch"
            }
        }

        `when`("_else is declared twice") {
            val ex = shouldThrow<IllegalArgumentException> {
                flow<DslState, DslStage, DslEvent> {
                    val branch = _if(::isPositive) {
                        stage(DslStage.Start)
                    }
                    branch _else { stage(DslStage.Next) }
                    branch _else { stage(DslStage.Other) }
                }
            }

            then("it fails immediately") {
                requireNotNull(ex.message) shouldContain "only one _else"
            }
        }

        `when`("condition description is omitted for a function reference") {
            val flow = flow<DslState, DslStage, DslEvent> {
                _if(::isPositive) { stage(DslStage.Start) } _else { stage(DslStage.Next) }
            }

            then("it infers a name") {
                requireNotNull(flow.initialCondition).description shouldBe "isPositive"
            }
        }

        `when`("condition description is omitted for a lambda") {
            val flow = flow<DslState, DslStage, DslEvent> {
                _if({ it.value > 0 }) { stage(DslStage.Start) } _else { stage(DslStage.Next) }
            }

            then("it falls back to a stable default") {
                requireNotNull(flow.initialCondition).description shouldBe "condition"
            }
        }
    }
})

private fun isPositive(state: DslState) = state.value > 0
