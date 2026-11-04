package com.sednium.kryptonide.ui.lsp

import android.content.Context
import com.sednium.kryptonide.ui.build.ToolchainDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Installs a single language server into whichever sandbox
 * [ToolchainDetector] finds. Shared by [LspManager] (lazy, per-file) and the
 * app's first-run setup flow (eager, all registered servers up front).
 */
object LspInstaller {

    suspend fun isInstalled(context: Context, config: LspServerConfig): Boolean = withContext(Dispatchers.IO) {
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

    /** Returns true if the server ends up installed (already was, or install succeeded). */
    suspend fun install(context: Context, config: LspServerConfig, log: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val tc = ToolchainDetector.detect(context)
        if (!tc.isUsable) {
            log("✗ ${config.displayName}: no local toolchain detected")
            return@withContext false
        }

        if (isInstalled(context, config)) {
            log("✓ ${config.displayName} already installed")
            return@withContext true
        }

        log("Installing ${config.displayName}…")
        val proc = ToolchainDetector.spawnBash(tc, listOf("-c", config.installCommand), null)
        if (proc == null) {
            log("✗ ${config.displayName}: could not launch install shell")
            return@withContext false
        }

        val errJob = launch(Dispatchers.IO) {
            BufferedReader(InputStreamReader(proc.errorStream)).forEachLine { line ->
                if (line.isNotBlank()) log("  $line")
            }
        }
        BufferedReader(InputStreamReader(proc.inputStream)).forEachLine { line ->
            if (line.isNotBlank()) log("  $line")
        }
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
}
