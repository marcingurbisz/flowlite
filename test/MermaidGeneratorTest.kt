package io.flowlite.test

import io.flowlite.api.Event
import io.flowlite.api.Flow
import io.flowlite.api.FlowBuilder
import io.flowlite.api.MermaidGenerator
import io.flowlite.api.Stage
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain

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
            val diagram = generator.generateDiagram("diagram-flow", flow)

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
})

private fun createDiagramFlow(): Flow<DiagramState> {
    return FlowBuilder<DiagramState>()
        .condition(
            predicate = { it.flag },
            description = "Is Ready",
            onTrue = {
                stage(DiagramStage.TrueStage, ::actionForTrueStage)
                    .condition(
                        predicate = { it.duplicate },
                        description = "Is VIP",
                        onTrue = { stage(DiagramStage.End) },
                        onFalse = { stage(DiagramStage.AltEndTrue) },
                    )
            },
            onFalse = {
                stage(DiagramStage.FalseStage)
                    .waitFor(DiagramEvent.Trigger)
                    .condition(
                        predicate = { it.vip },
                        description = "Is VIP",
                        onTrue = { stage(DiagramStage.AfterEvent) },
                        onFalse = { stage(DiagramStage.AltEndFalse) },
                    )
            },
        )
        .build()
}

private fun actionForTrueStage(state: DiagramState): DiagramState = state
