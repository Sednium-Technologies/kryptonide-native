package com.sednium.kryptonide.ui.monaco

/**
 * Data models representing entities in the Monaco Editor ecosystem.
 */
data class MonacoCompletionItem(
    val label: String,
    val kind: Int = 1,
    val detail: String = "",
    val documentation: String = "",
    val insertText: String = "",
    val isSnippet: Boolean = false
)

data class MonacoRange(
    val startLineNumber: Int,
    val startColumn: Int,
    val endLineNumber: Int,
    val endColumn: Int
)

data class MonacoTextEdit(
    val range: MonacoRange,
    val text: String
)

data class MonacoMarker(
    val owner: String,
    val severity: Int,
    val message: String,
    val startLineNumber: Int,
    val startColumn: Int,
    val endLineNumber: Int,
    val endColumn: Int
)

data class MonacoPluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val main: String? = null,
    val description: String? = null,
    val languages: List<String> = emptyList()
)
