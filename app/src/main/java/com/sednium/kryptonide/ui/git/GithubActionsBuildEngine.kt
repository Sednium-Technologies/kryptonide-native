package com.sednium.kryptonide.ui.git

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sednium.kryptonide.data.repository.ActionsJob
import com.sednium.kryptonide.data.repository.ActionsRun
import com.sednium.kryptonide.data.repository.GithubActionsRepository
import com.sednium.kryptonide.data.storage.SettingsStore
import com.sednium.kryptonide.service.BuildNotifier
import com.sednium.kryptonide.utils.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class CloudBuildPhase {
    IDLE, ENSURING_WORKFLOW, TRIGGERING, WAITING_FOR_RUN, QUEUED, IN_PROGRESS, DOWNLOADING, SUCCESS, FAILED, CANCELLED
}

data class PendingCleanup(val releaseId: Long, val tag: String)

data class CloudBuildState(
    val phase:   CloudBuildPhase = CloudBuildPhase.IDLE,
    val log:     List<String> = emptyList(),
    val runUrl:  String? = null,
    val jobs:    List<ActionsJob> = emptyList(),
    val apkPath: String? = null,
    val error:   String? = null,
    // Set after a successful build if a previous build's release still
    // exists — the UI should ask the user whether to delete it, not do so
    // silently. Cleared once the user answers either way.
    val pendingCleanup: PendingCleanup? = null,
)

/**
 * Runs a real build on GitHub's own runners instead of on-device — useful
 * when the local JDK/SDK toolchain isn't set up, or just to offload a big
 * build. Requires the project to already be linked + pushed to a GitHub repo
 * (see [GitPanel] / [GitViewModel]).
 */
class GithubActionsBuildEngine(app: Application) : AndroidViewModel(app) {

    private val actions  = GithubActionsRepository()
    private val settings = SettingsStore(app)

    private val _state = MutableStateFlow(CloudBuildState())
    val state: StateFlow<CloudBuildState> = _state.asStateFlow()

    private var job: Job? = null
    private var currentOwner: String? = null
    private var currentRepo:  String? = null

    fun start(owner: String, repo: String, branch: String = "main", buildType: String = "debug") {
        if (_state.value.phase !in setOf(CloudBuildPhase.IDLE, CloudBuildPhase.SUCCESS, CloudBuildPhase.FAILED, CloudBuildPhase.CANCELLED)) return

        val token = settings.getGithubToken()
        if (token.isBlank()) {
            _state.value = CloudBuildState(phase = CloudBuildPhase.FAILED, error = "Sign in to GitHub first.")
            return
        }

        _state.value = CloudBuildState(phase = CloudBuildPhase.ENSURING_WORKFLOW)
        currentOwner = owner
        currentRepo  = repo
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                log("Checking for a build workflow in $owner/$repo…")
                val created = runCatching { actions.ensureWorkflow(token, owner, repo, branch) }
                    .getOrElse { throw IllegalStateException("Could not write workflow file: ${it.message}") }
                log(if (created) "Added .github/workflows/kryptonide-build.yml" else "Workflow already present.")

                notify("Cloud build starting…", "Triggering GitHub Actions", indeterminate = true)
                updatePhase(CloudBuildPhase.TRIGGERING)
                val dispatchTime = System.currentTimeMillis()
                if (created) {
                    // A brand-new workflow file needs a moment before GitHub registers it as dispatchable.
                    delay(4000)
                }
                actions.dispatch(token, owner, repo, branch, buildType)
                log("Triggered $buildType build (workflow_dispatch) on '$branch'.")

                updatePhase(CloudBuildPhase.WAITING_FOR_RUN)
                var run: ActionsRun? = null
                repeat(15) {
                    delay(3000)
                    run = actions.findLatestRun(token, owner, repo, dispatchTime)
                    if (run != null) return@repeat
                }
                if (run == null) throw IllegalStateException("Could not find the triggered run — check the repo's Actions tab.")
                log("Run #${run!!.run_number} found: ${run!!.html_url}")
                _state.update { it.copy(runUrl = run!!.html_url) }

                // Poll until completed.
                var current = run!!
                while (current.status != "completed") {
                    val phase = if (current.status == "queued") CloudBuildPhase.QUEUED else CloudBuildPhase.IN_PROGRESS
                    updatePhase(phase)
                    val jobs = runCatching { actions.getJobs(token, owner, repo, current.id) }.getOrElse { emptyList() }
                    _state.update { it.copy(jobs = jobs) }
                    val activeStep = jobs.flatMap { it.steps }.lastOrNull { it.status == "in_progress" }?.name
                    notify(
                        "Cloud build ${if (phase == CloudBuildPhase.QUEUED) "queued" else "running"}…",
                        activeStep ?: "Run #${current.run_number}",
                        indeterminate = true
                    )
                    delay(6000)
                    current = actions.getRun(token, owner, repo, current.id)
                }

                if (current.conclusion != "success") {
                    log("✗ Run finished with conclusion: ${current.conclusion}")
                    _state.update { it.copy(phase = CloudBuildPhase.FAILED, error = "Build ${current.conclusion ?: "failed"} — see full log on GitHub.") }
                    BuildNotifier.failure(getApplication(), "Cloud build failed", "Conclusion: ${current.conclusion ?: "unknown"}")
                    return@launch
                }

                log("✓ Run completed successfully. Fetching release…")
                updatePhase(CloudBuildPhase.DOWNLOADING)
                notify("Downloading APK…", "Build succeeded, fetching release", indeterminate = true)

                // The "Publish as a Release" workflow step runs inside the
                // same job, before it reports "completed" — so the release
                // should already exist by now. A short retry still guards
                // against GitHub API read-after-write lag on their side.
                val tag = GithubActionsRepository.releaseTagFor(current.run_number)
                var release: com.sednium.kryptonide.data.repository.GithubActionsRepository.ReleaseInfo? = null
                repeat(5) {
                    release = actions.findRelease(token, owner, repo, tag)
                    if (release != null) return@repeat
                    delay(2000)
                }
                val rel = release
                    ?: throw IllegalStateException("Build succeeded but release '$tag' wasn't found (did the release-publish step run?).")
                val asset = rel.assets.firstOrNull { it.name.endsWith(".apk") }
                    ?: throw IllegalStateException("Release '$tag' has no .apk asset attached.")

                val destDir = File(StorageUtils.getKryptonideRoot(), ".actions-builds/$repo")
                destDir.mkdirs()
                val apkFile = File(destDir, asset.name)
                actions.downloadReleaseAsset(token, owner, repo, asset.id, apkFile)

                log("✓ APK ready: ${apkFile.absolutePath}")

                // Offer to clean up the *previous* build's release now that
                // this new one has landed safely — never the other way
                // around, so a failed new build never leaves the user with
                // nothing installable.
                val previous = settings.getLastCloudBuildRelease(owner, repo)
                val cleanup = if (previous != null && previous.first != rel.id) {
                    PendingCleanup(releaseId = previous.first, tag = previous.second)
                } else null
                settings.setLastCloudBuildRelease(owner, repo, rel.id, tag)

                _state.update { it.copy(phase = CloudBuildPhase.SUCCESS, apkPath = apkFile.absolutePath, pendingCleanup = cleanup) }
                BuildNotifier.success(getApplication(), "Cloud build successful", "APK downloaded — tap to open Kryptonide")

            } catch (e: Exception) {
                log("✗ ${e.message}")
                _state.update { it.copy(phase = CloudBuildPhase.FAILED, error = e.message ?: "Cloud build failed") }
                BuildNotifier.failure(getApplication(), "Cloud build failed", e.message ?: "See log for details")
            }
        }
    }

    /** User chose to delete the previous build's release + tag from the "clean up old build?" prompt. */
    fun confirmCleanup() {
        val cleanup = _state.value.pendingCleanup ?: return
        val owner = currentOwner ?: return
        val repo  = currentRepo  ?: return
        val token = settings.getGithubToken()
        if (token.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { actions.deleteRelease(token, owner, repo, cleanup.releaseId, cleanup.tag) }
            _state.update { it.copy(pendingCleanup = null) }
        }
    }

    /** User chose to keep the previous build's release around — just dismiss the prompt, don't delete anything. */
    fun dismissCleanup() {
        _state.update { it.copy(pendingCleanup = null) }
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(phase = CloudBuildPhase.CANCELLED) }
        BuildNotifier.clear(getApplication())
    }

    fun reset() {
        job?.cancel()
        _state.value = CloudBuildState()
    }

    private fun updatePhase(phase: CloudBuildPhase) {
        _state.update { it.copy(phase = phase) }
    }

    private fun log(line: String) {
        _state.update { it.copy(log = it.log + line) }
    }

    private fun notify(title: String, message: String, indeterminate: Boolean) {
        BuildNotifier.progress(getApplication(), title, message, indeterminate)
    }
}
