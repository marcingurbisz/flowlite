package io.flowlite.api

/**
 * Generates Mermaid diagram representation of a Flow.
 * This allows visualizing the flow structure for documentation or debugging.
 */
class MermaidGenerator {

    /**
     * Generates a Mermaid diagram string from a Flow object.
     *
     * @param flowId The identifier of the flow
     * @param flow The Flow object to convert to a diagram
     * @return String containing the Mermaid diagram definition
     */
    fun <T : Any> generateDiagram(flowId: String, flow: Flow<T>): String {
        val sb = StringBuilder()
        
        // Start the mermaid stateDiagram
        sb.append("stateDiagram-v2\n")
        
        // Track visited stages to avoid cycles in the diagram
        val visitedStages = mutableSetOf<Stage>()
        
        // Add all choice nodes (condition handlers)
        flow.stages.forEach { (stage, definition) ->
            if (definition.conditionHandler != null) {
                val choiceNodeName = "if_${stage.toString().lowercase()}"
                sb.append("    state $choiceNodeName <<choice>>\n")
            }
        }
        
        // Start with the initial stage
        sb.append("    [*] --> ${flow.initialStage}\n")
        
        // Process all stages starting with the initial stage
        processStage(flow, flow.initialStage, sb, visitedStages)
        
        // Add terminal states
        flow.stages.keys.forEach { stage ->
            if (isTerminalState(stage, flow)) {
                sb.append("    $stage --> [*]\n")
            }
        }
        
        return sb.toString()
    }
    
    /**
     * Process a stage and its transitions
     */
    private fun <T : Any> processStage(
        flow: Flow<T>,
        currentStage: Stage,
        sb: StringBuilder,
        visitedStages: MutableSet<Stage>
    ) {
        // Skip if already visited to avoid cycles
        if (!visitedStages.add(currentStage)) {
            return
        }
        
        val stageDefinition = flow.stages[currentStage] ?: return
        
        // Add stage description with action if present
        stageDefinition.action?.let { action ->
            val actionName = extractActionName(action)
            sb.append("    $currentStage: $currentStage $actionName()\n")
        }
        
        // Process condition handler if present
        stageDefinition.conditionHandler?.let { conditionHandler ->
            val choiceNodeName = "if_${currentStage.toString().lowercase()}"
            
            // Add transition to choice node
            sb.append("    $currentStage --> $choiceNodeName\n")
            
            // Add true branch
            val trueLabel = if (conditionHandler.description != null) {
                "(${conditionHandler.description}) == true"
            } else {
                "true"
            }
            sb.append("    $choiceNodeName --> ${conditionHandler.trueStage}: $trueLabel\n")
            
            // Process the true stage
            if (!visitedStages.contains(conditionHandler.trueStage)) {
                processStage(flow, conditionHandler.trueStage, sb, visitedStages)
            }
            
            // Add false branch
            val falseLabel = if (conditionHandler.description != null) {
                "(${conditionHandler.description}) == false"
            } else {
                "false"
            }
            sb.append("    $choiceNodeName --> ${conditionHandler.falseStage}: $falseLabel\n")
            
            // Process the false stage
            if (!visitedStages.contains(conditionHandler.falseStage)) {
                processStage(flow, conditionHandler.falseStage, sb, visitedStages)
            }
        }
        
        // Process event handlers
        stageDefinition.eventHandlers.forEach { (event, handler) ->
            sb.append("    $currentStage --> ${handler.targetStageDefinition.stage}: onEvent $event\n")
            
            // Process the target stage if not already processed
            if (!visitedStages.contains(handler.targetStageDefinition.stage)) {
                processStage(flow, handler.targetStageDefinition.stage, sb, visitedStages)
            }
        }
        
        // Process automatic progression to next stage
        stageDefinition.nextStage?.let { nextStage ->
            sb.append("    $currentStage --> $nextStage\n")
            
            // Process the next stage if not already processed
            if (!visitedStages.contains(nextStage)) {
                processStage(flow, nextStage, sb, visitedStages)
            }
        }
    }
    
    /**
     * Check if a stage is a terminal state (end of the flow)
     */
    private fun <T : Any> isTerminalState(stage: Stage, flow: Flow<T>): Boolean {
        val stageDefinition = flow.stages[stage] ?: return false
        
        // A stage is terminal if it has no outgoing transitions:
        // - No event handlers
        // - No condition handler  
        // - No next stage (automatic progression)
        return stageDefinition.eventHandlers.isEmpty() && 
               stageDefinition.conditionHandler == null &&
               stageDefinition.nextStage == null
    }
    
    /**
     * Extract a readable function name from a function reference
     */
    private fun extractActionName(action: Any): String {
        val actionStr = action.toString()
        return when {
            // Handle function references like "fun functionName(params): ReturnType"
            actionStr.startsWith("fun ") -> {
                actionStr.substringAfter("fun ")
                    .substringBefore("(")
            }
            // Fallback to original logic for other cases
            else -> {
                actionStr.substringAfterLast(".")
                    .substringBefore("(")
                    .substringBefore("$")
            }
        }
    }
    
}