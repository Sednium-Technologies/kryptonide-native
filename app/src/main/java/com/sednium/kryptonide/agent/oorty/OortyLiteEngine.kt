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
}

object OortyTagParser {

    private val CLOSED_TAG_REGEX = Regex(
        """<(edit|patch|ans|plan|thinking|think|status|aif_note|aif)(?:\s+([^>]*))?>([\s\S]*?)<\/\1>""",
        RegexOption.IGNORE_CASE
    )

    private val ATTR_REGEX = Regex("""([a-zA-Z_]+)="([^"]*)"""")

    fun cleanAnsText(text: String): String {
        return text.replace(Regex("""</?ans>""", RegexOption.IGNORE_CASE), "").trim()
    }

    fun parse(raw: String): List<OortyBlock> {
        val blocks = mutableListOf<OortyBlock>()
        var lastEnd = 0

        for (m in CLOSED_TAG_REGEX.findAll(raw)) {
            if (m.range.first > lastEnd) {
                val between = raw.substring(lastEnd, m.range.first)
                val cleanedBetween = cleanAnsText(between)
                if (cleanedBetween.isNotEmpty()) {
                    blocks += OortyBlock.Ans(cleanedBetween)
                }
            }

            val tagName = m.groupValues[1].lowercase()
            val attrsRaw = m.groupValues[2]
            val content = m.groupValues[3]

            val attrs = ATTR_REGEX.findAll(attrsRaw).associate {
                it.groupValues[1].lowercase() to it.groupValues[2]
            }

            when (tagName) {
                "ans" -> {
                    val cleaned = cleanAnsText(content)
                    if (cleaned.isNotEmpty()) blocks += OortyBlock.Ans(cleaned)
                }
                "plan" -> blocks += OortyBlock.Plan(content.trim())
                "status" -> blocks += OortyBlock.Status(content.trim())
                "edit", "patch" -> {
                    val file = attrs["file"] ?: "modified_file"
                    blocks += OortyBlock.Edit(file, cleanEditContent(content))
                }
                "thinking", "think" -> blocks += OortyBlock.Thinking(content.trim())
                "aif_note", "aif" -> {
                    val importance = attrs["importance"]?.toFloatOrNull() ?: 0.5f
                    val tags = attrs["tags"]?.split(",")?.map { it.trim() } ?: emptyList()
                    blocks += OortyBlock.AifNote(importance, content.trim(), tags)
                }
                else -> {
                    val cleaned = cleanAnsText(content)
                    if (cleaned.isNotEmpty()) blocks += OortyBlock.Ans(cleaned)
                }
            }

            lastEnd = m.range.last + 1
        }

        if (lastEnd < raw.length) {
            val tail = raw.substring(lastEnd)
            blocks.addAll(parseUnclosedTail(tail))
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

    private fun parseUnclosedTail(chunk: String): List<OortyBlock> {
        val trimmed = chunk.trim()
        if (trimmed.isEmpty()) return emptyList()

        // Find earliest unclosed Krypton tag header
        val tagHeaders = listOf("<edit", "<patch", "<thinking", "<think", "<plan", "<status", "<ans")
        val earliest = tagHeaders.map { tag ->
            val idx = chunk.indexOf(tag, ignoreCase = true)
            tag to idx
        }.filter { it.second >= 0 }.minByOrNull { it.second }

        if (earliest != null && earliest.second > 0) {
            val prefix = chunk.substring(0, earliest.second)
            val remainder = chunk.substring(earliest.second)
            val list = mutableListOf<OortyBlock>()
            val cleanedPrefix = cleanAnsText(prefix)
            if (cleanedPrefix.isNotEmpty()) list += OortyBlock.Ans(cleanedPrefix)
            list.addAll(parseUnclosedTail(remainder))
            return list
        }

        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("<ans") -> {
                val headerEnd = trimmed.indexOf('>')
                if (headerEnd != -1) {
                    val cleaned = cleanAnsText(trimmed.substring(headerEnd + 1))
                    if (cleaned.isNotEmpty()) listOf(OortyBlock.Ans(cleaned)) else emptyList()
                } else emptyList()
            }
            lower.startsWith("<plan") -> {
                val headerEnd = trimmed.indexOf('>')
                if (headerEnd != -1) listOf(OortyBlock.Plan(trimmed.substring(headerEnd + 1).trimStart()))
                else listOf(OortyBlock.Plan(""))
            }
            lower.startsWith("<status") -> {
                val headerEnd = trimmed.indexOf('>')
                if (headerEnd != -1) listOf(OortyBlock.Status(trimmed.substring(headerEnd + 1).trimStart()))
                else listOf(OortyBlock.Status(""))
            }
            lower.startsWith("<thinking") -> {
                val headerEnd = trimmed.indexOf('>')
                if (headerEnd != -1) listOf(OortyBlock.Thinking(trimmed.substring(headerEnd + 1).trimStart()))
                else listOf(OortyBlock.Thinking(""))
            }
            lower.startsWith("<think") -> {
                val headerEnd = trimmed.indexOf('>')
                if (headerEnd != -1) listOf(OortyBlock.Thinking(trimmed.substring(headerEnd + 1).trimStart()))
                else listOf(OortyBlock.Thinking(""))
            }
            lower.startsWith("<edit") || lower.startsWith("<patch") -> {
                val headerEnd = trimmed.indexOf('>')
                if (headerEnd != -1) {
                    val header = trimmed.substring(0, headerEnd)
                    val body = trimmed.substring(headerEnd + 1)
                    val fileMatch = Regex("""file="([^"]*)"""", RegexOption.IGNORE_CASE).find(header)
                    val file = fileMatch?.groupValues?.get(1) ?: "file"
                    listOf(OortyBlock.Edit(file, cleanEditContent(body)))
                } else {
                    listOf(OortyBlock.Thinking("Generating file changes..."))
                }
            }
            else -> {
                val cleaned = cleanAnsText(trimmed)
                if (cleaned.isNotEmpty()) listOf(OortyBlock.Ans(cleaned)) else emptyList()
            }
        }
    }

    fun cleanEditContent(raw: String): String {
        var c = raw.trim()
        if (c.startsWith("```")) {
            val firstLineEnd = c.indexOf('\n')
            if (firstLineEnd != -1) c = c.substring(firstLineEnd + 1)
            if (c.endsWith("```")) c = c.dropLast(3)
        }
        if (c.endsWith("</edit>", ignoreCase = true)) {
            c = c.substring(0, c.length - 7)
        }
        if (c.endsWith("</patch>", ignoreCase = true)) {
            c = c.substring(0, c.length - 8)
        }
        return c.trim()
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
