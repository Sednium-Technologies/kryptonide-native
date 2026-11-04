package com.sednium.kryptonide.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class WebSearchToolSet(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun registerAll() {
        AgentToolRegistry.register(
            ToolDefinition(
                name = "web_search",
                description = "Search the web for up-to-date documentation, technical answers, library syntax, or reference information.",
                parameters = listOf(
                    ToolParam("query", "string", "Search query terms (e.g. 'Tailwind CSS v4 flexbox', 'Kotlin coroutines flow')")
                )
            ) { args ->
                val query = args["query"]?.toString()?.trim()
                    ?: return@ToolDefinition ToolResult(false, "", "Missing 'query' parameter.", exitCode = 1)

                val results = search(query)
                if (results.isNotBlank()) {
                    ToolResult(success = true, output = results, exitCode = 0)
                } else {
                    ToolResult(success = false, output = "", error = "No search results found for query: $query", exitCode = 1)
                }
            }
        )
    }

    private suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&format=json&utf8=1&srlimit=4"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Kryptonide-Android/4.0 (Autonomous Tool Coprocessor)")
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext "Web search HTTP error ${resp.code}"
            val body = resp.body?.string() ?: return@withContext "Empty response"
            val parsed = json.parseToJsonElement(body).jsonObject
            val searchArr = parsed["query"]?.jsonObject?.get("search")?.jsonArray ?: return@withContext ""

            if (searchArr.isEmpty()) return@withContext "No direct results found for '$query'."

            buildString {
                appendLine("### Web Search Results for \"$query\":")
                for (item in searchArr) {
                    val obj = item.jsonObject
                    val title = obj["title"]?.jsonPrimitive?.content ?: continue
                    val snippetHtml = obj["snippet"]?.jsonPrimitive?.content ?: ""
                    val snippet = snippetHtml.replace(Regex("<[^>]*>"), "").replace("&quot;", "\"").replace("&amp;", "&")
                    val pageUrl = "https://en.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(" ", "_"), "UTF-8")
                    appendLine("- **$title** ($pageUrl)")
                    appendLine("  $snippet")
                }
            }
        } catch (e: Throwable) {
            "Web search failed: ${e.message}"
        }
    }
}
