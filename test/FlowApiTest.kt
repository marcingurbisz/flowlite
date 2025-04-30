package io.flowlite.test

import io.flowlite.api.Event
import io.flowlite.api.FlowBuilder
import io.flowlite.api.Stage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests to verify the correct implementation of the FlowApi, particularly the condition method.
 */
class FlowApiTest {

    enum class TestStage : Stage {
        Start,
        BranchA,
        BranchB
    }

    enum class TestEvent : Event {
        EventX
    }

    data class TestState(val value: Boolean)

    @Test
    fun `test condition method stores branches in flow structure`() {
        // Create a simple flow with a condition
        val flow = FlowBuilder<TestState>()
            .stage(TestStage.Start)
            .condition(
                { it.value },  // Predicate based on state value
                onTrue = {
                    stage(TestStage.BranchA)
                },
                onFalse = {
                    stage(TestStage.BranchB)
                }
            )
            .build()

        // Verify the initial stage
        assertEquals(TestStage.Start, flow.initialStage, "Initial stage should be Start")
        
        // Verify that all stages are present in the flow
        val stageKeys = flow.stages.keys
        println("Stages in flow: ${stageKeys.joinToString()}")
        assert(stageKeys.contains(TestStage.Start))
        assert(stageKeys.contains(TestStage.BranchA))
        assert(stageKeys.contains(TestStage.BranchB))
        
        // Verify that Start stage has a condition handler
        val startStageDefinition = flow.stages[TestStage.Start]
        assertNotNull(startStageDefinition, "Start stage definition should exist")
        assertNotNull(startStageDefinition.conditionHandler, "Start stage should have a condition handler")
        
        // Verify the condition branches
        assertEquals(TestStage.BranchA, startStageDefinition.conditionHandler?.trueStage, 
                    "True branch should go to BranchA")
        assertEquals(TestStage.BranchB, startStageDefinition.conditionHandler?.falseStage, 
                    "False branch should go to BranchB")
                    
        println("Test passed: condition method correctly stores branch information in flow structure")
    }
}