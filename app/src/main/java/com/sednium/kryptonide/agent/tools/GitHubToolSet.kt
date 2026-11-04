package com.sednium.kryptonide.agent.tools

import com.sednium.kryptonide.data.repository.GithubRepository
import com.sednium.kryptonide.utils.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

class GitHubToolSet(
    private val getLinkedRepo: () -> String?,
    private val setLinkedRepo: (String?) -> Unit,
    private val getGithubToken: () -> String,
    private val getFiles: () -> List<Pair<String, String>>,
    private val getProjectRootDir: () -> File = { StorageUtils.getProjectsDir() },
    private val onFilesChanged: (() -> Unit)? = null
) {
    private val github = GithubRepository()

    fun registerAll() {
        AgentToolRegistry.register(
            ToolDefinition(
                name = "github_push",
                description = "Push project workspace files to the linked GitHub repository with a commit message.",
                parameters = listOf(
                    ToolParam("commit_message", "string", "Git commit message (e.g. 'Update UI and components')", required = false)
                )
            ) { args ->
                val commitMsg = args["commit_message"]?.toString()?.ifBlank { "Update from Kryptonide" } ?: "Update from Kryptonide"
                val linked = getLinkedRepo()
                if (linked.isNullOrBlank()) {
                    return@ToolDefinition ToolResult(
                        success = false,
                        output = "",
                        error = "No GitHub repository is currently linked to this project. Connect one using github_connect(repo_name=\"owner/repo\") or via the Git panel.",
                        exitCode = 1
                    )
                }

                val parts = linked.split("/")
                if (parts.size != 2) {
                    return@ToolDefinition ToolResult(
                        success = false,
                        output = "",
                        error = "Invalid linked repository format: '$linked'. Expected 'owner/repo'.",
                        exitCode = 1
                    )
                }

                val token = getGithubToken()
                if (token.isBlank()) {
                    return@ToolDefinition ToolResult(
                        success = false,
                        output = "",
                        error = "No GitHub personal access token found. Please sign in or set your token in the Git tab.",
                        exitCode = 1
                    )
                }

                val files = getFiles()
                if (files.isEmpty()) {
                    return@ToolDefinition ToolResult(
                        success = false,
                        output = "",
                        error = "No project files available to push.",
                        exitCode = 1
                    )
                }

                try {
                    withContext(Dispatchers.IO) {
                        github.pushFiles(
                            token = token,
                            owner = parts[0],
                            repo = parts[1],
                            branch = "main",
                            files = files,
                            commitMessage = commitMsg
                        )
                    }
                    ToolResult(
                        success = true,
                        output = "🚀 **Pushed to GitHub**\n- **Repository**: `$linked`\n- **Branch**: `main`\n- **Files Pushed**: ${files.size} workspace files\n- **Commit**: \"$commitMsg\"",
                        exitCode = 0
                    )
                } catch (e: Throwable) {
                    ToolResult(
                        success = false,
                        output = "",
                        error = "GitHub push failed: ${e.message}",
                        exitCode = 1
                    )
                }
            }
        )

        AgentToolRegistry.register(
            ToolDefinition(
                name = "github_connect",
                description = "Connect or link a GitHub repository (e.g. 'owner/repo' or https://github.com/owner/repo) to the current project.",
                parameters = listOf(
                    ToolParam("repo_name", "string", "GitHub repository in 'owner/repo' format (or full github.com URL)")
                )
            ) { args ->
                val raw = args["repo_name"]?.toString()?.trim()
                    ?: return@ToolDefinition ToolResult(false, "", "Missing 'repo_name' parameter.", exitCode = 1)

                val clean = raw.removePrefix("https://github.com/")
                    .removePrefix("http://github.com/")
                    .removePrefix("git@github.com:")
                    .removeSuffix(".git")
                    .trim('/')

                val targetRepo = if (clean.contains("/") && clean.split("/").size == 2) {
                    clean
                } else {
                    // Try to resolve owner from user token or default Sednium-Technologies org
                    val token = getGithubToken()
                    var resolved: String? = null
                    if (token.isNotBlank()) {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val repos = github.listRepos(token)
                                val match = repos.firstOrNull { it.name.equals(clean, ignoreCase = true) || it.full_name.endsWith("/$clean", ignoreCase = true) }
                                if (match != null) {
                                    resolved = match.full_name
                                } else {
                                    val user = github.getUser(token)
                                    resolved = "${user.login}/$clean"
                                }
                            }
                        }
                    }
                    resolved ?: "Sednium-Technologies/$clean"
                }

                val parts = targetRepo.split("/")
                if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    return@ToolDefinition ToolResult(
                        success = false,
                        output = "",
                        error = "Invalid repository format: '$targetRepo'. Expected 'owner/repo'.",
                        exitCode = 1
                    )
                }

                val owner = parts[0]
                val repo = parts[1]
                val token = getGithubToken()

                val repoVerification = withContext(Dispatchers.IO) {
                    github.getRepoInfo(owner, repo, token)
                }

                if (repoVerification.isFailure) {
                    val ex = repoVerification.exceptionOrNull()
                    val errorMsg = ex?.message ?: "Repository '$targetRepo' could not be found or accessed on GitHub."
                    return@ToolDefinition ToolResult(
                        success = false,
                        output = "",
                        error = "⚠️ Connection Failed: $errorMsg\n\nIf this is a private repository, please ensure you are signed in to GitHub with a personal access token with 'repo' scope in the Git tab.",
                        exitCode = 1
                    )
                }

                val verifiedRepo = repoVerification.getOrThrow()
                val canonicalName = verifiedRepo.full_name.ifBlank { targetRepo }
                setLinkedRepo(canonicalName)
                ToolResult(
                    success = true,
                    output = "🔗 **GitHub Repository Linked**\n- **Repository**: `$canonicalName`\n- **Visibility**: ${if (verifiedRepo.private) "Private 🔒" else "Public 🌐"}\n- **Default Branch**: `${verifiedRepo.default_branch}`\n- **Status**: Verified and successfully connected. All subsequent git/push actions will target this repository.",
                    exitCode = 0
                )
            }
        )

        AgentToolRegistry.register(
            ToolDefinition(
                name = "github_disconnect",
                description = "Unlink and disconnect any currently linked GitHub repository from this project.",
                parameters = emptyList()
            ) {
                val previous = getLinkedRepo()
                setLinkedRepo(null)
                ToolResult(
                    success = true,
                    output = "🔌 **GitHub Repository Disconnected**\n- **Previous Repository**: ${previous ?: "None"}\n- **Status**: Successfully unlinked. No repository is currently linked to this project.",
                    exitCode = 0
                )
            }
        )

        AgentToolRegistry.register(
            ToolDefinition(
                name = "github_clone",
                description = "Clone a GitHub repository into a target folder or current project workspace within /storage/emulated/0/Kryptonide.",
                parameters = listOf(
                    ToolParam("repo_url", "string", "GitHub repository URL (e.g. https://github.com/owner/repo) or 'owner/repo'"),
                    ToolParam("target_folder", "string", "Target folder name or relative path to clone into (optional, defaults to repo name)", required = false)
                )
            ) { args ->
                val rawUrl = args["repo_url"]?.toString()?.trim()
                    ?: return@ToolDefinition ToolResult(false, "", "Missing required 'repo_url' parameter.", exitCode = 1)
                val rawTarget = args["target_folder"]?.toString()?.trim()

                val fullUrl = when {
                    rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
                    rawUrl.startsWith("git@") -> rawUrl
                    rawUrl.contains("/") -> "https://github.com/${rawUrl.removeSuffix(".git")}.git"
                    else -> "https://github.com/Sednium-Technologies/${rawUrl.removeSuffix(".git")}.git"
                }

                val repoName = fullUrl.substringAfterLast("/").removeSuffix(".git").ifBlank { "cloned-repo" }

                val kryptonideRoot = StorageUtils.getKryptonideRoot().canonicalFile
                val projectRoot = getProjectRootDir().canonicalFile

                val targetDir = when {
                    rawTarget.isNullOrBlank() || rawTarget.lowercase() in listOf("this folder", "current folder", "here", ".") -> {
                        if (projectRoot.exists() && projectRoot.listFiles()?.any { it.name != ".git" } == true) {
                            File(projectRoot, repoName).canonicalFile
                        } else {
                            projectRoot
                        }
                    }
                    rawTarget.startsWith("/") -> {
                        File(rawTarget).canonicalFile
                    }
                    else -> {
                        File(projectRoot, rawTarget).canonicalFile
                    }
                }

                // Strict boundary enforcement: MUST stay within /storage/emulated/0/Kryptonide
                if (!targetDir.path.startsWith(kryptonideRoot.path)) {
                    return@ToolDefinition ToolResult(
                        success = false,
                        output = "",
                        error = "Access denied: destination directory '${targetDir.path}' must be inside ${kryptonideRoot.path}",
                        exitCode = 1
                    )
                }

                val token = getGithubToken()

                try {
                    withContext(Dispatchers.IO) {
                        targetDir.mkdirs()
                        val cloneCmd = Git.cloneRepository()
                            .setURI(fullUrl)
                            .setDirectory(targetDir)
                        if (token.isNotBlank()) {
                            cloneCmd.setCredentialsProvider(
                                UsernamePasswordCredentialsProvider(token, "")
                            )
                        }
                        cloneCmd.call().use {
                            // Cloned successfully
                        }
                    }

                    val linkedSlug = if (fullUrl.contains("github.com/")) {
                        fullUrl.substringAfter("github.com/").removeSuffix(".git")
                    } else {
                        repoName
                    }
                    setLinkedRepo(linkedSlug)

                    onFilesChanged?.invoke()

                    val fileCount = targetDir.walkTopDown().filter { it.isFile && !it.path.contains("/.git/") }.count()

                    ToolResult(
                        success = true,
                        output = "📦 **Repository Cloned Successfully**\n- **Remote URL**: `$fullUrl`\n- **Destination**: `${targetDir.absolutePath}`\n- **Files Cloned**: $fileCount files\n- **Linked Repo**: `$linkedSlug`\n\nAll files have been cloned and are ready in your Kryptonide workspace.",
                        exitCode = 0
                    )
                } catch (e: org.eclipse.jgit.api.errors.TransportException) {
                    val msg = if (token.isBlank())
                        "Clone failed (TransportException): this may be a private repository. Please connect your GitHub account or Personal Access Token in the Git tab."
                    else
                        "Clone failed: ${e.message}"
                    ToolResult(false, "", msg, exitCode = 1)
                } catch (e: Throwable) {
                    ToolResult(false, "", "Clone failed: ${e.message}", exitCode = 1)
                }
            }
        )

        AgentToolRegistry.register(
            ToolDefinition(
                name = "github_status",
                description = "Get status of the linked GitHub repository, authentication, and workspace files.",
                parameters = emptyList()
            ) {
                val linked = getLinkedRepo()
                val token = getGithubToken()
                val hasToken = token.isNotBlank()
                val files = getFiles()

                val info = buildString {
                    appendLine("📊 **GitHub Integration Status**")
                    appendLine("- **Linked Repository**: ${linked ?: "None (not linked)"}")
                    appendLine("- **Authentication**: ${if (hasToken) "Authenticated (Token configured)" else "Not signed in"}")
                    appendLine("- **Project Files**: ${files.size} files in workspace")
                }

                ToolResult(success = true, output = info, exitCode = 0)
            }
        )
    }
}
