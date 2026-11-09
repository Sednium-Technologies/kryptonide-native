package com.sednium.kryptonide.utils

import android.os.Environment
import java.io.File

object StorageUtils {
    fun getExternalStorageDir(): File {
        return try {
            Environment.getExternalStorageDirectory()
        } catch (e: Exception) {
            // Fallback for Robolectric or devices where Environment APIs are broken
            File("/storage/emulated/0")
        }
    }

    val ROOT_SYSTEM_DIRECTORIES = setOf(
        ".checkpoints",
        ".metadata",
        "aif",
        "models",
        "mcp",
        "skills",
        "chats",
        "packages",
        "plugins",
        "projects"
    )

    /**
     * Primary root directory for all Kryptonide user workspace data on shared storage:
     * `/storage/emulated/0/Kryptonide`.
     */
    fun getKryptonideRoot(): File {
        val root = File(getExternalStorageDir(), "Kryptonide")
        if (!root.exists()) {
            root.mkdirs()
        }
        ensureRootStructure(root)
        return root
    }

    /**
     * Ensures all 10 standard root directories exist:
     * .checkpoints, .metadata, aif, models, mcp, skills, chats, packages, plugins, projects.
     */
    fun ensureRootStructure(root: File = File(getExternalStorageDir(), "Kryptonide")) {
        ROOT_SYSTEM_DIRECTORIES.forEach { dirName ->
            val dir = File(root, dirName)
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    /**
     * Dedicated user projects directory:
     * `/storage/emulated/0/Kryptonide/projects`.
     * All user projects live here.
     */
    fun getProjectsDir(): File {
        val dir = File(getKryptonideRoot(), "projects")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getCheckpointsDir(): File = File(getKryptonideRoot(), ".checkpoints").also { if (!it.exists()) it.mkdirs() }
    fun getMetadataDir(): File = File(getKryptonideRoot(), ".metadata").also { if (!it.exists()) it.mkdirs() }
    fun getAifDir(): File = File(getKryptonideRoot(), "aif").also { if (!it.exists()) it.mkdirs() }
    fun getModelsDir(): File = File(getKryptonideRoot(), "models").also { if (!it.exists()) it.mkdirs() }
    fun getMcpDir(): File = File(getKryptonideRoot(), "mcp").also { if (!it.exists()) it.mkdirs() }
    fun getSkillsDir(): File = File(getKryptonideRoot(), "skills").also { if (!it.exists()) it.mkdirs() }
    fun getChatsDir(): File = File(getKryptonideRoot(), "chats").also { if (!it.exists()) it.mkdirs() }
    fun getPackagesDir(): File = File(getKryptonideRoot(), "packages").also { if (!it.exists()) it.mkdirs() }
    fun getPluginsDir(): File = File(getKryptonideRoot(), "plugins").also { if (!it.exists()) it.mkdirs() }

    /**
     * Migrates any existing user project folders located directly in /sdcard/Kryptonide/
     * into /sdcard/Kryptonide/projects/ to maintain strict root directory cleanliness.
     */
    fun migrateRootProjectsToProjectsSubdir(root: File) {
        try {
            val projectsDir = File(root, "projects").also { if (!it.exists()) it.mkdirs() }
            val items = root.listFiles() ?: return
            for (item in items) {
                if (item.isDirectory && item.name !in ROOT_SYSTEM_DIRECTORIES && !item.name.startsWith(".")) {
                    val dest = File(projectsDir, item.name)
                    if (!dest.exists()) {
                        val moved = item.renameTo(dest)
                        if (!moved) {
                            runCatching {
                                item.copyRecursively(dest, overwrite = true)
                                item.deleteRecursively()
                            }
                        }
                    } else {
                        // Already safely copied to projects/, remove from root
                        runCatching { item.deleteRecursively() }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Legacy root directory used in earlier versions:
     * `/storage/emulated/0/KryptonIDE`.
     */
    fun getLegacyKryptonIDERoot(): File {
        return File(getExternalStorageDir(), "KryptonIDE")
    }

    /**
     * Migrates legacy files/folders from /sdcard/KryptonIDE to /sdcard/Kryptonide.
     * System folders go to root; project folders go into /sdcard/Kryptonide/projects/.
     */
    fun migrateLegacyDirectoryIfNeeded(targetRoot: File = File(getExternalStorageDir(), "Kryptonide")) {
        try {
            val legacy = getLegacyKryptonIDERoot()
            if (!legacy.exists() || !legacy.isDirectory) return

            val projectsDir = File(targetRoot, "projects").also { if (!it.exists()) it.mkdirs() }
            val legacyItems = legacy.listFiles() ?: return
            for (item in legacyItems) {
                if (item.name in ROOT_SYSTEM_DIRECTORIES) {
                    val dest = File(targetRoot, item.name)
                    if (!dest.exists()) {
                        try {
                            if (item.isDirectory) {
                                item.copyRecursively(dest, overwrite = false)
                            } else {
                                item.copyTo(dest, overwrite = false)
                            }
                        } catch (_: Exception) {}
                    }
                } else if (item.isDirectory && !item.name.startsWith(".")) {
                    // Legacy user project folder -> route into projects/
                    val dest = File(projectsDir, item.name)
                    if (!dest.exists()) {
                        try {
                            item.copyRecursively(dest, overwrite = false)
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun isManagerSafe(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }
        } catch (e: Exception) {
            try {
                val testFile = File(getExternalStorageDir(), ".test_access")
                if (testFile.exists()) testFile.delete()
                testFile.createNewFile()
                testFile.delete()
                true
            } catch (e2: Exception) {
                false
            }
        }
    }
}
