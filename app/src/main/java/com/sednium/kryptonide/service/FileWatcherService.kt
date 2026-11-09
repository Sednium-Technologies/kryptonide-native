package com.sednium.kryptonide.service

import com.sednium.kryptonide.data.model.Project
import com.sednium.kryptonide.data.model.ProjectTemplate
import com.sednium.kryptonide.utils.ProjectDirectoryResolver
import com.sednium.kryptonide.utils.ProjectDirResult
import com.sednium.kryptonide.utils.RecursiveFileObserver
import com.sednium.kryptonide.utils.StorageUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

sealed class DiskSyncEvent {
    data class FileCreated(val absolutePath: String, val projectName: String) : DiskSyncEvent()
    data class FileModified(val absolutePath: String, val projectName: String) : DiskSyncEvent()
    data class FileDeleted(val absolutePath: String, val projectName: String) : DiskSyncEvent()
    data class FileMoved(val fromPath: String, val toPath: String, val projectName: String) : DiskSyncEvent()
    data class ProjectDiscovered(val projectName: String, val path: String) : DiskSyncEvent()
    data class RescanRequired(val projectName: String) : DiskSyncEvent()
}

object FileWatcherService {

    private val _events = MutableSharedFlow<DiskSyncEvent>(
        replay              = 0,
        extraBufferCapacity = 64
    )
    val events: SharedFlow<DiskSyncEvent> = _events.asSharedFlow()

    // Overflow tracking and debounced recovery for eventual consistency
    val hasDroppedEvents = java.util.concurrent.atomic.AtomicBoolean(false)
    val overflowProjects = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val overflowRecoveryJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    internal fun safeEmit(event: DiskSyncEvent, projectName: String) {
        val success = _events.tryEmit(event)
        if (!success) {
            hasDroppedEvents.set(true)
            overflowProjects[projectName] = true
            overflowRecoveryJobs.compute(projectName) { _, existingJob ->
                existingJob?.cancel()
                scope.launch {
                    delay(300)
                    // Suspending emit guarantees delivery when buffer opens
                    _events.emit(DiskSyncEvent.RescanRequired(projectName))
                    overflowProjects.remove(projectName)
                }
            }
        }
    }

    // Paths the APP itself is currently writing — suppress these from sync loop
    private val selfWritePaths = mutableSetOf<String>()
    private val selfWriteLock  = Any()

    private val observers  = mutableMapOf<String, RecursiveFileObserver>() // projectName → observer
    private val debounceJobs = mutableMapOf<String, Job>()
    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val DEBOUNCE_MS = 400L

    val kryptonProjects: File
        get() = StorageUtils.getProjectsDir()

    val kryptonRoot: File
        get() = StorageUtils.getKryptonideRoot()

    // ── Called by repo BEFORE writing a file to disk ──────────────────────────
    // Suppresses the FileObserver event for this path for 1 second
    fun suppressPath(absolutePath: String) {
        synchronized(selfWriteLock) { selfWritePaths.add(absolutePath) }
        scope.launch {
            delay(1000)
            synchronized(selfWriteLock) { selfWritePaths.remove(absolutePath) }
        }
    }

    private fun isSuppressed(path: String): Boolean =
        synchronized(selfWriteLock) { path in selfWritePaths }

    private val INTERNAL_DIRS = com.sednium.kryptonide.data.repository.ProjectRepository.KRYPTON_INTERNAL_DIRS
    val IGNORED_SCAN_DIRS = setOf(
        ".git", ".gradle", "build", "node_modules", ".next", "dist",
        "target", ".turbo", ".idea", "__pycache__", ".pytest_cache",
        ".dart_tool", ".checkpoints"
    )

    val IGNORED_EXACT_NAMES = setOf(
        ".metadata", ".nomedia", ".DS_Store", "Thumbs.db",
        "desktop.ini", ".system_generated"
    )

    val IGNORED_TEMP_EXTENSIONS = setOf(
        "tmp", "swp", "swo", "bak", "orig", "lock", "lck"
    )

    fun shouldIncludeInScan(file: File): Boolean {
        val name = file.name
        if (name in IGNORED_EXACT_NAMES) return false
        if (name.startsWith("~") || name.endsWith("~")) return false
        val ext = file.extension.lowercase()
        if (ext in IGNORED_TEMP_EXTENSIONS) return false
        if (file.isDirectory && name in IGNORED_SCAN_DIRS) return false
        return true
    }

    // ── Start watching a project directory ────────────────────────────────────
    fun startWatching(projectName: String, projectDir: File? = null) {
        val lower = projectName.lowercase()
        if (lower.isEmpty() || lower in INTERNAL_DIRS || observers.containsKey(projectName)) return

        val targetDir = projectDir ?: run {
            val stub = Project(id = "", name = projectName, template = ProjectTemplate.BLANK)
            val res = ProjectDirectoryResolver.resolve(stub)
            if (res is ProjectDirResult.Resolved) res.directory else null
        }

        // Never auto-create non-existent directory on watcher start!
        if (targetDir == null || !targetDir.exists() || !targetDir.isDirectory) {
            return
        }

        val observer = RecursiveFileObserver(targetDir.absolutePath) { event, path ->
            handleFsEvent(event, path, projectName)
        }
        observer.startWatching()
        observers[projectName] = observer
    }

    fun stopWatchingProject(projectName: String) {
        observers.remove(projectName)?.stopWatching()
        debounceJobs.keys.filter { it.contains(projectName) }.forEach {
            debounceJobs.remove(it)?.cancel()
        }
    }

    fun stopWatching() {
        observers.values.forEach { it.stopWatching() }
        observers.clear()
        debounceJobs.values.forEach { it.cancel() }
        debounceJobs.clear()
        scope.coroutineContext[Job]?.cancelChildren()
    }

    // ── FileObserver event → DiskSyncEvent ───────────────────────────────────
    private fun handleFsEvent(event: Int, absolutePath: String?, projectName: String) {
        if (absolutePath == null) return
        if (projectName.lowercase() in INTERNAL_DIRS) return

        val file     = File(absolutePath)
        val fileName = file.name
        val ext      = file.extension.lowercase()

        // ── Ignore noise and internal tool artifacts, but keep dotfiles (.gitignore, .env, .github, etc.) ─────
        if (fileName in IGNORED_EXACT_NAMES) return
        if (fileName.startsWith("~") || fileName.endsWith("~")) return
        if (ext in IGNORED_TEMP_EXTENSIONS) return

        // ── Ignore build/cache and internal directories ───────────────
        if (file.isDirectory && fileName in IGNORED_SCAN_DIRS) return
        if (IGNORED_SCAN_DIRS.any { absolutePath.contains("/$it/") || absolutePath.endsWith("/$it") }) return
        if (IGNORED_EXACT_NAMES.any { absolutePath.contains("/$it/") || absolutePath.endsWith("/$it") }) return
        if (INTERNAL_DIRS.any { absolutePath.contains("/$it/") || absolutePath.endsWith("/$it") }) return
        if (isSuppressed(absolutePath)) return

        // FileObserver event masks
        val CREATE  = android.os.FileObserver.CREATE
        val MODIFY  = android.os.FileObserver.MODIFY
        val DELETE  = android.os.FileObserver.DELETE
        val MOVED_FROM = android.os.FileObserver.MOVED_FROM
        val MOVED_TO   = android.os.FileObserver.MOVED_TO
        val CLOSE_WRITE = android.os.FileObserver.CLOSE_WRITE

        val maskedEvent = event and 0xFFF   // mask out cookie

        when (maskedEvent) {
            CREATE -> debounce(absolutePath) {
                val f = File(absolutePath)
                if (f.isDirectory) {
                    // New dir — start watching it too
                    scope.launch { registerSubdir(absolutePath, projectName) }
                }
                safeEmit(DiskSyncEvent.FileCreated(absolutePath, projectName), projectName)
            }

            CLOSE_WRITE, MODIFY -> debounce(absolutePath) {
                if (File(absolutePath).isFile) {
                    safeEmit(DiskSyncEvent.FileModified(absolutePath, projectName), projectName)
                }
            }

            DELETE, MOVED_FROM -> {
                // No debounce for deletes — emit immediately
                safeEmit(DiskSyncEvent.FileDeleted(absolutePath, projectName), projectName)
            }

            MOVED_TO -> debounce(absolutePath) {
                safeEmit(DiskSyncEvent.FileCreated(absolutePath, projectName), projectName)
            }
        }
    }

    private fun registerSubdir(path: String, projectName: String) {
        val obs = observers[projectName] ?: return
        obs.stopWatching()
        obs.startWatching()
    }

    // ── Debounce rapid events ───
    private fun debounce(path: String, action: suspend () -> Unit) {
        debounceJobs[path]?.cancel()
        debounceJobs[path] = scope.launch {
            delay(DEBOUNCE_MS)
            action()
            debounceJobs.remove(path)
        }
    }

    // ── Scan project files (used by repository) ───────────────────────────────
    fun scanProjectFiles(projectName: String, projectDir: File? = null): List<DiskFile> {
        return scanProjectFilesDetailed(projectName, projectDir).files
    }

    fun scanProjectFilesDetailed(projectName: String, projectDir: File? = null): ScanSummary {
        val dir = projectDir ?: run {
            val stub = Project(id = "", name = projectName, template = ProjectTemplate.BLANK)
            val res = ProjectDirectoryResolver.resolve(stub)
            if (res is ProjectDirResult.Resolved) res.directory else null
        } ?: return ScanSummary(emptyList(), hasScanError = true)

        if (!dir.exists() || !dir.isDirectory || !dir.canRead()) {
            return ScanSummary(emptyList(), hasScanError = true)
        }

        val rootItems = dir.listFiles() ?: return ScanSummary(emptyList(), hasScanError = true)

        val unreadableDirs = mutableSetOf<String>()
        var hasError = false
        val result = mutableListOf<DiskFile>()

        fun scan(current: File) {
            val items = current.listFiles()
            if (items == null) {
                hasError = true
                val rel = current.absolutePath.removePrefix(dir.absolutePath).trimStart('/')
                if (rel.isNotEmpty()) unreadableDirs.add(rel)
                return
            }
            items.filter { shouldIncludeInScan(it) }
                .sortedBy { it.name }
                .forEach { f ->
                    val rel = f.absolutePath.removePrefix(dir.absolutePath).trimStart('/')
                    result.add(DiskFile(f.name, rel, f.isDirectory, f.absolutePath))
                    if (f.isDirectory) {
                        scan(f)
                    }
                }
        }

        scan(dir)
        return ScanSummary(result, hasScanError = hasError, unreadableDirectories = unreadableDirs)
    }
}

data class ScanSummary(
    val files: List<DiskFile>,
    val hasScanError: Boolean = false,
    val unreadableDirectories: Set<String> = emptySet()
)

data class DiskFile(
    val name:         String,
    val relativePath: String,
    val isDirectory:  Boolean,
    val absolutePath: String
)
