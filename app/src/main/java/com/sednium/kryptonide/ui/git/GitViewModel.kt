package com.sednium.kryptonide.ui.git

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sednium.kryptonide.data.model.FileNode
import com.sednium.kryptonide.data.model.FileType
import com.sednium.kryptonide.data.model.Project
import com.sednium.kryptonide.data.repository.GithubRepository
import com.sednium.kryptonide.data.repository.GitHubRepo
import com.sednium.kryptonide.data.repository.GitHubUser
import com.sednium.kryptonide.data.storage.AppDatabase
import com.sednium.kryptonide.data.storage.SettingsStore
import com.sednium.kryptonide.ui.editor.EditorViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class GitUiState(
    val githubUser: GitHubUser? = null,
    val repos: List<GitHubRepo> = emptyList(),
    val linkedRepo: String? = null,           // "owner/repo"
    val isLoadingRepos: Boolean = false,
    val isPushing: Boolean = false,
    val isPulling: Boolean = false,
    val statusMessage: String? = null,
    val isError: Boolean = false,
    val tokenInput: String = "",
    // Device Flow sign-in — the primary path now; tokenInput above remains
    // as a manual/advanced fallback.
    val deviceUserCode: String? = null,
    val deviceVerificationUri: String? = null,
    val isAwaitingDeviceApproval: Boolean = false,
    val deviceSecondsRemaining: Int = 0,
)

class GitViewModel(app: Application) : AndroidViewModel(app) {

    private val github     = GithubRepository()
    private val deviceAuth = com.sednium.kryptonide.data.repository.GitHubDeviceAuth()
    private val settings   = SettingsStore(app)

    private val _state = MutableStateFlow(GitUiState())
    val state: StateFlow<GitUiState> = _state.asStateFlow()

    private lateinit var editorVm: EditorViewModel
    private var currentProjectId: String? = null
    private var deviceFlowJob: kotlinx.coroutines.Job? = null

    fun init(editorViewModel: EditorViewModel, projectId: String?, linkedRepo: String?) {
        editorVm = editorViewModel
        currentProjectId = projectId
        _state.value = _state.value.copy(linkedRepo = linkedRepo)

        // Try to restore cached user from encrypted prefs
        val token = settings.getGithubToken()
        if (token.isNotBlank()) {
            viewModelScope.launch {
                runCatching {
                    val user = github.getUser(token)
                    _state.value = _state.value.copy(githubUser = user)
                }
            }
        }
    }

    fun setTokenInput(v: String) { _state.value = _state.value.copy(tokenInput = v) }

    fun signIn() {
        val token = _state.value.tokenInput.trim()
        if (token.isBlank()) return
        viewModelScope.launch {
            runCatching {
                val user = github.getUser(token)
                settings.setGithubToken(token)
                _state.value = _state.value.copy(githubUser = user, tokenInput = "", statusMessage = "Signed in as ${user.login}", isError = false)
            }.onFailure {
                _state.value = _state.value.copy(statusMessage = "Invalid token: ${it.message}", isError = true)
            }
        }
    }

    fun signOut() {
        settings.clearGithubToken()
        _state.value = _state.value.copy(githubUser = null, repos = emptyList(), linkedRepo = null)
    }

    fun getStoredGithubToken(): String = settings.getGithubToken()

    /** Starts the OAuth Device Flow — request a code, show it, poll until the user approves it in a browser. */
    fun startDeviceSignIn() {
        deviceFlowJob?.cancel()
        deviceFlowJob = viewModelScope.launch {
            _state.value = _state.value.copy(statusMessage = null, isError = false)
            val device = runCatching { deviceAuth.requestDeviceCode() }.getOrElse {
                _state.value = _state.value.copy(statusMessage = "Couldn't start sign-in: ${it.message}", isError = true)
                return@launch
            }
            _state.value = _state.value.copy(
                deviceUserCode = device.userCode,
                deviceVerificationUri = device.verificationUri,
                isAwaitingDeviceApproval = true,
                deviceSecondsRemaining = device.expiresInSeconds
            )

            val token = deviceAuth.pollUntilComplete(
                device,
                isCancelled = { !isActive },
                onTick = { remaining -> _state.value = _state.value.copy(deviceSecondsRemaining = remaining) }
            )

            if (token == null) {
                _state.value = _state.value.copy(
                    isAwaitingDeviceApproval = false,
                    deviceUserCode = null,
                    statusMessage = "Sign-in code expired or was denied — try again.",
                    isError = true
                )
                return@launch
            }

            runCatching { github.getUser(token) }
                .onSuccess { user ->
                    settings.setGithubToken(token)
                    _state.value = _state.value.copy(
                        githubUser = user,
                        isAwaitingDeviceApproval = false,
                        deviceUserCode = null,
                        statusMessage = "Signed in as ${user.login}",
                        isError = false
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        isAwaitingDeviceApproval = false,
                        deviceUserCode = null,
                        statusMessage = "Sign-in succeeded but couldn't load profile: ${it.message}",
                        isError = true
                    )
                }
        }
    }

    fun cancelDeviceSignIn() {
        deviceFlowJob?.cancel()
        _state.value = _state.value.copy(isAwaitingDeviceApproval = false, deviceUserCode = null)
    }

    fun loadRepos() {
        val token = settings.getGithubToken()
        if (token.isBlank()) return
        _state.value = _state.value.copy(isLoadingRepos = true)
        viewModelScope.launch {
            runCatching {
                val repos = github.listRepos(token)
                _state.value = _state.value.copy(repos = repos, isLoadingRepos = false)
            }.onFailure {
                _state.value = _state.value.copy(isLoadingRepos = false, statusMessage = it.message, isError = true)
            }
        }
    }

    fun createRepo(name: String, description: String, private: Boolean) {
        val token = settings.getGithubToken()
        viewModelScope.launch {
            runCatching {
                val repo = github.createRepo(token, name, description, private)
                _state.value = _state.value.copy(
                    linkedRepo = repo.full_name,
                    statusMessage = "Created ${repo.full_name}",
                    isError = false,
                )
                val pid = currentProjectId
                if (pid != null) {
                    val db = AppDatabase.getInstance(getApplication())
                    val p = db.getProjects().firstOrNull { it.id == pid }
                    if (p != null) {
                        db.saveProject(p.copy(githubRepo = repo.full_name))
                    }
                }
            }.onFailure {
                _state.value = _state.value.copy(statusMessage = it.message, isError = true)
            }
        }
    }

    fun linkRepo(fullName: String) {
        _state.value = _state.value.copy(linkedRepo = fullName, statusMessage = "Linked to $fullName", isError = false)
        val pid = currentProjectId ?: return
        viewModelScope.launch {
            runCatching {
                val db = AppDatabase.getInstance(getApplication())
                val p = db.getProjects().firstOrNull { it.id == pid }
                if (p != null) {
                    db.saveProject(p.copy(githubRepo = fullName))
                }
            }
        }
    }

    fun unlinkRepo() {
        _state.value = _state.value.copy(linkedRepo = null, statusMessage = "Unlinked repository", isError = false)
        val pid = currentProjectId ?: return
        viewModelScope.launch {
            runCatching {
                val db = AppDatabase.getInstance(getApplication())
                val p = db.getProjects().firstOrNull { it.id == pid }
                if (p != null) {
                    db.saveProject(p.copy(githubRepo = null))
                }
            }
        }
    }

    fun push(commitMessage: String) {
        val token = settings.getGithubToken()
        val linked = _state.value.linkedRepo ?: return
        val parts = linked.split("/")
        if (parts.size != 2) return

        _state.value = _state.value.copy(isPushing = true, statusMessage = null)
        viewModelScope.launch {
            runCatching {
                var allFiles = editorVm.state.value.files.values
                    .filter { it.type == FileType.FILE && it.content != null }
                    .toList()

                // Cloud Build's workflow needs settings.gradle(.kts) to
                // declare pluginManagement/dependencyResolutionManagement
                // repositories or AGP/Kotlin plugin resolution fails with
                // "Plugin not found" on the runner — KryptonIDE's own
                // project templates don't generate that block. Patch it
                // in-memory just before push (not at template-creation
                // time), so this also fixes projects created before this
                // existed. Only settings.gradle.kts/settings.gradle at
                // project root is targeted — findLast so a nested same-name
                // file (unlikely, but possible in an imported repo)
                // doesn't get matched instead of the real root one.
                val rootId = currentProjectId?.let { "root_$it" }
                val settingsFile = allFiles.filter { it.parentId == null || it.parentId == rootId }
                    .lastOrNull { it.name == "settings.gradle.kts" || it.name == "settings.gradle" }
                if (settingsFile != null) {
                    val patched = com.sednium.kryptonide.ui.build.GradleProjectPrep.patchRepositoriesText(
                        settingsFile.content!!, isKts = settingsFile.name.endsWith(".kts")
                    )
                    if (patched != null) {
                        editorVm.updateContent(settingsFile.id, patched)
                        allFiles = allFiles.map { if (it.id == settingsFile.id) it.copy(content = patched) else it }
                    }
                }

                val fileMap = editorVm.state.value.files
                val filesWithPaths = allFiles.map { file ->
                    val relPath = editorVm.buildPath(file, fileMap).ifEmpty { file.name }
                    relPath to (file.content ?: "")
                }

                github.pushFiles(
                    token = token,
                    owner = parts[0],
                    repo = parts[1],
                    branch = "main",
                    files = filesWithPaths,
                    commitMessage = commitMessage.ifBlank { "Update from Krypton IDE" },
                )
                _state.value = _state.value.copy(isPushing = false, statusMessage = "Pushed to $linked", isError = false)
            }.onFailure {
                _state.value = _state.value.copy(isPushing = false, statusMessage = it.message, isError = true)
            }
        }
    }

    fun clearStatus() { _state.value = _state.value.copy(statusMessage = null) }
}
