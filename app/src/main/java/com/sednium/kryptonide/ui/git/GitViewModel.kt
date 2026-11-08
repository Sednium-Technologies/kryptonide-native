package com.sednium.kryptonide.ui.git

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sednium.kryptonide.data.model.FileNode
import com.sednium.kryptonide.data.model.FileType
import com.sednium.kryptonide.data.model.Project
import com.sednium.kryptonide.data.repository.ActionsJob
import com.sednium.kryptonide.data.repository.ActionsRun
import com.sednium.kryptonide.data.repository.GithubActionsRepository
import com.sednium.kryptonide.data.repository.GithubRepository
import com.sednium.kryptonide.data.repository.GitHubRepo
import com.sednium.kryptonide.data.repository.GitHubUser
import com.sednium.kryptonide.data.storage.AppDatabase
import com.sednium.kryptonide.data.storage.SettingsStore
import com.sednium.kryptonide.ui.editor.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ChangeStatus { MODIFIED, ADDED, DELETED }

data class GitChangedFile(
    val relativePath: String,
    val status: ChangeStatus,
    val addedLines: Int,
    val removedLines: Int,
    val diffPatch: String,
    val selected: Boolean = true,
    val oldContent: String? = null,
    val newContent: String? = null
)

data class ActiveWorkflowRunState(
    val runId: Long,
    val runNumber: Int,
    val workflowName: String,
    val status: String, // queued | in_progress
    val currentStep: String,
    val stepIndex: Int,
    val totalSteps: Int,
    val elapsedSeconds: Long,
    val htmlUrl: String
)

data class WorkflowLogsState(
    val runId: Long,
    val runNumber: Int,
    val workflowName: String,
    val status: String,
    val conclusion: String?,
    val jobs: List<ActionsJob> = emptyList(),
    val selectedJobId: Long = 0L,
    val selectedStepIndex: Int = 0,
    val rawLogText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)

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
    val deviceUserCode: String? = null,
    val deviceVerificationUri: String? = null,
    val isAwaitingDeviceApproval: Boolean = false,
    val deviceSecondsRemaining: Int = 0,
)

class GitViewModel(app: Application) : AndroidViewModel(app) {

    private val github     = GithubRepository()
    private val actions    = GithubActionsRepository()
    private val deviceAuth = com.sednium.kryptonide.data.repository.GitHubDeviceAuth()
    private val settings   = SettingsStore(app)

    private val _state = MutableStateFlow(GitUiState())
    val state: StateFlow<GitUiState> = _state.asStateFlow()

    private val _changedFiles = MutableStateFlow<List<GitChangedFile>>(emptyList())
    val changedFiles: StateFlow<List<GitChangedFile>> = _changedFiles.asStateFlow()

    private val _activeWorkflowRun = MutableStateFlow<ActiveWorkflowRunState?>(null)
    val activeWorkflowRun: StateFlow<ActiveWorkflowRunState?> = _activeWorkflowRun.asStateFlow()

    private val _recentRuns = MutableStateFlow<List<ActionsRun>>(emptyList())
    val recentRuns: StateFlow<List<ActionsRun>> = _recentRuns.asStateFlow()

    private val _branches = MutableStateFlow<List<String>>(listOf("main"))
    val branches: StateFlow<List<String>> = _branches.asStateFlow()

    private val _selectedBranch = MutableStateFlow("main")
    val selectedBranch: StateFlow<String> = _selectedBranch.asStateFlow()

    private val _workflowLogs = MutableStateFlow<WorkflowLogsState?>(null)
    val workflowLogs: StateFlow<WorkflowLogsState?> = _workflowLogs.asStateFlow()

    var onSendToAi: ((prompt: String) -> Unit)? = null

    private lateinit var editorVm: EditorViewModel
    private var currentProjectId: String? = null
    private var deviceFlowJob: Job? = null
    private var cloudBuildJob: Job? = null
    private val baselineFiles = mutableMapOf<String, String>()

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

        if (linkedRepo != null) {
            loadBranches()
            refreshRecentRuns()
        }

        refreshChangedFiles()
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
                loadRepos()
            }.onFailure {
                _state.value = _state.value.copy(statusMessage = "Invalid token: ${it.message}", isError = true)
            }
        }
    }

    fun signOut() {
        settings.clearGithubToken()
        _state.value = _state.value.copy(githubUser = null, repos = emptyList(), linkedRepo = null)
        _recentRuns.value = emptyList()
        _activeWorkflowRun.value = null
    }

    fun getStoredGithubToken(): String = settings.getGithubToken()

    /** Starts the OAuth Device Flow */
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
                    loadRepos()
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
                loadBranches()
                refreshRecentRuns()
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
            loadBranches()
            refreshRecentRuns()
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

    // ── Branches ──────────────────────────────────────────────────────────

    fun loadBranches() {
        val token = settings.getGithubToken()
        val linked = _state.value.linkedRepo ?: return
        val parts = linked.split("/", limit = 2)
        if (parts.size != 2 || token.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val list = runCatching { github.listBranches(token, parts[0], parts[1]) }.getOrDefault(listOf("main"))
            _branches.value = list
            if (!_branches.value.contains(_selectedBranch.value) && list.isNotEmpty()) {
                _selectedBranch.value = list.first()
            }
        }
    }

    fun selectBranch(branch: String) {
        _selectedBranch.value = branch
    }

    fun createBranch(name: String) {
        val token = settings.getGithubToken()
        val linked = _state.value.linkedRepo ?: return
        val parts = linked.split("/", limit = 2)
        if (parts.size != 2 || token.isBlank() || name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val base = _selectedBranch.value
            val success = runCatching { github.createBranch(token, parts[0], parts[1], name, base) }.getOrDefault(false)
            if (success) {
                _branches.value = (_branches.value + name).distinct()
                _selectedBranch.value = name
                _state.value = _state.value.copy(statusMessage = "Created branch '$name' from '$base'", isError = false)
            } else {
                _state.value = _state.value.copy(statusMessage = "Failed to create branch '$name'", isError = true)
            }
        }
    }

    // ── Changed Files & Live Workspace Diff ─────────────────────────────

    fun refreshChangedFiles() {
        if (!::editorVm.isInitialized) return
        val fileMap = editorVm.state.value.files
        val currentFiles = fileMap.values.filter { it.type == FileType.FILE && it.content != null }
        val currentPaths = mutableMapOf<String, String>()
        currentFiles.forEach { file ->
            val relPath = editorVm.buildPath(file, fileMap).ifEmpty { file.name }
            currentPaths[relPath] = file.content ?: ""
        }

        val changed = mutableListOf<GitChangedFile>()
        val currentSelectedMap = _changedFiles.value.associate { it.relativePath to it.selected }

        for ((path, newContent) in currentPaths) {
            val oldContent = baselineFiles[path]
            if (oldContent == null) {
                val fileDiff = computeUnifiedDiff(path, null, newContent)
                changed.add(fileDiff.copy(selected = currentSelectedMap[path] ?: true))
            } else if (oldContent != newContent) {
                val fileDiff = computeUnifiedDiff(path, oldContent, newContent)
                changed.add(fileDiff.copy(selected = currentSelectedMap[path] ?: true))
            }
        }

        for ((path, oldContent) in baselineFiles) {
            if (!currentPaths.containsKey(path)) {
                val fileDiff = computeUnifiedDiff(path, oldContent, null)
                changed.add(fileDiff.copy(selected = currentSelectedMap[path] ?: true))
            }
        }

        _changedFiles.value = changed
    }

    fun toggleFileSelected(path: String) {
        _changedFiles.value = _changedFiles.value.map {
            if (it.relativePath == path) it.copy(selected = !it.selected) else it
        }
    }

    fun selectAllFiles(selected: Boolean) {
        _changedFiles.value = _changedFiles.value.map { it.copy(selected = selected) }
    }

    fun setBaselineFromCurrentFiles() {
        if (!::editorVm.isInitialized) return
        val fileMap = editorVm.state.value.files
        val currentFiles = fileMap.values.filter { it.type == FileType.FILE && it.content != null }
        baselineFiles.clear()
        currentFiles.forEach { file ->
            val relPath = editorVm.buildPath(file, fileMap).ifEmpty { file.name }
            baselineFiles[relPath] = file.content ?: ""
        }
        refreshChangedFiles()
    }

    // ── Push to Remote ───────────────────────────────────────────────────

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

                val selectedPaths = _changedFiles.value.filter { it.selected }.map { it.relativePath }.toSet()
                val filesToPush = if (selectedPaths.isNotEmpty()) {
                    filesWithPaths.filter { selectedPaths.contains(it.first) }
                } else {
                    filesWithPaths
                }

                val targetBranch = _selectedBranch.value.ifBlank { "main" }
                github.pushFiles(
                    token = token,
                    owner = parts[0],
                    repo = parts[1],
                    branch = targetBranch,
                    files = filesToPush,
                    commitMessage = commitMessage.ifBlank { "Update from Krypton IDE" },
                )

                // Update baseline files to reflect committed state
                filesToPush.forEach { (path, content) ->
                    baselineFiles[path] = content
                }
                refreshChangedFiles()

                _state.value = _state.value.copy(isPushing = false, statusMessage = "Pushed to $linked ($targetBranch)", isError = false)
            }.onFailure {
                _state.value = _state.value.copy(isPushing = false, statusMessage = it.message, isError = true)
            }
        }
    }

    // ── Persistent Cloud Build Engine ────────────────────────────────────

    fun startCloudBuild(owner: String, repo: String, branch: String = _selectedBranch.value, buildType: String = "debug") {
        val token = settings.getGithubToken()
        if (token.isBlank()) {
            _state.value = _state.value.copy(statusMessage = "Please sign in to GitHub first", isError = true)
            return
        }

        cloudBuildJob?.cancel()
        cloudBuildJob = viewModelScope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                _state.value = _state.value.copy(statusMessage = "Checking workflow in $owner/$repo…", isError = false)
                val created = runCatching { actions.ensureWorkflow(token, owner, repo, branch) }.getOrDefault(false)
                if (created) delay(4000)

                _state.value = _state.value.copy(statusMessage = "Triggering $buildType build…")
                val dispatchTime = System.currentTimeMillis()
                actions.dispatch(token, owner, repo, branch, buildType)

                _activeWorkflowRun.value = ActiveWorkflowRunState(
                    runId = 0L,
                    runNumber = 0,
                    workflowName = "Kryptonide Build",
                    status = "queued",
                    currentStep = "Queued on GitHub runner…",
                    stepIndex = 1,
                    totalSteps = 7,
                    elapsedSeconds = 0,
                    htmlUrl = ""
                )

                var run: ActionsRun? = null
                repeat(25) {
                    if (!isActive) return@launch
                    delay(2500)
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000
                    _activeWorkflowRun.value = _activeWorkflowRun.value?.copy(elapsedSeconds = elapsed)
                    run = actions.findLatestRun(token, owner, repo, dispatchTime)
                    if (run != null) return@repeat
                }

                if (run == null) {
                    _activeWorkflowRun.value = null
                    _state.value = _state.value.copy(statusMessage = "Run dispatch timed out — check GitHub Actions", isError = true)
                    return@launch
                }

                var current = run!!
                while (isActive && current.status in setOf("queued", "in_progress")) {
                    delay(3000)
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000
                    current = runCatching { actions.getRun(token, owner, repo, current.id) }.getOrDefault(current)
                    val jobs = runCatching { actions.getJobs(token, owner, repo, current.id) }.getOrDefault(emptyList())
                    val buildJob = jobs.firstOrNull { it.name.contains("build", ignoreCase = true) } ?: jobs.firstOrNull()
                    val steps = buildJob?.steps ?: emptyList()
                    val totalSteps = maxOf(steps.size, 7)
                    val runningStep = steps.firstOrNull { it.status == "in_progress" }
                        ?: steps.lastOrNull { it.conclusion == "success" }
                    val stepIndex = if (runningStep != null) steps.indexOf(runningStep) + 1 else 1
                    val stepName = runningStep?.name ?: if (current.status == "queued") "Queued on runner" else "Building APK…"

                    _activeWorkflowRun.value = ActiveWorkflowRunState(
                        runId = current.id,
                        runNumber = current.run_number,
                        workflowName = current.name.ifBlank { "Kryptonide Build" },
                        status = current.status,
                        currentStep = stepName,
                        stepIndex = stepIndex,
                        totalSteps = totalSteps,
                        elapsedSeconds = elapsed,
                        htmlUrl = current.html_url
                    )
                }

                _activeWorkflowRun.value = null
                refreshRecentRuns()

                if (current.conclusion == "success") {
                    _state.value = _state.value.copy(statusMessage = "Build #${current.run_number} succeeded! APK ready to install.", isError = false)
                } else if (current.conclusion == "failure") {
                    _state.value = _state.value.copy(statusMessage = "Build #${current.run_number} failed. Tap 'Fix with AI' to diagnose.", isError = true)
                }
            } catch (t: Throwable) {
                _activeWorkflowRun.value = null
                _state.value = _state.value.copy(statusMessage = "Build error: ${t.message}", isError = true)
            }
        }
    }

    fun cancelActiveBuild() {
        val run = _activeWorkflowRun.value ?: return
        val token = settings.getGithubToken()
        val linked = _state.value.linkedRepo ?: return
        val (owner, repo) = linked.split("/", limit = 2).takeIf { it.size == 2 } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            cloudBuildJob?.cancel()
            if (run.runId > 0) {
                actions.cancelRun(token, owner, repo, run.runId)
            }
            _activeWorkflowRun.value = null
            _state.value = _state.value.copy(statusMessage = "Build #${run.runNumber} cancelled.")
            refreshRecentRuns()
        }
    }

    // ── Recent Workflow Runs ─────────────────────────────────────────────

    fun refreshRecentRuns() {
        val token = settings.getGithubToken()
        val linked = _state.value.linkedRepo ?: return
        val parts = linked.split("/", limit = 2)
        if (parts.size != 2 || token.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val runs = runCatching { actions.listRecentRuns(token, parts[0], parts[1], perPage = 10) }.getOrDefault(emptyList())
            _recentRuns.value = runs
        }
    }

    fun downloadApk(run: ActionsRun, onDownloaded: (File) -> Unit) {
        val token = settings.getGithubToken()
        val linked = _state.value.linkedRepo ?: return
        val (owner, repo) = linked.split("/", limit = 2).takeIf { it.size == 2 } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(statusMessage = "Finding APK artifact for Run #${run.run_number}…")
            val tag = GithubActionsRepository.releaseTagFor(run.run_number)
            val release = actions.findRelease(token, owner, repo, tag)
            val asset = release?.assets?.firstOrNull { it.name.endsWith(".apk") }
            if (asset == null) {
                _state.value = _state.value.copy(statusMessage = "No APK asset found on release $tag", isError = true)
                return@launch
            }
            val dest = File(getApplication<Application>().getExternalFilesDir(null) ?: getApplication<Application>().filesDir, "builds/${asset.name}")
            _state.value = _state.value.copy(statusMessage = "Downloading ${asset.name}…")
            runCatching {
                actions.downloadReleaseAsset(token, owner, repo, asset.id, dest)
                _state.value = _state.value.copy(statusMessage = "Downloaded ${asset.name}", isError = false)
                withContext(Dispatchers.Main) { onDownloaded(dest) }
            }.onFailure {
                _state.value = _state.value.copy(statusMessage = "Download failed: ${it.message}", isError = true)
            }
        }
    }

    // ── Workflow Run Logs & Diagnostic Output ────────────────────────────

    fun openWorkflowLogs(run: ActionsRun) {
        val token = settings.getGithubToken()
        val linked = _state.value.linkedRepo ?: return
        val (owner, repo) = linked.split("/", limit = 2).takeIf { it.size == 2 } ?: return

        _workflowLogs.value = WorkflowLogsState(
            runId = run.id,
            runNumber = run.run_number,
            workflowName = run.name.ifBlank { "Kryptonide Build" },
            status = run.status,
            conclusion = run.conclusion,
            isLoading = true
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jobs = actions.getJobs(token, owner, repo, run.id)
                val firstJob = jobs.firstOrNull { it.conclusion == "failure" } ?: jobs.firstOrNull()
                val rawLogs = if (firstJob != null && firstJob.id > 0) {
                    actions.getJobLogs(token, owner, repo, firstJob.id)
                } else ""

                _workflowLogs.value = _workflowLogs.value?.copy(
                    jobs = jobs,
                    selectedJobId = firstJob?.id ?: 0L,
                    rawLogText = rawLogs,
                    isLoading = false
                )
            } catch (t: Throwable) {
                _workflowLogs.value = _workflowLogs.value?.copy(
                    isLoading = false,
                    error = "Failed to load logs: ${t.message}"
                )
            }
        }
    }

    fun selectLogStep(jobId: Long) {
        val token = settings.getGithubToken()
        val linked = _state.value.linkedRepo ?: return
        val (owner, repo) = linked.split("/", limit = 2).takeIf { it.size == 2 } ?: return

        _workflowLogs.value = _workflowLogs.value?.copy(isLoading = true, selectedJobId = jobId)
        viewModelScope.launch(Dispatchers.IO) {
            val logs = runCatching { actions.getJobLogs(token, owner, repo, jobId) }.getOrDefault("Log unavailable")
            _workflowLogs.value = _workflowLogs.value?.copy(rawLogText = logs, isLoading = false)
        }
    }

    fun closeWorkflowLogs() {
        _workflowLogs.value = null
    }

    // ── Send to AI & Fix ─────────────────────────────────────────────────

    fun sendRunFailureToAi(run: ActionsRun, logText: String? = null) {
        viewModelScope.launch {
            val token = settings.getGithubToken()
            val linked = _state.value.linkedRepo ?: return@launch
            val (owner, repo) = linked.split("/", limit = 2).takeIf { it.size == 2 } ?: return@launch

            val logs = if (!logText.isNullOrBlank()) logText else {
                withContext(Dispatchers.IO) {
                    val jobs = runCatching { actions.getJobs(token, owner, repo, run.id) }.getOrDefault(emptyList())
                    val failedJob = jobs.firstOrNull { it.conclusion == "failure" } ?: jobs.firstOrNull()
                    if (failedJob != null && failedJob.id > 0) {
                        runCatching { actions.getJobLogs(token, owner, repo, failedJob.id) }.getOrDefault("")
                    } else ""
                }
            }

            val failureContext = extractFailureContext(logs)
            val prompt = buildString {
                appendLine("### 🚨 GitHub Actions Build Failed (Run #${run.run_number})")
                appendLine("**Workflow**: ${run.name.ifBlank { "Kryptonide Build" }}")
                appendLine("**Branch**: ${run.head_branch.ifBlank { "main" }} • Commit: ${run.head_sha.take(7)}")
                appendLine()
                appendLine("Here is the relevant error extract from the build output:")
                appendLine("```")
                appendLine(failureContext)
                appendLine("```")
                appendLine()
                appendLine("Please inspect the repository files, diagnose the root cause of this build failure, and fix the code using `<edit file=\"...\">`.")
            }

            onSendToAi?.invoke(prompt)
        }
    }

    fun extractFailureContext(logs: String, maxLines: Int = 120): String =
        Companion.extractFailureContext(logs, maxLines)

    fun clearStatus() { _state.value = _state.value.copy(statusMessage = null) }

    companion object {
        enum class DiffType { SAME, ADD, REMOVE }
        data class DiffLine(val type: DiffType, val text: String)

        fun extractFailureContext(logs: String, maxLines: Int = 120): String {
            if (logs.isBlank()) return "No log output available."
            val lines = logs.lines()
            val errorIndices = lines.mapIndexedNotNull { idx, line ->
                val l = line.lowercase()
                if (l.contains("error:") || l.contains("failure:") || l.contains("e: ") || l.contains("compilation error") || l.contains("failed with an exception") || l.contains("build failed")) idx else null
            }
            if (errorIndices.isEmpty()) {
                return lines.takeLast(maxLines).joinToString("\n")
            }
            val firstError = errorIndices.first()
            val lastError = errorIndices.last()
            val start = (firstError - 10).coerceAtLeast(0)
            val end = (lastError + 25).coerceAtMost(lines.size)
            val slice = lines.subList(start, end)
            val result = if (slice.size > maxLines) slice.takeLast(maxLines) else slice
            return result.joinToString("\n")
        }

        fun computeUnifiedDiff(relativePath: String, oldContent: String?, newContent: String?): GitChangedFile {
            if (oldContent == null && newContent != null) {
                val lines = newContent.lines()
                val patch = buildString {
                    appendLine("--- /dev/null")
                    appendLine("+++ b/$relativePath")
                    appendLine("@@ -0,0 +1,${lines.size} @@")
                    for (line in lines) {
                        appendLine("+$line")
                    }
                }
                return GitChangedFile(
                    relativePath = relativePath,
                    status = ChangeStatus.ADDED,
                    addedLines = lines.size,
                    removedLines = 0,
                    diffPatch = patch,
                    selected = true,
                    oldContent = null,
                    newContent = newContent
                )
            }
            if (oldContent != null && newContent == null) {
                val lines = oldContent.lines()
                val patch = buildString {
                    appendLine("--- a/$relativePath")
                    appendLine("+++ /dev/null")
                    appendLine("@@ -1,${lines.size} +0,0 @@")
                    for (line in lines) {
                        appendLine("-$line")
                    }
                }
                return GitChangedFile(
                    relativePath = relativePath,
                    status = ChangeStatus.DELETED,
                    addedLines = 0,
                    removedLines = lines.size,
                    diffPatch = patch,
                    selected = true,
                    oldContent = oldContent,
                    newContent = null
                )
            }

            val oldLines = oldContent.orEmpty().lines()
            val newLines = newContent.orEmpty().lines()

            // LCS (Longest Common Subsequence) based line diff
            val n = oldLines.size
            val m = newLines.size
            val dp = Array(n + 1) { IntArray(m + 1) }

            for (i in 0 until n) {
                for (j in 0 until m) {
                    if (oldLines[i] == newLines[j]) {
                        dp[i + 1][j + 1] = dp[i][j] + 1
                    } else {
                        dp[i + 1][j + 1] = maxOf(dp[i + 1][j], dp[i][j + 1])
                    }
                }
            }

            var i = n
            var j = m
            val diffItems = mutableListOf<DiffLine>()
            while (i > 0 || j > 0) {
                if (i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1]) {
                    diffItems.add(DiffLine(DiffType.SAME, oldLines[i - 1]))
                    i--
                    j--
                } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                    diffItems.add(DiffLine(DiffType.ADD, newLines[j - 1]))
                    j--
                } else if (i > 0 && (j == 0 || dp[i][j - 1] < dp[i - 1][j])) {
                    diffItems.add(DiffLine(DiffType.REMOVE, oldLines[i - 1]))
                    i--
                }
            }
            diffItems.reverse()

            var added = 0
            var removed = 0
            val patch = buildString {
                appendLine("--- a/$relativePath")
                appendLine("+++ b/$relativePath")
                appendLine("@@ -1,$n +1,$m @@")
                for (d in diffItems) {
                    when (d.type) {
                        DiffType.SAME -> appendLine(" " + d.text)
                        DiffType.ADD -> {
                            added++
                            appendLine("+" + d.text)
                        }
                        DiffType.REMOVE -> {
                            removed++
                            appendLine("-" + d.text)
                        }
                    }
                }
            }

            val status = when {
                removed == 0 && added > 0 && oldLines.isEmpty() -> ChangeStatus.ADDED
                added == 0 && removed > 0 && newLines.isEmpty() -> ChangeStatus.DELETED
                else -> ChangeStatus.MODIFIED
            }

            return GitChangedFile(
                relativePath = relativePath,
                status = status,
                addedLines = added,
                removedLines = removed,
                diffPatch = patch,
                selected = true,
                oldContent = oldContent,
                newContent = newContent
            )
        }
    }
}
