package com.sednium.kryptonide.ui.projects

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sednium.kryptonide.data.model.Project
import com.sednium.kryptonide.data.model.ProjectTemplate
import com.sednium.kryptonide.data.repository.ProjectRepository
import com.sednium.kryptonide.data.storage.AppDatabase
import com.sednium.kryptonide.service.DiskSyncEvent
import com.sednium.kryptonide.service.FileWatcherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import com.sednium.kryptonide.utils.StorageUtils
import java.io.File

data class ProjectsUiState(
    val projects: List<Project> = emptyList(),
    val isLoading: Boolean = true,
    val currentProjectId: String? = null,
    val lastOpenedProjectId: String? = null,
)

class ProjectsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ProjectRepository(AppDatabase.getInstance(app))
    private val prefs = app.getSharedPreferences("krypton_projects_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(ProjectsUiState(
        lastOpenedProjectId = prefs.getString("last_opened_project_id", null)
    ))
    val state: StateFlow<ProjectsUiState> = _state.asStateFlow()

    init { 
        loadProjects() 
        watchForNewProjects()
    }

    // Watch the KryptonIDE root for new project folders appearing
    // (created externally by file managers, ZArchiver, etc.)
    private fun watchForNewProjects() {
        FileWatcherService.events
            .onEach { event ->
                if (event is DiskSyncEvent.ProjectDiscovered) {
                    val name = event.projectName.lowercase()
                    if (name.startsWith(".") || name in ProjectRepository.KRYPTON_INTERNAL_DIRS) {
                        return@onEach
                    }
                    // A new project folder appeared — import it and reload the list
                    viewModelScope.launch {
                        repo.importProjectFromDisk(event.projectName)
                        loadProjects()   // refresh the dashboard
                    }
                }
            }
            .launchIn(viewModelScope)

        // mkdirs() + the recursive FileObserver setup are real disk I/O (and,
        // pre-exclusion-list, could recurse into thousands of node_modules/
        // build/.git subfolders) — run off the main thread so app startup
        // doesn't stall on it. The collector above is already armed, so
        // nothing gets missed by starting the watch slightly later.
        viewModelScope.launch(Dispatchers.IO) {
            val root = StorageUtils.getKryptonideRoot()
            root.mkdirs()
            FileWatcherService.startWatching("") // watches root level only
        }
    }

    fun loadProjects() {
        viewModelScope.launch {
            // 1. Instant load from local Room DB — zero I/O delay for low-end devices
            val dbProjects = repo.getAllProjects().toMutableList()
            dbProjects.removeAll { it.name.startsWith(".") || it.name.lowercase() in ProjectRepository.KRYPTON_INTERNAL_DIRS }
            _state.update { it.copy(
                projects = dbProjects.sortedByDescending { p -> p.updatedAt },
                isLoading = false
            )}

            // 2. Background check for externally added folders without blocking the UI
            withContext(Dispatchers.IO) {
                // Ensure legacy and root project migrations have completed
                StorageUtils.migrateRootProjectsToProjectsSubdir(StorageUtils.getKryptonideRoot())

                val projectsDir = StorageUtils.getProjectsDir()
                val diskFolders = mutableListOf<String>()
                if (projectsDir.exists()) {
                    val folders = projectsDir.listFiles()
                        ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name.lowercase() !in ProjectRepository.KRYPTON_INTERNAL_DIRS }
                        ?.map { it.name }
                        ?: emptyList()
                    diskFolders.addAll(folders)
                }

                val registeredNames = dbProjects.map { it.name }.toSet()
                val unregistered = diskFolders.distinct().filter { it !in registeredNames }

                    if (unregistered.isNotEmpty()) {
                        unregistered.forEach { folderName ->
                            val imported = repo.importProjectFromDisk(folderName)
                            if (imported.id.isNotBlank()) {
                                dbProjects.add(imported)
                            }
                        }
                        dbProjects.removeAll { it.name.startsWith(".") || it.name.lowercase() in ProjectRepository.KRYPTON_INTERNAL_DIRS }
                        _state.update { it.copy(
                            projects = dbProjects.sortedByDescending { p -> p.updatedAt }
                        )}
                    }
                }
            }
        }

    fun createProject(
        name: String, 
        template: ProjectTemplate, 
        packageName: String = "com.example.myapp", 
        minSdk: Int = 24, 
        useKts: Boolean = false
    ) {
        viewModelScope.launch {
            repo.createProject(name, template, packageName, minSdk, useKts)
            loadProjects()
        }
    }

    /** Same as [createProject] but returns the new project's id, for callers that need to import files into it right after. */
    suspend fun createProjectAndGetId(
        name: String,
        template: ProjectTemplate = ProjectTemplate.BLANK,
        packageName: String = "com.example.myapp",
        minSdk: Int = 24,
        useKts: Boolean = false
    ): String {
        val project = repo.createProject(name, template, packageName, minSdk, useKts)
        loadProjects()
        return project.id
    }

    /** Rebuilds a project's FileNode DB from a real directory already on disk (GitHub clone, zip extraction). Returns file count imported. */
    suspend fun importDirectoryIntoProject(projectId: String, rootDir: File): Int {
        val count = repo.importDirectoryIntoProject(projectId, rootDir)
        loadProjects()
        return count
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            repo.deleteProject(id)
            val updated = _state.value.projects.filter { it.id != id }
            val newCurrentId = if (_state.value.currentProjectId == id) null else _state.value.currentProjectId
            _state.value = _state.value.copy(
                projects = updated, 
                currentProjectId = newCurrentId
            )
        }
    }

    fun renameProject(id: String, newName: String, onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            val success = repo.renameProject(id, newName)
            loadProjects()
            onComplete?.invoke(success)
        }
    }

    fun openProject(id: String) {
        prefs.edit().putString("last_opened_project_id", id).apply()
        _state.update { it.copy(currentProjectId = id, lastOpenedProjectId = id) }
        viewModelScope.launch {
            val project = repo.getProject(id)
            if (project != null) {
                repo.updateProject(project.copy(updatedAt = System.currentTimeMillis()))
            }
        }
    }

    fun closeProject() {
        _state.value = _state.value.copy(currentProjectId = null)
        loadProjects() 
    }
}
