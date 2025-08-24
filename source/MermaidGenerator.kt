package io.flowlite.api

/**
 * Generates Mermaid diagram representation of a Flow.
 * This allows visualizing the flow structure for documentation or debugging.
 */
class MermaidGenerator {

    // Tracks assigned names for conditions to ensure stable node names
    private val conditionNodeNames = mutableMapOf<ConditionHandler<*>, String>()
    private val conditionNameCounts = mutableMapOf<String, Int>()

    /**
     * Generates a Mermaid diagram string from a Flow object.
     *
     * @param flowId The identifier of the flow
     * @param flow The Flow object to convert to a diagram
     * @return String containing the Mermaid diagram definition
     */
    fun <T : Any> generateDiagram(flowId: String, flow: Flow<T>): String {
        conditionNodeNames.clear()
        conditionNameCounts.clear()
        val sb = StringBuilder()

        // Start the mermaid stateDiagram
        sb.append("stateDiagram-v2\n")

        // Track visited stages to avoid cycles in the diagram
        val visitedStages = mutableSetOf<Stage>()
        val visitedConditions = mutableSetOf<ConditionHandler<T>>()

        // Add all choice nodes (condition handlers)
        addAllChoiceNodes(flow, sb)
        
        // Handle initial stage or initial condition
        if (flow.initialStage != null) {
            sb.append("    [*] --> ${flow.initialStage}\n")
            // Process all stages starting with the initial stage
            processStage(flow, flow.initialStage, sb, visitedStages, visitedConditions)
        } else if (flow.initialCondition != null) {
            val nodeName = generateConditionNodeName(flow.initialCondition)
            sb.append("    [*] --> $nodeName\n")
            processCondition(flow, flow.initialCondition, nodeName, sb, visitedStages, visitedConditions)
        }
        
        // Add terminal states
        flow.stages.keys.forEach { stage ->
            if (isTerminalState(stage, flow)) {
                sb.append("    $stage --> [*]\n")
            }
        }
        
        return sb.toString()
    }
    
    /**
     * Add all choice nodes for conditions in the flow
     */
    private fun <T : Any> addAllChoiceNodes(flow: Flow<T>, sb: StringBuilder) {
        val visitedConditions = mutableSetOf<ConditionHandler<T>>()
        
        // Add choice node for initial condition if present
        flow.initialCondition?.let {
            val nodeName = generateConditionNodeName(it)
            sb.append("    state $nodeName <<choice>>\n")
            addNestedChoiceNodes(it, sb, visitedConditions)
        }
        
        // Add all choice nodes (condition handlers in stages)
        flow.stages.forEach { (stage, definition) ->
            if (definition.conditionHandler != null) {
                val choiceNodeName = generateConditionNodeName(definition.conditionHandler!!)
                sb.append("    state $choiceNodeName <<choice>>\n")

                // Add nested choice nodes recursively
                addNestedChoiceNodes(definition.conditionHandler!!, sb, visitedConditions)
            }
            
            // Add choice nodes for event-based conditions
            definition.eventHandlers.forEach { (_, handler) ->
                if (handler.targetCondition != null) {
                    val nodeName = generateConditionNodeName(handler.targetCondition)
                    if (!visitedConditions.contains(handler.targetCondition)) {
                        sb.append("    state $nodeName <<choice>>\n")
                        addNestedChoiceNodes(handler.targetCondition, sb, visitedConditions)
                    }
                }
            }
        }
    }
    
    /**
     * Recursively add choice nodes for nested conditions
     */
    private fun <T : Any> addNestedChoiceNodes(
        condition: ConditionHandler<T>, 
        sb: StringBuilder, 
        visitedConditions: MutableSet<ConditionHandler<T>>
    ) {
        if (!visitedConditions.add(condition)) {
            return  // Already processed this condition
        }
        
        condition.trueCondition?.let { trueCondition ->
            val nodeName = generateConditionNodeName(trueCondition)
            sb.append("    state $nodeName <<choice>>\n")
            addNestedChoiceNodes(trueCondition, sb, visitedConditions)
        }
        
        condition.falseCondition?.let { falseCondition ->
            val nodeName = generateConditionNodeName(falseCondition)
            sb.append("    state $nodeName <<choice>>\n")
            addNestedChoiceNodes(falseCondition, sb, visitedConditions)
        }
    }
    
    /**
     * Process a condition handler and its branches
     */
    private fun <T : Any> processCondition(
        flow: Flow<T>,
        condition: ConditionHandler<T>,
        choiceNodeName: String,
        sb: StringBuilder,
        visitedStages: MutableSet<Stage>,
        visitedConditions: MutableSet<ConditionHandler<T>>
    ) {
        if (!visitedConditions.add(condition)) {
            return  // Avoid cycles
        }
        
        // Process true branch
        val trueLabel = condition.description
        
        if (condition.trueStage != null) {
            sb.append("    $choiceNodeName --> ${condition.trueStage}: $trueLabel\n")
            processStage(flow, condition.trueStage, sb, visitedStages, visitedConditions)
        } else if (condition.trueCondition != null) {
            val trueChoiceNode = generateConditionNodeName(condition.trueCondition)
            sb.append("    $choiceNodeName --> $trueChoiceNode: $trueLabel\n")
            processCondition(flow, condition.trueCondition, trueChoiceNode, sb, visitedStages, visitedConditions)
        }
        
        // Process false branch
        val falseLabel = "NOT (${condition.description})"
        
        if (condition.falseStage != null) {
            sb.append("    $choiceNodeName --> ${condition.falseStage}: $falseLabel\n")
            processStage(flow, condition.falseStage, sb, visitedStages, visitedConditions)
        } else if (condition.falseCondition != null) {
            val falseChoiceNode = generateConditionNodeName(condition.falseCondition)
            sb.append("    $choiceNodeName --> $falseChoiceNode: $falseLabel\n")
            processCondition(flow, condition.falseCondition, falseChoiceNode, sb, visitedStages, visitedConditions)
        }
    }
    
    /**
     * Generate a unique node name for a condition based on its description
     */
    private fun generateConditionNodeName(condition: ConditionHandler<*>): String {
        return conditionNodeNames.getOrPut(condition) {
            val base = "if_" + condition.description.lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
            val count = (conditionNameCounts[base] ?: 0) + 1
            conditionNameCounts[base] = count
            if (count == 1) base else "${base}_$count"
        }
    }
    
    /**
     * Process a stage and its transitions
     */
    private fun <T : Any> processStage(
        flow: Flow<T>,
        currentStage: Stage,
        sb: StringBuilder,
        visitedStages: MutableSet<Stage>,
        visitedConditions: MutableSet<ConditionHandler<T>>
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
            val choiceNodeName = generateConditionNodeName(conditionHandler)

            // Add transition to choice node
            sb.append("    $currentStage --> $choiceNodeName\n")

            // Process the condition using the helper method
            processCondition(flow, conditionHandler, choiceNodeName, sb, visitedStages, visitedConditions)
        }
        
        // Process event handlers
        stageDefinition.eventHandlers.forEach { (event, handler) ->
            if (handler.targetStage != null) {
                // Event leads to a stage
                sb.append("    $currentStage --> ${handler.targetStage}: onEvent $event\n")
                
                // Process the target stage if not already processed
                if (!visitedStages.contains(handler.targetStage)) {
                    processStage(flow, handler.targetStage, sb, visitedStages, visitedConditions)
                }
            } else if (handler.targetCondition != null) {
                // Event leads to a condition
                val conditionNodeName = generateConditionNodeName(handler.targetCondition)
                sb.append("    $currentStage --> $conditionNodeName: onEvent $event\n")
                
                // Process the target condition
                processCondition(flow, handler.targetCondition, conditionNodeName, sb, visitedStages, visitedConditions)
            }
        }
        
        // Process automatic progression to next stage
        stageDefinition.nextStage?.let { nextStage ->
            sb.append("    $currentStage --> $nextStage\n")
            
            // Process the next stage if not already processed
            if (!visitedStages.contains(nextStage)) {
                processStage(flow, nextStage, sb, visitedStages, visitedConditions)
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