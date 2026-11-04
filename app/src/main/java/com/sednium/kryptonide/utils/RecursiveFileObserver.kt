package com.sednium.kryptonide.utils

import android.os.FileObserver
import java.io.File

class RecursiveFileObserver(
    private val path: String,
    private val onChange: (event: Int, absolutePath: String?) -> Unit
) {
    private val observers = mutableListOf<FileObserver>()

    // Only watch events we actually care about.
    // ALL_EVENTS includes ACCESS/OPEN/ATTRIB which fire constantly for
    // .metadata, thumbnails, and system files — we don't want those.
    private val WATCH_MASK =
        FileObserver.CREATE      or   // new file/folder created
        FileObserver.CLOSE_WRITE or   // file written and closed (safer than MODIFY)
        FileObserver.MOVED_FROM  or   // file moved away
        FileObserver.MOVED_TO    or   // file moved in
        FileObserver.DELETE            // file deleted

    // File names / extensions that must never trigger a sync event.
    // .metadata — Android media database sidecar files
    // .nomedia  — media scanner skip marker
    // .DS_Store — macOS metadata (appears when transferring from Mac)
    // *.tmp / *.swp / *.bak — temp files from editors
    // ~*  — backup copies from some file managers
    private val IGNORED_NAMES = setOf(
        ".metadata", ".nomedia", ".DS_Store", "Thumbs.db", "desktop.ini"
    )
    private val IGNORED_EXTENSIONS = setOf(
        "tmp", "swp", "swo", "bak", "orig", "lock", "lck"
    )
    private val IGNORED_PREFIXES = setOf(".", "~")

    // Directories that routinely contain thousands of nested subfolders
    // (dependency trees, build output, VCS internals) and never need live
    // watching — recursing into these is what actually made startWatching()
    // expensive/risky in the first place, not just running it off-thread.
    private val IGNORED_DIRS = setOf(
        "node_modules", "build", ".gradle", ".git", ".idea", "dist", "out",
        "target", ".dart_tool", "Pods", "__pycache__", ".venv", "venv",
        ".next", ".cxx", "captures"
    )

    fun startWatching() {
        observers.clear()
        val root = File(path)
        if (root.exists() && root.isDirectory) {
            watchDir(root)
        }
    }

    private fun watchDir(dir: File) {
        val observer = object : FileObserver(dir.absolutePath, WATCH_MASK) {
            override fun onEvent(event: Int, filename: String?) {
                if (filename == null) return
                if (shouldIgnore(filename)) return

                // Build the absolute path for this event
                val absolutePath = File(dir, filename).absolutePath
                onChange(event, absolutePath)
            }
        }
        observer.startWatching()
        observers.add(observer)

        // Recurse into existing subdirectories — skipping build/dependency/VCS
        // trees, which is what keeps this from turning into tens of thousands
        // of inotify watches on any real JS/Gradle project.
        dir.listFiles()
            ?.filter { it.isDirectory && !shouldIgnore(it.name) && it.name !in IGNORED_DIRS }
            ?.forEach { watchDir(it) }
    }

    fun stopWatching() {
        observers.forEach { it.stopWatching() }
        observers.clear()
    }

    private fun shouldIgnore(name: String): Boolean {
        if (name in IGNORED_NAMES) return true
        if (IGNORED_PREFIXES.any { name.startsWith(it) }) return true
        val ext = name.substringAfterLast('.', "")
        if (ext in IGNORED_EXTENSIONS) return true
        return false
    }
}
