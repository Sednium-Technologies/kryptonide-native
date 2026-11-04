package com.sednium.kryptonide.data.mcp

import android.content.Context
import com.sednium.kryptonide.utils.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class McpRepository(private val context: Context) {

    private val mcpDir: File by lazy {
        val externalDir = File(StorageUtils.getKryptonideRoot(), "mcp")
        val legacyDir = File(StorageUtils.getExternalStorageDir(), "KryptonIDE/mcp")
        if (legacyDir.exists() && legacyDir.isDirectory && (!externalDir.exists() || (externalDir.listFiles()?.isEmpty() == true))) {
            runCatching { legacyDir.copyRecursively(externalDir, overwrite = false) }
        }
        if (externalDir.exists() || externalDir.mkdirs() || externalDir.canWrite()) {
            externalDir
        } else {
            File(context.filesDir, "mcp").apply { mkdirs() }
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    suspend fun listServers(): List<McpServerConfig> = withContext(Dispatchers.IO) {
        val file = File(mcpDir, "mcp_servers.json")
        if (!file.exists()) return@withContext emptyList()
        val raw = try {
            file.readText()
        } catch (_: Exception) {
            return@withContext emptyList()
        }
        if (raw.isBlank()) return@withContext emptyList()
        try {
            json.decodeFromString<List<McpServerConfig>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveServers(servers: List<McpServerConfig>) = withContext(Dispatchers.IO) {
        mcpDir.mkdirs()
        val jsonFile = File(mcpDir, "mcp_servers.json")
        val mdFile = File(mcpDir, "mcp_servers.md")

        val raw = json.encodeToString(servers)
        jsonFile.writeText(raw)
        mdFile.writeText(generateMarkdownSummary(servers))
    }

    private fun generateMarkdownSummary(servers: List<McpServerConfig>): String {
        return buildString {
            append("# Model Context Protocol (MCP) Servers\n\n")
            if (servers.isEmpty()) {
                append("No MCP servers configured.\n")
            } else {
                servers.forEach { server ->
                    val status = if (server.isEnabled) "Enabled" else "Disabled"
                    append("## ${server.name}\n")
                    append("- **Endpoint:** ${server.endpointUrl}\n")
                    append("- **Transport:** ${server.transport}\n")
                    append("- **Status:** $status\n")
                    if (server.description.isNotBlank()) {
                        append("- **Description:** ${server.description}\n")
                    }
                    append("\n")
                }
            }
        }
    }

    suspend fun addServer(name: String, url: String, apiKey: String = "", transport: String = "SSE"): McpServerConfig = withContext(Dispatchers.IO) {
        val current = listServers().toMutableList()
        val newConfig = McpServerConfig(
            id = "mcp-${UUID.randomUUID().toString().take(8)}",
            name = name.trim().ifBlank { "Custom MCP" },
            endpointUrl = url.trim(),
            apiKey = apiKey.trim(),
            isEnabled = true,
            transport = transport,
            description = "Custom MCP server endpoint"
        )
        current.add(newConfig)
        saveServers(current)
        newConfig
    }

    suspend fun updateServer(id: String, name: String, url: String, apiKey: String = "", transport: String = "SSE") = withContext(Dispatchers.IO) {
        val updated = listServers().map {
            if (it.id == id) {
                it.copy(
                    name = name.trim().ifBlank { it.name },
                    endpointUrl = url.trim(),
                    apiKey = apiKey.trim(),
                    transport = transport
                )
            } else it
        }
        saveServers(updated)
    }

    suspend fun toggleServer(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val updated = listServers().map {
            if (it.id == id) it.copy(isEnabled = enabled) else it
        }
        saveServers(updated)
    }

    suspend fun deleteServer(id: String) = withContext(Dispatchers.IO) {
        val updated = listServers().filter { it.id != id }
        saveServers(updated)
    }

    suspend fun getEnabledServers(): List<McpServerConfig> = withContext(Dispatchers.IO) {
        listServers().filter { it.isEnabled }
    }

    suspend fun getMcpPromptContext(): String = withContext(Dispatchers.IO) {
        val enabled = getEnabledServers()
        if (enabled.isEmpty()) return@withContext ""
        buildString {
            append("\n\n---\n# Connected MCP Servers (Model Context Protocol)\n")
            append("The following external MCP servers are available for tool calls and context:\n")
            enabled.forEach { s ->
                append("- **${s.name}**: ${s.endpointUrl} (Transport: ${s.transport})\n")
                if (s.description.isNotBlank()) append("  Purpose: ${s.description}\n")
            }
            append("You can coordinate operations through these endpoints when tasks require them.\n\n")
        }
    }
}

