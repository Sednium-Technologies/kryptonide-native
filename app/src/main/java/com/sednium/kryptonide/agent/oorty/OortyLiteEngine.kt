package com.sednium.kryptonide.agent.oorty

import com.sednium.kryptonide.agent.aif.AifMemoryManager
import com.sednium.kryptonide.agent.pocket.ExecutionTier
import com.sednium.kryptonide.agent.pocket.LayaSystemOneEngine
import com.sednium.kryptonide.agent.pocket.RouteType
import com.sednium.kryptonide.agent.tools.AgentToolRegistry
import com.sednium.kryptonide.agent.tools.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

sealed class OortyBlock {
    data class Thinking(val text: String) : OortyBlock()
    data class Plan(val text: String) : OortyBlock()
    data class Ans(val text: String) : OortyBlock()
    data class Status(val text: String) : OortyBlock()
    data class Edit(val file: String, val content: String) : OortyBlock()
    data class AifNote(val importance: Float, val summary: String, val tags: List<String> = emptyList()) : OortyBlock()
    data class ToolCall(val name: String, val arguments: String) : OortyBlock()
}

object OortyTagParser {

    private data class TagSpec(
        val type: String,
        val openTags: List<String>,
        val closeTags: List<String>
    )

    private val TAG_SPECS = listOf(
        TagSpec("think", listOf("think", "thinking"), listOf("think", "thinking")),
        TagSpec("plan", listOf("plan"), listOf("plan")),
        TagSpec("ans", listOf("ans"), listOf("ans")),
        TagSpec("edit", listOf("edit", "patch"), listOf("edit", "patch")),
        TagSpec("status", listOf("status"), listOf("status")),
        TagSpec("aif", listOf("aif_note", "aif"), listOf("aif_note", "aif")),
        TagSpec("tool_call", listOf("tool_call", "needle_action", "function"), listOf("tool_call", "needle_action", "function"))
    )

    private val ATTR_REGEX = Regex("""([a-zA-Z_][a-zA-Z0-9_-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")

    fun cleanAnsText(text: String): String {
        return text.replace(Regex("""</?ans>""", RegexOption.IGNORE_CASE), "").trim()
    }

    fun cleanEditContent(raw: String): String {
        var c = raw.trim()
        if (c.startsWith("```")) {
            val firstLineEnd = c.indexOf('\n')
            if (firstLineEnd != -1) c = c.substring(firstLineEnd + 1)
            if (c.endsWith("```")) c = c.dropLast(3)
        }
        c = c.replace(Regex("""</?(?:edit|patch)>?$""", RegexOption.IGNORE_CASE), "").trim()
        return c
    }

    fun parseAttributes(header: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (m in ATTR_REGEX.findAll(header)) {
            val key = m.groupValues[1].lowercase()
            val value = when {
                m.groupValues[2].isNotEmpty() -> m.groupValues[2]
                m.groupValues[3].isNotEmpty() -> m.groupValues[3]
                else -> m.groupValues[4]
            }
            result[key] = value
        }
        return result
    }

    private fun extractToolNameFromJson(raw: String): String? {
        val trimmed = raw.trim()
        val regex = Regex(""""(?:name|tool|function)"\s*:\s*"([^"]+)"""")
        return regex.find(trimmed)?.groupValues?.get(1)
    }

    private fun extractToolArgsFromJson(raw: String): String {
        val trimmed = raw.trim()
        val regex = Regex(""""(?:arguments|parameters|args)"\s*:\s*(\{[\s\S]*\})""")
        val match = regex.find(trimmed)
        if (match != null) return match.groupValues[1].trim()
        return trimmed
    }

    fun parse(raw: String): List<OortyBlock> {
        if (raw.isBlank()) return emptyList()

        val blocks = mutableListOf<OortyBlock>()
        var cursor = 0
        val len = raw.length

        while (cursor < len) {
            val nextOpen = raw.indexOf('<', cursor)
            if (nextOpen == -1) {
                val tail = raw.substring(cursor)
                val cleaned = cleanAnsText(tail)
                if (cleaned.isNotEmpty()) blocks += OortyBlock.Ans(cleaned)
                break
            }

            // Check if '<' starts one of our recognized tags
            var matchedSpec: TagSpec? = null
            var matchedOpenTag = ""
            var nameEnd = -1

            if (nextOpen + 1 < len && raw[nextOpen + 1] != '/') {
                var idx = nextOpen + 1
                while (idx < len && (raw[idx].isLetterOrDigit() || raw[idx] == '_')) {
                    idx++
                }
                if (idx > nextOpen + 1) {
                    val candidate = raw.substring(nextOpen + 1, idx).lowercase()
                    val spec = TAG_SPECS.firstOrNull { candidate in it.openTags }
                    if (spec != null) {
                        // Ensure followed by whitespace, '>', or end of string
                        if (idx >= len || raw[idx].isWhitespace() || raw[idx] == '>') {
                            matchedSpec = spec
                            matchedOpenTag = candidate
                            nameEnd = idx
                        }
                    }
                }
            }

            if (matchedSpec == null) {
                // Not one of our tags (e.g. HTML <p>, <div> or stray '<')
                cursor = nextOpen + 1
                continue
            }

            // Text between previous cursor and this tag is plain text
            if (nextOpen > cursor) {
                val preText = raw.substring(cursor, nextOpen)
                val cleaned = cleanAnsText(preText)
                if (cleaned.isNotEmpty()) blocks += OortyBlock.Ans(cleaned)
            }

            // Find end of open tag header '>'
            val headerEnd = raw.indexOf('>', nameEnd)
            if (headerEnd == -1) {
                // Incomplete open tag at tail (e.g. "<edit file=\"index.html\"")
                blocks.addAll(handleIncompleteOpenTag(matchedSpec))
                cursor = len
                break
            }

            val headerAttrs = raw.substring(nameEnd, headerEnd)
            val attrs = parseAttributes(headerAttrs)
            val bodyStart = headerEnd + 1

            // Look for matching closing tag from this spec's allowed close tags
            val closeMatch = findMatchingCloseTag(raw, bodyStart, matchedSpec.closeTags)
            if (closeMatch != null) {
                val content = raw.substring(bodyStart, closeMatch.first)
                val block = createBlock(matchedSpec.type, matchedOpenTag, attrs, content)
                if (block != null) blocks += block
                cursor = closeMatch.second + 1
            } else {
                // Unclosed tag at tail (streaming mode)
                val tailContent = raw.substring(bodyStart)
                blocks.addAll(handleUnclosedTagTail(matchedSpec.type, matchedOpenTag, attrs, tailContent))
                cursor = len
                break
            }
        }

        // Consolidate adjacent Ans blocks if any
        val consolidated = mutableListOf<OortyBlock>()
        for (b in blocks) {
            val last = consolidated.lastOrNull()
            if (b is OortyBlock.Ans && last is OortyBlock.Ans) {
                consolidated[consolidated.size - 1] = OortyBlock.Ans(last.text + "\n\n" + b.text)
            } else {
                consolidated += b
            }
        }

        return consolidated.ifEmpty {
            val cleaned = cleanAnsText(raw)
            if (cleaned.isNotBlank()) listOf(OortyBlock.Ans(cleaned)) else emptyList()
        }
    }

    private fun findMatchingCloseTag(
        raw: String,
        startIndex: Int,
        closeTags: List<String>
    ): Pair<Int, Int>? {
        var earliest: Pair<Int, Int>? = null
        for (tag in closeTags) {
            val pattern = Regex("""</\s*${Regex.escape(tag)}\s*>""", RegexOption.IGNORE_CASE)
            val match = pattern.find(raw, startIndex)
            if (match != null) {
                if (earliest == null || match.range.first < earliest.first) {
                    earliest = match.range.first to match.range.last
                }
            }
        }
        return earliest
    }

    private fun createBlock(
        type: String,
        tagName: String,
        attrs: Map<String, String>,
        content: String
    ): OortyBlock? {
        return when (type) {
            "think" -> {
                val trimmed = content.trim()
                if (trimmed.isNotEmpty()) OortyBlock.Thinking(trimmed) else OortyBlock.Thinking("")
            }
            "plan" -> {
                val trimmed = content.trim()
                if (trimmed.isNotEmpty()) OortyBlock.Plan(trimmed) else OortyBlock.Plan("")
            }
            "status" -> {
                val trimmed = content.trim()
                if (trimmed.isNotEmpty()) OortyBlock.Status(trimmed) else OortyBlock.Status("")
            }
            "ans" -> {
                val cleaned = cleanAnsText(content)
                if (cleaned.isNotEmpty()) OortyBlock.Ans(cleaned) else null
            }
            "edit" -> {
                val file = attrs["file"] ?: attrs["path"] ?: attrs["name"] ?: "modified_file"
                OortyBlock.Edit(file, cleanEditContent(content))
            }
            "aif" -> {
                val importance = attrs["importance"]?.toFloatOrNull() ?: 0.5f
                val tags = attrs["tags"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                OortyBlock.AifNote(importance, content.trim(), tags)
            }
            "tool_call" -> {
                val name = attrs["name"] ?: extractToolNameFromJson(content) ?: tagName
                val args = extractToolArgsFromJson(content)
                OortyBlock.ToolCall(name, args)
            }
            else -> {
                val cleaned = cleanAnsText(content)
                if (cleaned.isNotEmpty()) OortyBlock.Ans(cleaned) else null
            }
        }
    }

    private fun handleUnclosedTagTail(
        type: String,
        tagName: String,
        attrs: Map<String, String>,
        content: String
    ): List<OortyBlock> {
        return when (type) {
            "think" -> listOf(OortyBlock.Thinking(content.trimStart()))
            "plan" -> listOf(OortyBlock.Plan(content.trimStart()))
            "status" -> listOf(OortyBlock.Status(content.trimStart()))
            "ans" -> {
                val cleaned = cleanAnsText(content)
                if (cleaned.isNotEmpty()) listOf(OortyBlock.Ans(cleaned)) else emptyList()
            }
            "edit" -> {
                val file = attrs["file"] ?: attrs["path"] ?: attrs["name"] ?: "file"
                listOf(OortyBlock.Edit(file, cleanEditContent(content)))
            }
            "tool_call" -> {
                val name = attrs["name"] ?: extractToolNameFromJson(content) ?: "tool"
                listOf(OortyBlock.ToolCall(name, extractToolArgsFromJson(content)))
            }
            else -> {
                val cleaned = cleanAnsText(content)
                if (cleaned.isNotEmpty()) listOf(OortyBlock.Ans(cleaned)) else emptyList()
            }
        }
    }

    private fun handleIncompleteOpenTag(spec: TagSpec): List<OortyBlock> {
        return when (spec.type) {
            "edit" -> listOf(OortyBlock.Thinking("Generating file changes..."))
            "think" -> listOf(OortyBlock.Thinking(""))
            "plan" -> listOf(OortyBlock.Plan(""))
            "status" -> listOf(OortyBlock.Status(""))
            else -> emptyList()
        }
    }
}

class OortyLiteEngine(
    private val aifMemoryManager: AifMemoryManager
) {
    var executionTier: ExecutionTier = ExecutionTier.SUPERVISED

    suspend fun prepareTurn(
        userPrompt: String,
        projectId: String,
        projectFileTree: String,
        activeFilePath: String?,
        activeFileContent: String?
    ): PreparedTurn {
        // Fast-path routing evaluation
        val routing = LayaSystemOneEngine.route(userPrompt)

        // Boot memory recall from AIF
        val aifContext = aifMemoryManager.bootRecall(projectId)

        // Generate full immutable prompt
        val fullSystemPrompt = SystemPrompt.buildImmutablePrompt(
            projectFileTree = projectFileTree,
            activeFilePath = activeFilePath,
            activeFileContent = activeFileContent,
            aifMemoryRecall = aifContext
        )

        return PreparedTurn(
            routingDecision = routing,
            systemPrompt = fullSystemPrompt,
            requiresDeepReasoning = routing.route == RouteType.DEEP_REASONING
        )
    }

    suspend fun onTurnCompleted(
        userPrompt: String,
        assistantResponse: String,
        projectId: String
    ) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val blocks = OortyTagParser.parse(assistantResponse)

            // Commit any explicit <aif_note> blocks
            for (b in blocks) {
                if (b is OortyBlock.AifNote) {
                    aifMemoryManager.commitTurn(
                        projectId = projectId,
                        importance = b.importance,
                        summary = b.summary,
                        tags = b.tags
                    )
                }
            }

            // Automatic Laya System 1 metadata tagging
            val editBlock = blocks.firstOrNull { it is OortyBlock.Edit } as? OortyBlock.Edit
            val meta = LayaSystemOneEngine.extractAifMetadata(userPrompt, editBlock?.content)
            if (meta.importance >= 0.2f) {
                aifMemoryManager.commitTurn(
                    projectId = projectId,
                    importance = meta.importance,
                    summary = meta.summary,
                    tags = meta.tags
                )
            }
        }.onFailure { err ->
            android.util.Log.w("OortyLiteEngine", "Failed to commit turn memory: ${err.message}")
        }
    }
}

data class PreparedTurn(
    val routingDecision: com.sednium.kryptonide.agent.pocket.RoutingDecision,
    val systemPrompt: String,
    val requiresDeepReasoning: Boolean
)
