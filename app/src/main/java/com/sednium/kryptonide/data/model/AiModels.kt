package com.sednium.kryptonide.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SavedModel(
    val id: String,
    val name: String,
    val provider: String,
    val model: String,
    val apiKey: String = "",
    val baseUrl: String = "",
)

enum class MessageRole { USER, ASSISTANT, TOOL, SYSTEM }

@Serializable
data class ChatMessage(
    val id: String,
    val sessionId: String,
    val projectId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCallsJson: String? = null,
)

@Serializable
data class ChatSession(
    val id: String,
    val projectId: String,
    val title: String = "New Chat",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
