package com.sednium.kryptonide.agent.aif

import com.sednium.kryptonide.agent.tools.AgentToolRegistry
import com.sednium.kryptonide.agent.tools.ToolDefinition
import com.sednium.kryptonide.agent.tools.ToolParam
import com.sednium.kryptonide.agent.tools.ToolResult

class AifProtocols(
    private val memoryManager: AifMemoryManager,
    private val activeProjectId: () -> String
) {
    fun registerAll() {
        AgentToolRegistry.register(
            ToolDefinition(
                name = "aif_recall",
                description = "Recall critical architectural and past cognitive memories for the active project.",
                parameters = emptyList()
            ) { _ ->
                val pid = activeProjectId()
                val recalled = memoryManager.bootRecall(pid)
                ToolResult(
                    success = true,
                    output = recalled.ifBlank { "No prior AIF cognitive memories recorded for project '$pid'." }
                )
            }
        )

        AgentToolRegistry.register(
            ToolDefinition(
                name = "aif_scratch",
                description = "Record volatile task intention before executing multi-file edits or destructive commands.",
                parameters = listOf(
                    ToolParam("intent", "string", "Intended operation and safety invariants")
                )
            ) { args ->
                val intent = args["intent"]?.toString() ?: return@ToolDefinition ToolResult(false, "", "Missing 'intent'")
                memoryManager.recordVolatileScratch(intent)
                ToolResult(true, "Recorded volatile scratch intention.")
            }
        )

        AgentToolRegistry.register(
            ToolDefinition(
                name = "aif_note",
                description = "Commit a persistent cognitive memory block with importance rating (0.0 to 1.0) and summary.",
                parameters = listOf(
                    ToolParam("importance", "number", "Cognitive importance rating between 0.0 and 1.0"),
                    ToolParam("summary", "string", "Concise architectural insight or decision"),
                    ToolParam("tags", "string", "Comma-separated tags (e.g. 'architecture, ui, bugfix')", required = false)
                )
            ) { args ->
                val importance = (args["importance"] as? Number)?.toFloat() ?: 0.5f
                val summary = args["summary"]?.toString() ?: return@ToolDefinition ToolResult(false, "", "Missing 'summary'")
                val tagStr = args["tags"]?.toString() ?: ""
                val tags = tagStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }

                val pid = activeProjectId()
                val committed = memoryManager.commitTurn(pid, importance, summary, tags)
                ToolResult(
                    success = true,
                    output = "Committed memory block [${committed.id.take(8)}] with importance ${committed.importance}."
                )
            }
        )

        AgentToolRegistry.register(
            ToolDefinition(
                name = "aif_query",
                description = "Search cognitive memory by keyword or semantic domain tag.",
                parameters = listOf(
                    ToolParam("keyword", "string", "Keyword to search across project and nexus memory")
                )
            ) { args ->
                val keyword = args["keyword"]?.toString() ?: ""
                val pid = activeProjectId()
                val matches = memoryManager.queryMemory(pid, keyword)

                val formatted = matches.joinToString("\n") { b ->
                    "- [${b.projectId}] (Imp: ${String.format("%.2f", b.importance)}) ${b.summary} (${b.tags.joinToString(", ")})"
                }
                ToolResult(
                    success = true,
                    output = formatted.ifBlank { "No memory blocks matching '$keyword' found." },
                    metadata = mapOf("matchCount" to matches.size)
                )
            }
        )
    }
}
