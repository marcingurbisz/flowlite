package io.flowlite.test

import io.flowlite.Event
import io.flowlite.MermaidGenerator
import io.flowlite.Stage
import io.flowlite.flow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import java.time.Instant

private enum class DiagramStage : Stage {
    TrueStage,
    FalseStage,
    AfterEvent,
    End,
    AltEndTrue,
    AltEndFalse,
}

private enum class DiagramEvent : Event { Trigger }

private data class DiagramState(
    val flag: Boolean,
    val vip: Boolean,
    val duplicate: Boolean,
)

class MermaidGeneratorTest : BehaviorSpec({
    given("a flow with nested and event-based conditions") {
        val flow = createDiagramFlow()
        val generator = MermaidGenerator()

        `when`("generating a diagram") {
            val diagram = generator.generateDiagram(flow)

            then("it includes initial condition node and transitions") {
                diagram shouldContain "stateDiagram-v2"
                diagram shouldContain "[*] --> if_is_ready"
            }

            then("it includes event-based condition transitions") {
                diagram shouldContain "onEvent Trigger"
                diagram shouldContain "FalseStage --> if_is_vip"
            }

            then("it de-duplicates condition node names") {
                diagram shouldContain "state if_is_vip <<choice>>"
                diagram shouldContain "state if_is_vip_2 <<choice>>"
            }

            then("it renders action names on stages") {
                diagram shouldContain "TrueStage: TrueStage actionForTrueStage()"
            }
        }
    }

    given("a flow where condition description is inferred") {
        val flow = createInferredDescriptionFlow()
        val generator = MermaidGenerator()

        `when`("generating a diagram") {
            val diagram = generator.generateDiagram(flow)

            then("it uses the inferred name for choice node and labels") {
                diagram shouldContain "state if_isready <<choice>>"
                diagram shouldContain "[*] --> if_isready"
                diagram shouldContain "if_isready --> TrueStage: isReady"
                diagram shouldContain "if_isready --> FalseStage: NOT (isReady)"
            }
        }
    }

    given("a flow with a timer stage") {
        val flow = createTimerDiagramFlow()
        val generator = MermaidGenerator()

        `when`("generating a diagram") {
            val diagram = generator.generateDiagram(flow)

            then("it renders the timer calculator name on the stage") {
                diagram shouldContain "AfterEvent: AfterEvent calculateWakeUp()"
            }
        }
    }
})

private fun createDiagramFlow() =
    flow<DiagramState, DiagramStage, DiagramEvent> {
        _if(
            predicate = { it.flag },
            description = "Is Ready",
        ) {
            stage(DiagramStage.TrueStage, ::actionForTrueStage)
            _if(
                predicate = { it.duplicate },
                description = "Is VIP",
            ) {
                stage(DiagramStage.End)
            } _else {
                stage(DiagramStage.AltEndTrue)
            }
        } _else {
            stage(DiagramStage.FalseStage, waitFor = DiagramEvent.Trigger)
            _if(
                predicate = { it.vip },
                description = "Is VIP",
            ) {
                stage(DiagramStage.AfterEvent)
            } _else {
                stage(DiagramStage.AltEndFalse)
            }
        }
    }

private fun actionForTrueStage(state: DiagramState) = state

private data class InferredState(val ready: Boolean)

private fun isReady(state: InferredState) = state.ready

private fun createInferredDescriptionFlow() =
    flow<InferredState, DiagramStage, DiagramEvent> {
        _if(::isReady) { stage(DiagramStage.TrueStage) } _else { stage(DiagramStage.FalseStage) }
    }

private fun calculateWakeUp(state: InferredState): Instant = Instant.EPOCH

private fun createTimerDiagramFlow() =
    flow<InferredState, DiagramStage, DiagramEvent> {
        timer(DiagramStage.AfterEvent, ::calculateWakeUp)
        stage(DiagramStage.End)
    }
