package com.sednium.kryptonide.service

import com.sednium.kryptonide.utils.StorageUtils
import com.sednium.kryptonide.utils.RecursiveFileObserver
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
}

object FileWatcherService {

    private val _events = MutableSharedFlow<DiskSyncEvent>(
        replay              = 0,
        extraBufferCapacity = 64
    )
    val events: SharedFlow<DiskSyncEvent> = _events.asSharedFlow()

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
    val IGNORED_SCAN_DIRS = setOf("node_modules", ".git", ".gradle", "build", ".next", "dist", "target", ".turbo", ".idea", "__pycache__", ".pytest_cache")

    // ── Start watching a project directory ────────────────────────────────────
    fun startWatching(projectName: String) {
        val lower = projectName.lowercase()
        if (lower.isEmpty() || lower in INTERNAL_DIRS || observers.containsKey(projectName)) return
        val projectDir = File(kryptonProjects, projectName)
        if (!projectDir.exists()) projectDir.mkdirs()

        val observer = RecursiveFileObserver(projectDir.absolutePath) { event, path ->
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

        // ── Ignore system / metadata / temp files ─────────────────────
        val IGNORED_NAMES = setOf(".metadata", ".nomedia", ".DS_Store", "Thumbs.db", "desktop.ini")
        val IGNORED_EXTS  = setOf("tmp", "swp", "swo", "bak", "orig", "lock", "lck")
        val ext = fileName.substringAfterLast('.', "")

        if (fileName in IGNORED_NAMES)                  return
        if (fileName.startsWith(".") || fileName.startsWith("~")) return
        if (ext in IGNORED_EXTS)                        return
        // ── Ignore KryptonIDE internal build/cache dirs ───────────────
        if (absolutePath.contains("/.krypton_")
            || absolutePath.contains("/.metadata")
            || absolutePath.contains("/build/")
            || absolutePath.contains("/.gradle/")
            || INTERNAL_DIRS.any { absolutePath.contains("/$it/") || absolutePath.endsWith("/$it") }) return
        if (isSuppressed(absolutePath))                  return

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
                _events.tryEmit(DiskSyncEvent.FileCreated(absolutePath, projectName))
            }

            CLOSE_WRITE, MODIFY -> debounce(absolutePath) {
                if (File(absolutePath).isFile) {
                    _events.tryEmit(DiskSyncEvent.FileModified(absolutePath, projectName))
                }
            }

            DELETE, MOVED_FROM -> {
                // No debounce for deletes — emit immediately
                _events.tryEmit(DiskSyncEvent.FileDeleted(absolutePath, projectName))
            }

            MOVED_TO -> debounce(absolutePath) {
                _events.tryEmit(DiskSyncEvent.FileCreated(absolutePath, projectName))
            }
        }
    }

    private fun registerSubdir(path: String, projectName: String) {
        // RecursiveFileObserver already handles subdirs, but if a new dir
        // is created after startWatching() we need to restart to include it.
        val obs = observers[projectName] ?: return
        obs.stopWatching()
        obs.startWatching()   // RecursiveFileObserver rescans the tree
    }

    // ── Debounce rapid events (e.g. editor autosave writes multiple chunks) ───
    private fun debounce(path: String, action: suspend () -> Unit) {
        debounceJobs[path]?.cancel()
        debounceJobs[path] = scope.launch {
            delay(DEBOUNCE_MS)
            action()
            debounceJobs.remove(path)
        }
    }

    // ── Scan project files (used by repository) ───────────────────────────────
    fun scanProjectFiles(projectName: String): List<DiskFile> {
        val dir = File(kryptonProjects, projectName)
        return if (dir.exists()) scanDir(dir, dir) else emptyList()
    }

    private fun scanDir(root: File, current: File): List<DiskFile> {
        val result = mutableListOf<DiskFile>()
        current.listFiles()
            ?.filter { !it.name.startsWith(".") && it.name !in IGNORED_SCAN_DIRS }
            ?.sortedBy { it.name }
            ?.forEach { f ->
                val rel = f.absolutePath.removePrefix(root.absolutePath).trimStart('/')
                result.add(DiskFile(f.name, rel, f.isDirectory, f.absolutePath))
                if (f.isDirectory) result.addAll(scanDir(root, f))
            }
        return result
    }
}

data class DiskFile(
    val name:         String,
    val relativePath: String,
    val isDirectory:  Boolean,
    val absolutePath: String
)
