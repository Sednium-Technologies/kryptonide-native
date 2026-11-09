package com.sednium.kryptonide.ui.editor

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sednium.kryptonide.data.model.FileNode
import com.sednium.kryptonide.data.model.FileType
import com.sednium.kryptonide.data.model.languageFromFilename
import com.sednium.kryptonide.data.repository.ProjectRepository
import com.sednium.kryptonide.data.storage.AppDatabase
import com.sednium.kryptonide.service.DiskSyncEvent
import com.sednium.kryptonide.service.FileWatcherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.sednium.kryptonide.data.model.Project
import com.sednium.kryptonide.data.model.ProjectTemplate
import com.sednium.kryptonide.utils.ProjectDirectoryResolver
import com.sednium.kryptonide.utils.StorageUtils
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class SidebarView { EXPLORER, SEARCH, GIT, AI, EXTENSIONS, SETTINGS }
enum class AppTheme { DARK, LIGHT, HC_BLACK }

data class EditorUiState(
    val files: Map<String, FileNode> = emptyMap(),
    val openFileIds: List<String> = emptyList(),
    val activeFileId: String? = null,
    val sidebarView: SidebarView = SidebarView.EXPLORER,
    val isSidebarOpen: Boolean = false,
    val isTerminalOpen: Boolean = false,
    val isPreviewOpen: Boolean = false,
    val isAiOpen: Boolean = false,
    val theme: AppTheme = AppTheme.DARK,
    val cursorLine: Int = 1,
    val cursorCol: Int = 1,
    val isHapticsEnabled: Boolean = true,
    val searchQuery: String = "",
    val searchResults: List<SearchResult> = emptyList(),
)

data class SearchResult(val fileId: String, val fileName: String, val line: Int, val snippet: String)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ProjectRepository(AppDatabase.getInstance(app))

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private var autoSaveJob: Job? = null
    private var watcherCollectorJob: Job? = null
    private var currentWatcherSessionId: String? = null
    private var currentProjectId: String? = null
    private var currentProjectName: String? = null

    // Document revision and mutex map for safe concurrent saves
    private val documentRevisions = ConcurrentHashMap<String, Long>()
    private val lastSavedDiskRevisions = ConcurrentHashMap<String, Long>()
    private val documentSaveMutexes = ConcurrentHashMap<String, Mutex>()

    fun getFileRevision(fileId: String): Long = documentRevisions[fileId] ?: 0L
    fun getLastSavedDiskRevision(fileId: String): Long = lastSavedDiskRevisions[fileId] ?: 0L
    fun getWatcherCollectorJob(): Job? = watcherCollectorJob
    fun getCurrentWatcherSessionId(): String? = currentWatcherSessionId

    // Set by EditorScreen when the editor is ready so we can reload it
    // if the currently-open file is modified externally.
    var soraHost: SoraEditorHost? = null

    fun getProjectDirectory(): java.io.File {
        val name = currentProjectName
        val id = currentProjectId
        return if (!name.isNullOrBlank()) {
            val proj = Project(
                id = id ?: "",
                name = name,
                template = ProjectTemplate.BLANK
            )
            ProjectDirectoryResolver.resolve(proj).directoryOrPreferred()
        } else {
            StorageUtils.getProjectsDir()
        }
    }

    fun getCurrentProjectId(): String? = currentProjectId
    fun getCurrentProjectName(): String? = currentProjectName

    // ── Project loading ──────────────────────────────────────

    fun loadProject(projectId: String, enableAutoSave: Boolean = true) {
        currentProjectId = projectId
        viewModelScope.launch {
            val project = repo.getProject(projectId)
            currentProjectName = project?.name
            val nodes = repo.getFiles(projectId)
            val fileMap = nodes.associateBy { it.id }
            _state.value = _state.value.copy(
                files = fileMap,
                openFileIds = emptyList(),
                activeFileId = null,
            )
            
            val firstFile = nodes.firstOrNull { it.type == FileType.FILE }
            firstFile?.let { openFile(it.id) }
            
            if (project != null) {
                startFileWatcher(projectId, project.name)
            }
        }
        if (enableAutoSave) {
            startAutoSave()
        }
    }

    fun stopAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = null
    }

    fun reloadCurrentProject() {
        val pid = currentProjectId ?: return
        viewModelScope.launch {
            val dir = getProjectDirectory()
            if (dir.exists()) {
                repo.importDirectoryIntoProject(pid, dir)
            }
            loadProject(pid)
        }
    }

    fun unloadProject() {
        autoSaveJob?.cancel()
        autoSaveJob = null

        val projectName = currentProjectName
        if (projectName != null) {
            stopFileWatcher(projectName)
        } else {
            watcherCollectorJob?.cancel()
            watcherCollectorJob = null
            currentWatcherSessionId = null
        }

        currentProjectId = null
        currentProjectName = null
        documentRevisions.clear()
        lastSavedDiskRevisions.clear()
        documentSaveMutexes.clear()
        _state.value = _state.value.copy(
            files = emptyMap(),
            openFileIds = emptyList(),
            activeFileId = null,
        )
    }

    // ── External Sync Logic ──────────────────────────────────

    // ── Start watching the project folder on disk ─────────────────────────────
    // Called once when a project is loaded. Sets up:
    //   1. FileWatcherService watching the project directory
    //   2. Flow collection of DiskSyncEvents → triggers resync

    fun startFileWatcher(projectId: String, projectName: String) {
        // Idempotency: cancel previous collector job so events are never duplicated
        watcherCollectorJob?.cancel()
        watcherCollectorJob = null

        val sessionId = UUID.randomUUID().toString()
        currentWatcherSessionId = sessionId

        val projDir = getProjectDirectory()
        if (projDir.exists() && projDir.isDirectory) {
            FileWatcherService.startWatching(projectName, projDir)
        }

        // Collect events in a tracked job with session isolation
        watcherCollectorJob = FileWatcherService.events
            .onEach { event ->
                if (currentWatcherSessionId == sessionId && currentProjectId == projectId) {
                    handleDiskEvent(event, projectId, projectName)
                }
            }
            .launchIn(viewModelScope)
    }

    fun stopFileWatcher(projectName: String) {
        watcherCollectorJob?.cancel()
        watcherCollectorJob = null
        currentWatcherSessionId = null
        FileWatcherService.stopWatchingProject(projectName)
    }

    // ── Handle a disk event ───────────────────────────────────────────────────

    @Volatile
    private var lastInternalSaveMs: Long = 0L

    private fun handleDiskEvent(
        event:       DiskSyncEvent,
        projectId:   String,
        projectName: String
    ) {
        when (event) {
            is DiskSyncEvent.FileCreated,
            is DiskSyncEvent.FileModified,
            is DiskSyncEvent.FileDeleted,
            is DiskSyncEvent.FileMoved,
            is DiskSyncEvent.RescanRequired -> {
                // Only handle events for this project
                val eventProject = when (event) {
                    is DiskSyncEvent.FileCreated   -> event.projectName
                    is DiskSyncEvent.FileModified  -> event.projectName
                    is DiskSyncEvent.FileDeleted   -> event.projectName
                    is DiskSyncEvent.FileMoved     -> event.projectName
                    is DiskSyncEvent.RescanRequired -> event.projectName
                    else -> return
                }
                if (eventProject != projectName) return

                // Ignore FileModified echoes caused by our own saveFileToDisk within the last 2.5s
                if (event is DiskSyncEvent.FileModified &&
                    System.currentTimeMillis() - lastInternalSaveMs < 2500L
                ) {
                    return
                }

                val sessionId = currentWatcherSessionId
                viewModelScope.launch {
                    if (currentWatcherSessionId != sessionId || currentProjectId != projectId) return@launch
                    val newFiles = repo.resyncProjectFromDisk(projectId)
                    if (currentWatcherSessionId != sessionId || currentProjectId != projectId) return@launch
                    val activeId = _state.value.activeFileId
                    val host = soraHost
                    val liveEditorContent = if (host != null && activeId != null) {
                        withContext(Dispatchers.Main) { host.getContent() }
                    } else null

                    _state.update { state ->
                        if (currentWatcherSessionId != sessionId || currentProjectId != projectId) return@update state
                        val updatedMap = newFiles.associateBy { f -> f.id }.toMutableMap()
                        // Preserve live unsaved buffer for the active file if it matches or is being edited
                        if (activeId != null && liveEditorContent != null) {
                            val currentActive = state.files[activeId]
                            val diskActive = updatedMap[activeId]
                            if (currentActive?.isUnsaved == true && diskActive != null) {
                                updatedMap[activeId] = diskActive.copy(
                                    content = liveEditorContent,
                                    isUnsaved = true
                                )
                            }
                        }
                        state.copy(files = updatedMap)
                    }

                    // Only reload the editor if the active file was genuinely modified externally
                    // (i.e., disk content differs from live editor buffer and user isn't mid-edit)
                    val activeFile = newFiles.firstOrNull { it.id == activeId }
                    if (activeFile != null &&
                        event is DiskSyncEvent.FileModified &&
                        event.absolutePath.endsWith(activeFile.name) &&
                        host != null
                    ) {
                        withContext(Dispatchers.Main) {
                            if (currentWatcherSessionId != sessionId || currentProjectId != projectId) return@withContext
                            val diskContent = activeFile.content ?: ""
                            if (host.getContent() != diskContent && _state.value.files[activeId]?.isUnsaved != true) {
                                host.loadFile(
                                    diskContent,
                                    activeFile.language,
                                    activeFile.name
                                )
                            }
                        }
                    }
                }
            }

            is DiskSyncEvent.ProjectDiscovered -> {
                // A new project folder appeared (e.g. from ZArchiver)
                // Import it so it shows up on the Projects Dashboard
                viewModelScope.launch {
                    repo.importProjectFromDisk(event.projectName)
                }
            }
        }
    }

    // ── Save a file to disk immediately (called from EditorScreen) ────────────
    // This is the "ctrl+s" equivalent — writes to both DB and disk.

    fun saveFileToDisk(fileId: String) {
        val currentFile = _state.value.files[fileId] ?: return
        // Binary and non-text files have null content; never save empty string over them!
        if (currentFile.content == null) return
        val contentToSave = currentFile.content
        val targetRevision = documentRevisions[fileId] ?: 0L
        saveSnapshotToDisk(fileId, contentToSave, targetRevision)
    }

    internal fun saveSnapshotToDisk(fileId: String, contentToSave: String, targetRevision: Long) {
        val projectId = currentProjectId ?: return
        lastInternalSaveMs = System.currentTimeMillis()

        viewModelScope.launch {
            val mutex = documentSaveMutexes.computeIfAbsent(fileId) { Mutex() }
            mutex.withLock {
                try {
                    if (currentProjectId != projectId) return@withLock
                    val lastDiskRev = lastSavedDiskRevisions[fileId] ?: 0L
                    if (targetRevision <= lastDiskRev) {
                        // Obsolete revision: a newer revision has already been saved to disk!
                        // Drop this write so an older snapshot can NEVER overwrite newer content on disk.
                        _state.update { s ->
                            if (currentProjectId != projectId) return@update s
                            val node = s.files[fileId] ?: return@update s
                            val currentRev = documentRevisions[fileId] ?: 0L
                            // Mark clean only if current editor buffer matches what is on disk
                            if (currentRev == lastDiskRev) {
                                s.copy(files = s.files.toMutableMap().apply {
                                    this[fileId] = node.copy(isUnsaved = false)
                                })
                            } else {
                                s
                            }
                        }
                        return@withLock
                    }

                    repo.markFileSaved(projectId, fileId, contentToSave)
                    lastSavedDiskRevisions[fileId] = targetRevision
                    lastInternalSaveMs = System.currentTimeMillis()

                    _state.update { s ->
                        if (currentProjectId != projectId) return@update s
                        val node = s.files[fileId] ?: return@update s
                        val currentRev = documentRevisions[fileId] ?: 0L
                        val diskRev = lastSavedDiskRevisions[fileId] ?: 0L
                        // Mark clean ONLY if no intermediate edits happened and disk matches live revision
                        if (currentRev == targetRevision && diskRev == currentRev) {
                            s.copy(files = s.files.toMutableMap().apply {
                                this[fileId] = node.copy(isUnsaved = false)
                            })
                        } else {
                            // Leave dirty; subsequent save will catch newer revision
                            s
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.util.Log.e("EditorViewModel", "Failed to save file $fileId to disk", e)
                }
            }
        }
    }

    fun buildPath(node: FileNode, filesMap: Map<String, FileNode>): String {
        val segments = mutableListOf<String>()
        var current: FileNode? = node
        while (current != null && current.parentId != null) {
            segments.add(0, current.name)
            current = filesMap[current.parentId]
        }
        return segments.joinToString("/")
    }

    // ── File operations ──────────────────────────────────────

    fun openFile(id: String) {
        val file = _state.value.files[id] ?: return
        if (file.type == FileType.FOLDER) return
        val openFiles = _state.value.openFileIds
        val newOpen = if (id in openFiles) openFiles else openFiles + id
        _state.value = _state.value.copy(openFileIds = newOpen, activeFileId = id)
    }

    fun closeFile(id: String) {
        val s = _state.value
        val newOpen = s.openFileIds.filter { it != id }
        val newActive = if (s.activeFileId == id) newOpen.lastOrNull() else s.activeFileId
        _state.value = s.copy(openFileIds = newOpen, activeFileId = newActive)
    }

    fun setActiveFile(id: String) {
        _state.value = _state.value.copy(activeFileId = id)
    }

    fun updateContent(fileId: String, content: String) {
        documentRevisions.compute(fileId) { _, rev -> (rev ?: 0L) + 1L }
        val files = _state.value.files.toMutableMap()
        files[fileId] = files[fileId]?.copy(content = content, isUnsaved = true) ?: return
        _state.value = _state.value.copy(files = files)
    }

    fun createFile(name: String, parentId: String?, type: FileType): String {
        val id = UUID.randomUUID().toString()
        val node = FileNode(
            id = id,
            projectId = currentProjectId ?: return "",
            name = name,
            type = type,
            parentId = parentId,
            content = if (type == FileType.FILE) "" else null,
            language = languageFromFilename(name),
            childrenIds = emptyList(),
        )
        val files = _state.value.files.toMutableMap()
        files[id] = node
        
        parentId?.let { pid ->
            val p = files[pid]
            if (p != null) {
                files[pid] = p.copy(childrenIds = p.childrenIds + id)
            }
        }
        
        _state.value = _state.value.copy(files = files)
        viewModelScope.launch { repo.upsertFile(node) }
        if (type == FileType.FILE) openFile(id)
        return id
    }

    fun renameFile(id: String, newName: String) {
        val pid = currentProjectId ?: return
        val files = _state.value.files.toMutableMap()
        files[id] = files[id]?.copy(name = newName, language = languageFromFilename(newName)) ?: return
        _state.value = _state.value.copy(files = files)
        viewModelScope.launch { repo.renameFile(pid, id, newName) }
    }

    /** True if `candidateId` is `ancestorId` itself or lives anywhere under it. Used to block
     *  moving/copying a folder into its own subtree. */
    private fun isSelfOrDescendant(candidateId: String, ancestorId: String, files: Map<String, FileNode>): Boolean {
        var curr: String? = candidateId
        while (curr != null) {
            if (curr == ancestorId) return true
            curr = files[curr]?.parentId
        }
        return false
    }

    /** Picks a name that doesn't collide with an existing sibling under `parentId`,
     *  appending " (2)", " (3)", etc. as needed — same convention as most file managers. */
    private fun uniqueSiblingName(parentId: String?, desiredName: String, files: Map<String, FileNode>): String {
        val siblingNames = files.values.filter { it.parentId == parentId }.map { it.name }.toSet()
        if (desiredName !in siblingNames) return desiredName
        val dot = desiredName.lastIndexOf('.')
        val base = if (dot > 0) desiredName.substring(0, dot) else desiredName
        val ext  = if (dot > 0) desiredName.substring(dot) else ""
        var n = 2
        while ("$base ($n)$ext" in siblingNames) n++
        return "$base ($n)$ext"
    }

    /** Cut+paste: reparents a node in place, no new IDs. */
    fun moveNode(nodeId: String, newParentId: String?) {
        val files = _state.value.files.toMutableMap()
        val node = files[nodeId] ?: return
        if (node.parentId == newParentId) return
        if (newParentId != null && isSelfOrDescendant(newParentId, nodeId, files)) return

        val finalName = uniqueSiblingName(newParentId, node.name, files.filterKeys { it != nodeId })

        node.parentId?.let { oldPid ->
            files[oldPid]?.let { p -> files[oldPid] = p.copy(childrenIds = p.childrenIds - nodeId) }
        }
        newParentId?.let { newPid ->
            files[newPid]?.let { p -> files[newPid] = p.copy(childrenIds = p.childrenIds + nodeId) }
        }
        files[nodeId] = node.copy(parentId = newParentId, name = finalName, language = languageFromFilename(finalName))

        _state.value = _state.value.copy(files = files)
        val pid = currentProjectId ?: return
        viewModelScope.launch {
            if (finalName != node.name) {
                repo.renameFile(pid, nodeId, finalName)
            }
            repo.moveFile(pid, nodeId, newParentId)
        }
    }

    /** Copy+paste: deep-clones the node (and, for folders, its whole subtree) under a fresh
     *  set of IDs, leaving the original untouched. */
    fun copyNode(nodeId: String, newParentId: String?) {
        val files = _state.value.files.toMutableMap()
        val node = files[nodeId] ?: return
        if (newParentId != null && isSelfOrDescendant(newParentId, nodeId, files)) return

        val createdIds = mutableListOf<String>()

        fun cloneRecursive(originalId: String, newParent: String?, forcedName: String? = null): String {
            val original = files[originalId] ?: return ""
            val newId = UUID.randomUUID().toString()
            val name = forcedName ?: uniqueSiblingName(newParent, original.name, files)
            val clonedChildrenIds = original.childrenIds.map { cloneRecursive(it, newId) }
            val clone = original.copy(
                id = newId,
                parentId = newParent,
                name = name,
                childrenIds = clonedChildrenIds,
                isUnsaved = false,
            )
            files[newId] = clone
            createdIds += newId
            return newId
        }

        val topLevelName = uniqueSiblingName(newParentId, node.name, files)
        val newRootId = cloneRecursive(nodeId, newParentId, topLevelName)

        newParentId?.let { pid ->
            files[pid]?.let { p -> files[pid] = p.copy(childrenIds = p.childrenIds + newRootId) }
        }

        _state.value = _state.value.copy(files = files)
        viewModelScope.launch {
            createdIds.forEach { id -> files[id]?.let { repo.upsertFile(it) } }
            newParentId?.let { pid -> files[pid]?.let { repo.upsertFile(it) } }
        }
    }

    /** Full slash-separated path from the project root, e.g. "src/main/Foo.kt" — used by
     *  the file explorer's "Copy Path" action. */
    fun pathFor(nodeId: String): String = _state.value.files[nodeId]?.let { buildPath(it, _state.value.files) } ?: ""

    fun deleteFile(id: String) {
        val files = _state.value.files.toMutableMap()
        fun deleteRecursive(nodeId: String) {
            files[nodeId]?.childrenIds?.forEach { deleteRecursive(it) }
            files.remove(nodeId)
        }
        val node = files[id]
        
        node?.parentId?.let { pid ->
            val p = files[pid]
            if (p != null) {
                files[pid] = p.copy(childrenIds = p.childrenIds.filter { it != id })
            }
        }
        
        deleteRecursive(id)
        val newOpen = _state.value.openFileIds.filter { it in files }
        val newActive = if (_state.value.activeFileId == id) newOpen.lastOrNull() else _state.value.activeFileId
        _state.value = _state.value.copy(files = files, openFileIds = newOpen, activeFileId = newActive)
        viewModelScope.launch { repo.deleteFile(currentProjectId ?: return@launch, id) }
    }

    fun saveFile(id: String) {
        saveFileToDisk(id)
    }

    fun saveAllFiles() {
        val unsaved = _state.value.files.values.filter { it.isUnsaved && it.type == FileType.FILE }
        unsaved.forEach { saveFile(it.id) }
    }

    // ── Search ───────────────────────────────────────────────

    fun search(query: String) {
        val trimmed = query.trim()
        _state.value = _state.value.copy(searchQuery = query)
        if (trimmed.isBlank()) {
            _state.value = _state.value.copy(searchResults = emptyList())
            return
        }
        val results = mutableListOf<SearchResult>()
        val filesMap = _state.value.files
        filesMap.values.forEach { file ->
            if (file.type != FileType.FILE) return@forEach
            val pathLower = buildPath(file, filesMap).lowercase()
            if (pathLower.contains("/node_modules/") || pathLower.startsWith("node_modules/") ||
                pathLower.contains("/.git/") || pathLower.startsWith(".git/") ||
                pathLower.contains("/build/") || pathLower.startsWith("build/") ||
                pathLower.contains("/.gradle/") || pathLower.startsWith(".gradle/") ||
                pathLower.contains("/dist/") || pathLower.startsWith("dist/") ||
                pathLower.contains("/target/") || pathLower.startsWith("target/") ||
                pathLower.contains("/.next/") || pathLower.startsWith(".next/")) {
                return@forEach
            }
            file.content?.lines()?.forEachIndexed { i, line ->
                if (line.contains(trimmed, ignoreCase = true)) {
                    results += SearchResult(file.id, file.name, i + 1, line.trim())
                }
            }
        }
        _state.value = _state.value.copy(searchResults = results)
    }

    // ── UI state toggles ─────────────────────────────────────

    fun setSidebarView(view: SidebarView) {
        val s = _state.value
        if (s.sidebarView == view && s.isSidebarOpen) {
            _state.value = s.copy(isSidebarOpen = false)
        } else {
            _state.value = s.copy(sidebarView = view, isSidebarOpen = true)
        }
    }

    fun toggleSidebar()  { _state.value = _state.value.copy(isSidebarOpen = !_state.value.isSidebarOpen) }
    fun toggleTerminal() { _state.value = _state.value.copy(isTerminalOpen = !_state.value.isTerminalOpen) }
    fun togglePreview()  { _state.value = _state.value.copy(isPreviewOpen = !_state.value.isPreviewOpen) }
    fun setTheme(t: AppTheme) { _state.value = _state.value.copy(theme = t) }
    fun setCursor(line: Int, col: Int) { _state.value = _state.value.copy(cursorLine = line, cursorCol = col) }

    // ── AI file tools (called by AiViewModel) ────────────────

    fun getProjectFileTree(): String {
        val map = _state.value.files
        val paths = map.values
            .filter { it.type == FileType.FILE }
            .map { buildPath(it, map) }
            .filter { it.isNotBlank() }
            .sorted()
        return if (paths.isEmpty()) "(empty project)" else paths.joinToString("\n")
    }

    fun getRawFileContent(path: String): String? {
        val normalizedPath = path.trim().trim('/')
        if (normalizedPath.isBlank() || normalizedPath == "No file selected") return null
        val file = _state.value.files.values.firstOrNull {
            it.type == FileType.FILE && (it.name == normalizedPath || buildPath(it, _state.value.files) == normalizedPath)
        }
        return file?.content
    }

    private fun syncActiveEditorAndSave(fileId: String, newContent: String) {
        updateContent(fileId, newContent)
        saveFileToDisk(fileId)
        if (_state.value.activeFileId == fileId) {
            viewModelScope.launch(Dispatchers.Main) {
                soraHost?.setContent(newContent)
            }
        }
    }

    fun readFileContent(path: String): String? {
        val normalizedPath = path.trim().trim('/')
        val file = _state.value.files.values.firstOrNull { 
            it.type == FileType.FILE && (it.name == normalizedPath || buildPath(it, _state.value.files) == normalizedPath)
        }
        return file?.content?.let { content ->
            content.lines().mapIndexed { i, line -> "${i + 1}: $line" }.joinToString("\n")
        }
    }

    fun writeFileContent(path: String, content: String): Boolean {
        val normalizedPath = path.trim().trim('/')
        val file = _state.value.files.values.firstOrNull { 
            it.type == FileType.FILE && (it.name == normalizedPath || buildPath(it, _state.value.files) == normalizedPath)
        }
        if (file != null) {
            val finalContent = PatchApplier.applyCodeOrPatch(file.content ?: "", content)
            syncActiveEditorAndSave(file.id, finalContent)
            return true
        }

        val segments = normalizedPath.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return false

        val rootNode = _state.value.files.values.firstOrNull { it.parentId == null }
        val rootId = rootNode?.id ?: currentProjectId?.let { "root_$it" }
        var currentParentId = rootId

        for (i in 0 until segments.size - 1) {
            val dirName = segments[i]
            val existingDir = _state.value.files.values.firstOrNull { 
                it.type == FileType.FOLDER && it.name == dirName && it.parentId == currentParentId 
            }
            currentParentId = if (existingDir != null) {
                existingDir.id
            } else {
                createFile(dirName, currentParentId, FileType.FOLDER)
            }
        }

        val fileName = segments.last()
        val newFileId = createFile(fileName, currentParentId, FileType.FILE)
        if (newFileId.isNotEmpty()) {
            syncActiveEditorAndSave(newFileId, content)
            return true
        }
        return false
    }

    fun patchFileContent(path: String, search: String, replace: String): Boolean {
        val normalizedPath = path.trim().trim('/')
        val file = _state.value.files.firstNotNullOfOrNull { (_, f) ->
            f.takeIf { it.type == FileType.FILE && (it.name == normalizedPath || buildPath(it, _state.value.files) == normalizedPath) }
        } ?: return false
        val content = file.content ?: return false
        if (!content.contains(search)) return false
        val updated = content.replace(search, replace, ignoreCase = false)
        syncActiveEditorAndSave(file.id, updated)
        return true
    }

    fun editLines(path: String, startLine: Int, endLine: Int, newContent: String): String {
        val normalizedPath = path.trim().trim('/')
        val file = _state.value.files.values.firstOrNull { 
            it.type == FileType.FILE && (it.name == normalizedPath || buildPath(it, _state.value.files) == normalizedPath)
        } ?: return "Error: file not found"
        val lines = (file.content ?: "").lines().toMutableList()
        val start = (startLine - 1).coerceAtLeast(0)
        val end = endLine.coerceAtMost(lines.size)
        repeat((end - start).coerceAtLeast(0)) { if (start < lines.size) lines.removeAt(start) }
        newContent.lines().forEachIndexed { i, l -> lines.add(start + i, l) }
        val updated = lines.joinToString("\n")
        syncActiveEditorAndSave(file.id, updated)
        return "Success"
    }

    fun insertLines(path: String, atLine: Int, content: String): String {
        val normalizedPath = path.trim().trim('/')
        val file = _state.value.files.values.firstOrNull { 
            it.type == FileType.FILE && (it.name == normalizedPath || buildPath(it, _state.value.files) == normalizedPath)
        } ?: return "Error: file not found"
        val lines = (file.content ?: "").lines().toMutableList()
        val idx = (atLine - 1).coerceIn(0, lines.size)
        content.lines().forEachIndexed { i, l -> lines.add(idx + i, l) }
        val updated = lines.joinToString("\n")
        syncActiveEditorAndSave(file.id, updated)
        return "Success"
    }

    // ── Auto-save ────────────────────────────────────────────

    private fun startAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            while (true) {
                delay(30_000)
                saveAllFiles()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        autoSaveJob?.cancel()
        autoSaveJob = null
        watcherCollectorJob?.cancel()
        watcherCollectorJob = null
        currentWatcherSessionId = null
        val projectName = currentProjectName
        if (projectName != null) {
            FileWatcherService.stopWatchingProject(projectName)
        }
    }
}
