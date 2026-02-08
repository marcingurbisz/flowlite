package io.flowlite.test

import io.flowlite.Flow
import io.flowlite.MermaidGenerator
import org.springframework.beans.factory.getBean
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.system.exitProcess

/** Updates README.md with flow diagrams and code snippets extracted from test files. */

data class FlowSpec(
    val id: String,
    val title: String,
    val source: Path,
    val factory: () -> Flow<*>
)

private val documentedFlows = listOf(
    FlowSpec(
        id = "employee-onboarding",
        title = "Employee Onboarding",
        source = Path.of("test/employeeOnboardingFlowTest.kt"),
        factory = ::createEmployeeOnboardingFlowFromSpring
    ),
    FlowSpec(
        id = "order-confirmation",
        title = "Order Confirmation",
        source = Path.of("test/OrderConfirmationTest.kt"),
        factory = ::createOrderConfirmationFlow
    )
)

private fun createEmployeeOnboardingFlowFromSpring(): Flow<*> {
    val context = startTestApplication()
    return context.use { context ->
        val actions = context.getBean<EmployeeOnboardingActions>()
        createEmployeeOnboardingFlow(actions)
    }
}

fun main() {
    val generator = MermaidGenerator()

    // Build FlowDoc objects for all documented flows
    val allDocs = documentedFlows.mapNotNull { spec ->
        val lines = spec.source.readLines()
        val start = lines.indexOfFirst { it.contains("FLOW-DEFINITION-START") }
        val end = lines.indexOfFirst { it.contains("FLOW-DEFINITION-END") }
        if (start == -1 || end == -1 || end <= start) {
            println("WARN: Could not find definition markers for flow id='${spec.id}' in ${spec.source}")
            null
        } else {
            val codeLines = lines.subList(start + 1, end)
            val code = codeLines.joinToString(System.lineSeparator())
            @Suppress("UNCHECKED_CAST")
            val flow = spec.factory() as Flow<Any>
            val diagram = generator.generateDiagram(spec.id, flow)
            FlowDoc(title = spec.title, diagram = diagram, code = code, id = spec.id)
        }
    }

    val readmePath = Path.of("README.md")
    val content = readmePath.readText()
    val lines = content.lines()

    // Regex for FlowDoc markers: <!-- FlowDoc(id) --> and terminator <!-- FlowDoc.end -->
    val startRegex = Regex("<!--\\s*FlowDoc\\(([^)]+)\\)\\s*-->")
    val endMarker = "<!-- FlowDoc.end -->"

    if (lines.none { it.contains("FlowDoc(") }) {
        println("No FlowDoc markers found; nothing to update.")
        exitProcess(0)
    }

    val updated = StringBuilder()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val match = startRegex.find(line)
        if (match == null) {
            updated.append(line).append('\n')
            i++
            continue
        }

        val rawId = match.groupValues[1].trim()
        updated.append(line).append('\n') // keep the start marker line

        // Skip existing block until end marker
        var j = i + 1
        var foundEnd = false
        while (j < lines.size) {
            if (lines[j].trim() == endMarker) {
                foundEnd = true
                break
            }
            j++
        }
        if (!foundEnd) {
            println("WARN: Missing FlowDoc.end for marker id='$rawId'")
            // Just treat rest as normal text
            i++
            continue
        }

        // Generate replacement block content
        val block = buildBlock(rawId, allDocs)
        updated.append(block)
        updated.append(endMarker).append('\n')
        i = j + 1
    }

    val newContent = updated.toString().trimEnd() + '\n'
    if (newContent != content) {
        Files.writeString(readmePath, newContent)
        println("README updated using FlowDoc markers.")
    } else {
        println("README is up to date.")
    }
}

private fun buildBlock(requestedId: String, docs: List<FlowDoc>): String {
    val newline = System.lineSeparator()
    val builder = StringBuilder()
    val normalizedRequested = normalizeId(requestedId)

    val selected = if (normalizedRequested == "all") {
        docs
    } else {
        val match = docs.find { normalizeId(it.id) == normalizedRequested || normalizeId(it.title) == normalizedRequested }
        if (match == null) emptyList() else listOf(match)
    }

    if (selected.isEmpty()) {
        builder.append("> No flows matched id '$requestedId'\n")
        return builder.toString()
    }

    selected.forEach { doc ->
        if (normalizedRequested == "all") {
            builder.append("### ").append(doc.title).append(newline).append(newline)
        }
        // Code first (matches existing README example), then mermaid diagram
        builder.append("```kotlin").append(newline).append(doc.code).append(newline).append("```").append(newline).append(newline)
        builder.append("```mermaid").append(newline).append(doc.diagram).append(newline).append("```").append(newline).append(newline)
    }
    return builder.toString()
}

private fun normalizeId(id: String): String = id
    .trim()
    .replace(Regex("([a-z0-9])([A-Z])"), "$1-$2") // camelCase -> camel-Case
    .lowercase()
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')

data class FlowDoc(val title: String, val diagram: String, val code: String, val id: String)

