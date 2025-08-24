package io.flowlite.test

import io.flowlite.api.Flow
import io.flowlite.api.MermaidGenerator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.readText

/** Updates README.md with flow diagrams and code snippets extracted from test files. */
fun main() {
    val testDir = Path.of("test")
    val generator = MermaidGenerator()
    val flows = mutableListOf<FlowDoc>()

    Files.list(testDir).use { paths ->
        paths.filter { it.toString().endsWith("Test.kt") }.forEach { path ->
            val lines = path.readLines()
            val start = lines.indexOfFirst { it.contains("FLOW-DEFINITION-START") }
            val end = lines.indexOfFirst { it.contains("FLOW-DEFINITION-END") }
            if (start != -1 && end != -1 && end > start) {
                val codeLines = lines.subList(start + 1, end)
                val code = codeLines.joinToString(System.lineSeparator())
                val funName = Regex("""fun\s+(\w+)""").find(code)?.groupValues?.get(1) ?: return@forEach
                val fileClass = path.fileName.toString().substringBeforeLast('.')
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } + "Kt"
                val fqcn = "io.flowlite.test.$fileClass"
                val clazz = Class.forName(fqcn)
                val method = clazz.getDeclaredMethod(funName)
                @Suppress("UNCHECKED_CAST")
                val flow = method.invoke(null) as Flow<Any>
                val flowId = funName.removePrefix("create").removeSuffix("Flow")
                    .replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()
                val diagram = generator.generateDiagram(flowId, flow)
                flows += FlowDoc(title = toTitle(flowId), diagram = diagram, code = code)
            }
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

private fun toTitle(id: String): String =
    id.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

