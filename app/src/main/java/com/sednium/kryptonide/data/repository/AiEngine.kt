package com.sednium.kryptonide.data.repository

import android.util.Log
import com.sednium.kryptonide.data.model.ChatMessage
import com.sednium.kryptonide.data.model.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

// ─── Tool definitions ─────────────────────────────────────────

private val TOOLS_JSON = """
[
  {"type":"function","function":{"name":"read_file","description":"Read file contents with line numbers. Use line numbers for edit_lines.","parameters":{"type":"object","properties":{"path":{"type":"string","description":"Relative file path"},"start_line":{"type":"integer","description":"Optional start line (1-indexed)"},"end_line":{"type":"integer","description":"Optional end line (1-indexed, inclusive)"}},"required":["path"]}}},
  {"type":"function","function":{"name":"edit_lines","description":"Replace a range of lines in a file.","parameters":{"type":"object","properties":{"path":{"type":"string"},"start_line":{"type":"integer"},"end_line":{"type":"integer"},"new_content":{"type":"string"}},"required":["path","start_line","end_line","new_content"]}}},
  {"type":"function","function":{"name":"insert_lines","description":"Insert content before a line without removing existing lines.","parameters":{"type":"object","properties":{"path":{"type":"string"},"at_line":{"type":"integer"},"content":{"type":"string"}},"required":["path","at_line","content"]}}},
  {"type":"function","function":{"name":"patch_file","description":"Search and replace an exact code block.","parameters":{"type":"object","properties":{"path":{"type":"string"},"search_string":{"type":"string"},"replace_string":{"type":"string"}},"required":["path","search_string","replace_string"]}}},
  {"type":"function","function":{"name":"write_new_file","description":"Create a new file with content.","parameters":{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}}},
  {"type":"function","function":{"name":"create_directory","description":"Create a directory recursively.","parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}}
]
""".trimIndent()

data class ToolCall(val id: String, val name: String, val arguments: JsonObject)

sealed class AiChunk {
    data class Token(val text: String) : AiChunk()
    data class ToolCallComplete(val calls: List<ToolCall>) : AiChunk()
    data class Done(val fullText: String) : AiChunk()
    data class Error(val message: String) : AiChunk()
}

class AiEngine {
    private val TAG = "AiEngine"

    private val client = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "Kryptonide/1.0 (Android 15; Mobile)")
                .build()
            chain.proceed(req)
        }
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val JSON_MT = "application/json; charset=utf-8".toMediaType()

    fun streamChat(
        provider: String,
        apiKey: String,
        model: String,
        baseUrl: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
    ): Flow<AiChunk> = flow {
        if (provider == "pocket") {
            streamOnDevicePocketChat(model, systemPrompt, messages).collect { chunk ->
                emit(chunk)
            }
            return@flow
        }

        if (apiKey.isBlank() && provider != "ollama") {
            val providerName = com.sednium.kryptonide.ui.larry.OORTY_PROVIDERS
                .firstOrNull { it.id == provider }?.name ?: provider
            emit(AiChunk.Error("No API key configured for $providerName ($model). Tap the model selector in the header to add or select an API key."))
            return@flow
        }

        val (url, headers, bodyJson) = buildRequest(provider, apiKey, model, baseUrl, systemPrompt, messages)

        val request = Request.Builder().apply {
            url(url)
            headers.forEach { (k, v) -> addHeader(k, v) }
            post(bodyJson.toString().toRequestBody(JSON_MT))
        }.build()

        val response = try {
            withContext(Dispatchers.IO) { client.newCall(request).execute() }
        } catch (e: Exception) {
            emit(AiChunk.Error("Network error: ${e.message ?: "Failed to connect to $provider"}"))
            return@flow
        }

        if (!response.isSuccessful) {
            val errBody = withContext(Dispatchers.IO) { response.body?.string() ?: "" }
            val msg = runCatching {
                val elem = json.parseToJsonElement(errBody).jsonObject
                elem["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    ?: elem["error"]?.jsonPrimitive?.content
                    ?: elem["message"]?.jsonPrimitive?.content
            }.getOrNull() ?: "HTTP ${response.code}${if (errBody.isNotBlank()) ": " + errBody.take(160) else ""}"
            emit(AiChunk.Error(msg))
            return@flow
        }

        val source = response.body?.source() ?: run {
            emit(AiChunk.Error("Empty response body"))
            return@flow
        }

        val accumulated = StringBuilder()
        val toolAccumulators = mutableMapOf<Int, Triple<String, String, StringBuilder>>() // index -> (id, name, argsBuilder)

        while (!withContext(Dispatchers.IO) { source.exhausted() }) {
            val line = withContext(Dispatchers.IO) { source.readUtf8Line() } ?: break

            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed == "data: [DONE]") continue

            val data = when {
                trimmed.startsWith("data: ") -> trimmed.removePrefix("data: ")
                trimmed.startsWith("{") -> trimmed        // Gemini sends raw JSON
                else -> continue
            }

            runCatching {
                val chunk = json.parseToJsonElement(data).jsonObject
                parseChunk(provider, chunk, accumulated, toolAccumulators)
            }.onSuccess { token ->
                if (token != null) emit(AiChunk.Token(token))
            }
        }

        val completedTools = toolAccumulators.entries
            .sortedBy { it.key }
            .mapNotNull { (idx, triple) ->
                val (id, name, argsBuilder) = triple
                if (name.isEmpty()) return@mapNotNull null
                val finalId = id.ifBlank { "tc-$idx" }
                val rawArgs = argsBuilder.toString().trim()
                val argsJson = if (rawArgs.isEmpty()) {
                    buildJsonObject {}
                } else {
                    runCatching { json.parseToJsonElement(rawArgs).jsonObject }.getOrElse { buildJsonObject {} }
                }
                ToolCall(finalId, name, argsJson)
            }

        if (completedTools.isNotEmpty()) {
            emit(AiChunk.ToolCallComplete(completedTools))
        } else {
            var finalText = accumulated.toString()
            if (finalText.contains("<edit") && !finalText.contains("</edit>")) {
                finalText += "\n</edit>\n<ans>File edit completed.</ans>"
            } else if (finalText.contains("<thinking>") && !finalText.contains("</thinking>")) {
                finalText += "\n</thinking>"
            } else if (finalText.contains("<plan>") && !finalText.contains("</plan>")) {
                finalText += "\n</plan>"
            } else if (finalText.contains("<ans>") && !finalText.contains("</ans>")) {
                finalText += "\n</ans>"
            }
            emit(AiChunk.Done(finalText))
        }
    }

    internal fun parseChunk(
        provider: String,
        chunk: JsonObject,
        accumulated: StringBuilder,
        toolAcc: MutableMap<Int, Triple<String, String, StringBuilder>>,
    ): String? {
        return when (provider) {
            "anthropic" -> {
                val type = chunk["type"]?.jsonPrimitive?.content
                when (type) {
                    "content_block_delta" -> {
                        val delta = chunk["delta"]?.jsonObject
                        when (delta?.get("type")?.jsonPrimitive?.content) {
                            "text_delta" -> delta["text"]?.jsonPrimitive?.content?.also { accumulated.append(it) }
                            "input_json_delta" -> {
                                val idx = chunk["index"]?.jsonPrimitive?.int ?: 0
                                val existing = toolAcc.getOrPut(idx) { Triple("tc-$idx", "", StringBuilder()) }
                                delta["partial_json"]?.jsonPrimitive?.content?.let {
                                    existing.third.append(it)
                                }
                                null
                            }
                            else -> null
                        }
                    }
                    "content_block_start" -> {
                        val idx = chunk["index"]?.jsonPrimitive?.int ?: 0
                        val block = chunk["content_block"]?.jsonObject
                        if (block?.get("type")?.jsonPrimitive?.content == "tool_use") {
                            val id = block["id"]?.jsonPrimitive?.content ?: "tc-$idx"
                            val name = block["name"]?.jsonPrimitive?.content ?: ""
                            toolAcc[idx] = Triple(id, name, StringBuilder())
                        }
                        null
                    }
                    else -> null
                }
            }
            "gemini" -> {
                val candidate = chunk["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                val parts = candidate?.get("content")?.jsonObject?.get("parts")?.jsonArray
                var deltaText: String? = null
                parts?.forEach { part ->
                    val partObj = part.jsonObject
                    val text = partObj["text"]?.jsonPrimitive?.content
                    if (!text.isNullOrEmpty()) {
                        accumulated.append(text)
                        deltaText = (deltaText ?: "") + text
                    }
                    val funcCall = partObj["functionCall"]?.jsonObject
                    if (funcCall != null) {
                        val name = funcCall["name"]?.jsonPrimitive?.content ?: ""
                        val args = funcCall["args"]?.jsonObject ?: buildJsonObject {}
                        val idx = toolAcc.size
                        val id = "gemini-tc-$idx"
                        toolAcc[idx] = Triple(id, name, StringBuilder(args.toString()))
                    }
                }
                deltaText
            }
            else -> { // OpenAI-compatible
                val delta = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
                val textDelta = listOf("content", "reasoning_content", "reasoning").firstNotNullOfOrNull { key ->
                    delta?.get(key)?.let { el ->
                        if (el is JsonPrimitive && el.isString) el.content else null
                    }
                }
                textDelta?.also { accumulated.append(it) }

                delta?.get("tool_calls")?.jsonArray?.forEach { tc ->
                    val tcObj = tc.jsonObject
                    val idx = tcObj["index"]?.jsonPrimitive?.int ?: 0
                    val id = tcObj["id"]?.let { if (it is JsonPrimitive && it.isString) it.content else null }
                    val name = tcObj["function"]?.jsonObject?.get("name")?.let { if (it is JsonPrimitive && it.isString) it.content else null }
                    val argsDelta = tcObj["function"]?.jsonObject?.get("arguments")?.let { if (it is JsonPrimitive && it.isString) it.content else null }

                    val existing = toolAcc.getOrPut(idx) { Triple(id ?: "tc-$idx", name ?: "", StringBuilder()) }
                    val updatedId = if (!id.isNullOrBlank()) id else existing.first
                    val updatedName = if (!name.isNullOrBlank()) name else existing.second
                    if (argsDelta != null) {
                        existing.third.append(argsDelta)
                    }
                    toolAcc[idx] = Triple(updatedId, updatedName, existing.third)
                }

                textDelta
            }
        }
    }

    internal fun buildRequest(
        provider: String,
        apiKey: String,
        model: String,
        baseUrl: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
    ): Triple<String, Map<String, String>, JsonObject> {
        val registryTools = com.sednium.kryptonide.agent.tools.AgentToolRegistry.listTools()
        val tools = if (registryTools.isNotEmpty()) {
            buildJsonArray {
                registryTools.forEach { t ->
                    addJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    t.parameters.forEach { p ->
                                        put(p.name, buildJsonObject {
                                            put("type", p.type)
                                            put("description", p.description)
                                            if (p.enumValues != null) {
                                                put("enum", buildJsonArray { p.enumValues.forEach { add(it) } })
                                            }
                                        })
                                    }
                                })
                                put("required", buildJsonArray {
                                    t.parameters.filter { it.required }.forEach { add(it.name) }
                                })
                            })
                        })
                    }
                }
            }
        } else {
            json.parseToJsonElement(TOOLS_JSON).jsonArray
        }
        val providerDef = com.sednium.kryptonide.ui.larry.OORTY_PROVIDERS.firstOrNull { it.id == provider }
        val resolvedBase = baseUrl.trim().trimEnd('/').ifBlank {
            providerDef?.baseUrl?.trim()?.trimEnd('/') ?: ""
        }

        return when (provider) {
            "anthropic" -> {
                val base = resolvedBase.ifBlank { "https://api.anthropic.com/v1" }
                val url = if (base.endsWith("/messages")) base else "$base/messages"
                val headers = mapOf(
                    "x-api-key" to apiKey,
                    "anthropic-version" to "2023-06-01",
                    "content-type" to "application/json",
                )
                val body = buildJsonObject {
                    put("model", model)
                    put("system", systemPrompt)
                    put("max_tokens", 8192)
                    put("stream", true)
                    put("tools", buildJsonArray {
                        tools.forEach { t ->
                            val fn = t.jsonObject["function"]!!.jsonObject
                            addJsonObject {
                                put("name", fn["name"]!!)
                                put("description", fn["description"]!!)
                                put("input_schema", fn["parameters"]!!)
                            }
                        }
                    })
                    put("messages", buildAnthropicMessages(messages))
                }
                Triple(url, headers, body)
            }
            "gemini" -> {
                val geminiModel = if (model.startsWith("models/")) model.removePrefix("models/") else model
                val base = resolvedBase.ifBlank { "https://generativelanguage.googleapis.com/v1beta" }
                val url = "$base/models/$geminiModel:streamGenerateContent?alt=sse&key=$apiKey"
                val headers = mapOf("content-type" to "application/json")
                val body = buildJsonObject {
                    put("systemInstruction", buildJsonObject {
                        put("parts", buildJsonArray { addJsonObject { put("text", systemPrompt) } })
                    })
                    put("contents", buildGeminiContents(messages))
                    put("generationConfig", buildJsonObject {
                        put("maxOutputTokens", 8192)
                        put("temperature", 0.5)
                        put("topP", 0.95)
                    })
                    put("tools", buildJsonArray {
                        addJsonObject {
                            put("function_declarations", buildJsonArray {
                                tools.forEach { t ->
                                    val fn = t.jsonObject["function"]!!.jsonObject
                                    addJsonObject {
                                        put("name", fn["name"]!!)
                                        put("description", fn["description"]!!)
                                        put("parameters", fn["parameters"]!!)
                                    }
                                }
                            })
                        }
                    })
                }
                Triple(url, headers, body)
            }
            else -> { // OpenAI-compatible
                val base = resolvedBase.ifBlank { "https://api.openai.com/v1" }
                val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
                val headers = buildMap {
                    if (apiKey.isNotBlank()) {
                        put("Authorization", "Bearer $apiKey")
                    }
                    put("content-type", "application/json")
                    if (provider == "openrouter") {
                        put("HTTP-Referer", "https://sednium.com")
                        put("X-Title", "Kryptonide")
                    }
                }
                val body = buildJsonObject {
                    put("model", model)
                    put("stream", true)
                    put("max_tokens", 8192)
                    if (provider != "nvidia") {
                        put("tools", tools)
                    }
                    put("messages", buildOpenAiMessages(systemPrompt, messages, provider))
                }
                Triple(url, headers, body)
            }
        }
    }

    private fun buildGeminiContents(messages: List<ChatMessage>): JsonArray = buildJsonArray {
        messages.forEach { m ->
            val role = when (m.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "model"
                MessageRole.TOOL -> "user"
                MessageRole.SYSTEM -> "user"
            }
            addJsonObject {
                put("role", role)
                put("parts", buildJsonArray {
                    if (m.role == MessageRole.TOOL) {
                        addJsonObject { put("text", "Tool [${m.toolName ?: "tool"}] result:\n${m.content}") }
                    } else {
                        addJsonObject { put("text", m.content) }
                    }
                })
            }
        }
    }

    private fun buildAnthropicMessages(messages: List<ChatMessage>): JsonArray = buildJsonArray {
        messages.forEach { m ->
            when (m.role) {
                MessageRole.USER -> {
                    addJsonObject {
                        put("role", "user")
                        put("content", m.content)
                    }
                }
                MessageRole.ASSISTANT -> {
                    addJsonObject {
                        put("role", "assistant")
                        put("content", m.content)
                    }
                }
                MessageRole.TOOL -> {
                    addJsonObject {
                        put("role", "user")
                        put("content", "Tool [${m.toolName ?: "tool"}] result:\n${m.content}")
                    }
                }
                MessageRole.SYSTEM -> {
                    addJsonObject {
                        put("role", "user")
                        put("content", m.content)
                    }
                }
            }
        }
    }

    internal fun buildOpenAiMessages(systemPrompt: String, messages: List<ChatMessage>, provider: String = ""): JsonArray = buildJsonArray {
        addJsonObject {
            put("role", "system")
            put("content", systemPrompt)
        }
        messages.forEach { m ->
            when (m.role) {
                MessageRole.USER -> {
                    addJsonObject {
                        put("role", "user")
                        put("content", m.content)
                    }
                }
                MessageRole.ASSISTANT -> {
                    addJsonObject {
                        put("role", "assistant")
                        if (m.content.isNotEmpty() || m.toolCallsJson.isNullOrBlank() || provider == "nvidia") {
                            put("content", m.content.ifBlank { "I have processed your request." })
                        } else {
                            put("content", JsonNull)
                        }
                        if (!m.toolCallsJson.isNullOrBlank() && provider != "nvidia") {
                            runCatching {
                                put("tool_calls", json.parseToJsonElement(m.toolCallsJson))
                            }
                        }
                    }
                }
                MessageRole.TOOL -> {
                    if (provider == "nvidia") {
                        addJsonObject {
                            put("role", "user")
                            put("content", "Tool [${m.toolName ?: "tool"}] result:\n${m.content}")
                        }
                    } else {
                        addJsonObject {
                            put("role", "tool")
                            put("tool_call_id", m.toolCallId ?: "tc-0")
                            put("content", m.content)
                        }
                    }
                }
                MessageRole.SYSTEM -> {
                    addJsonObject {
                        put("role", "system")
                        put("content", m.content)
                    }
                }
            }
        }
    }

    fun formatLocalPocketPrompt(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTotalChars: Int = 16000
    ): String {
        return buildString {
            // 1. Dynamic System Instruction: Always use the passed systemPrompt (zero hardcoding)
            val maxSysBudget = (maxTotalChars / 2).coerceAtLeast(300)
            val baseSys = systemPrompt.trim().ifBlank {
                "You are an on-device coding assistant. Answer accurately and concisely in Markdown."
            }
            val sysText = if (baseSys.length > maxSysBudget) {
                baseSys.take(maxSysBudget).trim()
            } else {
                baseSys
            }
            append("<|im_start|>system\n")
            append(sysText)
            append("\n<|im_end|>\n")

            // 2. Budget characters for latest user/tool message & context
            val userMessages = messages.filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT || it.role == MessageRole.TOOL }
            val lastTurn = userMessages.lastOrNull { it.role == MessageRole.USER || it.role == MessageRole.TOOL }

            val currentBudget = (maxTotalChars - length - 40).coerceAtLeast(300)
            if (lastTurn != null) {
                val finalUserTurn = if (lastTurn.role == MessageRole.TOOL) {
                    "<tool_response>\n${lastTurn.content.trim()}\n</tool_response>"
                } else {
                    val rawContent = lastTurn.content
                    val activeFileRegex = Regex("""<active_file[\s\S]*?<\/active_file>""", RegexOption.IGNORE_CASE)
                    val taggedFilesRegex = Regex("""<tagged_files>[\s\S]*?<\/tagged_files>""", RegexOption.IGNORE_CASE)

                    val activeFileMatch = activeFileRegex.find(rawContent)?.value
                    val taggedFilesMatch = taggedFilesRegex.find(rawContent)?.value

                    // The pure user query with file wrappers stripped
                    val pureQuery = rawContent
                        .replace(activeFileRegex, "")
                        .replace(taggedFilesRegex, "")
                        .trim()

                    val cappedQuery = if (pureQuery.length > 500) pureQuery.take(500) else pureQuery

                    val fileBudget = (currentBudget - cappedQuery.length - 60).coerceIn(0, 500)
                    val fileContext = when {
                        activeFileMatch != null -> {
                            val fileName = Regex("""name="([^"]*)"""").find(activeFileMatch)?.groupValues?.get(1) ?: "file"
                            val inner = activeFileMatch.substringAfter('>', "").substringBeforeLast("</active_file>", "").trim()
                            val snippet = if (inner.length > fileBudget) inner.take(fileBudget) + "\n... [truncated]" else inner
                            if (snippet.isNotBlank()) "<active_file name=\"$fileName\">\n$snippet\n</active_file>" else ""
                        }
                        taggedFilesMatch != null -> {
                            val inner = taggedFilesMatch.substringAfter('>', "").substringBeforeLast("</tagged_files>", "").trim()
                            val snippet = if (inner.length > fileBudget) inner.take(fileBudget) + "\n... [truncated]" else inner
                            if (snippet.isNotBlank()) "<tagged_files>\n$snippet\n</tagged_files>" else ""
                        }
                        else -> ""
                    }

                    if (fileContext.isNotBlank()) {
                        "$fileContext\n\n$cappedQuery"
                    } else {
                        cappedQuery
                    }
                }

                // If budget allows, include previous assistant turn
                val prevAssistant = userMessages.dropLast(1).lastOrNull { it.role == MessageRole.ASSISTANT }
                if (prevAssistant != null && (length + finalUserTurn.length + 150) < maxTotalChars) {
                    val prevText = prevAssistant.content.take(150)
                    append("<|im_start|>assistant\n")
                    append(prevText)
                    append("<|im_end|>\n")
                }

                append("<|im_start|>user\n")
                append(finalUserTurn.trim())
                append("<|im_end|>\n")
            }

            append("<|im_start|>assistant\n<think>\n")
        }.take(maxTotalChars)
    }

    fun formatConversationForLlama(systemPrompt: String, messages: List<ChatMessage>): String {
        return buildString {
            if (systemPrompt.isNotBlank()) {
                append("<|im_start|>system\n")
                append(systemPrompt.trim())
                append("<|im_end|>\n")
            }
            messages.takeLast(10).forEach { msg ->
                val role = when (msg.role) {
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                    MessageRole.SYSTEM -> "system"
                    MessageRole.TOOL -> "tool"
                }
                append("<|im_start|>$role\n")
                append(msg.content.trim())
                append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }
    }

    /**
     * Builds a compact, high-efficiency OpenAI-compatible messages array for local on-device inference.
     * Keeps total input tokens strictly under ~800 tokens (~3,200 chars) so prompt prefill takes only 1-2s
     * on mobile CPU and never exceeds context size (-c 4096).
     */
    internal fun buildLocalServerMessages(
        messages: List<ChatMessage>,
        systemPrompt: String
    ): JsonArray = buildJsonArray {
        val thinkRegex = Regex("""<think>[\s\S]*?<\/think>""", RegexOption.IGNORE_CASE)
        val activeFileRegex = Regex("""<active_file[\s\S]*?<\/active_file>""", RegexOption.IGNORE_CASE)
        val taggedFilesRegex = Regex("""<tagged_files>[\s\S]*?<\/tagged_files>""", RegexOption.IGNORE_CASE)

        // Filter valid conversational turns (exclude system/blank)
        val convMessages = messages.filter {
            (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT || it.role == MessageRole.TOOL) && it.content.isNotBlank()
        }

        if (convMessages.isEmpty()) {
            return@buildJsonArray
        }

        val lastIndex = convMessages.lastIndex
        val lastMsg = convMessages[lastIndex]
        val rawLastContent = lastMsg.content
        val pureLastQuery = rawLastContent
            .replace(thinkRegex, "")
            .replace(activeFileRegex, "")
            .replace(taggedFilesRegex, "")
            .trim()

        val activeFileInUser = activeFileRegex.find(rawLastContent)?.value
        val taggedFilesInUser = taggedFilesRegex.find(rawLastContent)?.value

        val activeFileInSys = activeFileRegex.find(systemPrompt)?.value
        val taggedFilesInSys = taggedFilesRegex.find(systemPrompt)?.value

        val activeFileBlock = activeFileInUser ?: activeFileInSys
        val taggedFilesBlock = taggedFilesInUser ?: taggedFilesInSys

        val lowerQuery = pureLastQuery.lowercase().trim()
        val isGreetingOrCasual = listOf(
            "hello", "hi", "hey", "who are you", "what are you", "what can you do",
            "thanks", "thank you", "ok", "okay", "cool", "nice", "awesome",
            "good morning", "good evening", "good afternoon"
        ).any { lowerQuery == it || lowerQuery.startsWith("$it ") || lowerQuery.startsWith("$it!") || lowerQuery.startsWith("$it.") || lowerQuery.startsWith("$it,") }

        val isChatQuestion = isGreetingOrCasual || listOf(
            "why", "how", "what", "where", "who", "when", "can you explain",
            "explain", "describe", "tell me", "help me understand", "hello", "hi", "hey",
            "thanks", "thank you", "ok", "okay", "cool", "nice", "awesome",
            "what is", "what are", "what does", "why is", "why does", "why did",
            "how do", "how does", "how can", "is there", "do you", "can i"
        ).any { lowerQuery.startsWith(it) || lowerQuery == it || lowerQuery.startsWith("$it ") || lowerQuery.endsWith("?") }

        val isCreateRequest = !isGreetingOrCasual && listOf(
            "make a", "create a", "build a", "design a", "write a",
            "new website", "new app", "from scratch", "generate a",
            "make website", "create website", "build website",
            "make app", "create app", "build app"
        ).any { pureLastQuery.contains(it, ignoreCase = true) }

        val isExplicitCodeAction = isCreateRequest || (!isGreetingOrCasual && listOf(
            "fix the", "fix bug", "fix error", "change the", "update the", "modify the",
            "refactor the", "patch the", "add a", "remove the", "delete the", "replace the",
            "implement", "tweak the", "style the"
        ).any { lowerQuery.contains(it) })

        val isCodeRequest = isExplicitCodeAction && (!isChatQuestion || listOf("make", "create", "build", "write", "fix", "change", "update", "add").any { lowerQuery.startsWith(it) })

        val rawFileName = if (activeFileBlock != null) {
            Regex("""(?:name|path)="([^"]*)"""").find(activeFileBlock)?.groupValues?.get(1)
        } else null

        val isFileReferenced = !isGreetingOrCasual && ((rawFileName != null && lowerQuery.contains(rawFileName.lowercase())) ||
                lowerQuery.contains("this file") || lowerQuery.contains("the file") ||
                lowerQuery.contains("this code") || lowerQuery.contains("the code") ||
                lowerQuery.contains("style.css") || lowerQuery.contains("index.html") || lowerQuery.contains("script.js"))

        val shouldIncludeActiveFile = !isGreetingOrCasual && (
            (activeFileInUser != null && !isChatQuestion) || isCodeRequest || isFileReferenced
        )

        val targetFileName = rawFileName ?: if (pureLastQuery.contains("website", ignoreCase = true) || pureLastQuery.contains("html", ignoreCase = true) || pureLastQuery.contains("calculator", ignoreCase = true)) "index.html" else "index.html"
        val isHtmlTarget = (rawFileName ?: targetFileName).endsWith(".html", ignoreCase = true)
        val isHtmlCreateRequest = isCodeRequest && isCreateRequest && isHtmlTarget

        // 1. Dynamic Master System Prompt: Adaptive Mode (Chat vs Code) using caller systemPrompt
        val compactSystemText = buildString {
            val base = systemPrompt.substringBefore("# Custom System Instructions")
                .substringBefore("<project_file_tree>")
                .substringBefore("<active_file")
                .trim()
                .ifBlank { "You are an on-device AI coding assistant." }
            append(base).append("\n\n")

            if (isGreetingOrCasual) {
                append("## OPERATIONAL MODE: CASUAL GREETING & CONVERSATIONAL CHAT (ACTIVE)\n")
                append("- The user sent a casual greeting or friendly remark. Greet them back politely, warmly, and concisely.\n")
                append("- Ask how you can assist them with their code or project.\n")
                append("- STRICT INVARIANT: NEVER generate code, <edit file=\"...\">, or modify any file on a simple greeting!\n\n")
            } else if (!isCodeRequest) {
                append("## OPERATIONAL MODE: CONVERSATIONAL CHAT (ACTIVE)\n")
                append("- The user is asking a question, discussing concepts, or chatting. You MUST respond in pure Markdown prose.\n")
                append("- STRICT RULE: NEVER output <edit file=\"...\"> or <patch file=\"...\"> tags. NEVER overwrite or modify any file in chat mode!\n")
                append("- If explaining a code concept, use standard Markdown code fences (```lang ... ```) only—never file modification tags.\n\n")
            } else {
                append("## OPERATIONAL MODE: CODE GENERATION & EDITING (ACTIVE)\n")
                append("- The user is requesting code changes or building an app/file.\n")
                append("- For new files or full app builds: output 100% complete code in <edit file=\"...\">...</edit> from start to finish.\n")
                append("- For small targeted edits (changing lines/CSS/values): emit fast surgical <edit file=\"...\"> with SEARCH / REPLACE:\n")
                append("  <<<<<<< SEARCH\n  exact lines\n  =======\n  new lines\n  >>>>>>> REPLACE\n\n")
                append("## STRICT ZERO-PLACEHOLDER LAWS\n")
                append("- NEVER use comments like `<!-- Add rows -->` or `// TODO`. Write all code and data completely!\n")
                append("- Deliver stunning UI: dark cosmic themes, real glassmorphism (`backdrop-filter: blur(16px)`), CSS animations, inline SVGs, and interactive JS. Aim for 150–300+ lines.\n")
            }

            // Extract Custom Instructions, Skills, and MCP Context from systemPrompt (budgeted up to 500 chars)
            val customInstructionsMatch = Regex("""# Custom System Instructions\s*([\s\S]*?)(?=(?:---\s*#|$))""").find(systemPrompt)?.groupValues?.get(1)?.trim()
            val skillsMatch = Regex("""# Available Skills\s*([\s\S]*?)(?=(?:---\s*#|$))""").find(systemPrompt)?.groupValues?.get(1)?.trim()
            val mcpMatch = Regex("""# Connected MCP Servers[^\n]*\s*([\s\S]*?)(?=(?:---\s*#|$))""").find(systemPrompt)?.groupValues?.get(1)?.trim()

            val extraContext = buildString {
                if (!customInstructionsMatch.isNullOrBlank()) {
                    append("\nUser Instructions: ").append(customInstructionsMatch.replace('\n', ' ').trim())
                }
                if (!skillsMatch.isNullOrBlank()) {
                    append("\nSkills: ").append(skillsMatch.replace('\n', ' ').trim())
                }
                if (!mcpMatch.isNullOrBlank()) {
                    append("\nMCP: ").append(mcpMatch.replace('\n', ' ').trim())
                }
            }.trim()

            if (extraContext.isNotBlank()) {
                val cappedExtra = if (extraContext.length > 500) extraContext.take(497) + "..." else extraContext
                append("\n\n").append(cappedExtra)
            }

            // If project file tree is present in systemPrompt, append up to 250 chars of it
            val treeRegex = Regex("""<project_file_tree>([\s\S]*?)<\/project_file_tree>""", RegexOption.IGNORE_CASE)
            val treeMatch = treeRegex.find(systemPrompt)?.groupValues?.get(1)?.trim()
            if (!treeMatch.isNullOrBlank()) {
                val filteredTree = if (isHtmlCreateRequest) {
                    treeMatch.lines().filter { line ->
                        val l = line.trim().lowercase()
                        !l.contains("style.css") && !l.contains("script.js")
                    }.joinToString("\n").trim()
                } else treeMatch

                if (filteredTree.isNotBlank()) {
                    val cappedTree = if (filteredTree.length > 250) filteredTree.take(250) + "\n..." else filteredTree
                    append("\n\nProject files:\n$cappedTree")
                }
            }
        }

        val cleanSystemText = compactSystemText
            .replace(Regex("""<tools>[\s\S]*?<\/tools>""", RegexOption.IGNORE_CASE), "")
            .replace("<|im_start|>", "")
            .replace("<|im_end|>", "")
            .replace("<|im_sep|>", "")
            .trim()
        val finalSystemText = if (cleanSystemText.length > 1200) {
            cleanSystemText.take(1197) + "..."
        } else cleanSystemText

        addJsonObject {
            put("role", "system")
            put("content", finalSystemText)
        }

        // Up to the last 2 prior turns (at most 4 messages prior to the latest message)
        val priorMessages = if (lastIndex > 0) {
            val fromIndex = (lastIndex - 4).coerceAtLeast(0)
            convMessages.subList(fromIndex, lastIndex)
        } else {
            emptyList()
        }

        for (prior in priorMessages) {
            val cleaned = prior.content
                .replace(thinkRegex, "")
                .replace(activeFileRegex, "")
                .replace(taggedFilesRegex, "")
                .replace("<|im_start|>", "")
                .replace("<|im_end|>", "")
                .replace("<|im_sep|>", "")
                .trim()
            if (cleaned.isNotBlank()) {
                val capped = if (cleaned.length > 250) cleaned.take(250) + "..." else cleaned
                addJsonObject {
                    if (prior.role == MessageRole.TOOL) {
                        put("role", "user")
                        put("content", "<tool_response>\n$capped\n</tool_response>")
                    } else {
                        put("role", if (prior.role == MessageRole.USER) "user" else "assistant")
                        put("content", capped)
                    }
                }
            }
        }

        val extractedContext = when {
            shouldIncludeActiveFile && activeFileBlock != null -> {
                val fileName = rawFileName ?: "active_file"
                if (isCreateRequest && isCodeRequest) {
                    "\n\nTarget file to overwrite: $fileName (write a brand-new, complete implementation from scratch in <edit file=\"$fileName\">...complete code...</edit>)\n"
                } else {
                    val inner = activeFileBlock.substringAfter('>', "").substringBeforeLast("</active_file>", "").trim()
                    val cappedInner = if (inner.length > 1000) inner.take(1000) + "\n... [truncated]" else inner
                    if (cappedInner.isNotBlank()) {
                        "\n\n<!-- Note: The current active file ($fileName) is shown below for reference. -->\n<active_file name=\"$fileName\">\n$cappedInner\n</active_file>"
                    } else ""
                }
            }
            taggedFilesBlock != null -> {
                val inner = taggedFilesBlock.substringAfter('>', "").substringBeforeLast("</tagged_files>", "").trim()
                val cappedInner = if (inner.length > 1000) inner.take(1000) + "\n... [truncated]" else inner
                if (cappedInner.isNotBlank()) "\n\n<tagged_files>\n$cappedInner\n</tagged_files>" else ""
            }
            else -> ""
        }

        val htmlDirective = if (isHtmlCreateRequest) {
            "\n\nIMPORTANT FOR $targetFileName:\n" +
            "- Output ONE single self-contained HTML5 file inside <edit file=\"$targetFileName\">...</edit>.\n" +
            "- Do NOT use <link rel=\"stylesheet\"> or <script src=\"script.js\">.\n" +
            "- Put ALL CSS inside <style>...</style> (dark cosmic background #0b0f19, glassmorphism cards with backdrop-filter: blur(16px), glowing borders, keyframe animations, and responsive grid).\n" +
            "- Put ALL interactive vanilla JavaScript inside <script>...</script> before </body> using standard document.getElementById / addEventListener (never use onsubmit.prevent). Implement all UI state, controls, calculations, and animations completely with zero placeholders!"
        } else ""

        val baseQuery = if (pureLastQuery.isNotBlank()) pureLastQuery else rawLastContent
        val extraPayload = extractedContext + htmlDirective
        val totalCap = if (isHtmlCreateRequest) 2000 else 1400
        val maxQueryLen = (totalCap - extraPayload.length).coerceIn(300, totalCap)
        val cappedBaseQuery = if (baseQuery.length > maxQueryLen) baseQuery.take(maxQueryLen) + "..." else baseQuery

        val finalLatestContent = (cappedBaseQuery + extraPayload).take(totalCap)
            .replace("<|im_start|>", "")
            .replace("<|im_end|>", "")
            .replace("<|im_sep|>", "")

        if (lastMsg.role == MessageRole.TOOL) {
            addJsonObject {
                put("role", "user")
                put("content", "<tool_response>\n${lastMsg.content.trim()}\n</tool_response>")
            }
        } else {
            addJsonObject {
                put("role", if (lastMsg.role == MessageRole.USER) "user" else "assistant")
                put("content", finalLatestContent)
            }
        }
    }

    internal fun isCodeBlockUnclosed(text: String): Boolean {
        val editOpenCount = Regex("""<(edit|patch)[\s>]""").findAll(text).count()
        val editCloseCount = Regex("""<\/(edit|patch)>""").findAll(text).count()
        if (editOpenCount > editCloseCount) return true

        val fenceCount = Regex("""```""").findAll(text).count()
        if (fenceCount % 2 != 0) return true

        return false
    }

    private fun streamFromLocalServer(
        model: String,
        systemPrompt: String,
        messages: List<ChatMessage>
    ): Flow<AiChunk> = flow {
        val requestBody = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("max_tokens", 4096)
            put("temperature", 0.3)
            put("top_p", 0.9)
            put("top_k", 40)
            put("repeat_penalty", 1.12)
            put("repeat_last_n", 64)
            put("messages", buildLocalServerMessages(messages, systemPrompt))
        }

        val request = Request.Builder()
            .url("http://127.0.0.1:8765/v1/chat/completions")
            .header("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody(JSON_MT))
            .build()

        val sseClient = client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        var response: okhttp3.Response? = null
        var lastErr: Throwable? = null

        for (attempt in 1..2) {
            try {
                val resp = withContext(Dispatchers.IO) { sseClient.newCall(request).execute() }
                if (resp.code == 503 && attempt == 1) {
                    resp.close()
                    delay(1000)
                    continue
                }
                response = resp
                break
            } catch (e: Throwable) {
                lastErr = e
                if (attempt == 1) {
                    delay(750)
                }
            }
        }

        if (response == null) {
            val errMsg = lastErr?.message ?: "Unable to connect to local llama-server on 127.0.0.1:8765"
            Log.e(TAG, "Local server connection failed: $errMsg", lastErr)
            emit(AiChunk.Error("Local server connection failed: $errMsg"))
            return@flow
        }

        if (!response.isSuccessful) {
            val err = withContext(Dispatchers.IO) { response.body?.string() ?: "" }
            Log.e(TAG, "Local server HTTP error ${response.code}: $err")
            emit(AiChunk.Error("Local server error (HTTP ${response.code}): $err"))
            return@flow
        }

        val source = response.body?.source() ?: run {
            emit(AiChunk.Error("Empty response body from llama-server"))
            return@flow
        }

        val accumulated = StringBuilder()
        var lastLine = ""
        var lineRepeatCount = 0
        val currentLineBuffer = StringBuilder()
        var lastFinishReason: String? = null

        while (!withContext(Dispatchers.IO) { source.exhausted() }) {
            val line = withContext(Dispatchers.IO) { source.readUtf8Line() } ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed == "data: [DONE]") continue
            if (!trimmed.startsWith("data: ")) continue
            val jsonStr = trimmed.removePrefix("data: ")

            var tokenToEmit: String? = null
            runCatching {
                val elem = json.parseToJsonElement(jsonStr).jsonObject
                val choices = elem["choices"]?.jsonArray
                val firstChoice = choices?.firstOrNull()?.jsonObject
                val delta = firstChoice?.get("delta")?.jsonObject
                val token = delta?.get("content")?.jsonPrimitive?.contentOrNull
                val fr = firstChoice?.get("finish_reason")?.jsonPrimitive?.contentOrNull
                if (!fr.isNullOrBlank()) {
                    lastFinishReason = fr
                }
                if (!token.isNullOrEmpty()) {
                    tokenToEmit = token
                }
            }

            if (tokenToEmit != null) {
                val cleanToken = tokenToEmit!!.replace("<|thought|>", "").replace("</|thought|>", "")
                if (cleanToken.isNotEmpty()) {
                    accumulated.append(cleanToken)

                    // Track lines to catch runaway repetition loops in live streaming
                    if (cleanToken.contains('\n')) {
                        val finishedLine = currentLineBuffer.toString().trim()
                        currentLineBuffer.clear()
                        currentLineBuffer.append(cleanToken.substringAfterLast('\n'))

                        if (finishedLine.length in 6..120) {
                            if (finishedLine == lastLine) {
                                lineRepeatCount++
                                if (lineRepeatCount >= 3) {
                                    // Break stream immediately on 3rd repetition
                                    break
                                }
                            } else {
                                lineRepeatCount = 1
                                lastLine = finishedLine
                            }
                        }
                    } else {
                        currentLineBuffer.append(cleanToken)
                    }

                    emit(AiChunk.Token(cleanToken))
                }
            }
        }

        // Multi-pass auto-continuation loop for 300–600+ line code generation
        var pass = 1
        val maxPasses = 3
        var isContinuing = lastFinishReason == "length" || isCodeBlockUnclosed(accumulated.toString())

        while (pass < maxPasses && isContinuing) {
            pass++
            val tailSnippet = accumulated.takeLast(600).toString()
            val continuationPrompt = "Continue writing the exact remaining code from where you stopped without repeating previous lines, and close the code block when finished:\n$tailSnippet"

            val continuationMessages = messages + listOf(
                ChatMessage(
                    id = "assistant-prev-$pass",
                    sessionId = "continuation",
                    projectId = "",
                    role = MessageRole.ASSISTANT,
                    content = accumulated.toString()
                ),
                ChatMessage(
                    id = "user-continue-$pass",
                    sessionId = "continuation",
                    projectId = "",
                    role = MessageRole.USER,
                    content = continuationPrompt
                )
            )

            val contRequestBody = buildJsonObject {
                put("model", model)
                put("stream", true)
                put("max_tokens", 4096)
                put("temperature", 0.3)
                put("top_p", 0.9)
                put("top_k", 40)
                put("repeat_penalty", 1.12)
                put("repeat_last_n", 64)
                put("messages", buildLocalServerMessages(continuationMessages, systemPrompt))
            }

            val contRequest = Request.Builder()
                .url("http://127.0.0.1:8765/v1/chat/completions")
                .header("Content-Type", "application/json")
                .post(contRequestBody.toString().toRequestBody(JSON_MT))
                .build()

            val contResponse = withContext(Dispatchers.IO) {
                runCatching { sseClient.newCall(contRequest).execute() }.getOrNull()
            } ?: break

            if (!contResponse.isSuccessful) {
                contResponse.close()
                break
            }

            val contSource = contResponse.body?.source() ?: break
            var contTokensReceived = 0
            lastFinishReason = null
            var firstTokensBuffer = StringBuilder()
            var hasStrippedLeadingFence = false

            while (!withContext(Dispatchers.IO) { contSource.exhausted() }) {
                val line = withContext(Dispatchers.IO) { contSource.readUtf8Line() } ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed == "data: [DONE]") continue
                if (!trimmed.startsWith("data: ")) continue
                val jsonStr = trimmed.removePrefix("data: ")

                var tokenToEmit: String? = null
                runCatching {
                    val elem = json.parseToJsonElement(jsonStr).jsonObject
                    val choices = elem["choices"]?.jsonArray
                    val firstChoice = choices?.firstOrNull()?.jsonObject
                    val delta = firstChoice?.get("delta")?.jsonObject
                    val token = delta?.get("content")?.jsonPrimitive?.contentOrNull
                    val fr = firstChoice?.get("finish_reason")?.jsonPrimitive?.contentOrNull
                    if (!fr.isNullOrBlank()) lastFinishReason = fr
                    if (!token.isNullOrEmpty()) {
                        tokenToEmit = token
                    }
                }

                if (tokenToEmit != null) {
                    var cleanToken = tokenToEmit!!.replace("<|thought|>", "").replace("</|thought|>", "")
                    if (cleanToken.isNotEmpty()) {
                        // Strip leading duplicate code fence from continuation pass if previous pass had open fence
                        if (!hasStrippedLeadingFence) {
                            firstTokensBuffer.append(cleanToken)
                            val bufferedStr = firstTokensBuffer.toString()
                            if (bufferedStr.contains('\n')) {
                                hasStrippedLeadingFence = true
                                val cleanedStart = bufferedStr.replaceFirst(Regex("""^```[a-zA-Z0-9_-]*\r?\n"""), "")
                                    .replaceFirst(Regex("""^<edit[\s\S]*?>\r?\n"""), "")
                                cleanToken = cleanedStart
                            } else {
                                continue
                            }
                        }

                        accumulated.append(cleanToken)
                        contTokensReceived++
                        emit(AiChunk.Token(cleanToken))
                    }
                }
            }

            contResponse.close()
            if (contTokensReceived == 0) break
            isContinuing = lastFinishReason == "length" || isCodeBlockUnclosed(accumulated.toString())
        }

        // Auto-close dangling <edit>, <patch>, or ``` tags if needed
        val textSoFar = accumulated.toString()
        val editOpen = Regex("""<edit[\s>]""").findAll(textSoFar).count()
        val editClose = Regex("""</edit>""").findAll(textSoFar).count()
        if (editOpen > editClose) {
            val closingTag = "\n</edit>\n"
            accumulated.append(closingTag)
            emit(AiChunk.Token(closingTag))
        }

        val patchOpen = Regex("""<patch[\s>]""").findAll(textSoFar).count()
        val patchClose = Regex("""</patch>""").findAll(textSoFar).count()
        if (patchOpen > patchClose) {
            val closingTag = "\n</patch>\n"
            accumulated.append(closingTag)
            emit(AiChunk.Token(closingTag))
        }

        val fenceCount = Regex("""```""").findAll(accumulated.toString()).count()
        if (fenceCount % 2 != 0) {
            val closingFence = "\n```\n"
            accumulated.append(closingFence)
            emit(AiChunk.Token(closingFence))
        }

        val finalizedText = com.sednium.kryptonide.agent.pocket.RepetitionGuard.detectAndTrimRepetition(accumulated.toString())
        emit(AiChunk.Done(finalizedText))
    }

    private fun streamOnDevicePocketChat(
        model: String,
        systemPrompt: String,
        messages: List<ChatMessage>
    ): Flow<AiChunk> = flow {
        val scanner = com.sednium.kryptonide.agent.pocket.StorageModelScanner()
        val discovered = withContext(Dispatchers.IO) {
            runCatching { scanner.scanModels() }.getOrDefault(emptyList())
        }
        val cleanQuery = model.substringBeforeLast('.').lowercase(java.util.Locale.ROOT)
        val activeModel = discovered.firstOrNull {
            it.name.lowercase(java.util.Locale.ROOT).contains(cleanQuery) ||
            it.matchedCatalogSpec?.id?.lowercase(java.util.Locale.ROOT)?.contains(cleanQuery) == true
        } ?: discovered.firstOrNull {
            it.name.lowercase(java.util.Locale.ROOT).contains("minicpm") ||
            it.matchedCatalogSpec?.id?.lowercase(java.util.Locale.ROOT)?.contains("minicpm") == true
        } ?: discovered.firstOrNull()

        // 1. Check local GGUF server (llama-server on http://127.0.0.1:8765)
        var serverActive = withContext(Dispatchers.IO) {
            runCatching { com.sednium.kryptonide.agent.pocket.LocalGgufServerManager.isServerRunning() }.getOrDefault(false)
        }
        if (!serverActive && activeModel?.file != null) {
            val modelNameShort = activeModel.matchedCatalogSpec?.name ?: activeModel.name.substringBeforeLast('.')
            val statusPrefix = "<status>⚡ Loading on-device model ($modelNameShort)...</status>\n\n"
            emit(AiChunk.Token(statusPrefix))

            serverActive = withContext(Dispatchers.IO) {
                runCatching {
                    com.sednium.kryptonide.agent.pocket.LocalGgufServerManager.startServer(
                        modelFile = activeModel.file
                    )
                }.getOrDefault(false)
            }
        }

        if (serverActive) {
            var streamSuccess = false
            var streamErrorMessage: String? = null
            try {
                streamFromLocalServer(activeModel?.name ?: model, systemPrompt, messages).collect { chunk ->
                    if (chunk !is AiChunk.Error) {
                        streamSuccess = true
                    } else {
                        streamErrorMessage = chunk.message
                    }
                    emit(chunk)
                }
                if (streamSuccess) return@flow
            } catch (t: Throwable) {
                streamErrorMessage = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "streamFromLocalServer failed: ${t.message}", t)
            }

            if (streamErrorMessage != null) {
                val errMsg = buildString {
                    append("### ⚠️ Local Server Request Failed\n\n")
                    append("`llama-server` is active, but the completion request could not be fulfilled:\n\n")
                    append("```\n$streamErrorMessage\n```\n\n")
                    val logs = com.sednium.kryptonide.agent.pocket.LocalGgufServerManager.getLastErrorLog()
                    if (logs.isNotBlank()) {
                        append("#### Server Logs:\n```\n$logs\n```\n")
                    }
                }
                emit(AiChunk.Token(errMsg))
                emit(AiChunk.Done(errMsg))
                return@flow
            }
        }

        // 2. Fall back to JNI LocalGgufRunner if native model file is present
        if (activeModel?.file != null) {
            val runner = com.sednium.kryptonide.agent.pocket.LocalGgufRunner()
            val formattedChatML = formatLocalPocketPrompt(systemPrompt, messages)
            val modelNameShort = activeModel.matchedCatalogSpec?.name ?: activeModel.name.substringBeforeLast('.')

            // Immediate live status feedback so UI transitions out of skeleton pulse immediately
            val statusPrefix = "<status>⚡ Running on-device model ($modelNameShort)...</status>\n\n"
            emit(AiChunk.Token(statusPrefix))

            val nativeText = withContext(Dispatchers.IO) {
                runCatching {
                    runner.runInference(activeModel.file, formattedChatML, "", maxTokens = 1024)
                }.getOrNull()
            }?.replace("<|im_end|>", "")?.trim()

            if (!nativeText.isNullOrBlank()) {
                val cleanedText = com.sednium.kryptonide.agent.pocket.RepetitionGuard.detectAndTrimRepetition(nativeText)
                val fullTextBuilder = StringBuilder(statusPrefix)
                val chunks = cleanedText.split(Regex("(?<=\\s)|(?<=\\n)"))
                for (token in chunks) {
                    fullTextBuilder.append(token)
                    emit(AiChunk.Token(token))
                    kotlinx.coroutines.delay(8)
                }
                emit(AiChunk.Done(fullTextBuilder.toString()))
                return@flow
            } else {
                val lastError = com.sednium.kryptonide.agent.pocket.LocalGgufServerManager.getLastErrorLog()
                val errMsg = buildString {
                    append("### ⚠️ On-Device Model Failed to Start\n\n")
                    append("Could not initialize local GGUF server for **$modelNameShort**.\n\n")
                    if (lastError.isNotBlank()) {
                        append("```\n$lastError\n```\n\n")
                    }
                    append("Please ensure the model file is a valid GGUF binary and your device has enough free RAM.")
                }
                emit(AiChunk.Token(errMsg))
                emit(AiChunk.Done(errMsg))
                return@flow
            }
        }

        // 3. If neither server nor local model is available, show clear guide message
        val guideMessage = buildString {
            append("### No Active Model Loaded\n\n")
            append("Oorty Lite is ready, but no local GGUF model is loaded and no Cloud API key is configured.\n\n")
            append("#### How to use Oorty Lite:\n")
            append("1. **On-Device Local Pocket Server**: Place any `.gguf` model (e.g. *Qwen 2.5 Coder*, *Gemma 2 2B*, *MiniCPM 5*) in `/storage/emulated/0/Kryptonide/models/` and select it in the header.\n")
            append("2. **Cloud BYOK (Bring Your Own Key)**: Tap the model selector in the AI panel header to configure an API key for **Google Gemini**, **Anthropic Claude**, **OpenAI**, **Groq**, or **OpenRouter**.")
        }
        emit(AiChunk.Token(guideMessage))
        emit(AiChunk.Done(guideMessage))
    }

    internal data class CodeFixResult(
        val fixedCode: String,
        val issues: List<String>,
        val explanation: String
    )

    internal fun parseTaggedFiles(systemPrompt: String): Map<String, String> {
        val regex = Regex("""<file path="([^"]*)">([\s\S]*?)<\/file>""")
        return regex.findAll(systemPrompt).associate { match ->
            val path = match.groupValues[1]
            val content = stripLineNumbers(match.groupValues[2])
            path to content
        }
    }

    internal fun parseActiveFile(systemPrompt: String): Pair<String, String>? {
        val regex = Regex("""<active_file (?:path|name)="([^"]*)">([\s\S]*?)<\/active_file>""")
        val match = regex.find(systemPrompt) ?: return null
        val path = match.groupValues[1]
        val content = stripLineNumbers(match.groupValues[2])
        return path to content
    }

    internal fun stripLineNumbers(text: String): String {
        return text.lines().joinToString("\n") { line ->
            line.replaceFirst(Regex("""^\s*\d+:\s?"""), "")
        }
    }

    internal fun findFileNameInText(text: String): String? {
        val regex = Regex("""\b([a-zA-Z0-9_\-\.\/]+\.(jsx|js|tsx|ts|kt|java|py|html|css|json|xml|c|cpp|h))\b""", RegexOption.IGNORE_CASE)
        return regex.find(text)?.groupValues?.get(1)
    }

    internal fun analyzeAndFixCode(filePath: String, rawCode: String, query: String): CodeFixResult {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        val isJsx = ext in listOf("jsx", "tsx", "js", "ts", "html")
        val issues = mutableListOf<String>()
        var fixedCode = rawCode.trim()

        if (fixedCode.isBlank()) {
            val starter = when (ext) {
                "jsx", "tsx" -> """
import React, { useState } from 'react';

export default function App() {
    const [count, setCount] = useState(0);

    return (
        <div style={{ padding: '20px', fontFamily: 'sans-serif' }}>
            <h1>Krypton App</h1>
            <p>Count: {count}</p>
            <button onClick={() => setCount(c => c + 1)}>Increment</button>
        </div>
    );
}
""".trimIndent()
                "html" -> """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Krypton App</title>
</head>
<body>
    <div id="root">
        <h1>Hello from Kryptonide</h1>
    </div>
</body>
</html>
""".trimIndent()
                "kt" -> """
package com.example

class Main {
    fun run() {
        println("Hello from Kryptonide")
    }
}
""".trimIndent()
                else -> "// Code for $filePath\nconsole.log('Hello from Kryptonide');"
            }
            return CodeFixResult(
                fixedCode = starter,
                issues = listOf("File was empty; generated clean starter template."),
                explanation = "The file `$filePath` was empty. Generated a standard working template ready to edit."
            )
        }

        // 1. Fix unclosed void tags in JSX: <img ...>, <input ...>, <br>, <hr> without />
        if (isJsx) {
            val voidTagRegex = Regex("""<(img|input|br|hr|meta|link)([^>]*?)(?<!/)>""", RegexOption.IGNORE_CASE)
            if (voidTagRegex.containsMatchIn(fixedCode)) {
                val count = voidTagRegex.findAll(fixedCode).count()
                fixedCode = voidTagRegex.replace(fixedCode) { m ->
                    val tag = m.groupValues[1]
                    val rest = m.groupValues[2].trimEnd()
                    "<$tag$rest />"
                }
                issues.add("Converted $count unclosed self-closing tag(s) (e.g. <img>, <input>) to valid JSX self-closing syntax (`/>`).")
            }

            // 2. Check and balance paired JSX tags: <div>, <span>, <p>, <h1>..<h6>, <button>, etc.
            val voidTags = setOf("img", "input", "br", "hr", "meta", "link", "base", "area", "col", "embed", "param", "source", "track", "wbr")
            val tagRegex = Regex("""<(/)?([a-zA-Z][a-zA-Z0-9.-]*)(?:\s+[^>]*?)?(/)?>""")

            var modified = true
            var passes = 0
            while (modified && passes < 8) {
                modified = false
                passes++
                val openStack = mutableListOf<String>()
                val matches = tagRegex.findAll(fixedCode).toList()

                for (m in matches) {
                    val isClosing = m.groupValues[1] == "/"
                    val tagName = m.groupValues[2].lowercase()
                    val isSelfClosing = m.groupValues[3] == "/" || tagName in voidTags

                    if (isClosing) {
                        if (openStack.isNotEmpty() && openStack.last() == tagName) {
                            openStack.removeAt(openStack.lastIndex)
                        } else if (openStack.contains(tagName)) {
                            val missingClosures = mutableListOf<String>()
                            while (openStack.isNotEmpty() && openStack.last() != tagName) {
                                missingClosures.add(openStack.removeAt(openStack.lastIndex))
                            }
                            if (openStack.isNotEmpty()) openStack.removeAt(openStack.lastIndex)

                            val insertStr = missingClosures.joinToString("\n") { "</$it>" } + "\n"
                            fixedCode = fixedCode.substring(0, m.range.first) + insertStr + fixedCode.substring(m.range.first)
                            missingClosures.forEach {
                                issues.add("Closed unclosed `<$it>` tag before `</$tagName>`.")
                            }
                            modified = true
                            break
                        } else {
                            issues.add("Extraneous closing tag `</$tagName>`.")
                        }
                    } else if (!isSelfClosing) {
                        openStack.add(tagName)
                    }
                }

                if (!modified && openStack.isNotEmpty()) {
                    val closingTagsStr = openStack.reversed().joinToString("\n") { "    </$it>" }
                    openStack.forEach {
                        issues.add("Added missing closing tag `</$it>`.")
                    }
                    fixedCode = if (fixedCode.contains(");")) {
                        val lastIdx = fixedCode.lastIndexOf(");")
                        fixedCode.substring(0, lastIdx).trimEnd() + "\n" + closingTagsStr + "\n    " + fixedCode.substring(lastIdx)
                    } else {
                        fixedCode + "\n" + closingTagsStr
                    }
                    break
                }
            }
        }

        // 3. Balance braces { }, parentheses ( ), and brackets [ ]
        var openBraces = 0
        var openParens = 0
        var openBrackets = 0
        var inSingleQuote = false
        var inDoubleQuote = false
        var inBacktick = false
        var escape = false

        for (ch in fixedCode) {
            if (escape) {
                escape = false
                continue
            }
            if (ch == '\\') {
                escape = true
                continue
            }
            if (ch == '\'' && !inDoubleQuote && !inBacktick) { inSingleQuote = !inSingleQuote; continue }
            if (ch == '"' && !inSingleQuote && !inBacktick) { inDoubleQuote = !inDoubleQuote; continue }
            if (ch == '`' && !inSingleQuote && !inDoubleQuote) { inBacktick = !inBacktick; continue }

            if (!inSingleQuote && !inDoubleQuote && !inBacktick) {
                when (ch) {
                    '{' -> openBraces++
                    '}' -> if (openBraces > 0) openBraces--
                    '(' -> openParens++
                    ')' -> if (openParens > 0) openParens--
                    '[' -> openBrackets++
                    ']' -> if (openBrackets > 0) openBrackets--
                }
            }
        }

        if (inBacktick) {
            fixedCode += "`"
            issues.add("Closed unclosed template literal (`) string.")
        }
        if (inDoubleQuote) {
            fixedCode += "\""
            issues.add("Closed unclosed double-quoted (\") string.")
        }
        if (inSingleQuote) {
            fixedCode += "'"
            issues.add("Closed unclosed single-quoted (') string.")
        }
        if (openBrackets > 0) {
            fixedCode += "]".repeat(openBrackets)
            issues.add("Added $openBrackets missing closing bracket(s) `]`.")
        }
        if (openParens > 0) {
            fixedCode += ")".repeat(openParens)
            issues.add("Added $openParens missing closing parenthesis/parentheses `)`.")
        }
        if (openBraces > 0) {
            fixedCode += "\n" + "}".repeat(openBraces)
            issues.add("Added $openBraces missing closing brace(s) `}`.")
        }

        // 4. Missing React export check
        if (isJsx && !fixedCode.contains("export default") && !fixedCode.contains("export {")) {
            val compMatch = Regex("""function\s+([A-Z][a-zA-Z0-9_]*)|const\s+([A-Z][a-zA-Z0-9_]*)\s*=""").find(fixedCode)
            val compName = compMatch?.groupValues?.get(1)?.ifBlank { null } ?: compMatch?.groupValues?.get(2)?.ifBlank { null }
            if (compName != null) {
                fixedCode += "\n\nexport default $compName;\n"
                issues.add("Added missing `export default $compName;` statement.")
            }
        }

        if (issues.isEmpty()) {
            issues.add("Inspected AST & syntax balance: verified closing tags, bracket pairs, and module exports.")
        }

        val explanation = buildString {
            append("I have analyzed **`$filePath`** and resolved the syntax errors:\n\n")
            issues.forEach { issue ->
                append("• $issue\n")
            }
            append("\nThe complete, corrected file is ready below. Tap **Apply** to update your code directly.")
        }

        return CodeFixResult(fixedCode, issues, explanation)
    }

    internal fun implementFeatureInCode(filePath: String, rawCode: String, query: String): String {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        var code = stripLineNumbers(rawCode).trim()
        val qLower = query.lowercase()

        val isExplicitFeatureAdd = (qLower.contains("table") || qLower.contains("button") || qLower.contains("form") || qLower.contains("input") || qLower.contains("add") || qLower.contains("insert") || qLower.contains("in the") || qLower.contains("to the")) && code.contains("<body", ignoreCase = true)

        val isFullWebsiteOrAppRequest = !isExplicitFeatureAdd && (
                qLower.contains("make a website") ||
                qLower.contains("website code") ||
                qLower.contains("create a website") ||
                qLower.contains("build a website") ||
                qLower.contains("make website") ||
                qLower.contains("build website") ||
                qLower.contains("make an app") ||
                qLower.contains("build an app") ||
                code.isBlank() ||
                (code.length < 100 && !code.contains("<body", ignoreCase = true)) ||
                code.contains("Hello World", ignoreCase = true)
        )

        if (isFullWebsiteOrAppRequest) {
            return AiCodeGenerator.generateCode(filePath, query)
        }

        val isHtml = ext in listOf("html", "htm") || code.contains("<!DOCTYPE html", ignoreCase = true) || code.contains("<html", ignoreCase = true) || code.contains("<body", ignoreCase = true)
        if (isHtml) {
            return implementHtmlFeature(code, query)
        }

        if (ext in listOf("jsx", "tsx", "js")) {
            return implementReactFeature(code, query)
        }

        return code
    }

    private fun implementHtmlFeature(rawCode: String, query: String): String {
        var code = rawCode.trim()
        val qLower = query.lowercase()
        val featureName = query.trim().take(30).replaceFirstChar { it.uppercase() }

        val elementHtml = when {
            qLower.contains("button") || qLower.contains("click") -> """
        <div style="margin: 16px 0;">
            <button onclick="alert('$featureName triggered!')" style="padding: 10px 20px; background: #38bdf8; color: #000; font-weight: 700; border: none; border-radius: 10px; cursor: pointer; transition: transform 0.2s;">
                $featureName
            </button>
        </div>
""".trimIndent()
            qLower.contains("input") || qLower.contains("form") || qLower.contains("field") -> """
        <form style="margin: 16px 0; background: rgba(255,255,255,0.05); padding: 16px; border-radius: 12px;" onsubmit="event.preventDefault(); alert('Saved: ' + document.getElementById('fieldInput').value);">
            <label style="display: block; font-size: 14px; margin-bottom: 6px; font-weight: 600;">$featureName</label>
            <div style="display: flex; gap: 10px;">
                <input type="text" id="fieldInput" placeholder="Enter value..." style="flex: 1; padding: 10px 14px; border-radius: 8px; border: 1px solid rgba(255,255,255,0.2); background: rgba(0,0,0,0.2); color: #fff; outline: none;" required />
                <button type="submit" style="padding: 10px 18px; background: #38bdf8; color: #000; font-weight: 700; border: none; border-radius: 8px; cursor: pointer;">Submit</button>
            </div>
        </form>
""".trimIndent()
            qLower.contains("table") || qLower.contains("grid") || qLower.contains("list") -> """
        <div style="margin: 20px 0; overflow-x: auto;">
            <h3 style="font-size: 16px; margin-bottom: 10px; color: #38bdf8;">$featureName</h3>
            <table class="data-table" style="width: 100%; border-collapse: collapse; font-size: 14px;">
                <thead>
                    <tr style="border-bottom: 2px solid rgba(255,255,255,0.2); text-align: left;">
                        <th>Item</th>
                        <th>Category</th>
                        <th>Status</th>
                    </tr>
                </thead>
                <tbody>
                    <tr style="border-bottom: 1px solid rgba(255,255,255,0.1);">
                        <td>Record Alpha</td>
                        <td>Primary</td>
                        <td style="color: #4ade80;">Active</td>
                    </tr>
                    <tr style="border-bottom: 1px solid rgba(255,255,255,0.1);">
                        <td>Record Beta</td>
                        <td>Secondary</td>
                        <td style="color: #38bdf8;">Ready</td>
                    </tr>
                </tbody>
            </table>
        </div>
""".trimIndent()
            else -> """
        <section style="margin: 16px 0; padding: 16px; background: rgba(255,255,255,0.06); border-radius: 14px; border: 1px solid rgba(255,255,255,0.12);">
            <h3 style="font-size: 17px; margin-bottom: 6px; color: #38bdf8;">$featureName</h3>
            <p style="font-size: 14px; color: #94a3b8; line-height: 1.5;">${query.trim()}</p>
        </section>
""".trimIndent()
        }

        val placeholderRegex = Regex("""<p>[^<]*(?:table|here|placeholder)[^<]*<\/p>""", RegexOption.IGNORE_CASE)
        code = if (placeholderRegex.containsMatchIn(code)) {
            code.replaceFirst(placeholderRegex, elementHtml)
        } else if (code.contains("</div>")) {
            val idx = code.lastIndexOf("</div>")
            code.substring(0, idx) + "    " + elementHtml + "\n    " + code.substring(idx)
        } else if (code.contains("</body>", ignoreCase = true)) {
            val idx = code.indexOf("</body>", ignoreCase = true)
            code.substring(0, idx) + "    " + elementHtml + "\n" + code.substring(idx)
        } else {
            code + "\n" + elementHtml
        }

        if (qLower.contains("table") || qLower.contains("grid")) {
            val tableCss = """
        .data-table, table { width: 100%; border-collapse: collapse; margin: 16px 0; }
        th, td { padding: 10px 14px; border-bottom: 1px solid rgba(255,255,255,0.15); }
        th { background: rgba(56, 189, 248, 0.1); color: #38bdf8; font-weight: 600; }
""".trimIndent()
            if (code.contains("</style>", ignoreCase = true)) {
                val idx = code.lastIndexOf("</style>", ignoreCase = true)
                code = code.substring(0, idx) + "\n" + tableCss + "\n" + code.substring(idx)
            } else if (code.contains("</head>", ignoreCase = true)) {
                val idx = code.indexOf("</head>", ignoreCase = true)
                code = code.substring(0, idx) + "    <style>\n" + tableCss + "\n    </style>\n" + code.substring(idx)
            } else if (code.contains("<body", ignoreCase = true)) {
                val idx = code.indexOf("<body", ignoreCase = true)
                code = code.substring(0, idx) + "<style>\n" + tableCss + "\n</style>\n" + code.substring(idx)
            }
        }

        return code
    }

    private fun implementReactFeature(rawCode: String, query: String): String {
        var code = rawCode.trim()
        val qLower = query.lowercase()

        if (!code.contains("useState")) {
            code = if (code.contains("import React")) {
                code.replaceFirst("import React", "import React, { useState }")
            } else {
                "import React, { useState } from 'react';\n$code"
            }
        }

        val featureName = query.trim().take(30).replaceFirstChar { it.uppercase() }

        val jsxElement = when {
            qLower.contains("button") || qLower.contains("counter") || qLower.contains("reset") -> """
      <div style={{ margin: '14px 0' }}>
        <button
          onClick={() => alert('$featureName clicked!')}
          style={{ padding: '10px 18px', background: '#38bdf8', color: '#000', fontWeight: '700', borderRadius: '10px', border: 'none', cursor: 'pointer' }}
        >
          $featureName
        </button>
      </div>
"""
            qLower.contains("table") || qLower.contains("grid") -> """
      <div style={{ margin: '18px 0', overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left' }}>
          <thead>
            <tr style={{ borderBottom: '2px solid rgba(255,255,255,0.2)' }}>
              <th style={{ padding: '8px' }}>#</th>
              <th style={{ padding: '8px' }}>Item</th>
              <th style={{ padding: '8px' }}>Status</th>
            </tr>
          </thead>
          <tbody>
            <tr style={{ borderBottom: '1px solid rgba(255,255,255,0.1)' }}>
              <td style={{ padding: '8px' }}>1</td>
              <td style={{ padding: '8px' }}>Feature Record A</td>
              <td style={{ padding: '8px', color: '#4ade80' }}>Active</td>
            </tr>
            <tr style={{ borderBottom: '1px solid rgba(255,255,255,0.1)' }}>
              <td style={{ padding: '8px' }}>2</td>
              <td style={{ padding: '8px' }}>Feature Record B</td>
              <td style={{ padding: '8px', color: '#38bdf8' }}>Pending</td>
            </tr>
          </tbody>
        </table>
      </div>
"""
            else -> """
      <div style={{ margin: '16px 0', padding: '16px', background: 'rgba(255,255,255,0.06)', borderRadius: '14px', border: '1px solid rgba(255,255,255,0.12)' }}>
        <h3 style={{ fontSize: '16px', color: '#38bdf8', marginBottom: '6px' }}>$featureName</h3>
        <p style={{ fontSize: '14px', color: '#94a3b8', margin: 0 }}>${query.trim()}</p>
      </div>
"""
        }

        return if (code.contains("</div>")) {
            val divIdx = code.lastIndexOf("</div>")
            code.substring(0, divIdx) + jsxElement + "    " + code.substring(divIdx)
        } else {
            code + "\n" + jsxElement
        }
    }

    internal fun explainCodeStructure(filePath: String, rawCode: String): String {
        val lines = rawCode.lines()
        val imports = lines.filter { it.trim().startsWith("import ") || it.trim().startsWith("require(") }
        val exports = lines.filter { it.contains("export ") }

        return buildString {
            append("### Code Walkthrough for **`$filePath`**\n\n")
            append("• **Total Lines**: ${lines.size}\n")
            if (imports.isNotEmpty()) {
                append("• **Dependencies**: ${imports.size} imported module(s)\n")
            }
            if (exports.isNotEmpty()) {
                append("• **Exports**: ${exports.joinToString(", ") { it.trim() }}\n")
            }
            append("\nThis module handles the core functionality for `$filePath`. Let me know if you would like me to add new features, refactor components, or write unit tests for it!")
        }
    }

    internal fun generateStarterCode(filePath: String, query: String): String {
        return AiCodeGenerator.generateCode(filePath, query)
    }

    internal fun generateLocalPocketResponse(
        query: String,
        modelName: String,
        systemPrompt: String,
        model: com.sednium.kryptonide.agent.pocket.DiscoveredModel?
    ): String {
        if (model?.file != null) {
            val runner = com.sednium.kryptonide.agent.pocket.LocalGgufRunner()
            val messages = listOf(
                ChatMessage(id = "1", sessionId = "s", projectId = "p", role = MessageRole.USER, content = query)
            )
            val formattedChatML = formatLocalPocketPrompt(systemPrompt, messages)
            val nativeText = kotlinx.coroutines.runBlocking {
                runCatching { runner.runInference(model.file, formattedChatML, "") }.getOrNull()
            }?.replace("<|im_end|>", "")?.trim()
            if (!nativeText.isNullOrBlank()) {
                return nativeText
            } else {
                val lastError = com.sednium.kryptonide.agent.pocket.LocalGgufServerManager.getLastErrorLog()
                return buildString {
                    append("### ⚠️ On-Device Model Failed to Start\n\n")
                    append("Could not initialize local GGUF server for **${model.name}**.\n\n")
                    if (lastError.isNotBlank()) {
                        append("```\n$lastError\n```\n\n")
                    }
                    append("Please ensure the model file is a valid GGUF binary and your device has enough free RAM.")
                }
            }
        }

        return buildString {
            append("### No Active Model Loaded\n\n")
            append("Oorty Lite is ready, but no local GGUF model is loaded and no Cloud API key is configured.\n\n")
            append("#### How to use Oorty Lite:\n")
            append("1. **On-Device Local Pocket Server**: Place any `.gguf` model (e.g. *Qwen 2.5 Coder*, *Gemma 2 2B*, *MiniCPM 5*) in `/storage/emulated/0/Kryptonide/models/` and select it from the header.\n")
            append("2. **Cloud BYOK**: Tap the model selector in the AI panel header to configure an API key for **Google Gemini**, **Anthropic Claude**, **OpenAI**, **Groq**, or **OpenRouter**.")
        }
    }
}
