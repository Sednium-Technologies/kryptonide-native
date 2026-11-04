package com.sednium.kryptonide.ui.larry

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sednium.kryptonide.ui.editor.EditorViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class LarryViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(LarryUiState())
    val state: StateFlow<LarryUiState> = _state.asStateFlow()

    private var currentJob: Job? = null

    // For simplicity, mimicking the structure expected by the interface.
    init {
        // Load initial mock values or from DataStore (which can be implemented later)
    }

    fun createNewSession(): String {
        val id = UUID.randomUUID().toString()
        val newSession = LarrySession(id = id, title = "New Chat")
        val sessions = listOf(newSession) + _state.value.sessions
        _state.value = _state.value.copy(sessions = sessions, activeSessionId = id)
        return id
    }

    fun setActiveSession(id: String) {
        _state.value = _state.value.copy(activeSessionId = id)
    }

    fun deleteSession(id: String) {
        val remaining = _state.value.sessions.filter { it.id != id }
        val newActive = if (_state.value.activeSessionId == id) remaining.firstOrNull()?.id else _state.value.activeSessionId
        _state.value = _state.value.copy(sessions = remaining, activeSessionId = newActive)
    }

    fun setSavedModel(model: SavedModel) {
        _state.value = _state.value.copy(savedModels = _state.value.savedModels + model)
    }

    fun deleteSavedModel(id: String) {
        val remaining = _state.value.savedModels.filter { it.id != id }
        val newActive = if (_state.value.activeModelId == id) null else _state.value.activeModelId
        _state.value = _state.value.copy(savedModels = remaining, activeModelId = newActive)
    }

    fun setActiveModel(id: String) {
        val model = _state.value.savedModels.find { it.id == id }
        if (model != null) {
            _state.value = _state.value.copy(
                activeModelId = id,
                provider = model.provider,
                model = model.model,
                baseUrl = model.baseUrl,
                apiKey = model.apiKey
            )
        }
    }

    fun setApiKey(key: String) {
        _state.value = _state.value.copy(apiKey = key)
    }

    fun setProvider(provider: String) {
        _state.value = _state.value.copy(provider = provider)
    }

    fun setModel(model: String) {
        _state.value = _state.value.copy(model = model)
    }

    fun sendMessage(
        sessionId: String,
        userPrompt: String,
        fileTree: String,
        activeFilePath: String?,
        taggedFiles: Map<String, String>,
        editorVm: EditorViewModel
    ) {
        val systemContext = LARRY_SYSTEM_PROMPT + "\n<project_file_tree>\n${fileTree}\n</project_file_tree>\n"

        val dynamicContext = buildString {
            if (activeFilePath != null) {
                append("<active_file path=\"$activeFilePath\" />\n\n")
            }
            if (taggedFiles.isNotEmpty()) {
                append("<tagged_files>\n")
                taggedFiles.forEach { (path, content) ->
                    append("<file path=\"$path\">\n$content\n</file>\n")
                }
                append("</tagged_files>\n\n")
            }
        }

        val fullUserPrompt = if (dynamicContext.isNotBlank()) dynamicContext + userPrompt else userPrompt
        val userMessage = LarryMessage("user", fullUserPrompt)
        addMessage(sessionId, userMessage)

        abortChat()

        currentJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, streamingText = "")
            // Mock response (api engine implementation goes here)
            _state.value = _state.value.copy(isLoading = false)
        }
    }

    private fun addMessage(sessionId: String, message: LarryMessage) {
        val sessions = _state.value.sessions.map { s ->
            if (s.id == sessionId) {
                s.copy(messages = s.messages + message, updatedAt = System.currentTimeMillis())
            } else s
        }
        _state.value = _state.value.copy(sessions = sessions)
    }

    fun abortChat() {
        currentJob?.cancel()
        currentJob = null
        _state.value = _state.value.copy(isLoading = false, streamingText = "")
    }
}
