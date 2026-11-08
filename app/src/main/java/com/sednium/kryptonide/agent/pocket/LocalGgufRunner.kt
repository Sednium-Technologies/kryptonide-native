package com.sednium.kryptonide.agent.pocket

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class LocalGgufRunner(private val context: Context? = null) {
    private val TAG = "LocalGgufRunner"

    /**
     * Preloads the GGUF model weights into RAM ahead of time by starting llama-server.
     */
    suspend fun preloadModel(modelFile: File): Boolean = withContext(Dispatchers.IO) {
        if (!modelFile.exists() || modelFile.length() == 0L) return@withContext false
        val isArm64 = Build.SUPPORTED_ABIS.any { it.contains("arm64") }
        if (!isArm64) return@withContext false

        try {
            Log.i(TAG, "Preloading GGUF model into RAM via local server: ${modelFile.name}")
            LocalGgufServerManager.startServer(context, modelFile)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed preloading model into RAM: ${t.message}")
            false
        }
    }

    /**
     * Executes real on-device LLM inference using llama-server over localhost HTTP.
     * Returns null if device architecture does not support native library,
     * model file does not exist, or execution fails.
     */
    suspend fun runInference(
        modelFile: File,
        prompt: String,
        systemPrompt: String,
        maxTokens: Int = 384,
        temperature: Float = 0.6f
    ): String? = withContext(Dispatchers.IO) {
        if (!modelFile.exists() || modelFile.length() == 0L) {
            Log.w(TAG, "Model file not found: ${modelFile.absolutePath}")
            return@withContext null
        }

        val isArm64 = Build.SUPPORTED_ABIS.any { it.contains("arm64") }
        if (!isArm64) {
            Log.d(TAG, "Non-arm64 architecture (${Build.SUPPORTED_ABIS.joinToString()})")
            return@withContext null
        }

        try {
            val serverStarted = if (LocalGgufServerManager.isServerRunning()) {
                true
            } else {
                LocalGgufServerManager.startServer(context, modelFile)
            }

            if (!serverStarted) {
                val err = LocalGgufServerManager.getLastErrorLog()
                Log.w(TAG, "llama-server could not be started for inference. Output logs:\n$err")
                return@withContext null
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .build()

            val messagesJson = JSONArray().apply {
                if (systemPrompt.isNotBlank()) {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                }
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }

            val requestJson = JSONObject().apply {
                put("model", modelFile.name)
                put("messages", messagesJson)
                put("max_tokens", maxTokens.coerceIn(32, 512))
                put("temperature", temperature)
                put("stream", false)
            }

            val jsonMediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url("${LocalGgufServerManager.getBaseUrl()}/chat/completions")
                .header("Content-Type", "application/json")
                .post(requestJson.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Local inference HTTP request failed with code ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val responseObj = JSONObject(body)
            val choices = responseObj.optJSONArray("choices")
            val content = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content")

            if (content.isNullOrBlank()) return@withContext null

            // Guard against autoregressive repetition loops
            val guardedText = RepetitionGuard.detectAndTrimRepetition(content)

            // If thinking model hit token budget before closing </think>, gracefully close it so UI renders properly
            if (guardedText.contains("<think", ignoreCase = true) && !guardedText.contains("</think", ignoreCase = true)) {
                "$guardedText\n</think>\n"
            } else {
                guardedText
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Local GGUF execution failed: ${t.message}", t)
            null
        }
    }

    fun release() {
        LocalGgufServerManager.stopServer()
    }

    companion object {
        fun estimateModelParams(fileSizeBytes: Long): Float {
            val mb = fileSizeBytes / (1024 * 1024)
            return when {
                mb < 300 -> 0.12f // Needle 3
                mb in 500..850 -> 1.08f // MiniCPM5 1B
                mb in 851..1200 -> 1.5f // Qwen 1.5B
                mb in 1201..2500 -> 2.0f // Gemma 2B
                mb in 2501..5500 -> 7.0f // Qwen 7B
                else -> 1.08f
            }
        }

        fun formatFullPrompt(systemPrompt: String, userPrompt: String, toolsJson: String? = null): String {
            return MiniCpmToolParser.formatPromptWithTools(
                systemInstruction = systemPrompt,
                userQuery = userPrompt,
                toolsJson = toolsJson
            )
        }
    }
}
