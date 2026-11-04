package com.sednium.kryptonide.ota

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.sednium.kryptonide.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class OtaReleaseInfo(
    val tagName: String,
    val name: String,
    val body: String,
    val apkUrl: String,
    val apkSize: Long,
    val isNewer: Boolean,
    val publishedAt: String = ""
)

sealed class OtaCheckResult {
    data class UpdateAvailable(val release: OtaReleaseInfo) : OtaCheckResult()
    data class UpToDate(val currentVersion: String, val latestTag: String? = null) : OtaCheckResult()
    data class Error(val message: String, val isNetworkError: Boolean = false) : OtaCheckResult()
}

sealed class OtaDownloadState {
    object Idle : OtaDownloadState()
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val percent: Int,
        val speedMbPerSec: Double
    ) : OtaDownloadState()
    data class ReadyToInstall(val apkFile: File, val release: OtaReleaseInfo) : OtaDownloadState()
    data class Error(val message: String) : OtaDownloadState()
}

object OtaUpdateManager {

    const val REPO_OWNER = "Sednium-Technologies"
    const val REPO_NAME = "kryptonide-native"
    const val GITHUB_REPO_URL = "https://github.com/$REPO_OWNER/$REPO_NAME"
    const val DEFAULT_BASE_VERSION = "4.0"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Parses version string into [major, minor, patch] integers.
     * Strips leading "v", "V", "release-", and suffixes like "-release", "-beta", etc.
     */
    fun parseVersionParts(version: String): Triple<Int, Int, Int> {
        var v = version.trim()
        while (v.startsWith("v", ignoreCase = true) || v.startsWith("release-", ignoreCase = true)) {
            if (v.startsWith("release-", ignoreCase = true)) {
                v = v.substring(8)
            } else if (v.startsWith("v", ignoreCase = true)) {
                v = v.substring(1)
            }
        }
        if (v.contains('-')) v = v.substringBefore('-')
        if (v.contains('+')) v = v.substringBefore('+')

        val parts = v.split('.').mapNotNull { it.trim().toIntOrNull() }
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        return Triple(major, minor, patch)
    }

    /**
     * Returns true ONLY when remoteTag is strictly greater than current version.
     * Prevents self-update loops (e.g. 4.0 vs v4.0 or v4.0.0 returns false).
     */
    fun isNewerVersion(current: String, remoteTag: String): Boolean {
        val (cMajor, cMinor, cPatch) = parseVersionParts(current)
        val (rMajor, rMinor, rPatch) = parseVersionParts(remoteTag)

        if (rMajor > cMajor) return true
        if (rMajor < cMajor) return false

        if (rMinor > cMinor) return true
        if (rMinor < cMinor) return false

        return rPatch > cPatch
    }

    /**
     * Comprehensive update check that distinguishes between:
     * 1. UpdateAvailable(release) -> newer tag exists
     * 2. UpToDate(currentVersion, latestTag) -> already on latest or repo has no newer releases
     * 3. Error(message) -> real network timeout or failure
     */
    suspend fun checkUpdateStatus(currentVersion: String = getCurrentAppVersion()): OtaCheckResult = withContext(Dispatchers.IO) {
        var networkException: Exception? = null
        var foundReleaseObj: JsonObject? = null

        val latestUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
        val request = Request.Builder()
            .url(latestUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "KryptonIDE-Android/$currentVersion")
            .get()
            .build()

        try {
            val resp = httpClient.newCall(request).execute()
            if (resp.isSuccessful) {
                val bodyStr = resp.body?.string().orEmpty()
                if (bodyStr.isNotBlank()) {
                    foundReleaseObj = json.parseToJsonElement(bodyStr).jsonObject
                }
                resp.close()
            } else if (resp.code == 404) {
                resp.close()
                // Try fetching from /releases list (index 0)
                val listUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=1"
                val listReq = Request.Builder()
                    .url(listUrl)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "KryptonIDE-Android/$currentVersion")
                    .get()
                    .build()
                val listResp = httpClient.newCall(listReq).execute()
                if (listResp.isSuccessful) {
                    val listBody = listResp.body?.string().orEmpty()
                    val array = json.parseToJsonElement(listBody).jsonArray
                    foundReleaseObj = array.firstOrNull()?.jsonObject
                }
                listResp.close()
            } else if (resp.code == 403 || resp.code == 429) {
                resp.close()
                return@withContext OtaCheckResult.Error("GitHub API rate limit reached. Please try again later.")
            } else {
                resp.close()
            }
        } catch (e: Exception) {
            networkException = e
        }

        if (foundReleaseObj == null) {
            if (networkException != null) {
                return@withContext OtaCheckResult.Error(
                    "Unable to check for updates. Check your internet connection.",
                    isNetworkError = true
                )
            }
            // Repo has no releases yet or is up to date: current version is the latest!
            return@withContext OtaCheckResult.UpToDate(currentVersion, null)
        }

        val releaseObj = foundReleaseObj
        val tagName = releaseObj["tag_name"]?.jsonPrimitive?.contentOrNull
        if (tagName.isNullOrBlank()) {
            return@withContext OtaCheckResult.UpToDate(currentVersion, null)
        }

        val releaseName = releaseObj["name"]?.jsonPrimitive?.contentOrNull ?: tagName
        val releaseBody = releaseObj["body"]?.jsonPrimitive?.contentOrNull ?: ""
        val publishedAt = releaseObj["published_at"]?.jsonPrimitive?.contentOrNull ?: ""

        val assets = releaseObj["assets"]?.jsonArray.orEmpty()
        var apkUrl = ""
        var apkSize = 0L

        for (assetElement in assets) {
            val asset = assetElement.jsonObject
            val name = asset["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkUrl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                apkSize = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L
                break
            }
        }

        val isNewer = isNewerVersion(currentVersion, tagName)
        val releaseInfo = OtaReleaseInfo(
            tagName = tagName,
            name = releaseName,
            body = releaseBody,
            apkUrl = apkUrl,
            apkSize = apkSize,
            isNewer = isNewer,
            publishedAt = publishedAt
        )

        return@withContext if (isNewer && apkUrl.isNotBlank()) {
            OtaCheckResult.UpdateAvailable(releaseInfo)
        } else {
            OtaCheckResult.UpToDate(currentVersion, tagName)
        }
    }

    /**
     * Backward-compatible helper returning OtaReleaseInfo if newer, or null if up to date/failed.
     */
    suspend fun checkForUpdates(currentVersion: String = getCurrentAppVersion()): OtaReleaseInfo? {
        return when (val res = checkUpdateStatus(currentVersion)) {
            is OtaCheckResult.UpdateAvailable -> res.release
            is OtaCheckResult.UpToDate -> res.latestTag?.let {
                OtaReleaseInfo(
                    tagName = it,
                    name = "Kryptonide $it",
                    body = "",
                    apkUrl = "",
                    apkSize = 0L,
                    isNewer = false
                )
            }
            is OtaCheckResult.Error -> null
        }
    }

    /**
     * Downloads the APK with live speed and progress reporting.
     */
    fun downloadApk(
        context: Context,
        release: OtaReleaseInfo
    ): Flow<OtaDownloadState> = flow {
        if (release.apkUrl.isBlank()) {
            emit(OtaDownloadState.Error("No APK download URL available for this release"))
            return@flow
        }

        val otaDir = File(context.externalCacheDir ?: context.cacheDir, "ota")
        if (!otaDir.exists()) otaDir.mkdirs()

        val cleanTag = release.tagName.replace("/", "_")
        val apkFile = File(otaDir, "KryptonIDE-$cleanTag.apk")

        val request = Request.Builder()
            .url(release.apkUrl)
            .header("User-Agent", "KryptonIDE-Android/${getCurrentAppVersion()}")
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            emit(OtaDownloadState.Error("HTTP download error: ${response.code}"))
            return@flow
        }

        val body = response.body ?: run {
            emit(OtaDownloadState.Error("Empty response body"))
            return@flow
        }

        val totalBytes = if (body.contentLength() > 0) body.contentLength() else release.apkSize
        var downloadedBytes = 0L
        var lastTime = System.currentTimeMillis()
        var lastBytes = 0L

        body.byteStream().use { input ->
            FileOutputStream(apkFile).use { output ->
                val buffer = ByteArray(32 * 1024)
                var bytesRead: Int

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    val now = System.currentTimeMillis()
                    val timeDiff = now - lastTime
                    if (timeDiff >= 200 || downloadedBytes == totalBytes) {
                        val bytesDiff = downloadedBytes - lastBytes
                        val speedMbPerSec = if (timeDiff > 0) {
                            (bytesDiff.toDouble() / (1024.0 * 1024.0)) / (timeDiff.toDouble() / 1000.0)
                        } else 0.0

                        val percent = if (totalBytes > 0) {
                            ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                        } else 0

                        emit(
                            OtaDownloadState.Downloading(
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                                percent = percent,
                                speedMbPerSec = speedMbPerSec
                            )
                        )

                        lastTime = now
                        lastBytes = downloadedBytes
                    }
                }
                output.flush()
            }
        }

        emit(OtaDownloadState.ReadyToInstall(apkFile, release))
    }.flowOn(Dispatchers.IO)

    /**
     * Checks unknown sources permission and launches package installer via FileProvider.
     */
    fun installApk(context: Context, apkFile: File, release: OtaReleaseInfo): Boolean {
        // Save changelog in preferences so "Welcome Back" screen can show it after restart
        saveReleaseNotesForUpgrade(context, release)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return false
            }
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(installIntent)
        return true
    }

    private fun saveReleaseNotesForUpgrade(context: Context, release: OtaReleaseInfo) {
        val prefs = context.getSharedPreferences("krypton_ota", Context.MODE_PRIVATE)
        val normTag = release.tagName.trim().removePrefix("v").removePrefix("V")
        prefs.edit()
            .putString("ota_release_notes_$normTag", release.body)
            .putString("pending_upgrade_version", normTag)
            .apply()
    }

    /**
     * Checks if the app was just updated.
     * Returns the release changelog / notes if updated, or null if running current version normally.
     */
    fun checkPostUpgrade(context: Context, currentVersion: String = getCurrentAppVersion()): String? {
        val prefs = context.getSharedPreferences("krypton_ota", Context.MODE_PRIVATE)
        val lastSeen = prefs.getString("last_seen_app_version", null)

        if (lastSeen == null) {
            // First time tracking: set to currentVersion
            prefs.edit().putString("last_seen_app_version", currentVersion).apply()
            return null
        }

        if (lastSeen != currentVersion) {
            // App was upgraded!
            val cachedNotes = prefs.getString("ota_release_notes_$currentVersion", null)
            return if (!cachedNotes.isNullOrBlank()) {
                cachedNotes
            } else {
                "Welcome to Kryptonide v$currentVersion!\n\nThis release includes high-performance optimizations, on-device AI streaming improvements, surgical SEARCH/REPLACE edits, and full bug fixes."
            }
        }

        return null
    }

    fun dismissPostUpgrade(context: Context, currentVersion: String = getCurrentAppVersion()) {
        val prefs = context.getSharedPreferences("krypton_ota", Context.MODE_PRIVATE)
        prefs.edit().putString("last_seen_app_version", currentVersion).apply()
    }

    fun getCurrentAppVersion(): String {
        return try {
            BuildConfig.VERSION_NAME
        } catch (e: Throwable) {
            DEFAULT_BASE_VERSION
        }
    }
}
