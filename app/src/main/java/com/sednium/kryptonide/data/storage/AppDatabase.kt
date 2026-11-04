package com.sednium.kryptonide.data.storage

import android.content.Context
import com.sednium.kryptonide.utils.StorageUtils
import com.sednium.kryptonide.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

// ─── Serializable wrappers ────────────────────────────────────

@Serializable
data class ProjectStore(val projects: List<Project> = emptyList())

@Serializable
data class FileStore(val files: List<FileNode> = emptyList())

@Serializable
data class ChatStore(
    val sessions: List<ChatSession> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
)

// ─── Simple JSON file database ────────────────────────────────

class AppDatabase(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val baseDir: File
        get() = try {
            val isManager = StorageUtils.isManagerSafe()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && isManager) {
                val metadataDir = File(StorageUtils.getKryptonideRoot(), ".metadata").also { it.mkdirs() }
                // Migrate legacy metadata if present
                val legacyMetadata = File(StorageUtils.getExternalStorageDir(), "KryptonIDE/.metadata")
                if (legacyMetadata.exists() && legacyMetadata.isDirectory) {
                    legacyMetadata.listFiles()?.forEach { file ->
                        val target = File(metadataDir, file.name)
                        if (!target.exists()) runCatching { file.copyTo(target, overwrite = false) }
                    }
                }
                metadataDir
            } else {
                context.filesDir.also { it.mkdirs() }
            }
        } catch (e: Exception) {
            context.filesDir.also { it.mkdirs() }
        }

    private fun projectsFile() = File(baseDir, "projects.json")
    private fun filesFile(projectId: String) = File(baseDir, "files_$projectId.json")

    /** Primary chats directory in /sdcard/Kryptonide/chats. */
    private fun chatsDir(): File = StorageUtils.getChatsDir()

    /** Primary chat file in /sdcard/Kryptonide/chats/chat_<id>.json. */
    private fun chatFile(projectId: String): File {
        return File(chatsDir(), "chat_$projectId.json")
    }

    /** Legacy or fallback chat file in baseDir/chat_<id>.json. */
    private fun fallbackChatFile(projectId: String): File {
        return File(baseDir, "chat_$projectId.json")
    }

    /** Real on-disk directory holding projects.json/files_*.json/chat_*.json — see [baseDir]. */
    fun metadataDir(): File = baseDir

    // ── Projects ─────────────────────────────────────────────

    suspend fun getProjects(): List<Project> = withContext(Dispatchers.IO) {
        val f = projectsFile()
        if (!f.exists()) return@withContext emptyList()
        runCatching { json.decodeFromString<ProjectStore>(f.readText()).projects }.getOrElse { emptyList() }
    }

    suspend fun saveProject(project: Project) = withContext(Dispatchers.IO) {
        val projects = getProjects().toMutableList()
        val idx = projects.indexOfFirst {
            it.id == project.id ||
            it.name.equals(project.name, ignoreCase = true) ||
            (it.path.isNotBlank() && project.path.isNotBlank() && it.path == project.path)
        }
        if (idx >= 0) {
            val existing = projects[idx]
            if (existing.id != project.id && existing.id.isNotBlank()) {
                val oldChat = chatFile(existing.id)
                val newChat = chatFile(project.id)
                if (oldChat.exists() && (!newChat.exists() || newChat.length() == 0L)) {
                    runCatching { oldChat.copyTo(newChat, overwrite = true); oldChat.delete() }
                }
                val oldFiles = filesFile(existing.id)
                val newFiles = filesFile(project.id)
                if (oldFiles.exists() && (!newFiles.exists() || newFiles.length() == 0L)) {
                    runCatching { oldFiles.copyTo(newFiles, overwrite = true); oldFiles.delete() }
                }
            }
            projects[idx] = project
        } else {
            projects.add(project)
        }
        projectsFile().writeText(json.encodeToString(ProjectStore(projects)))
    }

    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) {
        val projects = getProjects().filter { it.id != id }
        projectsFile().writeText(json.encodeToString(ProjectStore(projects)))
        filesFile(id).delete()
        chatFile(id).delete()
    }

    // ── Files ─────────────────────────────────────────────────

    suspend fun getFiles(projectId: String): List<FileNode> = withContext(Dispatchers.IO) {
        val f = filesFile(projectId)
        if (!f.exists()) return@withContext emptyList()
        runCatching { json.decodeFromString<FileStore>(f.readText()).files }.getOrElse { emptyList() }
    }

    suspend fun saveFiles(projectId: String, files: List<FileNode>) = withContext(Dispatchers.IO) {
        filesFile(projectId).writeText(json.encodeToString(FileStore(files)))
    }

    suspend fun upsertFile(file: FileNode) = withContext(Dispatchers.IO) {
        val files = getFiles(file.projectId).toMutableList()
        val idx = files.indexOfFirst { it.id == file.id }
        if (idx >= 0) files[idx] = file else files.add(file)
        saveFiles(file.projectId, files)
    }

    suspend fun deleteFile(projectId: String, fileId: String) = withContext(Dispatchers.IO) {
        val files = getFiles(projectId).filter { it.id != fileId }
        saveFiles(projectId, files)
    }

    suspend fun deleteFiles(projectId: String, fileIds: Set<String>) = withContext(Dispatchers.IO) {
        val files = getFiles(projectId).filter { it.id !in fileIds }
        saveFiles(projectId, files)
    }

    // ── Chat ──────────────────────────────────────────────────

    suspend fun getChatStore(projectId: String): ChatStore = withContext(Dispatchers.IO) {
        val primaryFile = chatFile(projectId)
        val all = getProjects()
        val currentProject = all.firstOrNull { it.id == projectId || it.name.equals(projectId, ignoreCase = true) }
        val targetName = currentProject?.name ?: projectId

        // Candidate files to search in priority order
        val candidateFiles = mutableListOf<File>()
        candidateFiles.add(primaryFile)
        candidateFiles.add(File(chatsDir(), "chat_$targetName.json"))

        // Inside project folder: <projectDir>/.kryptonide/chat.json
        currentProject?.path?.takeIf { it.isNotBlank() }?.let { path ->
            candidateFiles.add(File(path, ".kryptonide/chat.json"))
        }
        candidateFiles.add(File(StorageUtils.getProjectsDir(), "$targetName/.kryptonide/chat.json"))
        candidateFiles.add(File(StorageUtils.getKryptonideRoot(), "$targetName/.kryptonide/chat.json"))
        candidateFiles.add(File(StorageUtils.getExternalStorageDir(), "KryptonIDE/$targetName/.kryptonide/chat.json"))

        // Legacy storage locations:
        candidateFiles.add(fallbackChatFile(projectId))
        candidateFiles.add(fallbackChatFile(targetName))
        candidateFiles.add(File(StorageUtils.getExternalStorageDir(), "KryptonIDE/chats/chat_$projectId.json"))
        candidateFiles.add(File(StorageUtils.getExternalStorageDir(), "KryptonIDE/chats/chat_$targetName.json"))
        candidateFiles.add(File(StorageUtils.getExternalStorageDir(), "KryptonIDE/.metadata/chat_$projectId.json"))
        candidateFiles.add(File(StorageUtils.getExternalStorageDir(), "KryptonIDE/.metadata/chat_$targetName.json"))
        candidateFiles.add(File(context.filesDir, "chat_$projectId.json"))
        candidateFiles.add(File(context.filesDir, "chat_$targetName.json"))

        for (candidate in candidateFiles) {
            if (candidate.exists() && candidate.length() > 0) {
                val candidateStore = runCatching { json.decodeFromString<ChatStore>(candidate.readText()) }.getOrNull()
                if (candidateStore != null && (candidateStore.messages.isNotEmpty() || candidateStore.sessions.isNotEmpty())) {
                    val migratedSessions = candidateStore.sessions.map { it.copy(projectId = projectId) }
                    val migratedMessages = candidateStore.messages.map { it.copy(projectId = projectId) }
                    val migratedStore = ChatStore(sessions = migratedSessions, messages = migratedMessages)
                    // Ensure saved to primary location
                    saveChatStore(projectId, migratedStore)
                    return@withContext migratedStore
                }
            }
        }

        // Check sibling project IDs with matching name
        val siblingProjects = all.filter {
            it.name.equals(targetName, ignoreCase = true) && it.id != projectId
        }
        for (sibling in siblingProjects) {
            val siblingFiles = listOf(
                chatFile(sibling.id),
                fallbackChatFile(sibling.id),
                File(StorageUtils.getExternalStorageDir(), "KryptonIDE/chats/chat_${sibling.id}.json")
            )
            for (siblingFile in siblingFiles) {
                if (siblingFile.exists() && siblingFile.length() > 0) {
                    val siblingStore = runCatching { json.decodeFromString<ChatStore>(siblingFile.readText()) }.getOrNull()
                    if (siblingStore != null && (siblingStore.messages.isNotEmpty() || siblingStore.sessions.isNotEmpty())) {
                        val migratedSessions = siblingStore.sessions.map { it.copy(projectId = projectId) }
                        val migratedMessages = siblingStore.messages.map { it.copy(projectId = projectId) }
                        val migratedStore = ChatStore(sessions = migratedSessions, messages = migratedMessages)
                        saveChatStore(projectId, migratedStore)
                        return@withContext migratedStore
                    }
                }
            }
        }

        ChatStore()
    }

    suspend fun saveChatStore(projectId: String, store: ChatStore) = withContext(Dispatchers.IO) {
        val serialized = json.encodeToString(store)
        val all = getProjects()
        val currentProject = all.firstOrNull { it.id == projectId || it.name.equals(projectId, ignoreCase = true) }
        val targetName = currentProject?.name ?: projectId

        // 1. Primary chats directory (/sdcard/Kryptonide/chats/chat_<projectId>.json)
        runCatching {
            chatFile(projectId).writeText(serialized)
            if (targetName != projectId) {
                File(chatsDir(), "chat_$targetName.json").writeText(serialized)
            }
        }

        // 2. Fallback baseDir
        runCatching {
            fallbackChatFile(projectId).writeText(serialized)
        }

        // 3. Project-internal directory: <projectDir>/.kryptonide/chat.json
        runCatching {
            val projectDirs = mutableListOf<File>()
            currentProject?.path?.takeIf { it.isNotBlank() }?.let { projectDirs.add(File(it)) }
            projectDirs.add(File(StorageUtils.getProjectsDir(), targetName))
            projectDirs.add(File(StorageUtils.getKryptonideRoot(), targetName))

            for (pDir in projectDirs) {
                if (pDir.exists() && pDir.isDirectory) {
                    val dotDir = File(pDir, ".kryptonide").also { it.mkdirs() }
                    File(dotDir, "chat.json").writeText(serialized)
                }
            }
        }
    }

    // ── Singleton ─────────────────────────────────────────────

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: AppDatabase(context.applicationContext).also { instance = it }
            }
    }
}
