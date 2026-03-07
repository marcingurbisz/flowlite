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
            then("it fails") {
                shouldThrow<IllegalArgumentException> {
                    flow<DslState, DslStage, DslEvent> {}
                }
            }
        }

        `when`("a stage is defined more than once") {
            then("it fails validation") {
                val ex = shouldThrow<IllegalStateException> {
                    flow<DslState, DslStage, DslEvent> {
                        stage(DslStage.Start)
                        stage(DslStage.Start)
                    }
                }
                requireNotNull(ex.message) shouldContain "already defined - each stage should be defined only once"
            }
        }

        `when`("a wait stage has no continuation") {
            then("it fails validation") {
                val ex = shouldThrow<IllegalArgumentException> {
                    flow<DslState, DslStage, DslEvent> {
                        stage(DslStage.Start, waitFor = DslEvent.Go)
                    }
                }
                requireNotNull(ex.message) shouldContain "nothing follows it"
            }
        }

        `when`("an _if has no false continuation") {
            then("it fails validation") {
                val ex = shouldThrow<IllegalArgumentException> {
                    flow<DslState, DslStage, DslEvent> {
                        _if(::isPositive) {
                            stage(DslStage.Start)
                        }
                    }
                }
                requireNotNull(ex.message) shouldContain "false branch"
            }
        }

        `when`("_else is declared twice") {
            then("it fails immediately") {
                val ex = shouldThrow<IllegalArgumentException> {
                    flow<DslState, DslStage, DslEvent> {
                        val branch = _if(::isPositive) {
                            stage(DslStage.Start)
                        }
                        branch _else { stage(DslStage.Next) }
                        branch _else { stage(DslStage.Other) }
                    }
                }
                requireNotNull(ex.message) shouldContain "only one _else"
            }
        }

        `when`("condition description is omitted") {
            then("it infers a name for function references") {
                val flow = flow<DslState, DslStage, DslEvent> {
                    _if(::isPositive) { stage(DslStage.Start) } _else { stage(DslStage.Next) }
                }

                requireNotNull(flow.initialCondition).description shouldBe "isPositive"
            }

            then("it falls back to a stable default for lambdas") {
                val flow = flow<DslState, DslStage, DslEvent> {
                    _if({ it.value > 0 }) { stage(DslStage.Start) } _else { stage(DslStage.Next) }
                }

                requireNotNull(flow.initialCondition).description shouldBe "condition"
            }
        }
    }
})

private fun isPositive(state: DslState) = state.value > 0
