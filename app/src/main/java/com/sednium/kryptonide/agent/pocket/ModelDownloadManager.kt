package com.sednium.kryptonide.agent.pocket

import android.content.Context
import com.sednium.kryptonide.utils.StorageUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

enum class DownloadStatus {
    IDLE,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class ModelDownloadProgress(
    val modelId: String,
    val status: DownloadStatus = DownloadStatus.IDLE,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedMbPerSec: Double = 0.0,
    val errorMessage: String? = null
)

object ModelDownloadManager {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _downloadStates = MutableStateFlow<Map<String, ModelDownloadProgress>>(emptyMap())
    val downloadStates: StateFlow<Map<String, ModelDownloadProgress>> = _downloadStates.asStateFlow()

    fun getModelsDirectory(context: Context): File {
        val ext = File(StorageUtils.getKryptonideRoot(), "models")
        if (ext.exists() || ext.mkdirs() || ext.canWrite()) {
            return ext
        }
        return File(context.filesDir, "models").apply { mkdirs() }
    }

    fun getTargetModelFile(context: Context, spec: LocalModelSpec): File {
        val dir = getModelsDirectory(context)
        val ext = if (spec.format == ModelFormat.CACT || spec.downloadUrl?.endsWith(".cact") == true) {
            ".cact"
        } else if (spec.format == ModelFormat.GGUF) {
            ".gguf"
        } else {
            val cleanUrl = spec.downloadUrl?.substringBefore('?')
            cleanUrl?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() && it.length <= 5 }?.let { ".$it" }
                ?: spec.format.extension
        }
        val cleanName = "${spec.id}$ext"
        return File(dir, cleanName)
    }

    fun isModelInstalled(context: Context, spec: LocalModelSpec): Boolean {
        val target = getTargetModelFile(context, spec)
        if (target.exists() && target.length() > 0 && !target.name.endsWith(".downloading") && !target.name.endsWith(".tmp")) return true

        // Also check if any file in scan directories matches
        val dirs = listOf(
            getModelsDirectory(context),
            File(context.filesDir, "models"),
            File("/sdcard/Kryptonide/models"),
            File("/sdcard/KryptonIDE/models"),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        )
        for (dir in dirs) {
            val candidates = dir?.listFiles() ?: continue
            val found = candidates.any { f ->
                val name = f.name.lowercase()
                if (name.endsWith(".downloading") || name.endsWith(".tmp") || name.startsWith(".")) return@any false
                f.length() > 0 && (
                    name.contains(spec.id.lowercase()) ||
                    (spec.id.contains("needle") && name.contains("needle")) ||
                    (spec.id.contains("lfm") && name.contains("lfm")) ||
                    (spec.id.contains("minicpm") && name.contains("minicpm")) ||
                    (spec.id.contains("laya") && (name.contains("laya") || name.contains("0.5b")))
                )
            }
            if (found) return true
        }
        return false
    }

    fun startDownload(
        context: Context,
        spec: LocalModelSpec,
        onComplete: (() -> Unit)? = null
    ) {
        val urlString = spec.downloadUrl ?: return
        if (activeJobs.containsKey(spec.id)) return

        val targetFile = getTargetModelFile(context, spec)
        val parentDir = targetFile.parentFile ?: getModelsDirectory(context)
        parentDir.mkdirs()

        val usableSpace = parentDir.usableSpace
        if (usableSpace in 1 until spec.sizeBytesApprox) {
            val reqMb = spec.sizeBytesApprox / (1024 * 1024)
            val freeMb = usableSpace / (1024 * 1024)
            updateState(
                spec.id,
                ModelDownloadProgress(
                    modelId = spec.id,
                    status = DownloadStatus.FAILED,
                    errorMessage = "Insufficient storage: ~${reqMb}MB required, only ${freeMb}MB free."
                )
            )
            return
        }

        val tempFile = File(parentDir, "${targetFile.name}.downloading")
        tempFile.parentFile?.mkdirs()

        val existingBytes = if (tempFile.exists()) tempFile.length() else 0L
        val initialProgress = if (spec.sizeBytesApprox > 0) (existingBytes.toFloat() / spec.sizeBytesApprox.toFloat()).coerceIn(0f, 1f) else 0f
        updateState(spec.id, ModelDownloadProgress(spec.id, DownloadStatus.DOWNLOADING, initialProgress, existingBytes, spec.sizeBytesApprox))

        val job = scope.launch {
            var connection: HttpURLConnection? = null
            var input: InputStream? = null
            var output: FileOutputStream? = null
            try {
                var currentUrl = urlString
                var redirectCount = 0
                val maxRedirects = 8

                while (true) {
                    val url = URL(currentUrl)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20000
                        readTimeout = 45000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Kryptonide/1.0")
                        setRequestProperty("Accept", "*/*")
                        if (existingBytes > 0) {
                            setRequestProperty("Range", "bytes=$existingBytes-")
                        }
                    }
                    conn.connect()
                    connection = conn

                    val code = conn.responseCode
                    if (code in listOf(301, 302, 303, 307, 308) && redirectCount < maxRedirects) {
                        val location = conn.getHeaderField("Location")
                        if (!location.isNullOrBlank()) {
                            currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                                location
                            } else {
                                URL(url, location).toString()
                            }
                            redirectCount++
                            conn.disconnect()
                            continue
                        }
                    }
                    break
                }

                val activeConn = connection ?: throw IllegalStateException("Failed to open connection")
                val isPartial = activeConn.responseCode == 206
                if (activeConn.responseCode !in 200..299) {
                    throw IllegalStateException("Server returned HTTP ${activeConn.responseCode}")
                }

                val totalBytes = if (isPartial) {
                    val contentRange = activeConn.getHeaderField("Content-Range")
                    val totalFromRange = contentRange?.substringAfterLast('/')?.toLongOrNull()
                    totalFromRange ?: (existingBytes + activeConn.contentLengthLong.coerceAtLeast(0L))
                } else {
                    activeConn.contentLengthLong.let { if (it > 0) it else spec.sizeBytesApprox }
                }

                input = activeConn.inputStream
                output = FileOutputStream(tempFile, isPartial)

                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                var totalRead = if (isPartial) existingBytes else 0L
                var lastUpdate = System.currentTimeMillis()
                var lastSpeedCalcTime = System.currentTimeMillis()
                var bytesAtLastSpeedCalc = totalRead
                var currentSpeedMbPerSec = 0.0

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (!isActive) {
                        return@launch
                    }
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    val now = System.currentTimeMillis()
                    val windowElapsed = now - lastSpeedCalcTime
                    if (windowElapsed >= 500) {
                        val windowBytes = totalRead - bytesAtLastSpeedCalc
                        val sec = windowElapsed / 1000.0
                        if (sec > 0) {
                            currentSpeedMbPerSec = (windowBytes / (1024.0 * 1024.0)) / sec
                        }
                        lastSpeedCalcTime = now
                        bytesAtLastSpeedCalc = totalRead
                    }

                    if (now - lastUpdate > 250 || totalRead == totalBytes) {
                        lastUpdate = now
                        val fraction = if (totalBytes > 0) (totalRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
                        updateState(spec.id, ModelDownloadProgress(
                            modelId = spec.id,
                            status = DownloadStatus.DOWNLOADING,
                            progress = fraction,
                            downloadedBytes = totalRead,
                            totalBytes = totalBytes,
                            speedMbPerSec = currentSpeedMbPerSec
                        ))
                    }
                }

                output.flush()
                output.close()
                output = null

                if (tempFile.exists()) {
                    if (targetFile.exists()) targetFile.delete()
                    val renamed = tempFile.renameTo(targetFile)
                    if (!renamed) {
                        tempFile.copyTo(targetFile, overwrite = true)
                        tempFile.delete()
                    }
                }

                updateState(spec.id, ModelDownloadProgress(
                    modelId = spec.id,
                    status = DownloadStatus.COMPLETED,
                    progress = 1.0f,
                    downloadedBytes = totalRead,
                    totalBytes = totalRead,
                    speedMbPerSec = 0.0
                ))

                withContext(Dispatchers.Main) {
                    onComplete?.invoke()
                }

            } catch (e: CancellationException) {
                val current = _downloadStates.value[spec.id]
                if (current?.status != DownloadStatus.PAUSED) {
                    tempFile.delete()
                    updateState(spec.id, ModelDownloadProgress(spec.id, DownloadStatus.CANCELLED))
                }
            } catch (e: Exception) {
                updateState(spec.id, ModelDownloadProgress(
                    modelId = spec.id,
                    status = DownloadStatus.FAILED,
                    errorMessage = e.message ?: "Download failed"
                ))
            } finally {
                runCatching { input?.close() }
                runCatching { output?.close() }
                runCatching { connection?.disconnect() }
                activeJobs.remove(spec.id)
            }
        }

        activeJobs[spec.id] = job
    }

    fun pauseDownload(modelId: String) {
        val current = _downloadStates.value[modelId]
        if (current != null) {
            updateState(modelId, current.copy(status = DownloadStatus.PAUSED, speedMbPerSec = 0.0))
        }
        val job = activeJobs.remove(modelId)
        job?.cancel()
    }

    fun resumeDownload(
        context: Context,
        spec: LocalModelSpec,
        onComplete: (() -> Unit)? = null
    ) {
        startDownload(context, spec, onComplete)
    }

    fun stopDownload(
        modelId: String,
        context: Context? = null,
        spec: LocalModelSpec? = null
    ) {
        val job = activeJobs.remove(modelId)
        job?.cancel()
        if (context != null && spec != null) {
            val targetFile = getTargetModelFile(context, spec)
            val tempFile = File(targetFile.parentFile, "${targetFile.name}.downloading")
            if (tempFile.exists()) tempFile.delete()
        }
        updateState(modelId, ModelDownloadProgress(modelId, DownloadStatus.CANCELLED))
    }

    fun cancelDownload(modelId: String) {
        stopDownload(modelId)
    }

    private fun updateState(modelId: String, state: ModelDownloadProgress) {
        val current = _downloadStates.value.toMutableMap()
        current[modelId] = state
        _downloadStates.value = current
    }
}
