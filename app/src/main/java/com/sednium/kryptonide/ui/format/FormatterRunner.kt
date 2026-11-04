package com.sednium.kryptonide.ui.format

import android.content.Context
import com.sednium.kryptonide.ui.build.ToolchainDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object FormatterRunner {

    suspend fun isInstalled(context: Context, config: FormatterConfig): Boolean = withContext(Dispatchers.IO) {
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

    suspend fun install(context: Context, config: FormatterConfig, log: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val tc = ToolchainDetector.detect(context)
        if (!tc.isUsable) {
            log("✗ ${config.displayName}: no JDK/toolchain — set up the toolchain first")
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
            log("✓ ${config.displayName} installed")
            true
        } else {
            log("✗ ${config.displayName} failed (exit $exit)")
            false
        }
    }

    /**
     * Runs the formatter on [content] via stdin/stdout. Returns null (leaving
     * the editor untouched) if the formatter isn't installed, crashes, or the
     * source has a syntax error it refuses to format — never returns partial
     * or garbage output.
     */
    suspend fun format(context: Context, config: FormatterConfig, content: String, ext: String): String? = withContext(Dispatchers.IO) {
        val tc = ToolchainDetector.detect(context)
        if (!tc.isUsable) return@withContext null
        if (!isInstalled(context, config)) {
            val ok = install(context, config) { msg -> android.util.Log.d("FormatterRunner", msg) }
            if (!ok) return@withContext null
        }

        val command = config.formatCommand.replace("{ext}", ext)
        val proc = ToolchainDetector.spawnBash(tc, listOf("-c", command), null) ?: return@withContext null

        return@withContext try {
            // Read stdout and stderr concurrently to prevent 64KB pipe buffer deadlock
            val outputDeferred = async(Dispatchers.IO) {
                proc.inputStream.bufferedReader().use { it.readText() }
            }
            val errDeferred = async(Dispatchers.IO) {
                proc.errorStream.bufferedReader().use { it.readText() }
            }

            proc.outputStream.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val output = outputDeferred.await()
            errDeferred.await()

            val finished = proc.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return@withContext null
            }
            val exit = proc.exitValue()
            if (exit == 0 && output.isNotBlank()) output else null
        } catch (e: Exception) {
            runCatching { proc.destroyForcibly() }
            null
        }
    }
}
