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

        `when`("a stage declares both an action and event handlers") {
            then("it fails validation") {
                val ex = shouldThrow<IllegalStateException> {
                    flow<DslState, DslStage, DslEvent> {
                        stage(DslStage.Start, ::identityAction)
                        onEvent(DslEvent.Go)
                        stage(DslStage.Next)
                    }
                }
                requireNotNull(ex.message) shouldContain "cannot declare both an action and event handlers"
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

        `when`("an event is reused in multiple onEvent declarations") {
            then("it fails validation") {
                val ex = shouldThrow<IllegalStateException> {
                    flow<DslState, DslStage, DslEvent> {
                        stage(DslStage.Start)
                        onEvent(DslEvent.Go)
                        stage(DslStage.Other)
                        onEvent(DslEvent.Go)
                        stage(DslStage.Next)
                    }
                }
                requireNotNull(ex.message) shouldContain "Event"
            }
        }

        `when`("a goTo references an undefined stage") {
            then("it fails validation") {
                val ex = shouldThrow<IllegalStateException> {
                    flow<DslState, DslStage, DslEvent> {
                        stage(DslStage.Start)
                        onEvent(DslEvent.Go) { goTo(DslStage.Next) }
                    }
                }
                requireNotNull(ex.message) shouldContain "event transition to undefined stage"
            }
        }

        `when`("goTo is the first statement") {
            then("it fails immediately") {
                val ex = shouldThrow<IllegalStateException> {
                    flow<DslState, DslStage, DslEvent> {
                        goTo(DslStage.Next)
                        stage(DslStage.Start)
                        stage(DslStage.Next)
                    }
                }
                requireNotNull(ex.message) shouldContain "not allowed in top-level flow scope"
            }
        }

        `when`("goTo is used in top-level scope after a stage") {
            then("it fails immediately") {
                val ex = shouldThrow<IllegalStateException> {
                    flow<DslState, DslStage, DslEvent> {
                        stage(DslStage.Start)
                        goTo(DslStage.Next)
                    }
                }
                requireNotNull(ex.message) shouldContain "not allowed in top-level flow scope"
            }
        }

        `when`("an event transition references an undefined stage") {
            then("it fails validation") {
                val ex = shouldThrow<IllegalStateException> {
                    flow<DslState, DslStage, DslEvent> {
                        stage(DslStage.Start)
                        onEvent(DslEvent.Go) { goTo(DslStage.Next) }
                    }
                }
                requireNotNull(ex.message) shouldContain "event transition to undefined stage"
            }
        }

        `when`("onEvent is nested inside onEvent block") {
            then("it fails immediately") {
                val ex = shouldThrow<IllegalArgumentException> {
                    flow<DslState, DslStage, DslEvent> {
                        stage(DslStage.Start)
                        onEvent(DslEvent.Go) {
                            onEvent(DslEvent.Go)
                            stage(DslStage.Next)
                        }
                    }
                }
                requireNotNull(ex.message) shouldContain "cannot be the first statement"
            }
        }

        `when`("onEvent is used as first statement in root scope") {
            then("it fails immediately") {
                val ex = shouldThrow<IllegalArgumentException> {
                    flow<DslState, DslStage, DslEvent> {
                        onEvent(DslEvent.Go)
                        stage(DslStage.Start)
                    }
                }
                requireNotNull(ex.message) shouldContain "requires a previously defined stage"
            }
        }

        `when`("a stage is declared after goTo in the same scope") {
            then("it fails immediately because goTo terminates branch") {
                val ex = shouldThrow<IllegalStateException> {
                    flow<DslState, DslStage, DslEvent> {
                        condition(predicate = { it.value > 0 }) {
                            onTrue {
                                stage(DslStage.Start)
                                goTo(DslStage.Next)
                                stage(DslStage.Other)
                            }
                            onFalse { stage(DslStage.Next) }
                        }
                    }
                }
                requireNotNull(ex.message) shouldContain "goTo(...) ends the current branch"
            }
        }

        `when`("a condition branch does not resolve to a stage") {
            then("it fails validation") {
                val ex = shouldThrow<IllegalStateException> {
                    flow<DslState, DslStage, DslEvent> {
                        condition(
                            predicate = { it.value > 0 },
                            description = "value > 0",
                        ) {
                            onTrue { /* missing stage/goTo */ }
                            onFalse { stage(DslStage.Start) }
                        }
                    }
                }
                requireNotNull(ex.message) shouldContain "must resolve to a stage"
            }
        }

        `when`("condition description is omitted") {
            then("it infers a name for function references") {
                val flow = flow<DslState, DslStage, DslEvent> {
                    condition(
                        predicate = ::isPositive,
                    ) {
                        onTrue { stage(DslStage.Start) }
                        onFalse { stage(DslStage.Next) }
                    }
                }

                requireNotNull(flow.initialCondition).description shouldBe "isPositive"
            }

            then("it falls back to a stable default for lambdas") {
                val flow = flow<DslState, DslStage, DslEvent> {
                    condition(
                        predicate = { it.value > 0 },
                    ) {
                        onTrue { stage(DslStage.Start) }
                        onFalse { stage(DslStage.Next) }
                    }
                }

                requireNotNull(flow.initialCondition).description shouldBe "condition"
            }
        }

        `when`("condition block does not define both branches") {
            then("it fails for missing onTrue") {
                val ex = shouldThrow<IllegalArgumentException> {
                    flow<DslState, DslStage, DslEvent> {
                        condition(predicate = { it.value > 0 }) {
                            onFalse { stage(DslStage.Next) }
                        }
                    }
                }
                requireNotNull(ex.message) shouldContain "requires onTrue"
            }

            then("it fails for missing onFalse") {
                val ex = shouldThrow<IllegalArgumentException> {
                    flow<DslState, DslStage, DslEvent> {
                        condition(predicate = { it.value > 0 }) {
                            onTrue { stage(DslStage.Start) }
                        }
                    }
                }
                requireNotNull(ex.message) shouldContain "requires onFalse"
            }

            then("it fails when onTrue is declared twice") {
                val ex = shouldThrow<IllegalArgumentException> {
                    flow<DslState, DslStage, DslEvent> {
                        condition(predicate = { it.value > 0 }) {
                            onTrue { stage(DslStage.Start) }
                            onTrue { stage(DslStage.Next) }
                            onFalse { stage(DslStage.Other) }
                        }
                    }
                }
                requireNotNull(ex.message) shouldContain "only one onTrue"
            }

            then("it fails when onFalse is declared twice") {
                val ex = shouldThrow<IllegalArgumentException> {
                    flow<DslState, DslStage, DslEvent> {
                        condition(predicate = { it.value > 0 }) {
                            onTrue { stage(DslStage.Start) }
                            onFalse { stage(DslStage.Next) }
                            onFalse { stage(DslStage.Other) }
                        }
                    }
                }
                requireNotNull(ex.message) shouldContain "only one onFalse"
            }
        }
    }
})

private fun identityAction(state: DslState) = state

private fun isPositive(state: DslState) = state.value > 0
