package com.sednium.kryptonide.agent.tools

import kotlinx.serialization.json.*

data class ToolParam(
    val name: String,
    val type: String, // "string", "integer", "boolean", "number", "array", "object"
    val description: String,
    val required: Boolean = true,
    val enumValues: List<String>? = null
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParam>,
    val handler: suspend (Map<String, Any?>) -> ToolResult
)

data class ToolResult(
    val success: Boolean,
    val output: String,
    val error: String? = null,
    val exitCode: Int = 0,
    val metadata: Map<String, Any?> = emptyMap()
)

object AgentToolRegistry {
    private val tools = mutableMapOf<String, ToolDefinition>()

    fun register(tool: ToolDefinition) {
        tools[tool.name] = tool
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun getTool(name: String): ToolDefinition? = tools[name]

    fun listTools(): List<ToolDefinition> = tools.values.toList()

    suspend fun dispatch(name: String, args: Map<String, Any?>): ToolResult {
        val tool = tools[name] ?: return ToolResult(
            success = false,
            output = "",
            error = "Unknown tool '$name'. Available tools: ${tools.keys.joinToString(", ")}",
            exitCode = 127
        )
        return try {
            tool.handler(args)
        } catch (e: Throwable) {
            ToolResult(
                success = false,
                output = "",
                error = "Tool '$name' failed with exception: ${e.message}",
                exitCode = 1
            )
        }
    }

    /**
     * Converts registered tools into OpenAI-compatible tool specifications
     */
    fun toOpenAiToolList(): List<Map<String, Any>> {
        return tools.values.map { tool ->
            val properties = mutableMapOf<String, Any>()
            val requiredList = mutableListOf<String>()

            for (p in tool.parameters) {
                val prop = mutableMapOf<String, Any>(
                    "type" to p.type,
                    "description" to p.description
                )
                if (p.enumValues != null) {
                    prop["enum"] = p.enumValues
                }
                properties[p.name] = prop
                if (p.required) {
                    requiredList.add(p.name)
                }
            }

            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to tool.name,
                    "description" to tool.description,
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to properties,
                        "required" to requiredList
                    )
                )
            )
        }
    }

    /**
     * Converts registered tools into Gemini function declarations
     */
    fun toGeminiFunctionDeclarations(): List<Map<String, Any>> {
        return tools.values.map { tool ->
            val properties = mutableMapOf<String, Any>()
            val requiredList = mutableListOf<String>()

            for (p in tool.parameters) {
                val prop = mutableMapOf<String, Any>(
                    "type" to p.type.uppercase(),
                    "description" to p.description
                )
                if (p.enumValues != null) {
                    prop["enum"] = p.enumValues
                }
                properties[p.name] = prop
                if (p.required) {
                    requiredList.add(p.name)
                }
            }

            mapOf(
                "name" to tool.name,
                "description" to tool.description,
                "parameters" to mapOf(
                    "type" to "OBJECT",
                    "properties" to properties,
                    "required" to requiredList
                )
            )
        }
    }

    /**
     * Generates standard system prompt documentation of all tools
     */
    fun generatePromptDocumentation(): String {
        return buildString {
            appendLine("### Available Agent Tools")
            for (t in tools.values) {
                appendLine("- **${t.name}**: ${t.description}")
                append("  Parameters: ")
                if (t.parameters.isEmpty()) {
                    appendLine("none")
                } else {
                    appendLine(t.parameters.joinToString(", ") { "${it.name}: ${it.type}${if (!it.required) "?" else ""}" })
                }
            }
        }
    }
}
