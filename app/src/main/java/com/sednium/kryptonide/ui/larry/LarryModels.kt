package com.sednium.kryptonide.ui.larry

data class LarryMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCalls: List<LarryToolCall>? = null,
    val attachments: List<LarryAttachment>? = null
)

data class LarryToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

data class LarryAttachment(
    val name: String,
    val mimeType: String,
    val base64Data: String
)

data class LarrySession(
    val id: String,
    val title: String = "Chat",
    val messages: List<LarryMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class SavedModel(
    val id: String,
    val name: String,
    val provider: String,
    val model: String,
    val apiKey: String,
    val baseUrl: String
)

data class LarryUiState(
    val sessions: List<LarrySession> = emptyList(),
    val activeSessionId: String? = null,
    val isLoading: Boolean = false,
    val streamingText: String = "",
    val savedModels: List<SavedModel> = emptyList(),
    val activeModelId: String? = null,
    val provider: String = "gemini",
    val apiKey: String = "",
    val model: String = "",
    val baseUrl: String = ""
)
