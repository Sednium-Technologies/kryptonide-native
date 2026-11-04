package com.sednium.kryptonide.agent.pocket

data class ParsedModelOutput(
    val thinkContent: String?,
    val toolCalls: List<NeedleToolCall>,
    val cleanText: String
)

/**
 * Parser and ChatML prompt formatter for MiniCPM5-1B-Claude-Opus-Fable5-V2-Thinking.
 * Supports <think>...</think> reasoning traces, XML-based tool call blocks (<function name="...">
 * <param name="...">...</param></function>), and CDATA encapsulated arguments.
 */
object MiniCpmToolParser {

    private val THINK_REGEX = Regex("""(?s)<think>(.*?)(?:</think>|$)""")
    private val FUNCTION_REGEX = Regex("""(?s)<function\s+name="([^"]+)"\s*>(.*?)</function>""")
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

    fun parse(rawText: String): ParsedModelOutput {
        // 1. Extract think content
        var think: String? = null
        val thinkMatch = THINK_REGEX.find(rawText)
        if (thinkMatch != null) {
            think = thinkMatch.groupValues[1].trim()
        }

        // 2. Extract function calls
        val toolCalls = mutableListOf<NeedleToolCall>()
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

        // 3. Clean remaining text by stripping think and function blocks
        var clean = rawText
        clean = THINK_REGEX.replace(clean, "")
        clean = FUNCTION_REGEX.replace(clean, "")
        clean = clean.trim()

        return ParsedModelOutput(
            thinkContent = think,
            toolCalls = toolCalls,
            cleanText = clean
        )
    }

    /**
     * Formats prompt matching MiniCPM5's native Jinja template specifications:
     * System -> # Tools -> <tools> -> Instructions -> User -> Assistant -> <think>
     */
    fun formatPromptWithTools(
        systemInstruction: String,
        userQuery: String,
        history: List<Pair<String, String>> = emptyList(), // role, content
        toolsJson: String = DEFAULT_TOOLS_JSON
    ): String = buildString {
        append("<|im_start|>system\n")
        append(systemInstruction.trim())
        append("\n\n# Tools\n\n")
        append("You are provided with function signatures within <tools></tools> XML tags:\n")
        append("<tools>\n")
        append(toolsJson.trim())
        append("\n</tools>\n\n")
        append("Tool usage guidelines:\n")
        append("- You may call zero or more functions. If no function calls are needed, answer normally.\n")
        append("- When calling a function, return an XML object within <function ...</function> using:\n")
        append("<function name=\"function-name\"><param name=\"param-name\">param-value</param></function>\n")
        append("- Multi-line or special characters MUST be wrapped in CDATA: <param name=\"param-name\"><![CDATA[...]]></param>\n")
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
