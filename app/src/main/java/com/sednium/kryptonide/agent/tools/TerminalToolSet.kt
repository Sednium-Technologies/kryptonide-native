package com.sednium.kryptonide.agent.tools

import android.content.Context
import com.sednium.kryptonide.ui.terminal.KryptonShellEngine
import com.sednium.kryptonide.utils.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class TerminalToolSet(
    private val context: Context,
    private val workingDir: () -> File
) {

    companion object {
        private val ANSI_REGEX = Regex("\u001B\\[[0-9;]*[a-zA-Z]")
        private val SENTINEL_EXIT_REGEX = Regex("__KRYPTON_EXIT_(\\d+)__")
        private const val SENTINEL_START = "__KRYPTON_START__"
    }

    fun registerAll() {
        AgentToolRegistry.register(
            ToolDefinition(
                name = "execute_terminal_command",
                description = "Execute a shell or terminal command in the project environment with exit code tracking.",
                parameters = listOf(
                    ToolParam("command", "string", "Shell command line to execute"),
                    ToolParam("timeout_ms", "integer", "Timeout in milliseconds (default 30000)", required = false)
                )
            ) { args ->
                val command = args["command"]?.toString()?.trim()
                    ?: return@ToolDefinition ToolResult(false, "", "Missing 'command'", exitCode = 1)
                val timeoutMs = (args["timeout_ms"] as? Number)?.toLong() ?: 30_000L

                val dir = workingDir()
                dir.mkdirs()

                // Check built-in shell engine first (handles JGit git operations, builtins)
                if (KryptonShellEngine.canHandle(command)) {
                    val result = withContext(Dispatchers.IO) {
                        KryptonShellEngine.execute(context, command, dir.absolutePath, dir.absolutePath)
                    }
                    val cleanOutput = stripAnsi(result.output)
                    val cleanError = stripAnsi(result.error)
                    return@ToolDefinition ToolResult(
                        success = cleanError.isBlank(),
                        output = cleanOutput,
                        error = if (cleanError.isNotBlank()) cleanError else null,
                        exitCode = if (cleanError.isNotBlank()) 1 else 0
                    )
                }

                // ProcessBuilder execution with sentinel wrapper
                val wrappedScript = buildString {
                    appendLine("echo \"$SENTINEL_START\"")
                    appendLine(command)
                    appendLine("echo \"__KRYPTON_EXIT_\$?__\"")
                }

                val executed = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(timeoutMs) {
                        runProcess(wrappedScript, dir)
                    }
                }

                if (executed == null) {
                    return@ToolDefinition ToolResult(
                        success = false,
                        output = "",
                        error = "Command timed out after ${timeoutMs}ms",
                        exitCode = 124
                    )
                }

                executed
            }
        )
    }

    private fun runProcess(script: String, dir: File): ToolResult {
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", script)
            pb.directory(dir)
            pb.redirectErrorStream(false)

            // Inject node/python paths
            val packagesDir = com.sednium.kryptonide.ui.packages.PackageInstaller.getPackagesDir()
            val nodeModules = File(packagesDir, "node_modules").absolutePath
            val pythonPkgs = File(packagesDir, "python").absolutePath
            val env = pb.environment()
            env["HOME"] = context.filesDir.absolutePath
            env["TMPDIR"] = context.cacheDir.absolutePath
            env["NODE_PATH"] = nodeModules + (if (env["NODE_PATH"].isNullOrEmpty()) "" else ":${env["NODE_PATH"]}")
            env["PYTHONPATH"] = pythonPkgs + (if (env["PYTHONPATH"].isNullOrEmpty()) "" else ":${env["PYTHONPATH"]}")

            val proc = pb.start()

            val stdoutReader = BufferedReader(InputStreamReader(proc.inputStream))
            val stderrReader = BufferedReader(InputStreamReader(proc.errorStream))

            val stdoutSb = StringBuilder()
            val stderrSb = StringBuilder()

            var line: String?
            while (stdoutReader.readLine().also { line = it } != null) {
                stdoutSb.appendLine(line)
            }
            while (stderrReader.readLine().also { line = it } != null) {
                stderrSb.appendLine(line)
            }

            proc.waitFor()

            var rawStdout = stdoutSb.toString()
            var detectedExitCode = proc.exitValue()

            // Parse sentinel
            val sentinelMatch = SENTINEL_EXIT_REGEX.find(rawStdout)
            if (sentinelMatch != null) {
                detectedExitCode = sentinelMatch.groupValues[1].toIntOrNull() ?: detectedExitCode
                rawStdout = rawStdout.replace(SENTINEL_EXIT_REGEX, "")
            }
            if (rawStdout.contains(SENTINEL_START)) {
                rawStdout = rawStdout.substringAfter(SENTINEL_START).trimStart()
            }

            val cleanStdout = stripAnsi(rawStdout).trim()
            val cleanStderr = stripAnsi(stderrSb.toString()).trim()

            ToolResult(
                success = detectedExitCode == 0,
                output = cleanStdout,
                error = if (cleanStderr.isNotEmpty()) cleanStderr else null,
                exitCode = detectedExitCode
            )
        } catch (e: Throwable) {
            ToolResult(false, "", "Execution error: ${e.message}", exitCode = 1)
        }
    }

    private fun stripAnsi(input: String): String {
        return input.replace(ANSI_REGEX, "")
    }
}
