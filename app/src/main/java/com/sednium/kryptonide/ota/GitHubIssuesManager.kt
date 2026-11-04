package com.sednium.kryptonide.ota

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import com.sednium.kryptonide.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class IssueItem(
    val number: Int,
    val title: String,
    val body: String,
    val state: String, // "open" | "closed"
    val author: String,
    val labels: List<String>,
    val htmlUrl: String,
    val comments: Int,
    val createdAt: String
)

object GitHubIssuesManager {

    const val REPO_OWNER = "Sednium-Technologies"
    const val REPO_NAME = "kryptonide-native"
    const val ISSUES_WEB_URL = "https://github.com/$REPO_OWNER/$REPO_NAME/issues"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetchIssues(token: String? = null): List<IssueItem> = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/issues?state=all&per_page=30"
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "KryptonIDE-Android/4.0")

        if (!token.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $token")
        }

        try {
            val response = httpClient.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext emptyList()
            }

            val body = response.body?.string().orEmpty()
            response.close()

            if (body.isBlank()) return@withContext emptyList()

            val array = json.parseToJsonElement(body).jsonArray
            array.mapNotNull { element ->
                val obj = element.jsonObject
                // Exclude pull requests (GitHub Issues API returns PRs as issues with a "pull_request" key)
                if (obj.containsKey("pull_request")) return@mapNotNull null

                val number = obj["number"]?.jsonPrimitive?.intOrNull ?: 0
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "Issue #$number"
                val issueBody = obj["body"]?.jsonPrimitive?.contentOrNull ?: ""
                val state = obj["state"]?.jsonPrimitive?.contentOrNull ?: "open"
                val author = obj["user"]?.jsonObject?.get("login")?.jsonPrimitive?.contentOrNull ?: "anonymous"
                val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull ?: "$ISSUES_WEB_URL/$number"
                val comments = obj["comments"]?.jsonPrimitive?.intOrNull ?: 0
                val createdAt = obj["created_at"]?.jsonPrimitive?.contentOrNull ?: ""

                val labels = obj["labels"]?.jsonArray?.mapNotNull {
                    it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                } ?: emptyList()

                IssueItem(
                    number = number,
                    title = title,
                    body = issueBody,
                    state = state,
                    author = author,
                    labels = labels,
                    htmlUrl = htmlUrl,
                    comments = comments,
                    createdAt = createdAt
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun createIssue(
        title: String,
        body: String,
        labels: List<String>,
        token: String
    ): Result<IssueItem> = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/issues"
        val requestJson = buildJsonObject {
            put("title", title)
            put("body", body)
            put("labels", buildJsonArray { labels.forEach { add(it) } })
        }

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "KryptonIDE-Android/4.0")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            val respBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                return@withContext Result.failure(Exception("GitHub API Error ($code): $respBody"))
            }
            response.close()

            val obj = json.parseToJsonElement(respBody).jsonObject
            val number = obj["number"]?.jsonPrimitive?.intOrNull ?: 0
            val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull ?: "$ISSUES_WEB_URL/$number"
            val state = obj["state"]?.jsonPrimitive?.contentOrNull ?: "open"
            val author = obj["user"]?.jsonObject?.get("login")?.jsonPrimitive?.contentOrNull ?: "user"

            Result.success(
                IssueItem(
                    number = number,
                    title = title,
                    body = body,
                    state = state,
                    author = author,
                    labels = labels,
                    htmlUrl = htmlUrl,
                    comments = 0,
                    createdAt = "Just now"
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun buildBrowserNewIssueUrl(title: String, body: String, labels: List<String>): String {
        val enc = { s: String -> URLEncoder.encode(s, StandardCharsets.UTF_8.toString()) }
        val titleParam = enc(title)
        val bodyParam = enc(body)
        val labelsParam = enc(labels.joinToString(","))
        return "https://github.com/$REPO_OWNER/$REPO_NAME/issues/new?title=$titleParam&body=$bodyParam&labels=$labelsParam"
    }

    fun getDeviceDiagnostics(context: Context): String {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)
        val freeRamMb = memInfo.availMem / (1024 * 1024)
        val totalRamMb = memInfo.totalMem / (1024 * 1024)

        val version = try { BuildConfig.VERSION_NAME } catch (e: Throwable) { "4.0" }
        val code = try { BuildConfig.VERSION_CODE } catch (e: Throwable) { 40 }

        return """
            
            ---
            ### 📱 Environment & Diagnostics
            - **App Version**: Kryptonide v$version (build $code)
            - **OS**: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            - **Device**: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})
            - **Memory**: ${freeRamMb}MB free / ${totalRamMb}MB total
            - **ABI**: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"}
        """.trimIndent()
    }
}
