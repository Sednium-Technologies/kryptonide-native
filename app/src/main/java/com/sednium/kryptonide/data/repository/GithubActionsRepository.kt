package com.sednium.kryptonide.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Base64

@Serializable
data class ActionsRun(
    val id: Long,
    val status: String,           // queued | in_progress | completed
    val conclusion: String? = null, // success | failure | cancelled | null
    val html_url: String = "",
    val created_at: String = "",
    val run_number: Int = 0,
    val name: String = "",
    val head_branch: String = "",
    val head_sha: String = "",
    val updated_at: String = ""
)

data class ActionsJobStep(val name: String, val status: String, val conclusion: String?, val number: Int = 0)
data class ActionsJob(val id: Long = 0L, val name: String, val status: String, val conclusion: String?, val steps: List<ActionsJobStep>)

/**
 * Drives GitHub Actions to build an APK in the cloud: commits a workflow
 * file if the repo doesn't have one, triggers it, and polls run/job status.
 * Uses the same personal-access-token flow as [GithubRepository].
 */
class GithubActionsRepository(private val client: OkHttpClient = OkHttpClient()) {

    private val json = Json { ignoreUnknownKeys = true }
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private fun Request.Builder.githubHeaders(token: String) = apply {
        header("Authorization", "Bearer $token")
        header("Accept", "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
    }

    companion object {
        const val WORKFLOW_PATH = ".github/workflows/kryptonide-build.yml"

        // The workflow tags each release "build-<run_number>" — GitHub run
        // numbers are unique and monotonically increasing per workflow, so
        // once the app knows a run's run_number (from findLatestRun/getRun,
        // both already returning it) it can derive the exact release tag to
        // look up without any extra round-trip to agree on a name beforehand.
        fun releaseTagFor(runNumber: Int) = "build-$runNumber"

        fun workflowYaml(): String = """
            name: Kryptonide Build
            on:
              workflow_dispatch:
                inputs:
                  build_type:
                    description: 'debug or release'
                    required: false
                    default: 'debug'
              push:
                branches: [ main, master ]
            permissions:
              contents: write
            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v4
                  - uses: actions/setup-java@v4
                    with:
                      distribution: 'temurin'
                      java-version: '17'
                  # Runs plain `gradle` (installed + cached by this action)
                  # rather than depending on a committed ./gradlew wrapper.
                  # KryptonIDE's own project templates don't generate a
                  # wrapper, and — separately — KryptonIDE's GitHub push
                  # path can't push binary files at all yet (its tree-API
                  # call sends file content as plain UTF-8 text, no base64/
                  # blob-sha path), so a wrapper jar could never reach the
                  # repo through the app even if one were generated
                  # on-device. Using the runner's own Gradle install
                  # sidesteps needing one at all.
                  - uses: gradle/actions/setup-gradle@v4
                    with:
                      gradle-version: '8.4'
                  - name: Determine build type
                    id: vars
                    run: echo "type=${'$'}{{ github.event.inputs.build_type || 'debug' }}" >> "${'$'}GITHUB_OUTPUT"
                  - name: Build APK
                    run: gradle "assemble${'$'}{{ steps.vars.outputs.type == 'release' && 'Release' || 'Debug' }}" --stacktrace
                  - name: Locate APK
                    id: apk
                    run: |
                      APK_PATH=${'$'}(find . -path "*/build/outputs/apk/${'$'}{{ steps.vars.outputs.type }}/*.apk" -print -quit)
                      if [ -z "${'$'}APK_PATH" ]; then echo "No APK produced" >&2; exit 1; fi
                      echo "path=${'$'}APK_PATH" >> "${'$'}GITHUB_OUTPUT"
                  - name: Publish as a Release
                    # Releases have a 2GB-per-file limit, versus 500MB total for
                    # GitHub Actions Artifacts on the free tier — and no
                    # separate Artifacts-download-API dance (zip-wrapped,
                    # short-lived signed URLs) for the app to deal with. gh is
                    # preinstalled on GitHub-hosted runners and authenticates
                    # via GITHUB_TOKEN automatically, so no extra secret and no
                    # third-party Action to trust for something this
                    # security-sensitive (publishing installable APKs).
                    env:
                      GITHUB_TOKEN: ${'$'}{{ secrets.GITHUB_TOKEN }}
                    run: |
                      TAG="build-${'$'}{{ github.run_number }}"
                      gh release create "${'$'}TAG" "${'$'}{{ steps.apk.outputs.path }}" \
                        --title "Build ${'$'}{{ github.run_number }} (${'$'}{{ steps.vars.outputs.type }})" \
                        --notes "Built from ${'$'}{{ github.sha }}"
        """.trimIndent()
    }

    /** Creates or updates the workflow file in the repo. Returns true if it made a change. */
    suspend fun ensureWorkflow(token: String, owner: String, repo: String, branch: String): Boolean = withContext(Dispatchers.IO) {
        val getReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/contents/$WORKFLOW_PATH?ref=$branch")
            .githubHeaders(token).get().build()
        val getResp = client.newCall(getReq).execute()

        val existingSha = if (getResp.isSuccessful) {
            runCatching {
                json.parseToJsonElement(getResp.body!!.string()).jsonObject["sha"]?.jsonPrimitive?.content
            }.getOrNull()
        } else null

        val contentB64 = Base64.getEncoder().encodeToString(workflowYaml().toByteArray())
        val body = buildJsonObject {
            put("message", if (existingSha != null) "Update Kryptonide build workflow" else "Add Kryptonide build workflow")
            put("content", contentB64)
            put("branch", branch)
            if (existingSha != null) put("sha", existingSha)
        }.toString().toRequestBody(JSON_TYPE)

        val putReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/contents/$WORKFLOW_PATH")
            .githubHeaders(token).put(body).build()
        val putResp = client.newCall(putReq).execute()
        if (!putResp.isSuccessful) {
            val msg = runCatching { json.parseToJsonElement(putResp.body!!.string()).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
            error(msg ?: "Failed to write workflow file (${putResp.code})")
        }
        existingSha == null
    }

    /** Triggers workflow_dispatch. Actions API needs a couple seconds to register the new run afterwards. */
    suspend fun dispatch(token: String, owner: String, repo: String, branch: String, buildType: String = "debug") = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("ref", branch)
            putJsonObject("inputs") { put("build_type", buildType) }
        }.toString().toRequestBody(JSON_TYPE)
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/actions/workflows/${WORKFLOW_PATH.substringAfterLast('/')}/dispatches")
            .githubHeaders(token).post(body).build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) error("Failed to trigger workflow (${resp.code}): ${resp.body?.string()}")
    }

    /** Finds the run started after [afterEpochMillis] — used right after dispatch() to find "our" run. */
    suspend fun findLatestRun(token: String, owner: String, repo: String, afterEpochMillis: Long): ActionsRun? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/actions/workflows/${WORKFLOW_PATH.substringAfterLast('/')}/runs?per_page=10")
            .githubHeaders(token).get().build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) return@withContext null
        val runs = json.parseToJsonElement(resp.body!!.string()).jsonObject["workflow_runs"]?.jsonArray ?: return@withContext null
        runs.map { it.jsonObject }
            .mapNotNull { obj ->
                runCatching {
                    ActionsRun(
                        id = obj["id"]!!.jsonPrimitive.long,
                        status = obj["status"]!!.jsonPrimitive.content,
                        conclusion = obj["conclusion"]?.jsonPrimitive?.contentOrNull,
                        html_url = obj["html_url"]?.jsonPrimitive?.content ?: "",
                        created_at = obj["created_at"]?.jsonPrimitive?.content ?: "",
                        run_number = obj["run_number"]?.jsonPrimitive?.int ?: 0,
                        name = obj["name"]?.jsonPrimitive?.content ?: "",
                        head_branch = obj["head_branch"]?.jsonPrimitive?.content ?: "",
                        head_sha = obj["head_sha"]?.jsonPrimitive?.content ?: "",
                        updated_at = obj["updated_at"]?.jsonPrimitive?.content ?: ""
                    )
                }.getOrNull()
            }
            .filter { run ->
                val runEpoch = runCatching {
                    java.time.Instant.parse(run.created_at).toEpochMilli()
                }.getOrDefault(0L)
                runEpoch >= (afterEpochMillis - 30_000L) || run.status != "completed"
            }
            .maxByOrNull { it.run_number }
    }

    suspend fun getRun(token: String, owner: String, repo: String, runId: Long): ActionsRun = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/actions/runs/$runId")
            .githubHeaders(token).get().build()
        val resp = client.newCall(req).execute()
        val obj = json.parseToJsonElement(resp.body!!.string()).jsonObject
        ActionsRun(
            id = obj["id"]!!.jsonPrimitive.long,
            status = obj["status"]!!.jsonPrimitive.content,
            conclusion = obj["conclusion"]?.jsonPrimitive?.contentOrNull,
            html_url = obj["html_url"]?.jsonPrimitive?.content ?: "",
            created_at = obj["created_at"]?.jsonPrimitive?.content ?: "",
            run_number = obj["run_number"]?.jsonPrimitive?.int ?: 0,
            name = obj["name"]?.jsonPrimitive?.content ?: "",
            head_branch = obj["head_branch"]?.jsonPrimitive?.content ?: "",
            head_sha = obj["head_sha"]?.jsonPrimitive?.content ?: "",
            updated_at = obj["updated_at"]?.jsonPrimitive?.content ?: ""
        )
    }

    suspend fun listRecentRuns(token: String, owner: String, repo: String, perPage: Int = 10): List<ActionsRun> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/actions/runs?per_page=$perPage")
            .githubHeaders(token).get().build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) return@withContext emptyList()
        val runs = json.parseToJsonElement(resp.body!!.string()).jsonObject["workflow_runs"]?.jsonArray ?: return@withContext emptyList()
        runs.mapNotNull { elem ->
            runCatching {
                val obj = elem.jsonObject
                ActionsRun(
                    id = obj["id"]!!.jsonPrimitive.long,
                    status = obj["status"]!!.jsonPrimitive.content,
                    conclusion = obj["conclusion"]?.jsonPrimitive?.contentOrNull,
                    html_url = obj["html_url"]?.jsonPrimitive?.content ?: "",
                    created_at = obj["created_at"]?.jsonPrimitive?.content ?: "",
                    run_number = obj["run_number"]?.jsonPrimitive?.int ?: 0,
                    name = obj["name"]?.jsonPrimitive?.content ?: "",
                    head_branch = obj["head_branch"]?.jsonPrimitive?.content ?: "",
                    head_sha = obj["head_sha"]?.jsonPrimitive?.content ?: "",
                    updated_at = obj["updated_at"]?.jsonPrimitive?.content ?: ""
                )
            }.getOrNull()
        }
    }

    suspend fun cancelRun(token: String, owner: String, repo: String, runId: Long): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/cancel")
            .githubHeaders(token).post("{}".toRequestBody(JSON_TYPE)).build()
        val resp = client.newCall(req).execute()
        resp.isSuccessful
    }

    suspend fun getJobLogs(token: String, owner: String, repo: String, jobId: Long): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/actions/jobs/$jobId/logs")
            .githubHeaders(token)
            .get().build()
        val redirectClient = client.newBuilder().followRedirects(true).build()
        val resp = redirectClient.newCall(req).execute()
        if (!resp.isSuccessful) return@withContext "Log output unavailable (HTTP ${resp.code})"
        resp.body?.string() ?: ""
    }

    suspend fun getJobs(token: String, owner: String, repo: String, runId: Long): List<ActionsJob> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/jobs")
            .githubHeaders(token).get().build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) return@withContext emptyList()
        val jobsArr = json.parseToJsonElement(resp.body!!.string()).jsonObject["jobs"]?.jsonArray ?: return@withContext emptyList()
        jobsArr.map { it.jsonObject }.map { obj ->
            val steps = obj["steps"]?.jsonArray?.mapIndexed { idx, s ->
                val so = s.jsonObject
                ActionsJobStep(
                    name = so["name"]?.jsonPrimitive?.content ?: "",
                    status = so["status"]?.jsonPrimitive?.content ?: "",
                    conclusion = so["conclusion"]?.jsonPrimitive?.contentOrNull,
                    number = so["number"]?.jsonPrimitive?.int ?: (idx + 1)
                )
            } ?: emptyList()
            ActionsJob(
                id = obj["id"]?.jsonPrimitive?.long ?: 0L,
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                status = obj["status"]?.jsonPrimitive?.content ?: "",
                conclusion = obj["conclusion"]?.jsonPrimitive?.contentOrNull,
                steps = steps
            )
        }
    }

    data class ReleaseAsset(val id: Long, val name: String, val sizeBytes: Long)
    data class ReleaseInfo(val id: Long, val tagName: String, val assets: List<ReleaseAsset>)

    /** Looks up the release for a given tag (e.g. via [releaseTagFor]). Null if not published yet — caller polls. */
    suspend fun findRelease(token: String, owner: String, repo: String, tag: String): ReleaseInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/releases/tags/$tag")
            .githubHeaders(token).get().build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) return@withContext null // 404 until the release step runs — expected while polling
        val obj = json.parseToJsonElement(resp.body!!.string()).jsonObject
        val assets = obj["assets"]?.jsonArray?.map { it.jsonObject }?.mapNotNull { a ->
            runCatching {
                ReleaseAsset(
                    id = a["id"]!!.jsonPrimitive.long,
                    name = a["name"]?.jsonPrimitive?.content ?: "asset",
                    sizeBytes = a["size"]?.jsonPrimitive?.long ?: 0L
                )
            }.getOrNull()
        } ?: emptyList()
        ReleaseInfo(id = obj["id"]!!.jsonPrimitive.long, tagName = obj["tag_name"]?.jsonPrimitive?.content ?: tag, assets = assets)
    }

    /**
     * Downloads a release asset (the APK) to [destFile]. Uses the API asset
     * endpoint with an octet-stream Accept header rather than the plain
     * `browser_download_url` — the API endpoint works uniformly whether the
     * repo is public or private (browser_download_url needs different
     * no-auth/auth handling depending on repo visibility; this way it's one
     * code path either way).
     */
    suspend fun downloadReleaseAsset(token: String, owner: String, repo: String, assetId: Long, destFile: File) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/releases/assets/$assetId")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/octet-stream")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get().build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) error("Asset download failed (${resp.code})")
        destFile.parentFile?.mkdirs()
        resp.body!!.byteStream().use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /**
     * Deletes a previous build's release + its underlying git tag. Deleting
     * only the release leaves the tag behind forever (they're separate
     * objects in GitHub's model) — call this after a *new* build has already
     * succeeded, never before, so a failed new build can't leave the user
     * with nothing installable.
     */
    suspend fun deleteRelease(token: String, owner: String, repo: String, releaseId: Long, tag: String) = withContext(Dispatchers.IO) {
        val delReleaseReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/releases/$releaseId")
            .githubHeaders(token).delete().build()
        client.newCall(delReleaseReq).execute().close()

        val delTagReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/git/refs/tags/$tag")
            .githubHeaders(token).delete().build()
        client.newCall(delTagReq).execute().close()
    }
}
