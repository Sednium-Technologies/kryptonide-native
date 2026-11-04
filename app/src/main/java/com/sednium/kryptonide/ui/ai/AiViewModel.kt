package com.sednium.kryptonide.ui.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sednium.kryptonide.data.model.ChatMessage
import com.sednium.kryptonide.data.model.ChatSession
import com.sednium.kryptonide.data.model.FileType
import com.sednium.kryptonide.data.model.MessageRole
import com.sednium.kryptonide.data.model.SavedModel
import com.sednium.kryptonide.data.repository.AiChunk
import com.sednium.kryptonide.data.repository.AiEngine
import com.sednium.kryptonide.data.repository.AiRepository
import com.sednium.kryptonide.data.storage.AppDatabase
import com.sednium.kryptonide.data.storage.SettingsStore
import com.sednium.kryptonide.ui.editor.EditorViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.util.UUID

enum class ModelRamStatus {
    IDLE,
    LOADING,
    READY,
    ERROR
}

data class AiUiState(
    val sessions: List<ChatSession> = emptyList(),
    val activeSessionId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val sessionStats: Map<String, com.sednium.kryptonide.data.repository.SessionStats> = emptyMap(),
    val isLoading: Boolean = false,
    val streamingText: String = "",
    val provider: String = "openai",
    val model: String = "gpt-4o",
    val baseUrl: String = "",
    val savedModels: List<SavedModel> = emptyList(),
    val activeModelId: String? = null,
    val modelRamStatus: ModelRamStatus = ModelRamStatus.IDLE,
    val modelRamMessage: String? = null,
)

class AiViewModel(app: Application) : AndroidViewModel(app) {

    private val repo     = AiRepository(AppDatabase.getInstance(app))
    private val settings = SettingsStore(app)
    private val engine   = AiEngine()
    private val settingsStore = com.sednium.kryptonide.data.storage.SettingsStore(app)
    private val skillsRepo = com.sednium.kryptonide.data.repository.SkillsRepository(app)
    private val mcpRepo = com.sednium.kryptonide.data.mcp.McpRepository(app)

    val aifMemoryManager = com.sednium.kryptonide.agent.aif.AifMemoryManager(app)
    val oortyEngine = com.sednium.kryptonide.agent.oorty.OortyLiteEngine(aifMemoryManager)
    private val mcpToolBridge = com.sednium.kryptonide.agent.tools.McpToolBridge(mcpRepo)

    private val _state = MutableStateFlow(AiUiState())
    val state: StateFlow<AiUiState> = _state.asStateFlow()

    private val _draftPromptText = MutableStateFlow("")
    val draftPromptText: StateFlow<String> = _draftPromptText.asStateFlow()

    private val _draftAttachedFiles = MutableStateFlow<Set<String>>(emptySet())
    val draftAttachedFiles: StateFlow<Set<String>> = _draftAttachedFiles.asStateFlow()

    fun setDraftPromptText(text: String) {
        _draftPromptText.value = text
    }

    fun setDraftAttachedFiles(files: Set<String>) {
        _draftAttachedFiles.value = files
    }

    private var streamJob: Job? = null
    private var currentProjectId: String? = null
    private var editorVm: EditorViewModel? = null
    private var gitVm: com.sednium.kryptonide.ui.git.GitViewModel? = null
    private var needleToolExecutionResult: String? = null
    private var needlePreFlightFindings: String? = null

    init {
        com.sednium.kryptonide.agent.pocket.LocalGgufServerManager.appContext = app
        viewModelScope.launch {
            val p = settings.aiProvider.first()
            val m = settings.aiModel.first()
            val resolvedModel = if (p == "pocket" && (m.isBlank() || m == "gpt-4o")) "needle-3-q4" else m
            _state.value = _state.value.copy(
                provider = p,
                model    = resolvedModel,
                baseUrl  = settings.aiBaseUrl.first(),
                savedModels = settings.savedModels.first(),
                activeModelId = settings.activeModelId.first(),
            )
            if (p == "pocket") {
                preloadPocketModel(resolvedModel)
            }
        }
    }

    fun init(editorViewModel: EditorViewModel, gitViewModel: com.sednium.kryptonide.ui.git.GitViewModel? = null) {
        editorVm = editorViewModel
        if (gitViewModel != null) {
            gitVm = gitViewModel
        }
        val rootDir = {
            editorViewModel.getProjectDirectory()
        }
        com.sednium.kryptonide.agent.tools.NeedleToolExecutor.editorVmProvider = { editorVm }
        com.sednium.kryptonide.agent.tools.NeedleToolExecutor.projectDirProvider = rootDir
        com.sednium.kryptonide.agent.tools.NeedleToolExecutor.contextProvider = { getApplication() }
        com.sednium.kryptonide.agent.tools.NeedleToolExecutor.activeFilePathProvider = {
            editorVm?.state?.value?.activeFileId?.let { id -> editorVm?.state?.value?.files?.get(id)?.name }
        }
        com.sednium.kryptonide.agent.tools.NeedleToolExecutor.registerAll()
        com.sednium.kryptonide.agent.tools.FsToolSet(rootDir).registerAll()
        com.sednium.kryptonide.agent.tools.TerminalToolSet(getApplication(), rootDir).registerAll()
        com.sednium.kryptonide.agent.tools.LspToolSet(rootDir).registerAll()
        com.sednium.kryptonide.agent.tools.WebSearchToolSet().registerAll()
        com.sednium.kryptonide.agent.tools.GitHubToolSet(
            getLinkedRepo = { getLinkedRepo() },
            setLinkedRepo = { repo ->
                if (repo != null) {
                    gitVm?.linkRepo(repo)
                } else {
                    gitVm?.unlinkRepo()
                }
            },
            getGithubToken = { gitVm?.getStoredGithubToken() ?: settingsStore.getGithubToken() },
            getFiles = {
                val allFiles = editorViewModel.state.value.files.values
                    .filter { it.type == FileType.FILE && it.content != null }
                val fileMap = editorViewModel.state.value.files
                allFiles.map { file ->
                    val relPath = editorViewModel.buildPath(file, fileMap).ifEmpty { file.name }
                    relPath to (file.content ?: "")
                }
            },
            getProjectRootDir = rootDir,
            onFilesChanged = {
                editorViewModel.reloadCurrentProject()
            }
        ).registerAll()
        com.sednium.kryptonide.agent.aif.AifProtocols(aifMemoryManager) { currentProjectId ?: "default" }.registerAll()

        viewModelScope.launch {
            mcpToolBridge.syncToolsFromConfiguredServers()
            _state.value = _state.value.copy(
                provider = settings.aiProvider.first(),
                model    = settings.aiModel.first(),
                baseUrl  = settings.aiBaseUrl.first(),
                savedModels = settings.savedModels.first(),
                activeModelId = settings.activeModelId.first(),
            )
        }
    }

    fun setGitViewModel(gVm: com.sednium.kryptonide.ui.git.GitViewModel) {
        gitVm = gVm
    }

    fun getLinkedRepo(): String? = gitVm?.state?.value?.linkedRepo

    fun getGithubAuthStatus(): String {
        val user = gitVm?.state?.value?.githubUser
        return if (user != null) {
            "Signed in as @${user.login}"
        } else if (gitVm?.getStoredGithubToken()?.isNotBlank() == true || settingsStore.getGithubToken().isNotBlank()) {
            "Authenticated (Token configured)"
        } else {
            "Not signed in"
        }
    }

    fun loadForProject(projectId: String) {
        currentProjectId = projectId
        viewModelScope.launch {
            val sessions = repo.getSessionsForProject(projectId)
            val stats = repo.getSessionStats(projectId)
            val active = sessions.firstOrNull { (stats[it.id]?.messageCount ?: 0) > 0 } ?: sessions.firstOrNull()
            val messages = active?.let { repo.getMessages(it.projectId, it.id) } ?: emptyList()
            _state.value = _state.value.copy(
                sessions = sessions,
                sessionStats = stats,
                activeSessionId = active?.id,
                messages = messages,
            )
        }
    }

    fun clearSessions() {
        currentProjectId = null
        _state.value = _state.value.copy(sessions = emptyList(), activeSessionId = null, messages = emptyList())
    }

    // ── Settings ─────────────────────────────────────────────

    fun setProvider(p: String) {
        _state.value = _state.value.copy(provider = p)
        viewModelScope.launch { settings.setAiProvider(p) }
        if (p == "pocket") {
            preloadPocketModel(_state.value.model)
        }
    }

    fun setModel(m: String) {
        _state.value = _state.value.copy(model = m)
        viewModelScope.launch { settings.setAiModel(m) }
        if (_state.value.provider == "pocket") {
            preloadPocketModel(m)
        }
    }

    fun preloadPocketModel(modelName: String? = null) {
        val targetModel = modelName ?: _state.value.model
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _state.value = _state.value.copy(
                modelRamStatus = ModelRamStatus.LOADING,
                modelRamMessage = "Loading into RAM..."
            )
            val scanner = com.sednium.kryptonide.agent.pocket.StorageModelScanner(getApplication())
            val models = scanner.scanModels()
            val cleanQuery = targetModel.substringBeforeLast('.').lowercase(java.util.Locale.ROOT)
            if (cleanQuery.contains("needle") || targetModel.contains("needle")) {
                _state.value = _state.value.copy(
                    modelRamStatus = ModelRamStatus.READY,
                    modelRamMessage = "Ready in RAM • Needle 3 (System-1 Tool Engine)"
                )
                // If a background GGUF chat model is available, start it in llama-server
                val ggufModel = models.firstOrNull { it.matchedCatalogSpec?.id != "needle-3-q4" && com.sednium.kryptonide.agent.pocket.LocalGgufServerManager.isGgufFile(it.file) }
                if (ggufModel != null) {
                    val serverBinary = com.sednium.kryptonide.agent.pocket.LocalGgufServerManager.findServerBinary(getApplication())
                    if (serverBinary != null) {
                        com.sednium.kryptonide.agent.pocket.LocalGgufServerManager.startServer(
                            context = getApplication(),
                            modelFile = ggufModel.file
                        )
                    }
                }
                return@launch
            }

            val matched = models.firstOrNull {
                it.name.lowercase(java.util.Locale.ROOT).contains(cleanQuery) ||
                it.matchedCatalogSpec?.id?.lowercase(java.util.Locale.ROOT)?.contains(cleanQuery) == true
            } ?: models.firstOrNull()

            if (matched == null || !matched.file.exists()) {
                _state.value = _state.value.copy(
                    modelRamStatus = ModelRamStatus.ERROR,
                    modelRamMessage = "Model not found on storage"
                )
                return@launch
            }

            val serverBinary = com.sednium.kryptonide.agent.pocket.LocalGgufServerManager.findServerBinary(getApplication())
            val loaded = if (serverBinary != null) {
                com.sednium.kryptonide.agent.pocket.LocalGgufServerManager.startServer(
                    context = getApplication(),
                    modelFile = matched.file
                )
            } else {
                val runner = com.sednium.kryptonide.agent.pocket.LocalGgufRunner(getApplication())
                runner.preloadModel(matched.file)
            }

            val shortName = matched.matchedCatalogSpec?.name ?: matched.name.substringBeforeLast('.')
            if (loaded) {
                _state.value = _state.value.copy(
                    modelRamStatus = ModelRamStatus.READY,
                    modelRamMessage = "Ready in RAM • $shortName"
                )
            } else {
                _state.value = _state.value.copy(
                    modelRamStatus = ModelRamStatus.ERROR,
                    modelRamMessage = "RAM load failed ($shortName)"
                )
            }
        }
    }

    fun setBaseUrl(url: String) {
        _state.value = _state.value.copy(baseUrl = url)
        viewModelScope.launch { settings.setAiBaseUrl(url) }
    }

    fun setApiKey(key: String) = settings.setApiKey(key)

    fun getApiKey(): String {
        val s = _state.value
        val activeModel = s.savedModels.firstOrNull { it.id == s.activeModelId }
        if (activeModel != null && activeModel.apiKey.isNotBlank()) {
            return activeModel.apiKey
        }
        return if (s.provider == "gemini") {
            settings.getGeminiKey().ifBlank { settings.getApiKey() }
        } else {
            settings.getApiKey()
        }
    }

    fun hasValidApiKey(): Boolean {
        val s = _state.value
        return s.provider == "ollama" || s.provider == "pocket" || getApiKey().isNotBlank()
    }

    fun getProjectFilePaths(): List<String> {
        val tree = editorVm?.getProjectFileTree() ?: return emptyList()
        if (tree == "(empty project)") return emptyList()
        return tree.lines().filter { it.isNotBlank() }
    }

    fun getRawFileContent(path: String): String? = editorVm?.getRawFileContent(path)

    fun addSavedModel(model: SavedModel) {
        val shouldActivate = _state.value.savedModels.isEmpty() || _state.value.activeModelId == null
        val updated = _state.value.savedModels + model
        _state.value = _state.value.copy(savedModels = updated)
        viewModelScope.launch { settings.setSavedModels(updated) }
        if (shouldActivate) {
            setActiveSavedModel(model.id)
        }
    }

    fun updateSavedModel(model: SavedModel) {
        val updated = _state.value.savedModels.map { if (it.id == model.id) model else it }
        _state.value = _state.value.copy(savedModels = updated)
        viewModelScope.launch { settings.setSavedModels(updated) }
        if (_state.value.activeModelId == model.id) {
            setActiveSavedModel(model.id)
        }
    }

    fun removeSavedModel(id: String) {
        val updated = _state.value.savedModels.filter { it.id != id }
        val wasActive = _state.value.activeModelId == id
        val nextActive = if (wasActive) updated.firstOrNull()?.id else _state.value.activeModelId
        _state.value = _state.value.copy(savedModels = updated, activeModelId = nextActive)
        viewModelScope.launch {
            settings.setSavedModels(updated)
            if (wasActive) {
                if (nextActive != null) {
                    setActiveSavedModel(nextActive)
                } else {
                    settings.setActiveModelId(null)
                }
            }
        }
    }

    fun setActiveSavedModel(id: String) {
        val model = _state.value.savedModels.firstOrNull { it.id == id } ?: return
        _state.value = _state.value.copy(
            activeModelId = id,
            provider = model.provider,
            model = model.model,
            baseUrl = model.baseUrl,
        )
        if (model.apiKey.isNotBlank()) {
            if (model.provider == "gemini") {
                settings.setGeminiKey(model.apiKey)
            } else {
                settings.setApiKey(model.apiKey)
            }
        }
        viewModelScope.launch {
            settings.setAiProvider(model.provider)
            settings.setAiModel(model.model)
            settings.setAiBaseUrl(model.baseUrl)
            settings.setActiveModelId(id)
        }
        if (model.provider == "pocket") {
            preloadPocketModel(model.model)
        }
    }

    // ── Sessions ─────────────────────────────────────────────

    fun createSession(): String {
        val pid = currentProjectId ?: "default"
        val current = _state.value
        val activeSession = current.sessions.firstOrNull { it.id == current.activeSessionId }
        if (activeSession != null && activeSession.title == "New Chat" && current.messages.isEmpty()) {
            return activeSession.id
        }
        var newId = ""
        viewModelScope.launch {
            val session = repo.createSession(pid)
            newId = session.id
            val sessions = repo.getSessionsForProject(pid)
            val stats = repo.getSessionStats(pid)
            _state.value = _state.value.copy(
                sessions = sessions,
                sessionStats = stats,
                activeSessionId = session.id,
                messages = emptyList()
            )
        }
        return newId
    }

    fun renameSession(id: String, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isBlank()) return
        val session = _state.value.sessions.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            repo.updateSession(session.copy(title = trimmed))
            refreshSessions()
        }
    }

    fun setActiveSession(id: String) {
        val pid = currentProjectId ?: "default"
        viewModelScope.launch {
            val messages = repo.getMessages(pid, id)
            _state.value = _state.value.copy(activeSessionId = id, messages = messages)
        }
    }

    fun deleteSession(id: String) {
        val pid = currentProjectId ?: "default"
        viewModelScope.launch {
            repo.deleteSession(pid, id)
            val sessions = repo.getSessionsForProject(pid)
            val stats = repo.getSessionStats(pid)
            val newActive = sessions.firstOrNull()?.id
            val messages = newActive?.let { repo.getMessages(pid, it) } ?: emptyList()
            _state.value = _state.value.copy(
                sessions = sessions,
                sessionStats = stats,
                activeSessionId = newActive,
                messages = messages
            )
        }
    }

    fun clearAllSessions() {
        val pid = currentProjectId ?: "default"
        streamJob?.cancel()
        viewModelScope.launch {
            val sessions = repo.getSessionsForProject(pid)
            sessions.forEach { repo.deleteSession(pid, it.id) }
            val newSession = repo.createSession(pid)
            _state.value = _state.value.copy(
                sessions = listOf(newSession),
                sessionStats = mapOf(newSession.id to com.sednium.kryptonide.data.repository.SessionStats(0, "")),
                activeSessionId = newSession.id,
                messages = emptyList(),
                isLoading = false,
                streamingText = ""
            )
        }
    }

    fun clearCurrentSession() {
        val pid = currentProjectId ?: "default"
        val sid = _state.value.activeSessionId ?: return
        streamJob?.cancel()
        _draftPromptText.value = ""
        _draftAttachedFiles.value = emptySet()
        viewModelScope.launch {
            repo.clearSessionMessages(pid, sid)
            _state.value = _state.value.copy(messages = emptyList(), isLoading = false, streamingText = "")
        }
    }

    fun deleteSingleMessage(messageId: String) {
        val pid = currentProjectId ?: "default"
        viewModelScope.launch {
            repo.deleteMessage(pid, messageId)
            val remaining = _state.value.messages.filter { it.id != messageId }
            _state.value = _state.value.copy(messages = remaining)
        }
    }

    fun regenerateMessage(messageId: String, systemContext: String) {
        val pid = currentProjectId ?: "default"
        val sid = _state.value.activeSessionId ?: return
        val idx = _state.value.messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val target = _state.value.messages[idx]
        viewModelScope.launch {
            // If regenerating an assistant message, drop it and any subsequent messages; if user message, keep it and drop subsequent
            val keepUntil = if (target.role == MessageRole.USER) idx + 1 else idx
            val toDelete = _state.value.messages.drop(keepUntil)
            toDelete.forEach { repo.deleteMessage(pid, it.id) }
            val remaining = _state.value.messages.take(keepUntil)
            if (remaining.isEmpty()) return@launch
            _state.value = _state.value.copy(messages = remaining, isLoading = true, streamingText = "")
            val assistantTurnId = UUID.randomUUID().toString()
            currentTurnId = assistantTurnId
            runCatching { com.sednium.kryptonide.agent.checkpoint.CheckpointManager.startTurn(assistantTurnId) }
            streamJob?.cancel()
            streamJob = viewModelScope.launch {
                runChatRecursive(sid, systemContext, remaining)
            }
        }
    }

    fun retryFromError(errorMessageId: String, systemContext: String) {
        val pid = currentProjectId ?: "default"
        val sid = _state.value.activeSessionId ?: return
        viewModelScope.launch {
            repo.deleteMessage(pid, errorMessageId)
            val remaining = _state.value.messages.filter { it.id != errorMessageId }
            _state.value = _state.value.copy(messages = remaining, isLoading = true, streamingText = "")
            val assistantTurnId = UUID.randomUUID().toString()
            currentTurnId = assistantTurnId
            runCatching { com.sednium.kryptonide.agent.checkpoint.CheckpointManager.startTurn(assistantTurnId) }
            streamJob?.cancel()
            streamJob = viewModelScope.launch {
                runChatRecursive(sid, systemContext, remaining)
            }
        }
    }

    // ── Send message ─────────────────────────────────────────

    fun sendMessage(userText: String, systemContext: String) {
        viewModelScope.launch {
            try {
                var sessionId = _state.value.activeSessionId
                val pid = currentProjectId ?: "default"
                val autoTitle = userText.lineSequence().firstOrNull()?.take(42)?.trim()?.ifBlank { "New Chat" } ?: "New Chat"
                if (sessionId == null) {
                    val session = repo.createSession(pid, title = autoTitle)
                    sessionId = session.id
                    val sessions = repo.getSessionsForProject(pid)
                    _state.value = _state.value.copy(
                        sessions = sessions,
                        activeSessionId = sessionId,
                        messages = emptyList()
                    )
                } else {
                    val existingSession = _state.value.sessions.firstOrNull { it.id == sessionId }
                    if (existingSession != null && existingSession.title == "New Chat") {
                        repo.updateSession(existingSession.copy(title = autoTitle))
                        refreshSessions()
                    }
                }

                val userMsg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    projectId = pid,
                    role = MessageRole.USER,
                    content = userText,
                )

                val currentMessages = _state.value.messages + userMsg
                _state.value = _state.value.copy(messages = currentMessages, isLoading = true, streamingText = "")

                repo.insertMessage(userMsg)
                val assistantTurnId = UUID.randomUUID().toString()
                currentTurnId = assistantTurnId
                runCatching { com.sednium.kryptonide.agent.checkpoint.CheckpointManager.startTurn(assistantTurnId) }

                // 0. System-1 Needle Tool Dispatcher: parse intent with sub-15ms latency
                val activeNode = editorVm?.state?.value?.activeFileId?.let { editorVm?.state?.value?.files?.get(it) }
                val currentLinkedRepo = getLinkedRepo()
                val needleDispatch = com.sednium.kryptonide.agent.pocket.NeedleToolDispatcher.parseToolCall(
                    prompt = userText,
                    activeFilePath = activeNode?.name,
                    linkedRepo = currentLinkedRepo
                )

                if (needleDispatch.toolCall != null) {
                    val toolName = needleDispatch.toolCall.toolName
                    val argsObj = buildJsonObject {
                        needleDispatch.toolCall.arguments.forEach { (k, v) ->
                            put(k, v.toString())
                        }
                    }
                    val output = executeTool(toolName, argsObj)

                    val isDirectActionTool = toolName in listOf("github_connect", "github_disconnect", "github_clone", "github_push", "github_status", "execute_terminal_command")
                    if (isDirectActionTool) {
                        _state.value = _state.value.copy(isLoading = false, streamingText = "")
                        val assistantMsg = ChatMessage(
                            id = UUID.randomUUID().toString(),
                            sessionId = _state.value.activeSessionId ?: "default",
                            projectId = currentProjectId ?: "default",
                            role = MessageRole.ASSISTANT,
                            content = output
                        )
                        repo.insertMessage(assistantMsg)
                        _state.value = _state.value.copy(
                            messages = _state.value.messages + assistantMsg,
                            isLoading = false
                        )
                        return@launch
                    }

                    needleToolExecutionResult = "<!-- [Needle-3 On-Device System-1 Co-Processor executed '$toolName' in ${needleDispatch.latencyMs}ms] -->\n**System-1 Tool Execution ($toolName)**:\n```\n$output\n```\n\n"
                } else {
                    needleToolExecutionResult = null
                }

                // If running on-device pocket model, await RAM loading if currently in progress
                if (_state.value.provider == "pocket") {
                    if (_state.value.modelRamStatus == ModelRamStatus.LOADING) {
                        val waitStart = System.currentTimeMillis()
                        while (_state.value.modelRamStatus == ModelRamStatus.LOADING && System.currentTimeMillis() - waitStart < 12_000L) {
                            kotlinx.coroutines.delay(200)
                        }
                    } else if (_state.value.modelRamStatus != ModelRamStatus.READY) {
                        preloadPocketModel(_state.value.model)
                    }
                }

                streamJob?.cancel()
                val targetSessionId = sessionId
                streamJob = viewModelScope.launch {
                    runChatRecursive(targetSessionId, systemContext, currentMessages)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.e("AiViewModel", "Error in sendMessage: ${t.message}", t)
                _state.value = _state.value.copy(isLoading = false, streamingText = "")
                val errMsg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    sessionId = _state.value.activeSessionId ?: "default",
                    projectId = currentProjectId ?: "default",
                    role = MessageRole.ASSISTANT,
                    content = "⚠️ Error: ${t.localizedMessage ?: t.message ?: "Failed to process message"}"
                )
                _state.value = _state.value.copy(messages = _state.value.messages + errMsg)
            }
        }
    }

    private var currentTurnId: String? = null

    fun revertTurn(turnId: String) {
        viewModelScope.launch {
            val vm = editorVm ?: return@launch
            val success = com.sednium.kryptonide.agent.checkpoint.CheckpointManager.revertTurn(turnId) { path, content ->
                if (content != null) {
                    vm.writeFileContent(path, content)
                } else {
                    val node = vm.state.value.files.values.firstOrNull {
                        it.name == path || vm.buildPath(it, vm.state.value.files) == path
                    }
                    node?.let { vm.deleteFile(it.id) }
                }
            }
            if (success) {
                _state.value = _state.value.copy(messages = _state.value.messages.toList())
            }
        }
    }

    fun hasCheckpointForTurn(turnId: String): Boolean =
        com.sednium.kryptonide.agent.checkpoint.CheckpointManager.hasCheckpoint(turnId)

    fun getModifiedFilesForTurn(turnId: String): List<String> =
        com.sednium.kryptonide.agent.checkpoint.CheckpointManager.getModifiedFiles(turnId)

    fun isTurnReverted(turnId: String): Boolean =
        com.sednium.kryptonide.agent.checkpoint.CheckpointManager.isReverted(turnId)

    fun abortChat() {
        streamJob?.cancel()
        _state.value = _state.value.copy(isLoading = false, streamingText = "")
    }

    private fun pruneConversationMessages(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.size <= 3) return messages

        // 1. Truncate verbose older tool outputs to protect token budget
        val compacted = messages.mapIndexed { index, msg ->
            if (msg.role == MessageRole.TOOL && index < messages.size - 2) {
                val content = msg.content
                if (content.length > 500) {
                    val lines = content.lines()
                    val truncated = lines.take(6).joinToString("\n") + "\n... [truncated ${lines.size - 6} lines of earlier tool output]"
                    msg.copy(content = truncated)
                } else msg
            } else msg
        }

        // 2. Cap total conversational character length to ~24,000 chars (approx 6,000 tokens)
        val totalLength = compacted.sumOf { it.content.length }
        if (totalLength > 24_000 && compacted.size > 5) {
            val firstUser = compacted.firstOrNull { it.role == MessageRole.USER }
            val recent = compacted.takeLast(6)
            return if (firstUser != null && !recent.contains(firstUser)) {
                listOf(firstUser) + recent
            } else {
                recent
            }
        }

        return compacted
    }

    private suspend fun runChatRecursive(sessionId: String, systemContext: String, messages: List<ChatMessage>, depth: Int = 0) {
        if (depth > 10) return // safety cap on tool recursion
        if (depth == 0) {
            val lastUserMsg = messages.lastOrNull { it.role == MessageRole.USER }
            if (lastUserMsg != null) {
                val activeNode = editorVm?.state?.value?.activeFileId?.let { editorVm?.state?.value?.files?.get(it) }
                val currentLinkedRepo = getLinkedRepo()
                val needleDispatch = com.sednium.kryptonide.agent.pocket.NeedleToolDispatcher.parseToolCall(
                    prompt = lastUserMsg.content,
                    activeFilePath = activeNode?.name,
                    linkedRepo = currentLinkedRepo
                )

                if (needleDispatch.toolCall != null) {
                    val toolName = needleDispatch.toolCall.toolName
                    val argsObj = buildJsonObject {
                        needleDispatch.toolCall.arguments.forEach { (k, v) ->
                            put(k, v.toString())
                        }
                    }
                    val output = executeTool(toolName, argsObj)

                    val isDirectActionTool = toolName in listOf("github_connect", "github_disconnect", "github_clone", "github_push", "github_status", "execute_terminal_command")
                    if (isDirectActionTool) {
                        _state.value = _state.value.copy(isLoading = false, streamingText = "")
                        val assistantMsg = ChatMessage(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            projectId = currentProjectId ?: "default",
                            role = MessageRole.ASSISTANT,
                            content = output
                        )
                        repo.insertMessage(assistantMsg)
                        _state.value = _state.value.copy(
                            messages = messages + assistantMsg,
                            isLoading = false
                        )
                        return
                    }

                    needleToolExecutionResult = "<!-- [Needle-3 On-Device System-1 Co-Processor executed '$toolName' in ${needleDispatch.latencyMs}ms] -->\n**System-1 Tool Execution ($toolName)**:\n```\n$output\n```\n\n"
                } else {
                    needleToolExecutionResult = null
                }

                // STEP 1 — Pre-Flight Workspace Discovery & Runtime Error Inspection (Needle-3)
                val preFlight = com.sednium.kryptonide.agent.pocket.NeedleAgentCoordinator.runPreFlight(
                    userPrompt = lastUserMsg.content,
                    projectDir = editorVm?.getProjectDirectory(),
                    activeFile = activeNode?.name,
                    onStatus = { statusText ->
                        _state.value = _state.value.copy(streamingText = statusText)
                    }
                )
                needlePreFlightFindings = if (preFlight.enrichedContextBlock.isNotBlank()) {
                    preFlight.enrichedContextBlock + "\n\n"
                } else null
            }

            com.sednium.kryptonide.service.KryptonAiService.start(getApplication())
        }
        try {
            val s = _state.value
            val accumulated = StringBuilder()
            val customPrompt = settingsStore.getSystemPrompt().trim()

            // 1. Extract dynamic turn context (<active_file> and <tagged_files>) from systemContext if present
            var extractedDynamicContext = ""
            val activeFileRegex = Regex("""<active_file[\s\S]*?<\/active_file>""", RegexOption.IGNORE_CASE)
            val taggedFilesRegex = Regex("""<tagged_files>[\s\S]*?<\/tagged_files>""", RegexOption.IGNORE_CASE)
            val activeMatch = activeFileRegex.find(systemContext)?.value
            val taggedMatch = taggedFilesRegex.find(systemContext)?.value
            if (activeMatch != null || taggedMatch != null) {
                extractedDynamicContext = listOfNotNull(activeMatch, taggedMatch).joinToString("\n\n")
            }

            // 2. Build static system prompt (frozen across all turns for 100% KV cache hit rate)
            val sanitizedSystemContext = systemContext
                .replace(activeFileRegex, "")
                .replace(taggedFilesRegex, "")
                .trim()

            val tree = editorVm?.getProjectFileTree() ?: "(unknown)"
            val staticBaseContext = if (!sanitizedSystemContext.contains("<project_file_tree>")) {
                "$sanitizedSystemContext\n\n<project_file_tree>\n$tree\n</project_file_tree>"
            } else {
                sanitizedSystemContext
            }

            val aifRecall = runCatching { aifMemoryManager.bootRecall(currentProjectId ?: "default") }.getOrDefault("")
            val basePrompt = if (customPrompt.isNotBlank()) {
                "$staticBaseContext\n\n$aifRecall\n\n---\n# Custom System Instructions\n$customPrompt"
            } else {
                "$staticBaseContext\n\n$aifRecall"
            }

            // 3. Inject dynamic turn context ONLY into the latest user message turn payload
            val prunedMessages = pruneConversationMessages(messages)
            val messagesWithTurnContext = if (prunedMessages.isNotEmpty() && prunedMessages.last().role == MessageRole.USER) {
                val lastMsg = prunedMessages.last()
                val alreadyHasContext = lastMsg.content.contains("<active_file") || lastMsg.content.contains("<tagged_files")
                val turnContextToInject = when {
                    alreadyHasContext -> ""
                    extractedDynamicContext.isNotBlank() -> "$extractedDynamicContext\n\n"
                    else -> {
                        val activeId = editorVm?.state?.value?.activeFileId
                        val activeNode = activeId?.let { editorVm?.state?.value?.files?.get(it) }
                        if (activeNode != null) {
                            val userQuery = lastMsg.content.lowercase().trim()
                            val isChatQuestion = listOf(
                                "why", "how", "what", "where", "who", "when", "can you explain",
                                "explain", "describe", "tell me", "help me understand", "hello", "hi", "hey",
                                "thanks", "thank you", "ok", "okay", "cool", "nice", "awesome",
                                "what is", "what are", "what does", "why is", "why does", "why did",
                                "how do", "how does", "how can"
                            ).any { userQuery.startsWith(it) || userQuery == it || userQuery.startsWith("$it ") || userQuery.endsWith("?") }

                            val isCreateRequest = listOf(
                                "make a", "create a", "build a", "design a", "write a",
                                "new website", "new app", "from scratch", "generate a",
                                "make website", "create website", "build website",
                                "make app", "create app", "build app"
                            ).any { userQuery.contains(it) }

                            val isExplicitCodeAction = isCreateRequest || listOf(
                                "fix the", "fix bug", "fix error", "change the", "update the", "modify the",
                                "refactor the", "patch the", "add a", "remove the", "delete the", "replace the",
                                "implement", "tweak the", "style the"
                            ).any { userQuery.contains(it) }

                            val isCodeRequest = isExplicitCodeAction && (!isChatQuestion || listOf("make", "create", "build", "write", "fix", "change", "update", "add").any { userQuery.startsWith(it) })

                            if (isCreateRequest && isCodeRequest) {
                                "Target file to overwrite: ${activeNode.name} (write a brand-new, complete implementation from scratch in <edit file=\"${activeNode.name}\">...code...</edit>)\n\n"
                            } else if (isCodeRequest) {
                                val numbered = (activeNode.content ?: "").take(6_000).lines().mapIndexed { i, l -> "${i + 1}: $l" }.joinToString("\n")
                                "<!-- Note: The current active file (${activeNode.name}) is shown below for reference. -->\n<active_file name=\"${activeNode.name}\">\n$numbered\n</active_file>\n\n"
                            } else {
                                ""
                            }
                        } else ""
                    }
                }

                val finalTurnContext = buildString {
                    if (needleToolExecutionResult != null && !lastMsg.content.contains("System-1 Tool Execution")) {
                        append(needleToolExecutionResult)
                    }
                    if (needlePreFlightFindings != null && !lastMsg.content.contains("<needle_workspace_findings>")) {
                        append(needlePreFlightFindings)
                    }
                    append(turnContextToInject)
                }

                if (finalTurnContext.isNotBlank()) {
                    prunedMessages.dropLast(1) + lastMsg.copy(content = finalTurnContext + lastMsg.content)
                } else {
                    prunedMessages
                }
            } else {
                prunedMessages
            }

            val skillsContent = runCatching { skillsRepo.getCombinedSkillsContent() }.getOrDefault("")
            val mcpContent = runCatching { mcpRepo.getMcpPromptContext() }.getOrDefault("")

            engine.streamChat(
                provider = s.provider,
                apiKey = getApiKey(),
                model = s.model,
                baseUrl = s.baseUrl,
                systemPrompt = basePrompt + skillsContent + mcpContent,
                messages = messagesWithTurnContext,
            ).collect { chunk ->
                when (chunk) {
                    is AiChunk.Token -> {
                        accumulated.append(chunk.text)
                        _state.value = _state.value.copy(streamingText = accumulated.toString())
                    }
                    is AiChunk.ToolCallComplete -> {
                        val callsJson = buildJsonArray {
                            chunk.calls.forEach { tc ->
                                addJsonObject {
                                    put("id", tc.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", tc.name)
                                        put("arguments", tc.arguments.toString())
                                    })
                                }
                            }
                        }.toString()

                        // Persist assistant partial message with tool calls
                        val cleanAccumulated = accumulated.toString().trim()
                        val assistantMsg = ChatMessage(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            projectId = currentProjectId ?: "",
                            role = MessageRole.ASSISTANT,
                            content = if (cleanAccumulated == "null" || cleanAccumulated == "nullnull" || cleanAccumulated.isBlank()) "" else cleanAccumulated,
                            toolCallsJson = callsJson
                        )
                        repo.insertMessage(assistantMsg)

                        // Execute each tool
                        val toolMessages = mutableListOf<ChatMessage>()
                        for (tc in chunk.calls) {
                            val output = executeTool(tc.name, tc.arguments)
                            val toolMsg = ChatMessage(
                                id = UUID.randomUUID().toString(),
                                sessionId = sessionId,
                                projectId = currentProjectId ?: "",
                                role = MessageRole.TOOL,
                                content = output,
                                toolCallId = tc.id,
                                toolName = tc.name,
                            )
                            repo.insertMessage(toolMsg)
                            toolMessages += toolMsg
                        }

                        val updatedMessages = messages + assistantMsg + toolMessages
                        _state.value = _state.value.copy(messages = updatedMessages, streamingText = "")

                        // Recurse
                        runChatRecursive(sessionId, systemContext, updatedMessages, depth + 1)
                    }
                    is AiChunk.Done -> {
                        val modelCalls = com.sednium.kryptonide.agent.pocket.NeedleToolDispatcher.extractModelToolCalls(chunk.fullText)
                        if (modelCalls.isNotEmpty() && depth < 5) {
                            val cleanAccumulated = chunk.fullText.trim()
                            val assistantMsg = ChatMessage(
                                id = UUID.randomUUID().toString(),
                                sessionId = sessionId,
                                projectId = currentProjectId ?: "",
                                role = MessageRole.ASSISTANT,
                                content = cleanAccumulated
                            )
                            repo.insertMessage(assistantMsg)

                            val toolMessages = mutableListOf<ChatMessage>()
                            for (call in modelCalls) {
                                val argsObj = buildJsonObject {
                                    call.arguments.forEach { (k, v) -> put(k, v.toString()) }
                                }
                                val output = executeTool(call.toolName, argsObj)
                                val toolMsg = ChatMessage(
                                    id = UUID.randomUUID().toString(),
                                    sessionId = sessionId,
                                    projectId = currentProjectId ?: "",
                                    role = MessageRole.TOOL,
                                    content = "[Needle-3 Tool Result (${call.toolName})]:\n$output",
                                    toolName = call.toolName
                                )
                                repo.insertMessage(toolMsg)
                                toolMessages += toolMsg
                            }
                            val updatedMessages = messages + assistantMsg + toolMessages
                            _state.value = _state.value.copy(messages = updatedMessages, streamingText = "")
                            runChatRecursive(sessionId, systemContext, updatedMessages, depth + 1)
                            return@collect
                        }

                        com.sednium.kryptonide.agent.notifications.AiNotificationHelper.cancelProgressNotification(getApplication())
                        if (com.sednium.kryptonide.utils.AppLifecycleTracker.isAppInBackground) {
                            val cleanPreview = com.sednium.kryptonide.agent.notifications.AiNotificationHelper.cleanPreviewText(chunk.fullText)
                            com.sednium.kryptonide.agent.notifications.AiNotificationHelper.showCompletionNotification(getApplication(), cleanPreview)
                        }
                        // STEP 3 — Post-Flight Autonomous Runtime Verification & Self-Healing
                        val lastUserText = messages.lastOrNull { it.role == MessageRole.USER }?.content?.lowercase(java.util.Locale.ROOT) ?: ""
                        val hasEditOrCode = chunk.fullText.contains("<edit") || chunk.fullText.contains("fs_patch_file") || chunk.fullText.contains("fs_write_file")
                        val isVerifyRequested = hasEditOrCode || listOf("check", "verify", "runtime", "fix", "error", "console", "test", "syntax").any { lastUserText.contains(it) }

                        val contentWithVerification = if (isVerifyRequested && depth == 0) {
                            val activeNode = editorVm?.state?.value?.activeFileId?.let { editorVm?.state?.value?.files?.get(it) }
                            val targetFile = Regex("""<edit\s+file=["']([^"']+)["']""").find(chunk.fullText)?.groupValues?.get(1)
                                ?: activeNode?.name
                                ?: "index.html"

                            val postFlight = com.sednium.kryptonide.agent.pocket.NeedleAgentCoordinator.runPostFlightVerification(
                                targetFilePath = targetFile,
                                projectDir = editorVm?.getProjectDirectory(),
                                onStatus = { statusText ->
                                    _state.value = _state.value.copy(streamingText = statusText)
                                }
                            )
                            if (!chunk.fullText.contains("Runtime Verification Summary")) {
                                chunk.fullText + postFlight.summaryCard
                            } else {
                                chunk.fullText
                            }
                        } else {
                            chunk.fullText
                        }

                        val finalMsg = ChatMessage(
                            id = currentTurnId ?: UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            projectId = currentProjectId ?: "",
                            role = MessageRole.ASSISTANT,
                            content = contentWithVerification,
                            completedAt = System.currentTimeMillis(),
                        )
                        repo.insertMessage(finalMsg)
                        val updated = _state.value.messages + finalMsg
                        _state.value = _state.value.copy(
                            messages = updated,
                            isLoading = false,
                            streamingText = "",
                        )
                        runCatching {
                            val lastUserPrompt = updated.lastOrNull { it.role == MessageRole.USER }?.content ?: ""
                            oortyEngine.onTurnCompleted(lastUserPrompt, chunk.fullText, currentProjectId ?: "default")
                        }
                        // Auto-title session on first exchange
                        if (updated.size <= 3) {
                            val title = updated.firstOrNull { it.role == MessageRole.USER }?.content
                                ?.take(40)?.trim() ?: "New Chat"
                            val session = _state.value.sessions.firstOrNull { it.id == sessionId }
                            session?.let { repo.updateSession(it.copy(title = title)) }
                            refreshSessions()
                        }
                        currentTurnId = null
                    }
                    is AiChunk.Error -> {
                        com.sednium.kryptonide.agent.notifications.AiNotificationHelper.cancelProgressNotification(getApplication())
                        if (com.sednium.kryptonide.utils.AppLifecycleTracker.isAppInBackground) {
                            com.sednium.kryptonide.agent.notifications.AiNotificationHelper.showCompletionNotification(getApplication(), "⚠️ " + chunk.message.take(100))
                        }
                        val isNetworkError = chunk.message.contains("Unable to resolve host", ignoreCase = true) ||
                            chunk.message.contains("ConnectException", ignoreCase = true) ||
                            chunk.message.contains("SocketTimeout", ignoreCase = true) ||
                            chunk.message.contains("timeout", ignoreCase = true) ||
                            chunk.message.contains("No address associated", ignoreCase = true)

                        val replyContent = if (isNetworkError && s.provider != "pocket") {
                            val lastUser = messages.lastOrNull { it.role == MessageRole.USER }?.content ?: ""
                            val reflex = com.sednium.kryptonide.agent.pocket.LayaSystemOneEngine.route(lastUser)
                            "⚠️ Network unreachable (Offline Mode).\n\nKryptonide AI Fast-Path Routing: ${reflex.route.name} (${reflex.reasoning})\n\nTo continue offline, start Pocket Server in Settings → Pocket Server, or reconnect to Wi-Fi/cellular."
                        } else {
                            "Error: ${chunk.message}"
                        }

                        val errMsg = ChatMessage(
                            id = currentTurnId ?: UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            projectId = currentProjectId ?: "",
                            role = MessageRole.ASSISTANT,
                            content = replyContent,
                        )
                        repo.insertMessage(errMsg)
                        _state.value = _state.value.copy(
                            messages = _state.value.messages + errMsg,
                            isLoading = false,
                            streamingText = "",
                        )
                        currentTurnId = null
                    }
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            android.util.Log.e("AiViewModel", "Chat execution failed: ${t.message}", t)
            val errMsg = ChatMessage(
                id = currentTurnId ?: UUID.randomUUID().toString(),
                sessionId = sessionId,
                projectId = currentProjectId ?: "",
                role = MessageRole.ASSISTANT,
                content = "⚠️ AI Error: ${t.localizedMessage ?: t.message ?: "An unexpected error occurred during inference."}"
            )
            runCatching { repo.insertMessage(errMsg) }
            _state.value = _state.value.copy(
                messages = _state.value.messages + errMsg,
                isLoading = false,
                streamingText = "",
            )
            currentTurnId = null
        } finally {
            if (depth == 0) {
                com.sednium.kryptonide.service.KryptonAiService.stop(getApplication())
                com.sednium.kryptonide.agent.notifications.AiNotificationHelper.cancelProgressNotification(getApplication())
            }
        }
    }

    /** Correctly decodes a JSON string argument — NOT `.toString().trim('"')`, which only
     *  strips the outer quote characters and leaves \n, \t, \", \\ as literal two-char
     *  escape sequences instead of real characters. That silently corrupted every
     *  multi-line tool argument (i.e. almost every real file write/edit). */
    private fun kotlinx.serialization.json.JsonObject.stringArg(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

    private suspend fun executeTool(name: String, args: kotlinx.serialization.json.JsonObject): String {
        // 1. Dispatch via Unified AgentToolRegistry
        val toolDef = com.sednium.kryptonide.agent.tools.AgentToolRegistry.getTool(name)
        if (toolDef != null) {
            val convertedArgs = mutableMapOf<String, Any?>()
            args.forEach { (k, v) ->
                convertedArgs[k] = when (v) {
                    is JsonPrimitive -> {
                        if (v.isString) v.content
                        else v.booleanOrNull ?: v.longOrNull ?: v.doubleOrNull ?: v.content
                    }
                    else -> v.toString()
                }
            }

            // Risk gate for shell execution
            if (name == "execute_terminal_command") {
                val cmd = convertedArgs["command"]?.toString() ?: ""
                val risk = com.sednium.kryptonide.agent.pocket.LayaSystemOneEngine.risk(cmd, oortyEngine.executionTier)
                if (!risk.isAllowed) {
                    return "Command execution blocked by Kryptonide AI Risk Classifier: ${risk.reason}"
                }
            }

            if (name in setOf("write_file", "edit_lines", "insert_lines", "patch_file", "fs_write_file", "fs_patch_file", "fs_create_file")) {
                val targetPath = convertedArgs["path"]?.toString() ?: ""
                if (targetPath.isNotBlank() && currentTurnId != null) {
                    val original = getRawFileContent(targetPath)
                    com.sednium.kryptonide.agent.checkpoint.CheckpointManager.recordPreEdit(currentTurnId!!, targetPath, original)
                }
            }

            val result = com.sednium.kryptonide.agent.tools.AgentToolRegistry.dispatch(name, convertedArgs)
            val rawOutput = if (result.success) result.output else (result.error ?: "Tool execution failed")
            return com.sednium.kryptonide.agent.pocket.LayaSystemOneEngine.prune(rawOutput)
        }

        // 2. Direct dispatch to NeedleToolExecutor
        val converted = mutableMapOf<String, Any?>()
        args.forEach { (k, v) ->
            converted[k] = when (v) {
                is JsonPrimitive -> if (v.isString) v.content else v.booleanOrNull ?: v.longOrNull ?: v.doubleOrNull ?: v.content
                else -> v.toString()
            }
        }
        val needleRes = com.sednium.kryptonide.agent.tools.NeedleToolExecutor.execute(name, converted)
        if (needleRes.success || needleRes.exitCode == 0) {
            val raw = if (needleRes.success) needleRes.output else (needleRes.error ?: "Tool error")
            return com.sednium.kryptonide.agent.pocket.LayaSystemOneEngine.prune(raw)
        }

        // 2. Fallback to existing EditorViewModel direct operations
        val path = args.stringArg("path") ?: return "Error: missing path"
        val vm = editorVm ?: return "Error: Editor environment not ready for tool execution"
        if (name in setOf("write_file", "edit_lines", "insert_lines", "patch_file")) {
            if (currentTurnId != null) {
                val original = getRawFileContent(path)
                com.sednium.kryptonide.agent.checkpoint.CheckpointManager.recordPreEdit(currentTurnId!!, path, original)
            }
        }
        return try {
            when (name) {
                "read_file" -> {
                    val startLine = args["start_line"]?.toString()?.toIntOrNull()
                    val endLine   = args["end_line"]?.toString()?.toIntOrNull()
                    val content   = vm.readFileContent(path) ?: return "Error: file not found at $path"
                    if (startLine != null && endLine != null) {
                        content.lines().drop(startLine - 1).take(endLine - startLine + 1).joinToString("\n")
                    } else content
                }
                "edit_lines" -> {
                    val start = args["start_line"]?.toString()?.toIntOrNull() ?: return "Error: missing start_line"
                    val end   = args["end_line"]?.toString()?.toIntOrNull() ?: return "Error: missing end_line"
                    val newContent = args.stringArg("new_content") ?: ""
                    vm.editLines(path, start, end, newContent)
                }
                "insert_lines" -> {
                    val atLine  = args["at_line"]?.toString()?.toIntOrNull() ?: return "Error: missing at_line"
                    val content = args.stringArg("content") ?: ""
                    vm.insertLines(path, atLine, content)
                }
                "patch_file" -> {
                    val search  = args.stringArg("search_string") ?: return "Error: missing search_string"
                    val replace = args.stringArg("replace_string") ?: ""
                    if (vm.patchFileContent(path, search, replace)) "Success: File patched."
                    else "Error: Search string not found. Use read_file first to see current content."
                }
                "write_new_file" -> {
                    val content = args.stringArg("content") ?: ""
                    vm.writeFileContent(path, content)
                    "Success: File created at $path"
                }
                "create_directory" -> {
                    // Directories are implicit in this model — just succeed
                    "Success: Directory path $path is ready."
                }
                else -> "Error: Unknown tool $name"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    suspend fun refreshSessions() {
        val pid = currentProjectId ?: return
        val sessions = repo.getSessionsForProject(pid)
        val stats = repo.getSessionStats(pid)
        _state.value = _state.value.copy(sessions = sessions, sessionStats = stats)
    }
}
