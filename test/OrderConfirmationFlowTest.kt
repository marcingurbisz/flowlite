package io.flowlite.test

import io.flowlite.api.MermaidGenerator
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Test that verifies the generated Mermaid diagram for the order confirmation flow
 * matches the expected structure.
 */
class OrderConfirmationFlowTest {

    @Test
    fun `test order confirmation flow generates expected diagram`() {
        // Get the order confirmation flow
        val flow = createOrderConfirmationFlow()
        
        // Generate the diagram
        val generator = MermaidGenerator()
        val diagram = generator.generateDiagram("order-confirmation", flow)
        
        // Print for inspection
        println("Generated Mermaid Diagram:")
        println(diagram)
        
        // Verify the diagram structure
        assertTrue(diagram.contains("stateDiagram-v2"), "Should be a state diagram")
        assertTrue(diagram.contains("[*] --> InitializingConfirmation"), "Should start with InitializingConfirmation")
        
        // Verify all stages are present
        assertTrue(diagram.contains("InitializingConfirmation"), "Missing InitializingConfirmation stage")
        assertTrue(diagram.contains("WaitingForConfirmation"), "Missing WaitingForConfirmation stage") 
        assertTrue(diagram.contains("RemovingFromConfirmationQueue"), "Missing RemovingFromConfirmationQueue stage")
        assertTrue(diagram.contains("InformingCustomer"), "Missing InformingCustomer stage")
        
        // Verify stage actions are shown  
        assertTrue(diagram.contains("InitializingConfirmation: InitializingConfirmation"), "InitializingConfirmation should show action")
        assertTrue(diagram.contains("InformingCustomer: InformingCustomer"), "InformingCustomer should show action")
        // Note: RemovingFromConfirmationQueue shows action when visited through event, may not show when just referenced
        
        // Verify automatic progression (no event label)
        assertTrue(diagram.contains("InitializingConfirmation --> WaitingForConfirmation"), 
                   "Should have automatic progression from InitializingConfirmation to WaitingForConfirmation")
        assertTrue(diagram.contains("RemovingFromConfirmationQueue --> InformingCustomer"), 
                   "Should have automatic progression from RemovingFromConfirmationQueue to InformingCustomer")
        
        // Verify event transitions
        assertTrue(diagram.contains("onEvent ConfirmedDigitally"), "Missing ConfirmedDigitally event")
        assertTrue(diagram.contains("onEvent ConfirmedPhysically"), "Missing ConfirmedPhysically event")
        
        // Verify specific event transitions
        assertTrue(diagram.contains("WaitingForConfirmation --> RemovingFromConfirmationQueue: onEvent ConfirmedDigitally"), 
                   "Should have ConfirmedDigitally transition to RemovingFromConfirmationQueue")
        assertTrue(diagram.contains("WaitingForConfirmation --> InformingCustomer: onEvent ConfirmedPhysically"), 
                   "Should have ConfirmedPhysically transition to InformingCustomer")
        
        // Verify terminal state
        assertTrue(diagram.contains("InformingCustomer --> [*]"), "Should end at InformingCustomer")
    }
}