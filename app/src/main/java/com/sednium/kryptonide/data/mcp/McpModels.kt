package com.sednium.kryptonide.data.mcp

import kotlinx.serialization.Serializable

@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val endpointUrl: String,
    val apiKey: String = "",
    val isEnabled: Boolean = true,
    val transport: String = "SSE",
    val description: String = ""
)

val DEFAULT_MCP_PRESETS = emptyList<McpServerConfig>()
