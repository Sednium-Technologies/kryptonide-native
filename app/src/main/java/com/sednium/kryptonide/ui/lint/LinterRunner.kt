package com.sednium.kryptonide.ui.lint

import android.content.Context
import android.util.Log
import com.sednium.kryptonide.ui.build.ToolchainDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object LinterRunner {

    private const val TAG = "LinterRunner"

    suspend fun isInstalled(context: Context, config: LinterConfig): Boolean = withContext(Dispatchers.IO) {
        val tc = ToolchainDetector.detect(context)
        if (!tc.isUsable) return@withContext false
        val proc = ToolchainDetector.spawnBash(tc, listOf("-c", config.checkInstalledCommand), null) ?: return@withContext false
        val finished = proc.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return@withContext false
        }
        return@withContext proc.exitValue() == 0
    }

    suspend fun install(context: Context, config: LinterConfig, log: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val tc = ToolchainDetector.detect(context)
        if (!tc.isUsable) {
            log("✗ ${config.displayName}: no toolchain available — configure sandbox first")
            return@withContext false
        }
        if (isInstalled(context, config)) {
            log("✓ ${config.displayName} already installed")
            return@withContext true
        }
        log("Installing ${config.displayName}…")
        val proc = ToolchainDetector.spawnBash(tc, listOf("-c", config.installCommand), null) ?: run {
            log("✗ ${config.displayName}: could not launch install shell")
            return@withContext false
        }
        val errJob = launch(Dispatchers.IO) {
            BufferedReader(InputStreamReader(proc.errorStream)).forEachLine { if (it.isNotBlank()) log("  $it") }
        }
        BufferedReader(InputStreamReader(proc.inputStream)).forEachLine { if (it.isNotBlank()) log("  $it") }
        val finished = proc.waitFor(180, TimeUnit.SECONDS)
        errJob.cancel()
        if (!finished) {
            proc.destroyForcibly()
            log("✗ ${config.displayName} timed out after 3 minutes")
            return@withContext false
        }
        val exit = proc.exitValue()
        return@withContext if (exit == 0) {
            log("✓ ${config.displayName} installed successfully")
            true
        } else {
            log("✗ ${config.displayName} install failed (exit $exit)")
            false
        }
    }

    suspend fun lint(context: Context, config: LinterConfig, filePath: String): JSONArray = withContext(Dispatchers.IO) {
        val markers = JSONArray()
        val tc = ToolchainDetector.detect(context)
        if (!tc.isUsable) return@withContext markers

        val command = config.lintCommand.replace("{file}", filePath)
        val proc = ToolchainDetector.spawnBash(tc, listOf("-c", command), null) ?: return@withContext markers

        val outDeferred = async(Dispatchers.IO) { proc.inputStream.bufferedReader().use { it.readText() } }
        val errDeferred = async(Dispatchers.IO) { proc.errorStream.bufferedReader().use { it.readText() } }

        val finished = proc.waitFor(15, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return@withContext markers
        }

        val output = outDeferred.await()
        val errOutput = errDeferred.await()

        val fullOutput = if (output.isNotBlank()) output else errOutput
        if (fullOutput.isBlank()) return@withContext markers

        runCatching {
            when (config.id) {
                "eslint" -> parseEslint(fullOutput, markers)
                "ruff" -> parseRuff(fullOutput, markers)
                "shellcheck" -> parseShellcheck(fullOutput, markers)
                "detekt" -> parseDetekt(fullOutput, markers)
                "clang-tidy" -> parseClangTidy(fullOutput, markers)
                else -> parseGeneric(fullOutput, markers)
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to parse linter output for ${config.id}: ${e.message}", e)
        }

        markers
    }

    private fun parseEslint(raw: String, markers: JSONArray) {
        val root = JSONArray(raw.trim())
        for (f in 0 until root.length()) {
            val fileObj = root.optJSONObject(f) ?: continue
            val messages = fileObj.optJSONArray("messages") ?: continue
            for (m in 0 until messages.length()) {
                val msg = messages.optJSONObject(m) ?: continue
                val line = msg.optInt("line", 1)
                val col = msg.optInt("column", 1)
                val endLine = msg.optInt("endLine", line)
                val endCol = msg.optInt("endColumn", col + 1)
                val sev = if (msg.optInt("severity", 2) == 2) 8 else 4
                val text = msg.optString("message", "")
                val ruleId = msg.optString("ruleId", "")
                val fullMsg = if (ruleId.isNotBlank()) "[$ruleId] $text" else text
                markers.put(JSONObject().apply {
                    put("startLineNumber", line)
                    put("startColumn", col)
                    put("endLineNumber", endLine)
                    put("endColumn", endCol)
                    put("severity", sev)
                    put("message", fullMsg)
                })
            }
        }
    }

    private fun parseRuff(raw: String, markers: JSONArray) {
        val root = JSONArray(raw.trim())
        for (i in 0 until root.length()) {
            val v = root.optJSONObject(i) ?: continue
            val loc = v.optJSONObject("location")
            val endLoc = v.optJSONObject("end_location")
            val line = loc?.optInt("row", 1) ?: 1
            val col = loc?.optInt("column", 1) ?: 1
            val endLine = endLoc?.optInt("row", line) ?: line
            val endCol = endLoc?.optInt("column", col + 1) ?: (col + 1)
            val code = v.optString("code", "")
            val message = v.optString("message", "")
            markers.put(JSONObject().apply {
                put("startLineNumber", line)
                put("startColumn", col)
                put("endLineNumber", endLine)
                put("endColumn", endCol)
                put("severity", 8)
                put("message", if (code.isNotBlank()) "[$code] $message" else message)
            })
        }
    }

    private fun parseShellcheck(raw: String, markers: JSONArray) {
        val root = JSONArray(raw.trim())
        for (i in 0 until root.length()) {
            val v = root.optJSONObject(i) ?: continue
            val line = v.optInt("line", 1)
            val col = v.optInt("column", 1)
            val endLine = v.optInt("endLine", line)
            val endCol = v.optInt("endColumn", col + 1)
            val level = v.optString("level", "warning")
            val sev = if (level.equals("error", ignoreCase = true)) 8 else 4
            val code = v.optInt("code", 0)
            val message = v.optString("message", "")
            markers.put(JSONObject().apply {
                put("startLineNumber", line)
                put("startColumn", col)
                put("endLineNumber", endLine)
                put("endColumn", endCol)
                put("severity", sev)
                put("message", if (code > 0) "[SC$code] $message" else message)
            })
        }
    }

    private fun parseDetekt(raw: String, markers: JSONArray) {
        val regex = Regex("""(?::|^)(\d+):(\d+):\s*(.+?)(?:\s*\[([^\]]+)\])?$""")
        raw.lines().forEach { line ->
            val m = regex.find(line.trim())
            if (m != null) {
                val row = m.groupValues[1].toIntOrNull() ?: 1
                val col = m.groupValues[2].toIntOrNull() ?: 1
                val text = m.groupValues[3]
                val rule = m.groupValues.getOrNull(4)
                val fullMsg = if (!rule.isNullOrBlank()) "[$rule] $text" else text
                markers.put(JSONObject().apply {
                    put("startLineNumber", row)
                    put("startColumn", col)
                    put("endLineNumber", row)
                    put("endColumn", col + 1)
                    put("severity", 4)
                    put("message", fullMsg)
                })
            }
        }
    }

    private fun parseClangTidy(raw: String, markers: JSONArray) {
        val regex = Regex("""(?::|^)(\d+):(\d+):\s*(error|warning|note):\s*(.+)$""")
        raw.lines().forEach { line ->
            val m = regex.find(line.trim())
            if (m != null) {
                val row = m.groupValues[1].toIntOrNull() ?: 1
                val col = m.groupValues[2].toIntOrNull() ?: 1
                val sevStr = m.groupValues[3].lowercase()
                val sev = if (sevStr == "error") 8 else 4
                val msg = m.groupValues[4]
                markers.put(JSONObject().apply {
                    put("startLineNumber", row)
                    put("startColumn", col)
                    put("endLineNumber", row)
                    put("endColumn", col + 1)
                    put("severity", sev)
                    put("message", msg)
                })
            }
        }
    }

    private fun parseGeneric(raw: String, markers: JSONArray) {
        val regex = Regex("""(?::|^)(\d+):(\d+):\s*(.+)$""")
        raw.lines().forEach { line ->
            val m = regex.find(line.trim())
            if (m != null) {
                val row = m.groupValues[1].toIntOrNull() ?: 1
                val col = m.groupValues[2].toIntOrNull() ?: 1
                val msg = m.groupValues[3]
                markers.put(JSONObject().apply {
                    put("startLineNumber", row)
                    put("startColumn", col)
                    put("endLineNumber", row)
                    put("endColumn", col + 1)
                    put("severity", 4)
                    put("message", msg)
                })
            }
        }
    }
}
