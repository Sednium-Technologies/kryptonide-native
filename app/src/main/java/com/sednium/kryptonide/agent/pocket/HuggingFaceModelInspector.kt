package com.sednium.kryptonide.agent.pocket

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class OomRiskLevel(val label: String) {
    LOW("LOW OOM RISK (Safe for your device)"),
    MODERATE("MODERATE OOM RISK (Close background apps)"),
    HIGH("HIGH OOM RISK (Exceeds available RAM — likely to crash)")
}

data class HuggingFaceModelInfo(
    val filename: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val quantization: String,
    val estimatedRamBytes: Long,
    val oomRisk: OomRiskLevel
)

object HuggingFaceModelInspector {
    private const val TAG = "HFModelInspector"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun normalizeUrl(raw: String): String {
        var clean = raw.trim()
        if (clean.contains("huggingface.co") && clean.contains("/blob/")) {
            clean = clean.replace("/blob/", "/resolve/")
        }
        return clean
    }

    fun extractQuantization(filename: String): String {
        val upper = filename.uppercase()
        val regex = Regex("""(Q[0-9]_[A-Z0-9_]+|BF16|F16|F32|IQ[0-9]_[A-Z0-9_]+)""")
        return regex.find(upper)?.value ?: "GGUF"
    }

    fun calculateRequiredRamBytes(fileSizeBytes: Long): Long {
        val sizeMb = fileSizeBytes / (1024 * 1024)
        val ramMb = (sizeMb * 1.25 + 300).toLong()
        return ramMb * 1024 * 1024
    }

    fun evaluateOomRisk(requiredRamBytes: Long, freeRamBytes: Long): OomRiskLevel {
        return when {
            requiredRamBytes <= (freeRamBytes * 0.70).toLong() -> OomRiskLevel.LOW
            requiredRamBytes <= (freeRamBytes * 0.95).toLong() -> OomRiskLevel.MODERATE
            else -> OomRiskLevel.HIGH
        }
    }

    fun extractRepoId(urlOrId: String): String? {
        val trimmed = urlOrId.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("huggingface.co/")
        val parts = trimmed.split('/')
        if (parts.size >= 2) {
            return "${parts[0]}/${parts[1]}"
        }
        return null
    }

    private fun fetchFileSize(url: String): Long {
        return runCatching {
            val req = Request.Builder().url(url).head().build()
            client.newCall(req).execute().use { resp ->
                val len = resp.header("Content-Length")?.toLongOrNull()
                len ?: 0L
            }
        }.getOrDefault(0L)
    }

    fun getFreeRamBytes(context: Context): Long {
        return runCatching {
            val mi = ActivityManager.MemoryInfo()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getMemoryInfo(mi)
            mi.availMem
        }.getOrDefault(2048L * 1024 * 1024)
    }

    fun getTotalRamBytes(context: Context): Long {
        return runCatching {
            val mi = ActivityManager.MemoryInfo()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getMemoryInfo(mi)
            mi.totalMem
        }.getOrDefault(4096L * 1024 * 1024)
    }

    suspend fun inspectUrl(context: Context, inputUrl: String): Result<List<HuggingFaceModelInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val input = inputUrl.trim()
            val freeRamBytes = getFreeRamBytes(context)

            // 1. Direct .gguf download link
            if (input.endsWith(".gguf", ignoreCase = true) || input.contains(".gguf?", ignoreCase = true)) {
                val directUrl = normalizeUrl(input)
                val filename = directUrl.substringBefore('?').substringAfterLast('/')
                val size = fetchFileSize(directUrl)
                val quant = extractQuantization(filename)
                val reqRam = calculateRequiredRamBytes(size)
                val risk = evaluateOomRisk(reqRam, freeRamBytes)
                return@runCatching listOf(
                    HuggingFaceModelInfo(
                        filename = filename,
                        downloadUrl = directUrl,
                        sizeBytes = size,
                        quantization = quant,
                        estimatedRamBytes = reqRam,
                        oomRisk = risk
                    )
                )
            }

            // 2. Repo link or Repo ID (e.g. Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF or https://huggingface.co/Qwen/...)
            val repoId = extractRepoId(input) ?: throw IllegalArgumentException("Invalid HuggingFace repo or file URL: $input")
            val apiUrl = "https://huggingface.co/api/models/$repoId"
            val req = Request.Builder().url(apiUrl).build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HuggingFace API error HTTP ${resp.code}: ${resp.message}")
            }
            val body = resp.body?.string() ?: throw IllegalStateException("Empty response from HuggingFace")
            val json = JSONObject(body)
            val siblings = json.optJSONArray("siblings") ?: throw IllegalStateException("No files found in repo $repoId")

            val ggufList = mutableListOf<HuggingFaceModelInfo>()
            for (i in 0 until siblings.length()) {
                val item = siblings.getJSONObject(i)
                val rfilename = item.optString("rfilename")
                if (rfilename.endsWith(".gguf", ignoreCase = true)) {
                    val downloadUrl = "https://huggingface.co/$repoId/resolve/main/$rfilename"
                    val quant = extractQuantization(rfilename)
                    val size = runCatching { fetchFileSize(downloadUrl) }.getOrDefault(1024L * 1024 * 1024)
                    val reqRam = calculateRequiredRamBytes(size)
                    val risk = evaluateOomRisk(reqRam, freeRamBytes)
                    ggufList += HuggingFaceModelInfo(
                        filename = rfilename,
                        downloadUrl = downloadUrl,
                        sizeBytes = size,
                        quantization = quant,
                        estimatedRamBytes = reqRam,
                        oomRisk = risk
                    )
                }
            }
            if (ggufList.isEmpty()) {
                throw IllegalStateException("No .gguf files found in HuggingFace repository $repoId")
            }
            ggufList
        }
    }
}
