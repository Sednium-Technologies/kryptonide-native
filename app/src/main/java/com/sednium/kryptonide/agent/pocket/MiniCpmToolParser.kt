package com.sednium.kryptonide.agent.pocket

import com.sednium.kryptonide.agent.tools.AgentToolRegistry
import kotlinx.serialization.json.*

data class ParsedModelOutput(
    val thinkContent: String?,
    val toolCalls: List<NeedleToolCall>,
    val cleanText: String
)

/**
 * Native parser and ChatML prompt formatter for MiniCPM (e.g. MiniCPM5-1B Thinking).
 * Supports:
 * - Special tags: <|im_start|>, <|im_end|>, <think>...</think>, <thinking>...</thinking>
 * - XML tool calling: <function name="..."><param name="...">...</param></function> with CDATA
 * - Standard JSON tool calls: <tool_call>{"name":"...","arguments":{...}}</tool_call>
 * - Dynamic system prompt and dynamic tool registry (zero hardcoding)
 */
object MiniCpmToolParser {

    private val THINK_REGEX = Regex("""(?s)<(?:think|thinking)>(.*?)(?:</(?:think|thinking)>|$)""")
    private val FUNCTION_REGEX = Regex("""(?s)<function\s+name="([^"]+)"\s*>(.*?)</function>""")
    private val TOOL_CALL_REGEX = Regex("""(?s)<tool_call>\s*(\{.*?\})\s*</tool_call>""")
    private val PARAM_REGEX = Regex("""(?s)<param\s+name="([^"]+)"\s*>(.*?)</param>""")
    private val CDATA_REGEX = Regex("""(?s)<!\[CDATA\[(.*?)\]\]>""")

    const val DEFAULT_TOOLS_JSON = """
[
  {"type":"function","function":{"name":"read_file","description":"Read file contents with line numbers. Use line numbers for edit_lines.","parameters":{"type":"object","properties":{"path":{"type":"string","description":"Relative file path"},"start_line":{"type":"integer","description":"Optional start line (1-indexed)"},"end_line":{"type":"integer","description":"Optional end line (1-indexed, inclusive)"}},"required":["path"]}}},
  {"type":"function","function":{"name":"edit_lines","description":"Replace a range of lines in a file.","parameters":{"type":"object","properties":{"path":{"type":"string"},"start_line":{"type":"integer"},"end_line":{"type":"integer"},"new_content":{"type":"string"}},"required":["path","start_line","end_line","new_content"]}}},
  {"type":"function","function":{"name":"insert_lines","description":"Insert content before a line without removing existing lines.","parameters":{"type":"object","properties":{"path":{"type":"string"},"at_line":{"type":"integer"},"content":{"type":"string"}},"required":["path","at_line","content"]}}},
  {"type":"function","function":{"name":"patch_file","description":"Search and replace an exact code block.","parameters":{"type":"object","properties":{"path":{"type":"string"},"search_string":{"type":"string"},"replace_string":{"type":"string"}},"required":["path","search_string","replace_string"]}}},
  {"type":"function","function":{"name":"write_new_file","description":"Create a new file with content.","parameters":{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}}},
  {"type":"function","function":{"name":"create_directory","description":"Create a directory recursively.","parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}}
]"""

    /**
     * Dynamically builds tool definitions from the registered agent tools.
     * Uses registered tools at runtime with zero static tool arrays.
     */
    fun getDynamicToolsJson(): String {
        val dynamic = AgentToolRegistry.getToolsForPrompt()
        if (dynamic.isNotBlank() && dynamic != "[]") {
            return dynamic
        }
        return DEFAULT_TOOLS_JSON
    }

    fun parse(rawText: String): ParsedModelOutput {
        // 1. Extract think content (<think>...</think> or <thinking>...</thinking>)
        var think: String? = null
        val thinkMatch = THINK_REGEX.find(rawText)
        if (thinkMatch != null) {
            think = thinkMatch.groupValues[1].trim()
        }

        // 2. Extract tool calls (supports both XML <function> and <tool_call> formats)
        val toolCalls = mutableListOf<NeedleToolCall>()

        // 2a. XML <function name="..."><param name="...">...</param></function>
        val functionMatches = FUNCTION_REGEX.findAll(rawText).toList()
        for (fn in functionMatches) {
            val functionName = fn.groupValues[1].trim()
            val innerBody = fn.groupValues[2]

            val arguments = mutableMapOf<String, Any>()
            val paramMatches = PARAM_REGEX.findAll(innerBody)

            for (p in paramMatches) {
                val paramName = p.groupValues[1].trim()
                var rawValue = p.groupValues[2].trim()

                // Check for CDATA wrapper
                val cdataMatch = CDATA_REGEX.find(rawValue)
                if (cdataMatch != null) {
                    rawValue = cdataMatch.groupValues[1]
                }

                arguments[paramName] = rawValue
            }

            toolCalls.add(
                NeedleToolCall(
                    toolName = functionName,
                    arguments = arguments,
                    confidence = 0.98f
                )
            )
        }

        // 2b. Standard <tool_call>{"name":"...","arguments":{...}}</tool_call>
        val toolCallMatches = TOOL_CALL_REGEX.findAll(rawText).toList()
        for (tc in toolCallMatches) {
            val jsonStr = tc.groupValues[1].trim()
            runCatching {
                val element = Json.parseToJsonElement(jsonStr).jsonObject
                val fnName = element["name"]?.jsonPrimitive?.content ?: ""
                val argsObj = element["arguments"]?.jsonObject
                val argsMap = mutableMapOf<String, Any>()
                argsObj?.forEach { (k, v) ->
                    argsMap[k] = v.jsonPrimitive.content
                }
                if (fnName.isNotBlank()) {
                    toolCalls.add(
                        NeedleToolCall(
                            toolName = fnName,
                            arguments = argsMap,
                            confidence = 0.98f
                        )
                    )
                }
            }
        }

        // 3. Clean remaining text by stripping think, function, and tool_call blocks
        var clean = rawText
        clean = THINK_REGEX.replace(clean, "")
        clean = FUNCTION_REGEX.replace(clean, "")
        clean = TOOL_CALL_REGEX.replace(clean, "")
        clean = clean.trim()

        return ParsedModelOutput(
            thinkContent = think,
            toolCalls = toolCalls,
            cleanText = clean
        )
    }

    /**
     * Formats prompt matching MiniCPM's native ChatML specifications:
     * System -> (# Tools -> <tools> if tools available) -> Instructions -> History -> User -> Assistant -> <think>
     */
    fun formatPromptWithTools(
        systemInstruction: String,
        userQuery: String,
        history: List<Pair<String, String>> = emptyList(), // role, content
        toolsJson: String? = null
    ): String = buildString {
        append("<|im_start|>system\n")
        append(systemInstruction.trim())

        val effectiveTools = toolsJson ?: getDynamicToolsJson()
        if (effectiveTools.isNotBlank() && effectiveTools != "[]") {
            append("\n\n# Tools\n\n")
            append("You are provided with function signatures within <tools></tools> XML tags:\n")
            append("<tools>\n")
            append(effectiveTools.trim())
            append("\n</tools>\n\n")
            append("Tool usage guidelines:\n")
            append("- You may call zero or more functions. If no function calls are needed, answer normally.\n")
            append("- When calling a function, return an XML object within <function ...</function> using:\n")
            append("<function name=\"function-name\"><param name=\"param-name\">param-value</param></function>\n")
            append("- Multi-line or special characters MUST be wrapped in CDATA: <param name=\"param-name\"><![CDATA[...]]></param>\n")
        }
        append("<|im_end|>\n")

        for ((role, content) in history) {
            append("<|im_start|>$role\n")
            append(content.trim())
            append("<|im_end|>\n")
        }

        append("<|im_start|>user\n")
        append(userQuery.trim())
        append("<|im_end|>\n")
        append("<|im_start|>assistant\n<think>\n")
    }
}
