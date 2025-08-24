package io.flowlite.test

import io.flowlite.api.Flow
import io.flowlite.api.MermaidGenerator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.readText

/** Updates README.md with flow diagrams and code snippets extracted from test files. */

data class FlowSpec(
    val id: String,
    val title: String,
    val source: Path,
    val factory: () -> Flow<*>
)

private val documentedFlows = listOf(
    FlowSpec(
        id = "pizza-order",
        title = "Pizza Order",
        source = Path.of("test/pizzaOrderFlowTest.kt"),
        factory = ::createPizzaOrderFlow
    ),
    FlowSpec(
        id = "employee-onboarding",
        title = "Employee Onboarding",
        source = Path.of("test/employeeOnboardingFlowTest.kt"),
        factory = ::createEmployeeOnboardingFlow
    ),
    FlowSpec(
        id = "order-confirmation",
        title = "Order Confirmation",
        source = Path.of("test/OrderConfirmationTest.kt"),
        factory = ::createOrderConfirmationFlow
    )
)

fun main() {
    val generator = MermaidGenerator()
    val flows = mutableListOf<FlowDoc>()

    documentedFlows.forEach { spec ->
        val lines = spec.source.readLines()
        val start = lines.indexOfFirst { it.contains("FLOW-DEFINITION-START") }
        val end = lines.indexOfFirst { it.contains("FLOW-DEFINITION-END") }
        if (start != -1 && end != -1 && end > start) {
            val codeLines = lines.subList(start + 1, end)
            val code = codeLines.joinToString(System.lineSeparator())
            @Suppress("UNCHECKED_CAST")
            val flow = spec.factory() as Flow<Any>
            val diagram = generator.generateDiagram(spec.id, flow)
            flows += FlowDoc(title = spec.title, diagram = diagram, code = code)
        }
    }

    val readmePath = Path.of("README.md")
    val startMarker = "<!-- FLOW-DOCS-START -->"
    val endMarker = "<!-- FLOW-DOCS-END -->"
    val content = readmePath.readText()
    val startIdx = content.indexOf(startMarker)
    val endIdx = content.indexOf(endMarker)
    if (startIdx == -1 || endIdx == -1 || endIdx < startIdx) {
        println("README markers not found")
        return
    }
    val before = content.substring(0, startIdx + startMarker.length)
    val after = content.substring(endIdx)
    val newline = System.lineSeparator()
    val builder = StringBuilder()
    flows.forEach { doc ->
        builder.append(newline).append(newline).append("### ").append(doc.title).append(newline).append(newline)
        builder.append("```mermaid").append(newline).append(doc.diagram).append(newline).append("```").append(newline).append(newline)
        builder.append("```kotlin").append(newline).append(doc.code).append(newline).append("```").append(newline)
    }
    val newContent = before + builder.toString() + after
    if (newContent != content) {
        Files.writeString(readmePath, newContent)
    } else {
        println("README is up to date")
    }
}

data class FlowDoc(val title: String, val diagram: String, val code: String)

