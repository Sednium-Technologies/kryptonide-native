package com.sednium.kryptonide.agent.tools

import com.sednium.kryptonide.data.mcp.McpRepository
import com.sednium.kryptonide.data.mcp.McpServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class McpToolBridge(
    private val mcpRepo: McpRepository,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun syncToolsFromConfiguredServers() = withContext(Dispatchers.IO) {
        val enabledServers = mcpRepo.getEnabledServers()
        for (server in enabledServers) {
            try {
                fetchAndRegisterServerTools(server)
            } catch (e: Throwable) {
                // Ignore transient network errors on individual servers
            }
        }
    }

    private suspend fun fetchAndRegisterServerTools(server: McpServerConfig) = withContext(Dispatchers.IO) {
        val rpcPayload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tools/list")
            put("params", buildJsonObject {})
        }

        val request = Request.Builder()
            .url(server.endpointUrl)
            .post(rpcPayload.toString().toRequestBody("application/json".toMediaType()))
            .apply {
                if (server.apiKey.isNotBlank()) {
                    addHeader("Authorization", "Bearer ${server.apiKey}")
                }
            }
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext
            val body = response.body?.string() ?: return@withContext
            val element = json.parseToJsonElement(body)
            val toolsArray = element.jsonObject["result"]?.jsonObject?.get("tools")?.jsonArray ?: return@withContext

            for (t in toolsArray) {
                val tObj = t.jsonObject
                val toolName = tObj["name"]?.jsonPrimitive?.content ?: continue
                val toolDesc = tObj["description"]?.jsonPrimitive?.content ?: "MCP tool from ${server.name}"
                val prefixedName = "mcp_${server.name.lowercase().replace(" ", "_")}_$toolName"

                val paramsList = mutableListOf<ToolParam>()
                val schemaProps = tObj["inputSchema"]?.jsonObject?.get("properties")?.jsonObject
                val requiredList = tObj["inputSchema"]?.jsonObject?.get("required")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

                schemaProps?.forEach { (paramName, propElem) ->
                    val pType = propElem.jsonObject["type"]?.jsonPrimitive?.content ?: "string"
                    val pDesc = propElem.jsonObject["description"]?.jsonPrimitive?.content ?: ""
                    paramsList.add(ToolParam(paramName, pType, pDesc, required = requiredList.contains(paramName)))
                }

                AgentToolRegistry.register(
                    ToolDefinition(
                        name = prefixedName,
                        description = "[MCP Server: ${server.name}] $toolDesc",
                        parameters = paramsList
                    ) { args ->
                        callMcpTool(server, toolName, args)
                    }
                )
            }
        }
    }

    private suspend fun callMcpTool(server: McpServerConfig, originalName: String, args: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        try {
            val rpcPayload = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", System.currentTimeMillis().toInt())
                put("method", "tools/call")
                put("params", buildJsonObject {
                    put("name", originalName)
                    put("arguments", json.encodeToJsonElement(args.mapValues { (_, v) ->
                        when (v) {
                            is String -> JsonPrimitive(v)
                            is Number -> JsonPrimitive(v)
                            is Boolean -> JsonPrimitive(v)
                            else -> JsonPrimitive(v?.toString())
                        }
                    }))
                })
            }

            val request = Request.Builder()
                .url(server.endpointUrl)
                .post(rpcPayload.toString().toRequestBody("application/json".toMediaType()))
                .apply {
                    if (server.apiKey.isNotBlank()) {
                        addHeader("Authorization", "Bearer ${server.apiKey}")
                    }
                }
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext ToolResult(
                        success = false,
                        output = "",
                        error = "MCP Server HTTP ${response.code}: $body",
                        exitCode = response.code
                    )
                }

                val elem = json.parseToJsonElement(body)
                val resultObj = elem.jsonObject["result"]?.jsonObject
                val isError = elem.jsonObject["error"] != null || resultObj?.get("isError")?.jsonPrimitive?.booleanOrNull == true
                val contentArray = resultObj?.get("content")?.jsonArray

                val textOutput = contentArray?.joinToString("\n") { item ->
                    item.jsonObject["text"]?.jsonPrimitive?.content ?: item.toString()
                } ?: body

                ToolResult(
                    success = !isError,
                    output = textOutput,
                    error = if (isError) textOutput else null,
                    exitCode = if (isError) 1 else 0
                )
            }
        } catch (e: Throwable) {
            ToolResult(false, "", "MCP RPC call failed: ${e.message}", exitCode = 1)
        }
    }
}
