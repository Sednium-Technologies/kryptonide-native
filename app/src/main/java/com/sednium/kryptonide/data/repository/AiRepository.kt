package com.sednium.kryptonide.data.repository

import com.sednium.kryptonide.data.model.ChatMessage
import com.sednium.kryptonide.data.model.ChatSession
import com.sednium.kryptonide.data.storage.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class SessionStats(val messageCount: Int = 0, val lastSnippet: String = "")

class AiRepository(private val db: AppDatabase) {

    suspend fun getSessionsForProject(projectId: String): List<ChatSession> =
        db.getChatStore(projectId).sessions.sortedByDescending { it.updatedAt }

    suspend fun getSessionStats(projectId: String): Map<String, SessionStats> = withContext(Dispatchers.IO) {
        val store = db.getChatStore(projectId)
        val grouped = store.messages.groupBy { it.sessionId }
        store.sessions.associate { s ->
            val msgs = grouped[s.id] ?: emptyList()
            val lastMsg = msgs.lastOrNull { it.role != com.sednium.kryptonide.data.model.MessageRole.TOOL }
                ?.content?.trim()?.take(80)?.replace("\n", " ") ?: ""
            s.id to SessionStats(messageCount = msgs.size, lastSnippet = lastMsg)
        }
    }

    suspend fun createSession(projectId: String, title: String = "New Chat"): ChatSession = withContext(Dispatchers.IO) {
        val session = ChatSession(id = UUID.randomUUID().toString(), projectId = projectId, title = title)
        val store = db.getChatStore(projectId)
        db.saveChatStore(projectId, store.copy(sessions = store.sessions + session))
        session
    }

    suspend fun updateSession(session: ChatSession) = withContext(Dispatchers.IO) {
        val store = db.getChatStore(session.projectId)
        val sessions = store.sessions.toMutableList()
        val idx = sessions.indexOfFirst { it.id == session.id }
        if (idx >= 0) sessions[idx] = session.copy(updatedAt = System.currentTimeMillis())
        db.saveChatStore(session.projectId, store.copy(sessions = sessions))
    }

    suspend fun deleteSession(projectId: String, sessionId: String) = withContext(Dispatchers.IO) {
        val store = db.getChatStore(projectId)
        db.saveChatStore(projectId, store.copy(
            sessions = store.sessions.filter { it.id != sessionId },
            messages = store.messages.filter { it.sessionId != sessionId },
        ))
    }

    suspend fun getMessages(projectId: String, sessionId: String): List<ChatMessage> =
        db.getChatStore(projectId).messages
            .filter { it.sessionId == sessionId }
            .sortedBy { it.timestamp }

    suspend fun insertMessage(message: ChatMessage) = withContext(Dispatchers.IO) {
        val store = db.getChatStore(message.projectId)
        val sessions = store.sessions.toMutableList()
        val idx = sessions.indexOfFirst { it.id == message.sessionId }
        if (idx >= 0) sessions[idx] = sessions[idx].copy(updatedAt = System.currentTimeMillis())
        db.saveChatStore(message.projectId, store.copy(
            messages = store.messages + message,
            sessions = sessions,
        ))
    }

    suspend fun clearSessionMessages(projectId: String, sessionId: String) = withContext(Dispatchers.IO) {
        val store = db.getChatStore(projectId)
        db.saveChatStore(projectId, store.copy(
            messages = store.messages.filter { it.sessionId != sessionId }
        ))
    }

    suspend fun deleteMessage(projectId: String, messageId: String) = withContext(Dispatchers.IO) {
        val store = db.getChatStore(projectId)
        db.saveChatStore(projectId, store.copy(
            messages = store.messages.filter { it.id != messageId }
        ))
    }
}
