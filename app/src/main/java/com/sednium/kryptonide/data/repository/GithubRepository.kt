package com.sednium.kryptonide.data.repository

import com.sednium.kryptonide.data.model.FileNode
import com.sednium.kryptonide.data.model.FileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64

@Serializable
data class GitHubUser(
    val login: String,
    val name: String? = null,
    val avatar_url: String = "",
    val bio: String? = null,
    val public_repos: Int = 0,
    val html_url: String = "",
)

@Serializable
data class GitHubRepo(
    val name: String,
    val full_name: String,
    val description: String? = null,
    val private: Boolean = false,
    val html_url: String = "",
    val default_branch: String = "main",
    val updated_at: String = "",
    val language: String? = null,
    val stargazers_count: Int = 0,
    val fork: Boolean = false,
)

class GithubRepository(private val client: OkHttpClient = OkHttpClient()) {

    private val json = Json { ignoreUnknownKeys = true }
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private fun headers(token: String) = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
        "Content-Type" to "application/json",
    )

    private fun Request.Builder.githubHeaders(token: String) = apply {
        headers(token).forEach { (k, v) -> header(k, v) }
    }

    // ── Auth ─────────────────────────────────────────────────

    suspend fun getUser(token: String): GitHubUser = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.github.com/user").githubHeaders(token).get().build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) error("GitHub auth failed: ${resp.code}")
        json.decodeFromString(resp.body!!.string())
    }

    // ── Repos ────────────────────────────────────────────────

    suspend fun getRepoInfo(owner: String, repo: String, token: String? = null): Result<GitHubRepo> = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Kryptonide-Android")
        if (!token.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $token")
        }
        val req = reqBuilder.get().build()
        try {
            val resp = client.newCall(req).execute()
            resp.use {
                when (it.code) {
                    200 -> {
                        val body = it.body?.string() ?: return@withContext Result.failure(IOException("Empty response body from GitHub"))
                        Result.success(json.decodeFromString<GitHubRepo>(body))
                    }
                    404 -> Result.failure(IOException("Repository '$owner/$repo' does not exist on GitHub (HTTP 404)."))
                    401 -> Result.failure(IOException("Unauthorized (HTTP 401). Please check your GitHub token."))
                    403 -> Result.failure(IOException("Forbidden (HTTP 403) or rate limit exceeded. Please check your GitHub token."))
                    else -> Result.failure(IOException("GitHub API returned HTTP ${it.code}"))
                }
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    suspend fun listRepos(token: String): List<GitHubRepo> = withContext(Dispatchers.IO) {
        val repos = mutableListOf<GitHubRepo>()
        var page = 1
        while (true) {
            val req = Request.Builder()
                .url("https://api.github.com/user/repos?sort=updated&per_page=50&page=$page")
                .githubHeaders(token).get().build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) break
            val batch = json.decodeFromString<List<GitHubRepo>>(resp.body!!.string())
            repos += batch
            if (batch.size < 50 || page >= 5) break
            page++
        }
        repos
    }

    suspend fun createRepo(token: String, name: String, description: String = "", private: Boolean = false): GitHubRepo = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("name", name)
            put("description", description)
            put("private", private)
            put("auto_init", true)
        }.toString().toRequestBody(JSON_TYPE)

        val req = Request.Builder().url("https://api.github.com/user/repos").githubHeaders(token).post(body).build()
        val resp = client.newCall(req).execute()
        val bodyStr = resp.body!!.string()
        if (!resp.isSuccessful) {
            val errMsg = runCatching { json.parseToJsonElement(bodyStr).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
            error(errMsg ?: "Failed to create repo (${resp.code})")
        }
        json.decodeFromString(bodyStr)
    }

    // ── Smart-diff push ──────────────────────────────────────

    suspend fun pushFiles(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        files: List<Pair<String, String>>, // relativePath to content
        commitMessage: String = "Update from Krypton IDE",
    ) = withContext(Dispatchers.IO) {
        // 1. Get current commit sha
        val refReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/git/refs/heads/$branch")
            .githubHeaders(token).get().build()
        val commitSha = client.newCall(refReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: ""
                throw IOException("GitHub API error fetching branch '$branch' (HTTP ${resp.code}): $err")
            }
            val refJson = json.parseToJsonElement(resp.body!!.string()).jsonObject
            refJson["object"]?.jsonObject?.get("sha")?.jsonPrimitive?.content
                ?: throw IOException("GitHub API returned invalid ref object for branch '$branch'")
        }

        // 2. Get base tree sha
        val commitReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/git/commits/$commitSha")
            .githubHeaders(token).get().build()
        val treeSha = client.newCall(commitReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: ""
                throw IOException("GitHub API error fetching commit '$commitSha' (HTTP ${resp.code}): $err")
            }
            val commitJson = json.parseToJsonElement(resp.body!!.string()).jsonObject
            commitJson["tree"]?.jsonObject?.get("sha")?.jsonPrimitive?.content
                ?: throw IOException("GitHub API returned invalid tree for commit '$commitSha'")
        }

        // 3. Build tree items with relative file paths
        val treeItems = buildJsonArray {
            for ((relPath, content) in files) {
                if (relPath.isNotBlank()) {
                    addJsonObject {
                        put("path", relPath)
                        put("mode", "100644")
                        put("type", "blob")
                        put("content", content)
                    }
                }
            }
        }

        // 4. Create new tree
        val newTreeBody = buildJsonObject {
            put("base_tree", treeSha)
            put("tree", treeItems)
        }.toString().toRequestBody(JSON_TYPE)

        val treeReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/git/trees")
            .githubHeaders(token).post(newTreeBody).build()
        val newTreeSha = client.newCall(treeReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: ""
                throw IOException("GitHub API error creating tree (HTTP ${resp.code}): $err")
            }
            json.parseToJsonElement(resp.body!!.string())
                .jsonObject["sha"]?.jsonPrimitive?.content
                ?: throw IOException("GitHub API returned invalid tree SHA")
        }

        // 5. Create commit
        val newCommitBody = buildJsonObject {
            put("message", commitMessage)
            put("tree", newTreeSha)
            putJsonArray("parents") { add(commitSha) }
        }.toString().toRequestBody(JSON_TYPE)

        val newCommitReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/git/commits")
            .githubHeaders(token).post(newCommitBody).build()
        val newCommitSha = client.newCall(newCommitReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: ""
                throw IOException("GitHub API error creating commit (HTTP ${resp.code}): $err")
            }
            json.parseToJsonElement(resp.body!!.string())
                .jsonObject["sha"]?.jsonPrimitive?.content
                ?: throw IOException("GitHub API returned invalid commit SHA")
        }

        // 6. Update ref
        val updateRefBody = buildJsonObject {
            put("sha", newCommitSha)
            put("force", false)
        }.toString().toRequestBody(JSON_TYPE)

        val updateRefReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/git/refs/heads/$branch")
            .githubHeaders(token).patch(updateRefBody).build()
        client.newCall(updateRefReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: ""
                throw IOException("GitHub API error updating branch '$branch' (HTTP ${resp.code}): $err")
            }
        }
    }

    suspend fun listBranches(token: String, owner: String, repo: String): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/branches?per_page=100")
            .githubHeaders(token).get().build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) return@withContext listOf("main")
        val arr = json.parseToJsonElement(resp.body!!.string()).jsonArray
        val branches = arr.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
        if (branches.isEmpty()) listOf("main") else branches
    }

    suspend fun createBranch(token: String, owner: String, repo: String, branch: String, baseBranch: String = "main"): Boolean = withContext(Dispatchers.IO) {
        val refReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/git/refs/heads/$baseBranch")
            .githubHeaders(token).get().build()
        val baseSha = client.newCall(refReq).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext false
            val refJson = json.parseToJsonElement(resp.body!!.string()).jsonObject
            refJson["object"]?.jsonObject?.get("sha")?.jsonPrimitive?.content ?: return@withContext false
        }

        val body = buildJsonObject {
            put("ref", "refs/heads/$branch")
            put("sha", baseSha)
        }.toString().toRequestBody(JSON_TYPE)

        val createReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/git/refs")
            .githubHeaders(token).post(body).build()
        val resp = client.newCall(createReq).execute()
        resp.isSuccessful
    }
}
