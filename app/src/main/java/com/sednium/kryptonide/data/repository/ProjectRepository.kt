package com.sednium.kryptonide.data.repository

import com.sednium.kryptonide.utils.StorageUtils
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.sednium.kryptonide.data.model.*
import com.sednium.kryptonide.data.storage.AppDatabase
import com.sednium.kryptonide.service.DiskFile
import com.sednium.kryptonide.service.FileWatcherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ProjectRepository(private val db: AppDatabase) {

    @Suppress("UNUSED_PARAMETER")
    suspend fun syncProjectToDrive(projectId: String, account: GoogleSignInAccount) = withContext(Dispatchers.IO) {
        // TODO: Phase 2 implementation
    }
    
    @Suppress("UNUSED_PARAMETER")
    suspend fun fetchProjectFromDrive(driveFolderId: String, account: GoogleSignInAccount) = withContext(Dispatchers.IO) {
        // TODO: Phase 2 implementation
    }

    companion object {
        val KRYPTON_INTERNAL_DIRS = setOf(
            "mcp",
            "skills",
            "plugins",
            "packages",
            "aif",
            "models",
            "model",
            "chats",
            "projects",
            "checkpoints",
            ".checkpoints",
            ".git",
            ".metadata",
            ".nomedia",
            ".system_generated"
        )
    }

    fun getProjectDir(project: Project): File {
        if (project.path.isNotBlank()) {
            val f = File(project.path)
            if (f.exists()) return f
        }
        val projectsSubdir = File(StorageUtils.getProjectsDir(), project.name)
        if (projectsSubdir.exists()) return projectsSubdir

        val rootPath = File(StorageUtils.getKryptonideRoot(), project.name)
        if (rootPath.exists() && rootPath.name !in KRYPTON_INTERNAL_DIRS && !rootPath.name.startsWith(".")) {
            val migrated = File(StorageUtils.getProjectsDir(), project.name)
            if (rootPath.renameTo(migrated)) {
                return migrated
            }
            return rootPath
        }

        val legacyPath = File(StorageUtils.getExternalStorageDir(), "KryptonIDE/${project.name}")
        if (legacyPath.exists()) return legacyPath

        return projectsSubdir
    }

    suspend fun getAllProjects(): List<Project> = withContext(Dispatchers.IO) {
        val projects = db.getProjects()
        val validProjects = mutableListOf<Project>()
        
        projects.forEach { p ->
            val isInternal = p.name.startsWith(".") || p.name.lowercase() in KRYPTON_INTERNAL_DIRS
            val projectDir = getProjectDir(p)
            if (isInternal) {
                // Purge internal directories from Room DB projects
                db.deleteProject(p.id)
            } else if (projectDir.exists()) {
                validProjects.add(p)
            } else {
                db.deleteProject(p.id)
            }
        }
        
        // Deduplicate projects by case-insensitive name, keeping the one with newest updatedAt
        val seenNames = mutableSetOf<String>()
        val deduplicated = mutableListOf<Project>()
        for (p in validProjects.sortedByDescending { it.updatedAt }) {
            val key = p.name.lowercase().trim()
            if (key !in seenNames) {
                seenNames.add(key)
                deduplicated.add(p)
            } else {
                // Prune duplicate record with the same project name
                db.deleteProject(p.id)
            }
        }
        deduplicated
    }

    suspend fun renameProject(id: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val cleanNewName = newName.trim()
        if (cleanNewName.isBlank() || cleanNewName.startsWith(".") || cleanNewName.lowercase() in KRYPTON_INTERNAL_DIRS) {
            return@withContext false
        }
        val project = getProject(id) ?: return@withContext false
        if (project.name == cleanNewName) return@withContext true

        val oldDir = getProjectDir(project)
        val baseDir = oldDir.parentFile ?: StorageUtils.getProjectsDir()
        val newDir = File(baseDir, cleanNewName)
        if (newDir.exists()) {
            return@withContext false
        }

        if (oldDir.exists()) {
            val renamed = oldDir.renameTo(newDir)
            if (!renamed) {
                try {
                    oldDir.copyRecursively(newDir, overwrite = true)
                    oldDir.deleteRecursively()
                } catch (e: Exception) {
                    return@withContext false
                }
            }
        } else {
            newDir.mkdirs()
        }

        FileWatcherService.stopWatchingProject(project.name)
        FileWatcherService.startWatching(cleanNewName)

        val updatedProject = project.copy(
            name = cleanNewName,
            path = newDir.absolutePath,
            updatedAt = System.currentTimeMillis()
        )
        db.saveProject(updatedProject)

        val rootId = "root_$id"
        val files = db.getFiles(id).toMutableList()
        val rootIdx = files.indexOfFirst { it.id == rootId }
        if (rootIdx >= 0) {
            files[rootIdx] = files[rootIdx].copy(name = cleanNewName)
            db.saveFiles(id, files)
        }
        true
    }

    suspend fun getProject(id: String): Project? = db.getProjects().firstOrNull { it.id == id }

    suspend fun createProject(
        name: String, 
        template: ProjectTemplate, 
        packageName: String = "com.example.myapp", 
        minSdk: Int = 24, 
        useKts: Boolean = false
    ): Project = withContext(Dispatchers.IO) {
        val existingNames = db.getProjects().map { it.name.trim().lowercase() }.toSet()
        var finalName = name.trim().ifBlank { "my-awesome-project" }
        if (finalName.lowercase() in existingNames) {
            var counter = 1
            while ("$finalName-$counter".lowercase() in existingNames) {
                counter++
            }
            finalName = "$finalName-$counter"
        }

        val id = UUID.randomUUID().toString()
        val projectDir = File(StorageUtils.getProjectsDir(), finalName)
        val project = Project(id = id, name = finalName, template = template, path = projectDir.absolutePath)
        db.saveProject(project)
        val rootId = "root_$id"
        
        val templateNodes = buildTemplateNodes(
            projectId = id, 
            rootId = rootId, 
            template = template, 
            appName = finalName, 
            packageName = packageName, 
            minSdk = minSdk, 
            useKts = useKts
        )
        
        val rootNode = FileNode(
            id = rootId, 
            projectId = id, 
            name = finalName, 
            type = FileType.FOLDER, 
            parentId = null, 
            content = "", 
            language = "", 
            childrenIds = templateNodes.filter { it.parentId == rootId }.map { it.id }
        )
        
        val allNodes = listOf(rootNode) + templateNodes
        db.saveFiles(id, allNodes)
        syncProjectToDisk(finalName, allNodes)
        project
    }

    suspend fun updateProject(project: Project) = db.saveProject(
        project.copy(updatedAt = System.currentTimeMillis())
    )

    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) {
        val project = getProject(id)
        db.deleteProject(id)
        if (project != null) {
            val projectDir = getProjectDir(project)
            if (projectDir.exists()) projectDir.deleteRecursively()
        }
    }

    suspend fun getFiles(projectId: String): List<FileNode> = db.getFiles(projectId)
    
    suspend fun upsertFile(node: FileNode) = withContext(Dispatchers.IO) { 
        db.upsertFile(node)
        syncFullProject(node.projectId) 
    }

    suspend fun upsertFiles(nodes: List<FileNode>) = withContext(Dispatchers.IO) {
        if (nodes.isEmpty()) return@withContext
        val projectId = nodes.first().projectId
        val existing = db.getFiles(projectId).toMutableList()
        nodes.forEach { node ->
            val idx = existing.indexOfFirst { it.id == node.id }
            if (idx >= 0) existing[idx] = node else existing.add(node)
        }
        db.saveFiles(projectId, existing)
        syncFullProject(projectId)
    }

    suspend fun updateFileContent(projectId: String, id: String, content: String) = withContext(Dispatchers.IO) {
        val files = db.getFiles(projectId).toMutableList()
        val idx = files.indexOfFirst { it.id == id }
        if (idx >= 0) files[idx] = files[idx].copy(content = content, isUnsaved = true)
        db.saveFiles(projectId, files)
    }

    suspend fun markFileSaved(projectId: String, id: String, content: String) = withContext(Dispatchers.IO) {
        val files = db.getFiles(projectId).toMutableList()
        val idx = files.indexOfFirst { it.id == id }
        if (idx >= 0) {
            files[idx] = files[idx].copy(content = content, isUnsaved = false)
            db.saveFiles(projectId, files)
            syncSingleFileToDisk(projectId, files, files[idx])
        }
    }

    private suspend fun syncSingleFileToDisk(projectId: String, nodes: List<FileNode>, targetNode: FileNode) {
        val project = getProject(projectId) ?: return
        val projectDir = getProjectDir(project)
        val nodeMap = nodes.associateBy { it.id }
        
        fun getRelativePath(node: FileNode): String {
            val parts = mutableListOf(node.name)
            var currId = node.parentId
            while (currId != null) {
                val parent = nodeMap[currId]
                if (parent != null && parent.parentId != null) { 
                    parts.add(0, parent.name)
                    currId = parent.parentId 
                } else break
            }
            return parts.joinToString("/")
        }
        
        val physicalFile = File(projectDir, getRelativePath(targetNode))
        try {
            if (targetNode.type == FileType.FOLDER) {
                if (!physicalFile.exists()) physicalFile.mkdirs()
            } else {
                if (physicalFile.parentFile?.exists() == false) {
                    physicalFile.parentFile?.mkdirs()
                }
                val contentToWrite = targetNode.content ?: ""
                if (!physicalFile.exists() || physicalFile.readText() != contentToWrite) {
                    FileWatcherService.suppressPath(physicalFile.absolutePath)
                    physicalFile.writeText(contentToWrite)
                }
            }
        } catch (_: Exception) {
            // Log or suppress write errors gracefully
        }
    }

    suspend fun deleteFile(projectId: String, fileId: String) = withContext(Dispatchers.IO) {
        val allFiles = db.getFiles(projectId)
        val fileToDelete = allFiles.find { it.id == fileId }
        val project = getProject(projectId)

        // Recursively find all child IDs so deleting folders does not orphan child records in SQLite/metadata
        val idsToDelete = mutableSetOf(fileId)
        fun collectChildren(parentId: String) {
            allFiles.filter { it.parentId == parentId }.forEach { child ->
                idsToDelete.add(child.id)
                collectChildren(child.id)
            }
        }
        collectChildren(fileId)

        db.deleteFiles(projectId, idsToDelete)
        if (project != null && fileToDelete != null) {
            val nodeMap = allFiles.associateBy { it.id }
            fun getRelativePath(node: FileNode): String {
                val parts = mutableListOf(node.name)
                var currId = node.parentId
                while (currId != null) {
                    val parent = nodeMap[currId]
                    if (parent != null && parent.parentId != null) { 
                        parts.add(0, parent.name)
                        currId = parent.parentId 
                    } else break
                }
                return parts.joinToString("/")
            }
            val projectDir = getProjectDir(project)
            val physicalFile = File(projectDir, getRelativePath(fileToDelete))
            if (physicalFile.exists()) physicalFile.deleteRecursively()
        }
    }

    suspend fun syncFullProject(projectId: String) {
        val project = getProject(projectId) ?: return
        val projectDir = getProjectDir(project)
        syncProjectToDisk(projectDir, db.getFiles(projectId))
    }

    /**
     * The reverse of syncFullProject: walks a real directory already on disk
     * (e.g. freshly `git clone`'d, or extracted from a .zip) and rebuilds the
     * project's FileNode DB entries from it, so it actually shows up in the
     * file explorer/editor — not just on the SD card. Replaces whatever
     * FileNode entries the project had (e.g. a BLANK template's placeholder
     * README) with the real imported tree.
     */
    suspend fun importDirectoryIntoProject(
        projectId: String,
        rootDir: File,
        excludeDirNames: Set<String> = setOf(".git", ".gradle", ".idea", "build", "node_modules", ".DS_Store")
    ): Int = withContext(Dispatchers.IO) {
        val rootId = "root_$projectId"
        val nodes = mutableListOf<FileNode>()

        fun walk(dir: File, parentId: String) {
            val children = dir.listFiles()?.sortedWith(compareBy({ it.isFile }, { it.name.lowercase() })) ?: return
            for (child in children) {
                if (child.name in excludeDirNames) continue
                val nodeId = UUID.randomUUID().toString()
                if (child.isDirectory) {
                    nodes += FileNode(
                        id = nodeId, projectId = projectId, parentId = parentId,
                        name = child.name, type = FileType.FOLDER
                    )
                    walk(child, nodeId)
                } else {
                    val content = if (isLikelyTextFile(child) && child.length() < 2_000_000L) {
                        runCatching { child.readText() }.getOrNull()
                    } else null
                    nodes += FileNode(
                        id = nodeId, projectId = projectId, parentId = parentId,
                        name = child.name, type = FileType.FILE,
                        content = content ?: "",
                        language = languageFromFilename(child.name)
                    )
                }
            }
        }
        walk(rootDir, rootId)

        // The root FileNode itself — same convention as createProject's rootNode
        // (id = rootId, parentId = null). This was the actual bug: every walked
        // file/folder correctly pointed to rootId as its parent, but nothing
        // with that id — or with parentId == null at all — ever got created,
        // so the whole imported tree was orphaned and rendered as empty.
        val rootNode = FileNode(
            id = rootId, projectId = projectId, parentId = null,
            name = rootDir.name, type = FileType.FOLDER,
            content = "", language = "",
            childrenIds = nodes.filter { it.parentId == rootId }.map { it.id }
        )

        // Auto-wire childrenIds for everything else, same convention as buildTemplateNodes.
        val wired = nodes.map { node -> node.copy(childrenIds = nodes.filter { it.parentId == node.id }.map { it.id }) }
        db.saveFiles(projectId, listOf(rootNode) + wired)

        // createProject() always stores a freshly-cloned/imported project as
        // ProjectTemplate.BLANK with an empty path — nothing ever went back
        // and corrected either field afterward, so a cloned repo's dashboard
        // card showed "Blank" forever (regardless of what was actually
        // imported), and BuildEngine had no real on-disk path to build from.
        // Fix both here now that we actually know what's in the tree.
        val existing = db.getProjects().firstOrNull { it.id == projectId }
        if (existing != null) {
            db.saveProject(existing.copy(
                template = detectProjectTemplate(rootDir),
                path = rootDir.absolutePath
            ))
        }

        wired.size
    }

    /** Best-effort project-type detection from root-level marker files —
     *  good enough to replace a permanently-wrong "Blank" label, not meant
     *  to be exhaustive. Falls back to BLANK if nothing recognizable is found. */
    private fun detectProjectTemplate(rootDir: File): ProjectTemplate {
        fun has(vararg names: String) = names.any { File(rootDir, it).exists() }

        return when {
            has("build.gradle.kts", "build.gradle") && has("settings.gradle.kts", "settings.gradle") -> {
                val appBuildFile = listOf("app/build.gradle.kts", "app/build.gradle")
                    .map { File(rootDir, it) }.firstOrNull { it.exists() }
                val buildText = appBuildFile?.let { runCatching { it.readText() }.getOrNull() } ?: ""
                when {
                    buildText.contains("compose", ignoreCase = true) -> ProjectTemplate.ANDROID_COMPOSE
                    countFilesWithExtension(rootDir, "kt") >= countFilesWithExtension(rootDir, "java") -> ProjectTemplate.ANDROID_KOTLIN
                    else -> ProjectTemplate.ANDROID_JAVA
                }
            }
            has("package.json") -> {
                val pkgText = runCatching { File(rootDir, "package.json").readText() }.getOrDefault("")
                when {
                    pkgText.contains("\"next\"") -> ProjectTemplate.NEXTJS
                    pkgText.contains("\"react\"") && pkgText.contains("\"vite\"") -> ProjectTemplate.REACT_VITE
                    pkgText.contains("\"react\"") -> ProjectTemplate.REACT
                    pkgText.contains("\"vite\"") -> ProjectTemplate.VITE
                    pkgText.contains("\"express\"") -> ProjectTemplate.NODE_EXPRESS
                    else -> ProjectTemplate.NODE
                }
            }
            has("Cargo.toml") -> ProjectTemplate.RUST
            has("go.mod") -> ProjectTemplate.GO
            has("composer.json") -> ProjectTemplate.PHP
            rootDir.listFiles()?.any { it.extension == "csproj" } == true -> ProjectTemplate.CSHARP
            has("requirements.txt", "pyproject.toml") || rootDir.listFiles()?.any { it.extension == "py" } == true -> {
                val hasFastApi = listOf("main.py", "app.py").map { File(rootDir, it) }
                    .any { it.exists() && runCatching { it.readText() }.getOrDefault("").contains("fastapi", ignoreCase = true) }
                if (hasFastApi) ProjectTemplate.FASTAPI else ProjectTemplate.PYTHON
            }
            has("CMakeLists.txt") || rootDir.listFiles()?.any { it.extension == "cpp" } == true -> ProjectTemplate.CPP
            rootDir.listFiles()?.any { it.extension == "c" } == true -> ProjectTemplate.C
            has("index.html") -> ProjectTemplate.HTML
            has("pom.xml") || rootDir.listFiles()?.any { it.extension == "java" } == true -> ProjectTemplate.JAVA
            rootDir.listFiles()?.any { it.extension == "kt" || it.extension == "kts" } == true -> ProjectTemplate.KOTLIN
            else -> ProjectTemplate.BLANK
        }
    }

    private fun countFilesWithExtension(dir: File, ext: String, depth: Int = 3): Int {
        if (depth <= 0) return 0
        var count = 0
        dir.listFiles()?.forEach {
            if (it.isDirectory) count += countFilesWithExtension(it, ext, depth - 1)
            else if (it.extension == ext) count++
        }
        return count
    }

    private fun isLikelyTextFile(file: File): Boolean {
        val binaryExtensions = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "icns",
            "zip", "jar", "apk", "aab", "so", "dex", "class", "war", "aar",
            "woff", "woff2", "ttf", "otf", "eot",
            "mp3", "mp4", "wav", "ogg", "webm", "mov", "avi",
            "pdf", "keystore", "jks", "bin", "exe", "dll", "obj", "o"
        )
        return file.extension.lowercase() !in binaryExtensions
    }

    private fun syncProjectToDisk(projectName: String, nodes: List<FileNode>) {
        val baseDir = StorageUtils.getProjectsDir()
        syncProjectToDisk(File(baseDir, projectName), nodes)
    }

    private fun syncProjectToDisk(projectDir: File, nodes: List<FileNode>) {
        try {
            if (!projectDir.exists()) projectDir.mkdirs()
            
            val nodeMap = nodes.associateBy { it.id }
            
            fun getRelativePath(node: FileNode): String {
                val parts = mutableListOf(node.name)
                var currId = node.parentId
                while (currId != null) {
                    val parent = nodeMap[currId]
                    if (parent != null && parent.parentId != null) { 
                        parts.add(0, parent.name)
                        currId = parent.parentId 
                    } else break
                }
                return parts.joinToString("/")
            }
            
            nodes.filter { it.parentId != null }.forEach { node ->
                val physicalFile = File(projectDir, getRelativePath(node))
                if (node.type == FileType.FOLDER) {
                    if (!physicalFile.exists()) physicalFile.mkdirs()
                } else {
                    if (physicalFile.parentFile?.exists() == false) {
                        physicalFile.parentFile?.mkdirs()
                    }
                    val contentToWrite = node.content ?: ""
                    if (!physicalFile.exists() || physicalFile.readText() != contentToWrite) {
                        physicalFile.writeText(contentToWrite)
                    }
                }
            }
        } catch (e: Exception) { 
            e.printStackTrace() 
        }
    }

    // ─── IMPORT PROJECT FROM DISK ─────────────────────────────────────────────
    // Called when FileWatcherService detects a new project folder
    // (e.g. user unzipped a project via ZArchiver into KryptonIDE/).
    // Creates a Project record + FileNode tree from the disk files.

    suspend fun importProjectFromDisk(
        projectName: String,
        template: ProjectTemplate = ProjectTemplate.BLANK
    ): Project = withContext(Dispatchers.IO) {
        if (projectName.isBlank() || projectName.startsWith(".") || projectName.lowercase() in KRYPTON_INTERNAL_DIRS) {
            return@withContext Project(id = "", name = projectName, template = template)
        }
        // Check if project already registered (case-insensitive)
        val existing = getAllProjects().firstOrNull { it.name.equals(projectName.trim(), ignoreCase = true) }
        if (existing != null) return@withContext existing

        // Scan disk files to detect the template
        val diskFiles = FileWatcherService.scanProjectFiles(projectName)
        val names     = diskFiles.map { it.name }.toSet()
        val exts      = diskFiles.filter { !it.isDirectory }
                            .map { it.name.substringAfterLast('.', "").lowercase() }
                            .toSet()

        val detectedTemplate: ProjectTemplate = when {
            // Explicit template passed (e.g. from ProjectsDashboard) — use it
            template != ProjectTemplate.BLANK -> template
            // HTML/CSS/JS vanilla web project
            "index.html" in names && ("style.css" in names || "script.js" in names)
                -> ProjectTemplate.HTML
            // Any HTML file present
            "index.html" in names || exts.contains("html")
                -> ProjectTemplate.HTML
            // React — has JSX files
            exts.contains("jsx") || exts.contains("tsx") || "App.jsx" in names || "App.tsx" in names
                -> ProjectTemplate.REACT
            // Python
            "main.py" in names || "app.py" in names || (exts.contains("py") && diskFiles.size <= 10)
                -> ProjectTemplate.PYTHON
            // Node / Express
            "index.js" in names || "server.js" in names || "app.js" in names
                -> ProjectTemplate.NODE
            // PHP
            "index.php" in names || exts.contains("php")
                -> ProjectTemplate.PHP
            // Go
            exts.contains("go") || "go.mod" in names
                -> ProjectTemplate.GO
            // Rust
            "Cargo.toml" in names || exts.contains("rs")
                -> ProjectTemplate.RUST
            // C++
            exts.contains("cpp") || exts.contains("cc") || exts.contains("cxx")
                -> ProjectTemplate.CPP
            // C
            exts.contains("c") && !exts.contains("cpp")
                -> ProjectTemplate.C
            // C#
            exts.contains("cs") || "*.csproj".let { names.any { n -> n.endsWith(".csproj") } }
                -> ProjectTemplate.CSHARP
            // Kotlin (non-Android)
            exts.contains("kt") && "AndroidManifest.xml" !in names
                -> ProjectTemplate.KOTLIN
            // Android
            "AndroidManifest.xml" in names
                -> ProjectTemplate.ANDROID_KOTLIN
            // Markdown
            exts.contains("md") && diskFiles.size <= 5
                -> ProjectTemplate.MARKDOWN
            // JSON
            exts.contains("json") && diskFiles.size <= 3
                -> ProjectTemplate.JSON
            else -> ProjectTemplate.BLANK
        }

        val id = java.util.UUID.randomUUID().toString()
        val projectDir = File(StorageUtils.getProjectsDir(), projectName)
        val project = Project(id = id, name = projectName, template = detectedTemplate, path = projectDir.absolutePath)
        db.saveProject(project)

        val rootId  = "root_$id"
        val rootNode = FileNode(
            id          = rootId,
            projectId   = id,
            name        = projectName,
            type        = FileType.FOLDER,
            parentId    = null,
            childrenIds = emptyList()
        )

        val nodes     = buildNodesFromDisk(id, rootId, diskFiles)
        val allNodes  = listOf(rootNode) + nodes

        // Auto-wire childrenIds
        val wired = allNodes.map { node ->
            node.copy(childrenIds = allNodes.filter { it.parentId == node.id }.map { it.id })
        }

        db.saveFiles(id, wired)
        project
    }

    // ─── RESYNC A PROJECT FROM DISK ───────────────────────────────────────────
    // Called by EditorViewModel when FileWatcherService fires an event.
    // Merges the current disk state into the FileNode tree:
    //   - New disk files → added as FileNodes (content read from disk)
    //   - Modified disk files → FileNode.content updated
    //   - Deleted disk files → FileNode removed from tree
    // Existing FileNodes with unsaved changes are NOT overwritten unless
    // the disk file is newer than the last in-app save.

    suspend fun resyncProjectFromDisk(projectId: String): List<FileNode> = withContext(Dispatchers.IO) {
        val project  = getProject(projectId) ?: return@withContext emptyList()
        val existing = db.getFiles(projectId).toMutableList()
        val diskFiles = FileWatcherService.scanProjectFiles(project.name)

        val existingByPath = mutableMapOf<String, FileNode>()
        val nodeMap = existing.associateBy { it.id }

        // Build relative-path → FileNode map
        existing.filter { it.parentId != null }.forEach { node ->
            val path = buildRelativePath(node, nodeMap)
            if (path.isNotEmpty()) existingByPath[path] = node
        }

        val diskByPath = diskFiles.associateBy { it.relativePath }
        val result     = existing.toMutableList()

        // ── Step 1: Add / update ──────────────────────────────────────────────
        diskFiles.forEach { diskFile ->
            val existingNode = existingByPath[diskFile.relativePath]
            if (existingNode == null) {
                // New file on disk → create FileNode
                val parentPath = diskFile.relativePath.substringBeforeLast("/", "")
                val parentId   = if (parentPath.isEmpty()) {
                    existing.firstOrNull { it.parentId == null }?.id ?: "root_$projectId"
                } else {
                    existingByPath[parentPath]?.id
                        ?: createParentFolders(parentPath, projectId, result, existingByPath)
                }

                val newNode = FileNode(
                    id        = java.util.UUID.randomUUID().toString(),
                    projectId = projectId,
                    name      = diskFile.name,
                    type      = if (diskFile.isDirectory) FileType.FOLDER else FileType.FILE,
                    parentId  = parentId,
                    content   = if (!diskFile.isDirectory) {
                        runCatching { File(diskFile.absolutePath).readText() }.getOrElse { "" }
                    } else null,
                    language  = languageFromFilename(diskFile.name),
                    isUnsaved = false
                )
                result.add(newNode)
                existingByPath[diskFile.relativePath] = newNode

            } else if (!diskFile.isDirectory && !existingNode.isUnsaved) {
                // Existing file was modified externally — update content
                val diskContent = runCatching {
                    File(diskFile.absolutePath).readText()
                }.getOrElse { existingNode.content ?: "" }

                if (diskContent != existingNode.content) {
                    val idx = result.indexOfFirst { it.id == existingNode.id }
                    if (idx >= 0) {
                        result[idx] = existingNode.copy(content = diskContent, isUnsaved = false)
                    }
                }
            }
        }

        // ── Step 2: Remove deleted files ──────────────────────────────────────
        val toRemove = existingByPath.entries
            .filter { (path, _) -> !diskByPath.containsKey(path) }
            .map { it.value.id }
            .toSet()

        if (toRemove.isNotEmpty()) {
            result.removeAll { it.id in toRemove }
        }

        // ── Step 3: Re-wire childrenIds ───────────────────────────────────────
        val wired = result.map { node ->
            node.copy(childrenIds = result.filter { it.parentId == node.id }.map { it.id })
        }

        db.saveFiles(projectId, wired)
        wired
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun buildRelativePath(node: FileNode, nodeMap: Map<String, FileNode>): String {
        val parts  = mutableListOf(node.name)
        var currId = node.parentId
        while (currId != null) {
            val parent = nodeMap[currId]
            if (parent?.parentId != null) {
                parts.add(0, parent.name)
                currId = parent.parentId
            } else break
        }
        return parts.joinToString("/")
    }

    private fun buildNodesFromDisk(
        projectId: String,
        rootId:    String,
        diskFiles: List<DiskFile>
    ): List<FileNode> {
        val nodes      = mutableListOf<FileNode>()
        val pathToId   = mutableMapOf<String, String>()
        pathToId[""]   = rootId

        diskFiles.forEach { f ->
            val parentPath = f.relativePath.substringBeforeLast("/", "")
            val parentId   = pathToId[parentPath] ?: rootId
            val id         = java.util.UUID.randomUUID().toString()
            pathToId[f.relativePath] = id

            nodes.add(FileNode(
                id        = id,
                projectId = projectId,
                name      = f.name,
                type      = if (f.isDirectory) FileType.FOLDER else FileType.FILE,
                parentId  = parentId,
                content   = if (!f.isDirectory) {
                    runCatching { File(f.absolutePath).readText() }.getOrElse { "" }
                } else null,
                language  = languageFromFilename(f.name),
                isUnsaved = false
            ))
        }
        return nodes
    }

    private fun createParentFolders(
        parentPath: String,
        projectId:  String,
        result:     MutableList<FileNode>,
        pathToNode: MutableMap<String, FileNode>
    ): String {
        // Walk up and create each missing folder
        val parts = parentPath.split("/")
        var builtPath = ""
        var lastId    = result.firstOrNull { it.parentId == null }?.id ?: "root_$projectId"

        for (part in parts) {
            builtPath = if (builtPath.isEmpty()) part else "$builtPath/$part"
            val existing = pathToNode[builtPath]
            if (existing != null) {
                lastId = existing.id
            } else {
                val newId = java.util.UUID.randomUUID().toString()
                val folder = FileNode(
                    id        = newId,
                    projectId = projectId,
                    name      = part,
                    type      = FileType.FOLDER,
                    parentId  = lastId
                )
                result.add(folder)
                pathToNode[builtPath] = folder
                lastId = newId
            }
        }
        return lastId
    }

    // ─── SAFE TEMPLATE BUILDER WITH ALL 18 PROJECTS ──────────────
    
    private fun buildTemplateNodes(
        projectId: String, 
        rootId: String, 
        template: ProjectTemplate, 
        appName: String, 
        packageName: String, 
        minSdk: Int, 
        useKts: Boolean
    ): List<FileNode> {
        val nodes = mutableMapOf<String, FileNode>()

        fun getOrCreateFolder(path: String): String {
            if (path.isEmpty()) return rootId
            if (nodes.containsKey(path)) return nodes[path]!!.id
            val parentPath = path.substringBeforeLast("/", "")
            val name = path.substringAfterLast("/")
            val parentId = getOrCreateFolder(parentPath)
            val id = UUID.randomUUID().toString()
            
            // Explicitly named arguments fixes Kotlin type mismatches
            nodes[path] = FileNode(
                id = id, 
                projectId = projectId, 
                name = name, 
                type = FileType.FOLDER, 
                parentId = parentId, 
                content = "", 
                language = "", 
                childrenIds = emptyList()
            )
            return id
        }

        fun file(path: String, content: String) {
            val parentPath = path.substringBeforeLast("/", "")
            val name = path.substringAfterLast("/")
            val parentId = getOrCreateFolder(parentPath)
            val id = UUID.randomUUID().toString()
            
            // Explicitly named arguments fixes Kotlin type mismatches
            nodes[path] = FileNode(
                id = id, 
                projectId = projectId, 
                name = name, 
                type = FileType.FILE, 
                parentId = parentId, 
                content = content, 
                language = languageFromFilename(name), 
                childrenIds = emptyList()
            )
        }

        fun folder(path: String) { 
            getOrCreateFolder(path) 
        }

        fun content(vararg lines: String): String {
            return lines.joinToString("\n")
        }

        val pkgPath = packageName.replace(".", "/")

        when (template.name.uppercase()) {

            // ── 1. ANDROID TEMPLATES ──────────────
            "ANDROID_JAVA" -> {
                file(
                    "app/src/main/java/$pkgPath/MainActivity.java", 
                    content(
                        "package $packageName;",
                        "",
                        "import android.os.Bundle;",
                        "import androidx.appcompat.app.AppCompatActivity;",
                        "",
                        "public class MainActivity extends AppCompatActivity {",
                        "    @Override",
                        "    protected void onCreate(Bundle savedInstanceState) {",
                        "        super.onCreate(savedInstanceState);",
                        "        setContentView(R.layout.activity_main);",
                        "    }",
                        "}"
                    )
                )
                folder("app/src/main/java/$pkgPath/ui")
                file(
                    "app/src/main/res/layout/activity_main.xml", 
                    content(
                        "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
                        "<androidx.constraintlayout.widget.ConstraintLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"",
                        "    xmlns:app=\"http://schemas.android.com/apk/res-auto\"",
                        "    android:layout_width=\"match_parent\"",
                        "    android:layout_height=\"match_parent\">",
                        "",
                        "    <TextView",
                        "        android:layout_width=\"wrap_content\"",
                        "        android:layout_height=\"wrap_content\"",
                        "        android:text=\"Hello $appName!\"",
                        "        app:layout_constraintBottom_toBottomOf=\"parent\"",
                        "        app:layout_constraintLeft_toLeftOf=\"parent\"",
                        "        app:layout_constraintRight_toRightOf=\"parent\"",
                        "        app:layout_constraintTop_toTopOf=\"parent\" />",
                        "</androidx.constraintlayout.widget.ConstraintLayout>"
                    )
                )
                folder("app/src/main/res/values")
                folder("app/src/main/res/drawable")
                file(
                    "app/src/main/AndroidManifest.xml", 
                    content(
                        "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
                        "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"",
                        "    package=\"$packageName\">",
                        "    <application",
                        "        android:allowBackup=\"true\"",
                        "        android:label=\"$appName\"",
                        "        android:supportsRtl=\"true\">",
                        "        <activity",
                        "            android:name=\".MainActivity\"",
                        "            android:exported=\"true\">",
                        "            <intent-filter>",
                        "                <action android:name=\"android.intent.action.MAIN\" />",
                        "                <category android:name=\"android.intent.category.LAUNCHER\" />",
                        "            </intent-filter>",
                        "        </activity>",
                        "    </application>",
                        "</manifest>"
                    )
                )
                
                if (useKts) {
                    file(
                        "build.gradle.kts", 
                        content(
                            "plugins {", 
                            "    id(\"com.android.application\") version \"8.1.0\" apply false", 
                            "}"
                        )
                    )
                    file(
                        "app/build.gradle.kts", 
                        content(
                            "plugins {", 
                            "    id(\"com.android.application\")", 
                            "}", 
                            "android {", 
                            "    namespace = \"$packageName\"", 
                            "    compileSdk = 34", 
                            "    defaultConfig {", 
                            "        applicationId = \"$packageName\"", 
                            "        minSdk = $minSdk", 
                            "        targetSdk = 34", 
                            "        versionCode = 1", 
                            "        versionName = \"1.0\"", 
                            "    }", 
                            "}"
                        )
                    )
                    file(
                        "settings.gradle.kts", 
                        content(
                            "pluginManagement {", 
                            "    repositories {", 
                            "        google()", 
                            "        mavenCentral()", 
                            "        gradlePluginPortal()", 
                            "    }", 
                            "}", 
                            "dependencyResolutionManagement {", 
                            "    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)", 
                            "    repositories {", 
                            "        google()", 
                            "        mavenCentral()", 
                            "    }", 
                            "}", 
                            "rootProject.name = \"$appName\"", 
                            "include(\":app\")"
                        )
                    )
                } else {
                    file(
                        "build.gradle", 
                        content(
                            "plugins {", 
                            "    id 'com.android.application' version '8.1.0' apply false", 
                            "}"
                        )
                    )
                    file(
                        "app/build.gradle", 
                        content(
                            "plugins {", 
                            "    id 'com.android.application'", 
                            "}", 
                            "android {", 
                            "    namespace '$packageName'", 
                            "    compileSdk 34", 
                            "    defaultConfig {", 
                            "        applicationId \"$packageName\"", 
                            "        minSdk $minSdk", 
                            "        targetSdk 34", 
                            "        versionCode 1", 
                            "        versionName \"1.0\"", 
                            "    }", 
                            "}"
                        )
                    )
                    file(
                        "settings.gradle", 
                        content(
                            "pluginManagement {", 
                            "    repositories {", 
                            "        google()", 
                            "        mavenCentral()", 
                            "        gradlePluginPortal()", 
                            "    }", 
                            "}", 
                            "dependencyResolutionManagement {", 
                            "    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)", 
                            "    repositories {", 
                            "        google()", 
                            "        mavenCentral()", 
                            "    }", 
                            "}", 
                            "rootProject.name = \"$appName\"", 
                            "include ':app'"
                        )
                    )
                }
                file("proguard-rules.pro", content("# Proguard rules"))
                file("gradle.properties", content("android.useAndroidX=true", "org.gradle.jvmargs=-Xmx2048m"))
            }

            "ANDROID_KOTLIN" -> {
                file(
                    "app/src/main/java/$pkgPath/MainActivity.kt", 
                    content(
                        "package $packageName", 
                        "", 
                        "import android.os.Bundle", 
                        "import androidx.appcompat.app.AppCompatActivity", 
                        "", 
                        "class MainActivity : AppCompatActivity() {", 
                        "    override fun onCreate(savedInstanceState: Bundle?) {", 
                        "        super.onCreate(savedInstanceState)", 
                        "        setContentView(R.layout.activity_main)", 
                        "    }", 
                        "}"
                    )
                )
                folder("app/src/main/java/$pkgPath/ui")
                file(
                    "app/src/main/res/layout/activity_main.xml", 
                    content(
                        "<?xml version=\"1.0\" encoding=\"utf-8\"?>", 
                        "<androidx.constraintlayout.widget.ConstraintLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"", 
                        "    xmlns:app=\"http://schemas.android.com/apk/res-auto\"", 
                        "    android:layout_width=\"match_parent\"", 
                        "    android:layout_height=\"match_parent\">", 
                        "    <TextView", 
                        "        android:layout_width=\"wrap_content\"", 
                        "        android:layout_height=\"wrap_content\"", 
                        "        android:text=\"Hello $appName (Kotlin)!\"", 
                        "        app:layout_constraintBottom_toBottomOf=\"parent\"", 
                        "        app:layout_constraintLeft_toLeftOf=\"parent\"", 
                        "        app:layout_constraintRight_toRightOf=\"parent\"", 
                        "        app:layout_constraintTop_toTopOf=\"parent\" />", 
                        "</androidx.constraintlayout.widget.ConstraintLayout>"
                    )
                )
                folder("app/src/main/res/values")
                folder("app/src/main/res/drawable")
                file(
                    "app/src/main/AndroidManifest.xml", 
                    content(
                        "<?xml version=\"1.0\" encoding=\"utf-8\"?>", 
                        "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"", 
                        "    package=\"$packageName\">", 
                        "    <application android:allowBackup=\"true\" android:label=\"$appName\">", 
                        "        <activity android:name=\".MainActivity\" android:exported=\"true\">", 
                        "            <intent-filter>", 
                        "                <action android:name=\"android.intent.action.MAIN\" />", 
                        "                <category android:name=\"android.intent.category.LAUNCHER\" />", 
                        "            </intent-filter>", 
                        "        </activity>", 
                        "    </application>", 
                        "</manifest>"
                    )
                )
                
                if (useKts) {
                    file(
                        "build.gradle.kts", 
                        content(
                            "plugins {", 
                            "    id(\"com.android.application\") version \"8.1.0\" apply false", 
                            "    id(\"org.jetbrains.kotlin.android\") version \"1.8.0\" apply false", 
                            "}"
                        )
                    )
                    file(
                        "app/build.gradle.kts", 
                        content(
                            "plugins {", 
                            "    id(\"com.android.application\")", 
                            "    id(\"org.jetbrains.kotlin.android\")", 
                            "}", 
                            "android {", 
                            "    namespace = \"$packageName\"", 
                            "    compileSdk = 34", 
                            "    defaultConfig {", 
                            "        applicationId = \"$packageName\"", 
                            "        minSdk = $minSdk", 
                            "        targetSdk = 34", 
                            "        versionCode = 1", 
                            "        versionName = \"1.0\"", 
                            "    }", 
                            "}"
                        )
                    )
                    file(
                        "settings.gradle.kts", 
                        content(
                            "pluginManagement {", 
                            "    repositories {", 
                            "        google()", 
                            "        mavenCentral()", 
                            "        gradlePluginPortal()", 
                            "    }", 
                            "}", 
                            "dependencyResolutionManagement {", 
                            "    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)", 
                            "    repositories {", 
                            "        google()", 
                            "        mavenCentral()", 
                            "    }", 
                            "}", 
                            "rootProject.name = \"$appName\"", 
                            "include(\":app\")"
                        )
                    )
                } else {
                    file(
                        "build.gradle", 
                        content(
                            "plugins {", 
                            "    id 'com.android.application' version '8.1.0' apply false", 
                            "    id 'org.jetbrains.kotlin.android' version '1.8.0' apply false", 
                            "}"
                        )
                    )
                    file(
                        "app/build.gradle", 
                        content(
                            "plugins {", 
                            "    id 'com.android.application'", 
                            "    id 'org.jetbrains.kotlin.android'", 
                            "}", 
                            "android {", 
                            "    namespace '$packageName'", 
                            "    compileSdk 34", 
                            "    defaultConfig {", 
                            "        applicationId \"$packageName\"", 
                            "        minSdk $minSdk", 
                            "        targetSdk 34", 
                            "        versionCode 1", 
                            "        versionName \"1.0\"", 
                            "    }", 
                            "}"
                        )
                    )
                    file(
                        "settings.gradle", 
                        content(
                            "pluginManagement {", 
                            "    repositories {", 
                            "        google()", 
                            "        mavenCentral()", 
                            "        gradlePluginPortal()", 
                            "    }", 
                            "}", 
                            "dependencyResolutionManagement {", 
                            "    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)", 
                            "    repositories {", 
                            "        google()", 
                            "        mavenCentral()", 
                            "    }", 
                            "}", 
                            "rootProject.name = \"$appName\"", 
                            "include ':app'"
                        )
                    )
                }
                file("proguard-rules.pro", content("# Proguard rules"))
                file("gradle.properties", content("android.useAndroidX=true"))
            }

            "ANDROID_COMPOSE", "COMPOSE" -> {
                file(
                    "app/src/main/java/$pkgPath/MainActivity.kt", 
                    content(
                        "package $packageName", 
                        "", 
                        "import android.os.Bundle", 
                        "import androidx.activity.ComponentActivity", 
                        "import androidx.activity.compose.setContent", 
                        "import androidx.compose.material3.Text", 
                        "import $packageName.ui.theme.MyAppTheme", 
                        "", 
                        "class MainActivity : ComponentActivity() {", 
                        "    override fun onCreate(savedInstanceState: Bundle?) {", 
                        "        super.onCreate(savedInstanceState)", 
                        "        setContent {", 
                        "            MyAppTheme {", 
                        "                Text(\"Welcome to $appName (Compose)!\")", 
                        "            }", 
                        "        }", 
                        "    }", 
                        "}"
                    )
                )
                file(
                    "app/src/main/java/$pkgPath/ui/theme/Color.kt", 
                    content(
                        "package $packageName.ui.theme", 
                        "", 
                        "// Define your Compose Colors here"
                    )
                )
                file(
                    "app/src/main/java/$pkgPath/ui/theme/Theme.kt", 
                    content(
                        "package $packageName.ui.theme", 
                        "", 
                        "import androidx.compose.material3.MaterialTheme", 
                        "import androidx.compose.runtime.Composable", 
                        "", 
                        "@Composable", 
                        "fun MyAppTheme(content: @Composable () -> Unit) {", 
                        "    MaterialTheme(content = content)", 
                        "}"
                    )
                )
                file(
                    "app/src/main/java/$pkgPath/ui/theme/Type.kt", 
                    content(
                        "package $packageName.ui.theme", 
                        "", 
                        "// Define your Compose Typography here"
                    )
                )
                file(
                    "app/src/main/res/values/themes.xml", 
                    content(
                        "<?xml version=\"1.0\" encoding=\"utf-8\"?>", 
                        "<resources>", 
                        "    <style name=\"Theme.MyApp\" parent=\"android:Theme.Material.Light.NoActionBar\" />", 
                        "</resources>"
                    )
                )
                folder("app/src/main/res/drawable")
                file(
                    "app/src/main/AndroidManifest.xml", 
                    content(
                        "<?xml version=\"1.0\" encoding=\"utf-8\"?>", 
                        "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"", 
                        "    package=\"$packageName\">", 
                        "    <application android:allowBackup=\"true\" android:label=\"$appName\" android:theme=\"@style/Theme.MyApp\">", 
                        "        <activity android:name=\".MainActivity\" android:exported=\"true\">", 
                        "            <intent-filter>", 
                        "                <action android:name=\"android.intent.action.MAIN\" />", 
                        "                <category android:name=\"android.intent.category.LAUNCHER\" />", 
                        "            </intent-filter>", 
                        "        </activity>", 
                        "    </application>", 
                        "</manifest>"
                    )
                )
                
                if (useKts) {
                    file(
                        "build.gradle.kts", 
                        content(
                            "plugins {", 
                            "    id(\"com.android.application\") version \"8.1.0\" apply false", 
                            "    id(\"org.jetbrains.kotlin.android\") version \"1.8.0\" apply false", 
                            "}"
                        )
                    )
                    file(
                        "app/build.gradle.kts", 
                        content(
                            "plugins {", 
                            "    id(\"com.android.application\")", 
                            "    id(\"org.jetbrains.kotlin.android\")", 
                            "}", 
                            "android {", 
                            "    namespace = \"$packageName\"", 
                            "    compileSdk = 34", 
                            "    defaultConfig {", 
                            "        applicationId = \"$packageName\"", 
                            "        minSdk = $minSdk", 
                            "        targetSdk = 34", 
                            "        versionCode = 1", 
                            "        versionName = \"1.0\"", 
                            "    }", 
                            "    buildFeatures { compose = true }", 
                            "    composeOptions { kotlinCompilerExtensionVersion = \"1.5.3\" }", 
                            "}"
                        )
                    )
                    file(
                        "settings.gradle.kts", 
                        content(
                            "pluginManagement {", 
                            "    repositories {", 
                            "        google()", 
                            "        mavenCentral()", 
                            "        gradlePluginPortal()", 
                            "    }", 
                            "}", 
                            "dependencyResolutionManagement {", 
                            "    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)", 
                            "    repositories {", 
                            "        google()", 
                            "        mavenCentral()", 
                            "    }", 
                            "}", 
                            "rootProject.name = \"$appName\"", 
                            "include(\":app\")"
                        )
                    )
                } else {
                    file(
                        "build.gradle", 
                        content(
                            "plugins {", 
                            "    id 'com.android.application' version '8.1.0' apply false", 
                            "    id 'org.jetbrains.kotlin.android' version '1.8.0' apply false", 
                            "}"
                        )
                    )
                    file(
                        "app/build.gradle", 
                        content(
                            "plugins {", 
                            "    id 'com.android.application'", 
                            "    id 'org.jetbrains.kotlin.android'", 
                            "}", 
                            "android {", 
                            "    namespace '$packageName'", 
                            "    compileSdk 34", 
                            "    defaultConfig {", 
                            "        applicationId \"$packageName\"", 
                            "        minSdk $minSdk", 
                            "        targetSdk 34", 
                            "        versionCode 1", 
                            "        versionName \"1.0\"", 
                            "    }", 
                            "    buildFeatures { compose true }", 
                            "    composeOptions { kotlinCompilerExtensionVersion '1.5.3' }", 
                            "}"
                        )
                    )
                    file(
                        "settings.gradle", 
                        content(
                            "pluginManagement {", 
                            "    repositories {", 
                            "        google()", 
                            "        mavenCentral()", 
                            "        gradlePluginPortal()", 
                            "    }", 
                            "}", 
                            "dependencyResolutionManagement {", 
                            "    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)", 
                            "    repositories {", 
                            "        google()", 
                            "        mavenCentral()", 
                            "    }", 
                            "}", 
                            "rootProject.name = \"$appName\"", 
                            "include ':app'"
                        )
                    )
                }
                file("proguard-rules.pro", content("# Proguard rules"))
                file("gradle.properties", content("android.useAndroidX=true"))
            }

            // ── 2. WEB / FRONTEND TEMPLATES ────────────────────────────────
            "VANILLA", "HTML" -> {
                file(
                    "index.html", 
                    content(
                        "<!DOCTYPE html>", 
                        "<html lang=\"en\">", 
                        "<head>", 
                        "  <meta charset=\"UTF-8\">", 
                        "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">", 
                        "  <title>Vanilla Web App</title>", 
                        "  <link rel=\"stylesheet\" href=\"style.css\">", 
                        "</head>", 
                        "<body>", 
                        "  <h1>Hello World</h1>", 
                        "  <script src=\"script.js\"></script>", 
                        "</body>", 
                        "</html>"
                    )
                )
                file(
                    "style.css", 
                    content(
                        "* { box-sizing: border-box; }", 
                        "body {", 
                        "  font-family: system-ui, sans-serif;", 
                        "  margin: 0;", 
                        "  padding: 2rem;", 
                        "  background: #f4f4f5;", 
                        "  color: #18181b;", 
                        "}", 
                        "h1 { color: #4f46e5; }"
                    )
                )
                file(
                    "script.js", 
                    content("console.log('Ready');")
                )
                folder("assets/images")
                file(
                    "README.md", 
                    content("# Vanilla Web Project", "", "A basic HTML, CSS, and JS boilerplate.")
                )
            }

            "REACT" -> {
                file(
                    "public/index.html", 
                    content(
                        "<!DOCTYPE html>", 
                        "<html lang=\"en\">", 
                        "  <head>", 
                        "    <meta charset=\"utf-8\" />", 
                        "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />", 
                        "    <title>React App</title>", 
                        "  </head>", 
                        "  <body>", 
                        "    <noscript>You need JavaScript to run this app.</noscript>", 
                        "    <div id=\"root\"></div>", 
                        "  </body>", 
                        "</html>"
                    )
                )
                file("public/favicon.ico", content(""))
                file(
                    "src/App.jsx", 
                    content(
                        "import React from 'react';", 
                        "import './App.css';", 
                        "", 
                        "export default function App() {", 
                        "  return (", 
                        "    <div className=\"App\">", 
                        "      <h1>Hello React!</h1>", 
                        "    </div>", 
                        "  );", 
                        "}"
                    )
                )
                file(
                    "src/index.js", 
                    content(
                        "import React from 'react';", 
                        "import ReactDOM from 'react-dom/client';", 
                        "import './App.css';", 
                        "import App from './App';", 
                        "", 
                        "const root = ReactDOM.createRoot(document.getElementById('root'));", 
                        "root.render(", 
                        "  <React.StrictMode>", 
                        "    <App />", 
                        "  </React.StrictMode>", 
                        ");"
                    )
                )
                file(
                    "src/App.css", 
                    content(".App { font-family: system-ui, sans-serif; text-align: center; padding: 2rem; }")
                )
                folder("src/components")
                file(
                    "package.json", 
                    content(
                        "{", 
                        "  \"name\": \"react-app\",", 
                        "  \"version\": \"0.1.0\",", 
                        "  \"dependencies\": {", 
                        "    \"react\": \"^18.2.0\",", 
                        "    \"react-dom\": \"^18.2.0\",", 
                        "    \"react-scripts\": \"5.0.1\"", 
                        "  },", 
                        "  \"scripts\": {", 
                        "    \"start\": \"react-scripts start\",", 
                        "    \"build\": \"react-scripts build\"", 
                        "  }", 
                        "}"
                    )
                )
                file(
                    "README.md", 
                    content("# React App", "", "Created with Create React App style layout.")
                )
                file(
                    ".gitignore", 
                    content("node_modules/", "build/", ".env")
                )
            }

            "REACT_VITE", "VITE" -> {
                folder("public")
                file(
                    "src/App.jsx", 
                    content(
                        "import { useState } from 'react'", 
                        "import './App.css'", 
                        "", 
                        "function App() {", 
                        "  const [count, setCount] = useState(0)", 
                        "", 
                        "  return (", 
                        "    <div className=\"App\">", 
                        "      <h1>Vite + React</h1>", 
                        "      <div className=\"card\">", 
                        "        <button onClick={() => setCount((count) => count + 1)}>", 
                        "          count is {count}", 
                        "        </button>", 
                        "      </div>", 
                        "    </div>", 
                        "  )", 
                        "}", 
                        "", 
                        "export default App"
                    )
                )
                file(
                    "src/main.jsx", 
                    content(
                        "import React from 'react'", 
                        "import ReactDOM from 'react-dom/client'", 
                        "import App from './App.jsx'", 
                        "import './App.css'", 
                        "", 
                        "ReactDOM.createRoot(document.getElementById('root')).render(", 
                        "  <React.StrictMode>", 
                        "    <App />", 
                        "  </React.StrictMode>,", 
                        ")"
                    )
                )
                file(
                    "src/App.css", 
                    content(".App { font-family: system-ui, sans-serif; text-align: center; padding: 2rem; }")
                )
                folder("src/assets")
                file(
                    "index.html", 
                    content(
                        "<!DOCTYPE html>", 
                        "<html lang=\"en\">", 
                        "  <head>", 
                        "    <meta charset=\"UTF-8\" />", 
                        "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />", 
                        "    <title>Vite App</title>", 
                        "  </head>", 
                        "  <body>", 
                        "    <div id=\"root\"></div>", 
                        "    <script type=\"module\" src=\"/src/main.jsx\"></script>", 
                        "  </body>", 
                        "</html>"
                    )
                )
                file(
                    "vite.config.js", 
                    content(
                        "import { defineConfig } from 'vite'", 
                        "import react from '@vitejs/plugin-react'", 
                        "", 
                        "export default defineConfig({", 
                        "  plugins: [react()],", 
                        "})"
                    )
                )
                file(
                    "package.json", 
                    content(
                        "{", 
                        "  \"name\": \"vite-react-app\",", 
                        "  \"private\": true,", 
                        "  \"version\": \"0.0.0\",", 
                        "  \"scripts\": {", 
                        "    \"dev\": \"vite\",", 
                        "    \"build\": \"vite build\",", 
                        "    \"preview\": \"vite preview\"", 
                        "  },", 
                        "  \"dependencies\": {", 
                        "    \"react\": \"^18.2.0\",", 
                        "    \"react-dom\": \"^18.2.0\"", 
                        "  },", 
                        "  \"devDependencies\": {", 
                        "    \"@vitejs/plugin-react\": \"^4.0.0\",", 
                        "    \"vite\": \"^4.4.0\"", 
                        "  }", 
                        "}"
                    )
                )
                file(
                    "README.md", 
                    content("# Vite + React Project")
                )
                file(
                    ".gitignore", 
                    content("node_modules/", "dist/", ".env")
                )
            }

            "NEXTJS" -> {
                file(
                    "app/layout.tsx", 
                    content(
                        "import './globals.css'", 
                        "", 
                        "export const metadata = {", 
                        "  title: 'Next.js App',", 
                        "}", 
                        "", 
                        "export default function RootLayout({", 
                        "  children,", 
                        "}: {", 
                        "  children: React.ReactNode", 
                        "}) {", 
                        "  return (", 
                        "    <html lang=\"en\">", 
                        "      <body>{children}</body>", 
                        "    </html>", 
                        "  )", 
                        "}"
                    )
                )
                file(
                    "app/page.tsx", 
                    content(
                        "export default function Home() {", 
                        "  return (", 
                        "    <main className=\"p-8 font-sans\">", 
                        "      <h1 className=\"text-2xl font-bold\">Hello Next.js</h1>", 
                        "      <p>Edit app/page.tsx to get started.</p>", 
                        "    </main>", 
                        "  )", 
                        "}"
                    )
                )
                file(
                    "app/globals.css", 
                    content(
                        "* { box-sizing: border-box; }", 
                        "body { margin: 0; padding: 0; font-family: system-ui, sans-serif; }"
                    )
                )
                folder("public")
                file(
                    "next.config.js", 
                    content(
                        "/** @type {import('next').NextConfig} */", 
                        "const nextConfig = {}", 
                        "module.exports = nextConfig"
                    )
                )
                file(
                    "package.json", 
                    content(
                        "{", 
                        "  \"name\": \"nextjs-app\",", 
                        "  \"version\": \"0.1.0\",", 
                        "  \"private\": true,", 
                        "  \"scripts\": {", 
                        "    \"dev\": \"next dev\",", 
                        "    \"build\": \"next build\",", 
                        "    \"start\": \"next start\"", 
                        "  },", 
                        "  \"dependencies\": {", 
                        "    \"next\": \"14.0.0\",", 
                        "    \"react\": \"^18\",", 
                        "    \"react-dom\": \"^18\"", 
                        "  },", 
                        "  \"devDependencies\": {", 
                        "    \"typescript\": \"^5\",", 
                        "    \"@types/node\": \"^20\",", 
                        "    \"@types/react\": \"^18\"", 
                        "  }", 
                        "}"
                    )
                )
                file(
                    "tsconfig.json", 
                    content(
                        "{", 
                        "  \"compilerOptions\": {", 
                        "    \"target\": \"es5\",", 
                        "    \"lib\": [\"dom\", \"dom.iterable\", \"esnext\"],", 
                        "    \"allowJs\": true,", 
                        "    \"skipLibCheck\": true,", 
                        "    \"strict\": true,", 
                        "    \"noEmit\": true,", 
                        "    \"esModuleInterop\": true,", 
                        "    \"module\": \"esnext\",", 
                        "    \"moduleResolution\": \"bundler\",", 
                        "    \"resolveJsonModule\": true,", 
                        "    \"isolatedModules\": true,", 
                        "    \"jsx\": \"preserve\",", 
                        "    \"incremental\": true,", 
                        "    \"paths\": {", 
                        "      \"@/*\": [\"./*\"]", 
                        "    }", 
                        "  },", 
                        "  \"include\": [\"next-env.d.ts\", \"**/*.ts\", \"**/*.tsx\", \".next/types/**/*.ts\"],", 
                        "  \"exclude\": [\"node_modules\"]", 
                        "}"
                    )
                )
                file(
                    "README.md", 
                    content("# Next.js App")
                )
                file(
                    ".gitignore", 
                    content("node_modules/", ".next/", "out/", ".env*.local")
                )
            }

            // ── 3. BACKEND / API TEMPLATES ─────────────────────────────────
            "FASTAPI" -> {
                file(
                    "app/__init__.py", 
                    content("")
                )
                file(
                    "app/main.py", 
                    content(
                        "from fastapi import FastAPI", 
                        "", 
                        "app = FastAPI()", 
                        "", 
                        "@app.get(\"/\")", 
                        "def read_root():", 
                        "    return {\"message\": \"Hello from FastAPI\"}"
                    )
                )
                folder("app/routers")
                file(
                    "requirements.txt", 
                    content("fastapi", "uvicorn")
                )
                file(
                    "README.md", 
                    content("# FastAPI Project", "", "Run with: `uvicorn app.main:app --reload`")
                )
                file(
                    ".env.example", 
                    content("ENV=development", "DEBUG=True")
                )
            }

            "NODE_EXPRESS", "NODE" -> {
                file(
                    "src/server.js", 
                    content(
                        "const express = require('express');", 
                        "const app = express();", 
                        "const PORT = process.env.PORT || 3000;", 
                        "", 
                        "app.get('/', (req, res) => {", 
                        "  res.send('Hello Express');", 
                        "});", 
                        "", 
                        "app.listen(PORT, () => {", 
                        "  console.log('Server running on port ' + PORT);", 
                        "});"
                    )
                )
                folder("src/routes")
                folder("src/controllers")
                file(
                    "package.json", 
                    content(
                        "{", 
                        "  \"name\": \"express-app\",", 
                        "  \"version\": \"1.0.0\",", 
                        "  \"main\": \"src/server.js\",", 
                        "  \"scripts\": {", 
                        "    \"start\": \"node src/server.js\"", 
                        "  },", 
                        "  \"dependencies\": {", 
                        "    \"express\": \"^4.18.2\"", 
                        "  }", 
                        "}"
                    )
                )
                file(
                    ".env.example", 
                    content("PORT=3000")
                )
                file(
                    "README.md", 
                    content("# Node Express App", "", "Run with `npm start`")
                )
                file(
                    ".gitignore", 
                    content("node_modules/", ".env")
                )
            }

            "PHP" -> {
                file(
                    "public/index.php", 
                    content(
                        "<?php", 
                        "echo \"Hello from PHP!\";"
                    )
                )
                folder("src")
                file(
                    "composer.json", 
                    content(
                        "{", 
                        "  \"name\": \"vendor/project\",", 
                        "  \"require\": {}", 
                        "}"
                    )
                )
                file(
                    "README.md", 
                    content("# PHP Project")
                )
                file(
                    ".gitignore", 
                    content("vendor/", ".env")
                )
            }

            "PYTHON" -> {
                file(
                    "src/main.py", 
                    content(
                        "def main():", 
                        "    print(\"Hello, Python!\")", 
                        "", 
                        "if __name__ == \"__main__\":", 
                        "    main()"
                    )
                )
                file(
                    "requirements.txt", 
                    content("# Add dependencies here")
                )
                file(
                    "README.md", 
                    content("# Python Script")
                )
                file(
                    ".gitignore", 
                    content("__pycache__/", "*.pyc", ".venv/")
                )
            }

            "RUST" -> {
                file(
                    "src/main.rs", 
                    content(
                        "fn main() {", 
                        "    println!(\"Hello, Rust!\");", 
                        "}"
                    )
                )
                file(
                    "Cargo.toml", 
                    content(
                        "[package]", 
                        "name = \"rust-project\"", 
                        "version = \"0.1.0\"", 
                        "edition = \"2021\"", 
                        "", 
                        "[dependencies]"
                    )
                )
                file(
                    "README.md", 
                    content("# Rust Project")
                )
                file(
                    ".gitignore", 
                    content("target/", "Cargo.lock")
                )
            }

            "CPP", "C" -> {
                val isCpp = template.name.uppercase() == "CPP"
                val ext = if (isCpp) "cpp" else "c"
                if (isCpp) {
                    file(
                        "src/main.cpp", 
                        content(
                            "#include <iostream>", 
                            "", 
                            "int main() {", 
                            "    std::cout << \"Hello, C++!\" << std::endl;", 
                            "    return 0;", 
                            "}"
                        )
                    )
                } else {
                    file(
                        "src/main.c", 
                        content(
                            "#include <stdio.h>", 
                            "", 
                            "int main() {", 
                            "    printf(\"Hello, C!\\n\");", 
                            "    return 0;", 
                            "}"
                        )
                    )
                }
                folder("include")
                file(
                    "Makefile", 
                    content(
                        "CC = gcc", 
                        "CFLAGS = -Wall -Wextra", 
                        "", 
                        "all: main", 
                        "", 
                        "main: src/main.$ext", 
                        "\t\$(CC) \$(CFLAGS) -Iinclude -o main src/main.$ext", 
                        "", 
                        "clean:", 
                        "\trm -f main"
                    )
                )
                file(
                    "README.md", 
                    content("# C/C++ Project")
                )
                file(
                    ".gitignore", 
                    content("main", "*.o")
                )
            }

            "CSHARP" -> {
                file(
                    "Program.cs", 
                    content(
                        "using System;", 
                        "", 
                        "class Program {", 
                        "    static void Main() {", 
                        "        Console.WriteLine(\"Hello C#!\");", 
                        "    }", 
                        "}"
                    )
                )
                file(
                    "MyApp.csproj", 
                    content(
                        "<Project Sdk=\"Microsoft.NET.Sdk\">", 
                        "  <PropertyGroup>", 
                        "    <OutputType>Exe</OutputType>", 
                        "    <TargetFramework>net8.0</TargetFramework>", 
                        "  </PropertyGroup>", 
                        "</Project>"
                    )
                )
                file(
                    "README.md", 
                    content("# C# Project")
                )
                file(
                    ".gitignore", 
                    content("bin/", "obj/", "*.user")
                )
            }

            // ── RESTORED MISSING LANGUAGES ─────────────────────────────────
            "JAVA" -> {
                file(
                    "src/Main.java", 
                    content(
                        "public class Main {", 
                        "    public static void main(String[] args) {", 
                        "        System.out.println(\"Hello, Java!\");", 
                        "    }", 
                        "}"
                    )
                )
                file(
                    "README.md", 
                    content("# Java Project")
                )
                file(
                    ".gitignore", 
                    content("*.class")
                )
            }

            "KOTLIN" -> {
                file(
                    "src/Main.kt", 
                    content(
                        "fun main() {", 
                        "    println(\"Hello, Kotlin!\")", 
                        "}"
                    )
                )
                file(
                    "README.md", 
                    content("# Kotlin Project")
                )
                file(
                    ".gitignore", 
                    content("*.class", "*.jar")
                )
            }

            "GO" -> {
                file(
                    "main.go", 
                    content(
                        "package main", 
                        "", 
                        "import \"fmt\"", 
                        "", 
                        "func main() {", 
                        "    fmt.Println(\"Hello, Go!\")", 
                        "}"
                    )
                )
                file(
                    "go.mod", 
                    content(
                        "module myapp", 
                        "", 
                        "go 1.21"
                    )
                )
                file(
                    "README.md", 
                    content("# Go Project")
                )
            }

            // ── 4. SIMPLE / UTILITY TEMPLATES ──────────────────────────────
            "MARKDOWN" -> {
                file(
                    "README.md", 
                    content(
                        "# Project Title", 
                        "", 
                        "A short description of this project.", 
                        "", 
                        "## Overview", 
                        "", 
                        "Write your documentation here.", 
                        "", 
                        "## Installation", 
                        "", 
                        "```bash", 
                        "# installation steps", 
                        "```", 
                        "", 
                        "## Usage", 
                        "", 
                        "Describe how to use the project.", 
                        "", 
                        "## License", 
                        "", 
                        "MIT"
                    )
                )
            }

            "JSON" -> {
                file(
                    "data.json", 
                    content(
                        "{", 
                        "  \"version\": \"1.0\",", 
                        "  \"status\": \"ready\"", 
                        "}"
                    )
                )
                file(
                    "config.json", 
                    content(
                        "{", 
                        "  \"env\": \"development\"", 
                        "}"
                    )
                )
            }

            "BLANK" -> {
                file(
                    "README.md", 
                    content("# Blank Project")
                )
                file(
                    ".gitignore", 
                    content("node_modules/", "build/", ".DS_Store")
                )
            }

            else -> {
                file(
                    "README.md", 
                    content(
                        "# New Project", 
                        "", 
                        "Start coding here."
                    )
                )
            }
        }

        // Post-process: Auto-wire all childrenIds perfectly for SQLite!
        val nodeList = nodes.values.toList()
        return nodeList.map { node ->
            val children = nodeList.filter { it.parentId == node.id }.map { it.id }
            node.copy(childrenIds = children)
        }
    }
}
