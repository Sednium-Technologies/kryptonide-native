package com.sednium.kryptonide.utils

import com.sednium.kryptonide.data.model.Project
import java.io.File

enum class ProjectDirSource {
    EXPLICIT_PATH,
    PREFERRED_PROJECTS_DIR,
    ROOT_WORKSPACE_DIR,
    LEGACY_KRYPTON_IDE_DIR
}

sealed class ProjectDirResult {
    data class Resolved(val directory: File, val source: ProjectDirSource) : ProjectDirResult()
    data class Missing(val searchedLocations: List<File>, val preferredLocation: File) : ProjectDirResult()
    data class Invalid(val reason: String) : ProjectDirResult()

    /**
     * Helper to return directory if resolved, or null otherwise.
     */
    fun directoryOrNull(): File? = when (this) {
        is Resolved -> directory
        else -> null
    }

    /**
     * Safe fallback: return resolved directory or the preferred location without creating it.
     */
    fun directoryOrPreferred(): File = when (this) {
        is Resolved -> directory
        is Missing -> preferredLocation
        is Invalid -> File(StorageUtils.getProjectsDir(), "invalid_project")
    }
}

class ProjectDirectoryResolver(
    private val externalStorageDir: File = StorageUtils.getExternalStorageDir(),
    private val kryptonideRoot: File = StorageUtils.getKryptonideRoot(),
    private val projectsDir: File = StorageUtils.getProjectsDir(),
    private val legacyRootDir: File = StorageUtils.getLegacyKryptonIDERoot()
) {

    companion object {
        val DEFAULT = ProjectDirectoryResolver()

        val RESERVED_INTERNAL_NAMES = setOf(
            ".checkpoints",
            ".metadata",
            ".nomedia",
            ".system_generated",
            "aif",
            "models",
            "mcp",
            "skills",
            "chats",
            "packages",
            "plugins",
            "projects",
            ".git",
            ".gradle",
            "build",
            "node_modules"
        )

        fun resolve(project: Project): ProjectDirResult = DEFAULT.resolve(project)

        fun isValidProjectName(name: String): Boolean {
            val trimmed = name.trim()
            if (trimmed.isBlank()) return false
            if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("\u0000")) return false
            if (trimmed == "." || trimmed == "..") return false
            if (trimmed.startsWith("../") || trimmed.startsWith("..\\")) return false
            if (trimmed.contains("..")) return false
            if (trimmed.lowercase() in RESERVED_INTERNAL_NAMES) return false
            return true
        }
    }

    fun resolve(project: Project): ProjectDirResult {
        // 1. Validate explicit path if provided
        if (project.path.isNotBlank()) {
            val explicitPath = project.path.trim()
            if (explicitPath.contains("\u0000")) {
                return ProjectDirResult.Invalid("Explicit path contains null byte: $explicitPath")
            }
            val explicitFile = File(explicitPath)
            if (explicitFile.exists() && explicitFile.isDirectory) {
                return ProjectDirResult.Resolved(explicitFile, ProjectDirSource.EXPLICIT_PATH)
            }
        }

        // 2. Validate project name
        val projectName = project.name.trim()
        if (!isValidProjectName(projectName)) {
            return ProjectDirResult.Invalid("Invalid project name or attempted path traversal: '$projectName'")
        }

        val searched = mutableListOf<File>()

        // 3. Check preferred location: Kryptonide/projects/<name>
        val preferred = File(projectsDir, projectName)
        searched.add(preferred)
        if (preferred.exists() && preferred.isDirectory) {
            return ProjectDirResult.Resolved(preferred, ProjectDirSource.PREFERRED_PROJECTS_DIR)
        }

        // 4. Check root workspace: Kryptonide/<name>
        val rootCandidate = File(kryptonideRoot, projectName)
        searched.add(rootCandidate)
        if (rootCandidate.exists() && rootCandidate.isDirectory && projectName.lowercase() !in RESERVED_INTERNAL_NAMES) {
            return ProjectDirResult.Resolved(rootCandidate, ProjectDirSource.ROOT_WORKSPACE_DIR)
        }

        // 5. Check legacy KryptonIDE/<name>
        val legacyCandidate = File(legacyRootDir, projectName)
        searched.add(legacyCandidate)
        if (legacyCandidate.exists() && legacyCandidate.isDirectory) {
            return ProjectDirResult.Resolved(legacyCandidate, ProjectDirSource.LEGACY_KRYPTON_IDE_DIR)
        }

        // 6. Missing everywhere — NEVER auto-create here!
        return ProjectDirResult.Missing(searchedLocations = searched, preferredLocation = preferred)
    }

    fun getPreferredDir(projectName: String): File = File(projectsDir, projectName.trim())
}
