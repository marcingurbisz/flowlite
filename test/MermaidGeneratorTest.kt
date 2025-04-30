package io.flowlite.test

import io.flowlite.api.MermaidGenerator
import io.flowlite.test.OrderEvent.*
import io.flowlite.test.OrderStage.*
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Tests the MermaidGenerator to ensure it properly converts Flow objects into Mermaid diagram syntax.
 */
class MermaidGeneratorTest {

    @Test
    fun `test generate mermaid diagram for pizza order flow`() {
        // Get the pizza flow definition from our test domain
        val pizzaFlow = createPizzaOrderFlow()
        
        // Debug: Print the flow structure
        println("Flow Structure:")
        println("Initial Stage: ${pizzaFlow.initialStage}")
        println("Stages: ${pizzaFlow.stages.keys.joinToString()}")
        pizzaFlow.stages.forEach { (stage, definition) ->
            println("$stage:")
            println("  - Has action: ${definition.action != null}")
            println("  - Has condition: ${definition.conditionHandler != null}")
            if (definition.conditionHandler != null) {
                println("    - True stage: ${definition.conditionHandler?.trueStage}")
                println("    - False stage: ${definition.conditionHandler?.falseStage}")
            }
            println("  - Events: ${definition.eventHandlers.keys.joinToString()}")
        }
        
        // Create an instance of our generator
        val generator = MermaidGenerator()
        
        // Generate the diagram
        val diagram = generator.generateDiagram("pizza-order", pizzaFlow)
        
        // Print the diagram for visual inspection
        println("Generated Mermaid Diagram:")
        println(diagram)
        
        // Debug: Check for specific stage to see if it's present
        println("Checking for ExpiringOnlinePayment in diagram:")
        println("Contains ExpiringOnlinePayment: ${diagram.contains("ExpiringOnlinePayment")}")
        
        // Check that the diagram starts correctly
        assertTrue(diagram.contains("stateDiagram-v2"), "Diagram should start with stateDiagram-v2")
        assertTrue(diagram.contains("[*] --> $Started"), "Diagram should have initial state")
        
        // Verify all stages are present
        assertTrue(diagram.contains(Started.toString()), "Missing stage: $Started")
        assertTrue(diagram.contains(InitializingCashPayment.toString()), "Missing stage: $InitializingCashPayment")
        assertTrue(diagram.contains(InitializingOnlinePayment.toString()), "Missing stage: $InitializingOnlinePayment")
        assertTrue(diagram.contains(ExpiringOnlinePayment.toString()), "Missing stage: $ExpiringOnlinePayment")
        assertTrue(diagram.contains(StartingOrderPreparation.toString()), "Missing stage: $StartingOrderPreparation")
        assertTrue(diagram.contains(InitializingDelivery.toString()), "Missing stage: $InitializingDelivery")
        assertTrue(diagram.contains(CompletingOrder.toString()), "Missing stage: $CompletingOrder")
        assertTrue(diagram.contains(CancellingOrder.toString()), "Missing stage: $CancellingOrder")
        
        // Verify key event transitions
        assertTrue(diagram.contains("onEvent $PaymentConfirmed"), "Missing event: $PaymentConfirmed")
        assertTrue(diagram.contains("onEvent $PaymentCompleted"), "Missing event: $PaymentCompleted")
        assertTrue(diagram.contains("onEvent $SwitchToCashPayment"), "Missing event: $SwitchToCashPayment")
        assertTrue(diagram.contains("onEvent $ReadyForDelivery"), "Missing event: $ReadyForDelivery")
        assertTrue(diagram.contains("onEvent $DeliveryCompleted"), "Missing event: $DeliveryCompleted")
        
        // Verify terminal nodes
        assertTrue(diagram.contains("$CompletingOrder --> [*]") || 
                   diagram.contains("$CancellingOrder --> [*]"),
                   "Diagram should have at least one terminal state")
    }
    
}