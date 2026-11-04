package com.sednium.kryptonide.ui.terminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sednium.kryptonide.ui.build.ToolchainDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.util.concurrent.atomic.AtomicLong

// ─── Data Models ──────────────────────────────────────────────────────────────

data class TerminalLine(
    val id:   Long,
    val text: String,
    val type: TerminalLineType = TerminalLineType.OUTPUT
)

enum class TerminalLineType { OUTPUT, ERROR, INPUT, SYSTEM }

enum class TerminalConnectionMode {
    KRYPTON,        // Built-in POSIX/Git engine + shell fallback
    TERMUX,         // Direct Termux bridge
    SYSTEM          // Raw /system/bin/sh
}

data class TerminalState(
    val lines: List<TerminalLine> = emptyList(),
    val isConnected: Boolean = false,
    val currentDir: String = "~",
    val projectRoot: String = "",
    val connectionMode: TerminalConnectionMode = TerminalConnectionMode.KRYPTON,
    val isTermuxAvailable: Boolean = false,
    val isExecuting: Boolean = false
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(TerminalState())
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    private val nextLineId = AtomicLong(1L)
    private val commandHistory = mutableListOf<String>()

    // Background shell process for pass-through commands
    private var shellProcess: Process? = null
    private var stdinWriter: BufferedWriter? = null
    private var readJob: Job? = null

    fun init(projectName: String, initialDir: String = "") {
        val resolvedDir = initialDir.ifBlank {
            File(com.sednium.kryptonide.utils.StorageUtils.getProjectsDir(), projectName).let {
                if (it.exists()) it.absolutePath else {
                    val rootDir = File(com.sednium.kryptonide.utils.StorageUtils.getKryptonideRoot(), projectName)
                    if (rootDir.exists()) rootDir.absolutePath else {
                        val legacy = File(com.sednium.kryptonide.utils.StorageUtils.getExternalStorageDir(), "KryptonIDE/$projectName")
                        if (legacy.exists()) legacy.absolutePath else it.absolutePath
                    }
                }
            }
        }

        val termuxInstalled = TermuxBridge.isTermuxInstalled(getApplication())

        _state.update {
            it.copy(
                currentDir = resolvedDir,
                projectRoot = resolvedDir,
                isTermuxAvailable = termuxInstalled
            )
        }

        if (_state.value.isConnected) return
        startShell()
    }

    fun setConnectionMode(mode: TerminalConnectionMode) {
        if (_state.value.connectionMode == mode) return
        _state.update { it.copy(connectionMode = mode) }
        appendSystem("Switched terminal mode to ${mode.name.lowercase().replace('_', ' ')}")
    }

    private fun startShell() {
        viewModelScope.launch(Dispatchers.IO) {
            appendSystem("Initializing Kryptonide terminal environment…")

            // 1. Try Toolchain if available
            val toolchain = ToolchainDetector.detect(getApplication())
            if (toolchain.isUsable || (toolchain.bashBin.exists() && toolchain.bashBin.canExecute())) {
                try {
                    val env = ToolchainDetector.buildEnvironment(toolchain)
                    val pb = ProcessBuilder(toolchain.bashBin.absolutePath, "--login", "-i")
                        .redirectErrorStream(true)
                        .apply {
                            environment().clear()
                            environment().putAll(env)
                            val packagesDir = com.sednium.kryptonide.ui.packages.PackageInstaller.getPackagesDir()
                            val nodeModules = File(packagesDir, "node_modules").absolutePath
                            val pythonPkgs = File(packagesDir, "python").absolutePath
                            environment()["NODE_PATH"] = nodeModules + if (environment()["NODE_PATH"].isNullOrEmpty()) "" else ":${environment()["NODE_PATH"]}"
                            environment()["PYTHONPATH"] = pythonPkgs + if (environment()["PYTHONPATH"].isNullOrEmpty()) "" else ":${environment()["PYTHONPATH"]}"
                            val internalTmpDir = File(getApplication<android.app.Application>().cacheDir, "tmp").apply { mkdirs() }
                            environment()["CARGO_TARGET_DIR"] = File(internalTmpDir, "cargo").apply { mkdirs() }.absolutePath
                            environment()["GOTMPDIR"] = File(internalTmpDir, "go").apply { mkdirs() }.absolutePath
                            environment()["TMPDIR"] = internalTmpDir.absolutePath
                            val dir = File(_state.value.currentDir)
                            if (dir.exists()) directory(dir)
                        }
                    shellProcess = pb.start()
                    stdinWriter = shellProcess!!.outputStream.bufferedWriter()
                    startOutputReader(shellProcess!!.inputStream)
                    _state.update { it.copy(isConnected = true) }
                    appendSystem("Connected with ${toolchain.kind.name} toolchain")
                    appendWelcomeMessage()
                    return@launch
                } catch (_: Exception) {}
            }

            // 2. Fallback to /system/bin/sh
            try {
                val sh = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
                val pb = ProcessBuilder(sh)
                    .redirectErrorStream(true)
                    .apply {
                        val home = _state.value.projectRoot
                        environment()["HOME"] = home
                        environment()["TERM"] = "xterm-256color"
                        environment()["SHELL"] = sh
                        val existing = environment()["PATH"] ?: ""
                        environment()["PATH"] = "/system/bin:/system/xbin:/sbin" + if (existing.isNotEmpty()) ":$existing" else ""
                        val packagesDir = com.sednium.kryptonide.ui.packages.PackageInstaller.getPackagesDir()
                        val nodeModules = File(packagesDir, "node_modules").absolutePath
                        val pythonPkgs = File(packagesDir, "python").absolutePath
                        environment()["NODE_PATH"] = nodeModules + if (environment()["NODE_PATH"].isNullOrEmpty()) "" else ":${environment()["NODE_PATH"]}"
                        environment()["PYTHONPATH"] = pythonPkgs + if (environment()["PYTHONPATH"].isNullOrEmpty()) "" else ":${environment()["PYTHONPATH"]}"
                        val internalTmpDir = File(getApplication<android.app.Application>().cacheDir, "tmp").apply { mkdirs() }
                        environment()["CARGO_TARGET_DIR"] = File(internalTmpDir, "cargo").apply { mkdirs() }.absolutePath
                        environment()["GOTMPDIR"] = File(internalTmpDir, "go").apply { mkdirs() }.absolutePath
                        environment()["TMPDIR"] = internalTmpDir.absolutePath
                        val dir = File(_state.value.currentDir)
                        if (dir.exists()) directory(dir)
                    }
                shellProcess = pb.start()
                stdinWriter = shellProcess!!.outputStream.bufferedWriter()
                startOutputReader(shellProcess!!.inputStream)
                _state.update { it.copy(isConnected = true) }
                appendWelcomeMessage()
            } catch (e: Exception) {
                // Even if system shell fails, Kryptonide built-in engine remains active!
                _state.update { it.copy(isConnected = true) }
                appendSystem("Running in standalone Kryptonide Shell engine")
                appendWelcomeMessage()
            }
        }
    }

    private fun appendWelcomeMessage() {
        appendSystem("Kryptonide Shell active. Standalone package engine (npm, pip, git) ready.")
        appendSystem("Type 'help' for available commands.")
    }

    // ── Non-blocking Chunk Stream Reader ──────────────────────────────────────

    private fun startOutputReader(stream: InputStream) {
        readJob?.cancel()
        readJob = viewModelScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(2048)
            val lineBuffer = StringBuilder()
            try {
                while (isActive) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    val chunk = String(buffer, 0, read, Charsets.UTF_8)
                    lineBuffer.append(chunk)

                    // Flush complete lines
                    var newlineIndex = lineBuffer.indexOf('\n')
                    while (newlineIndex != -1) {
                        val line = lineBuffer.substring(0, newlineIndex)
                        lineBuffer.delete(0, newlineIndex + 1)
                        if (line.isNotEmpty()) {
                            appendOutput(line)
                        }
                        newlineIndex = lineBuffer.indexOf('\n')
                    }

                    // If remaining buffer has prompt or text without newline, flush after brief timeout
                    if (lineBuffer.isNotEmpty() && lineBuffer.length < 500) {
                        delay(50)
                        if (lineBuffer.isNotEmpty() && lineBuffer.indexOf('\n') == -1) {
                            val line = lineBuffer.toString()
                            lineBuffer.clear()
                            appendOutput(line)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (lineBuffer.isNotEmpty()) {
                    appendOutput(lineBuffer.toString())
                }
            }
        }
    }

    // ── Command Dispatcher ────────────────────────────────────────────────────

    fun sendCommand(command: String, onOpenFile: ((String) -> Unit)? = null) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return

        commandHistory.add(trimmed)
        appendLine(TerminalLine(id = nextLineId.getAndIncrement(), text = "$ $trimmed", type = TerminalLineType.INPUT))

        val dispatchedCommand = escapeNoExec(trimmed)

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isExecuting = true) }
            try {
                // Termux Bridge Mode
                if (_state.value.connectionMode == TerminalConnectionMode.TERMUX) {
                    if (dispatchedCommand == "termux open" || dispatchedCommand == "open") {
                        TermuxBridge.openTermuxAt(getApplication(), _state.value.currentDir)
                        appendSystem("Opening interactive Termux session in ${_state.value.currentDir}")
                    } else {
                        val dispatched = TermuxBridge.executeInTermux(
                            getApplication(),
                            dispatchedCommand,
                            _state.value.currentDir,
                            background = false
                        )
                        if (dispatched) {
                            appendSystem("Dispatched to Termux: $dispatchedCommand")
                        } else {
                            appendError("Termux dispatch failed. Is Termux installed?")
                        }
                    }
                    return@launch
                }

                // Krypton Built-in Shell Mode
                if (_state.value.connectionMode == TerminalConnectionMode.KRYPTON) {
                    if (KryptonShellEngine.canHandle(dispatchedCommand)) {
                        val result = KryptonShellEngine.execute(
                            context = getApplication(),
                            commandLine = dispatchedCommand,
                            currentDir = _state.value.currentDir,
                            projectRoot = _state.value.projectRoot,
                            historyList = commandHistory
                        )

                        if (result.output.isNotEmpty()) {
                            result.output.lines().forEach { line ->
                                if (line.isNotEmpty()) appendOutput(line)
                            }
                        }

                        if (result.error.isNotEmpty()) {
                            result.error.lines().forEach { line ->
                                if (line.isNotEmpty()) appendError(line)
                            }
                        }

                        result.newWorkingDir?.let { newDir ->
                            _state.update { it.copy(currentDir = newDir) }
                            // Also cd the background shell process
                            try {
                                stdinWriter?.write("cd \"$newDir\"\n")
                                stdinWriter?.flush()
                            } catch (_: Exception) {}
                        }

                        result.openFileRequested?.let { fileToOpen ->
                            withContext(Dispatchers.Main) {
                                onOpenFile?.invoke(fileToOpen)
                            }
                        }
                        return@launch
                    }
                }

                // Pass-through to underlying shell process (System sh or Toolchain bash)
                if (stdinWriter != null) {
                    stdinWriter?.write(dispatchedCommand)
                    stdinWriter?.newLine()
                    stdinWriter?.flush()
                } else {
                    appendError("Command not found: $dispatchedCommand. Type 'help' for built-in commands.")
                }
            } catch (e: Exception) {
                appendError("Error: ${e.message}")
            } finally {
                _state.update { it.copy(isExecuting = false) }
            }
        }
    }

    private fun escapeNoExec(cmd: String): String {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return trimmed

        val firstToken = trimmed.substringBefore(" ")
        val interpreterPrefixes = setOf("sh", "bash", "zsh", "source", ".", "python", "python3", "node")
        if (interpreterPrefixes.contains(firstToken.lowercase())) {
            return trimmed
        }

        val isScriptExecution = trimmed.startsWith("./") ||
            firstToken.endsWith(".sh", ignoreCase = true) ||
            trimmed.endsWith(".sh", ignoreCase = true)

        if (isScriptExecution) {
            val toolchain = ToolchainDetector.detect(getApplication())
            val hasBash = toolchain.isUsable ||
                (toolchain.bashBin.exists() && toolchain.bashBin.canExecute()) ||
                File("/data/data/com.termux/files/usr/bin/bash").exists() ||
                File("/system/bin/bash").exists()
            val interpreter = if (hasBash) "bash" else "sh"
            return "$interpreter $trimmed"
        }

        return trimmed
    }

    fun sendInterrupt() {
        appendLine(TerminalLine(id = nextLineId.getAndIncrement(), text = "^C", type = TerminalLineType.SYSTEM))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                stdinWriter?.write("\u0003")
                stdinWriter?.flush()
            } catch (_: Exception) {}
        }
    }

    fun sendTab() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                stdinWriter?.write("\t")
                stdinWriter?.flush()
            } catch (_: Exception) {}
        }
    }

    fun reconnect() {
        disconnect()
        _state.update { it.copy(lines = emptyList()) }
        startShell()
    }

    fun disconnect() {
        readJob?.cancel()
        try {
            stdinWriter?.write("exit\n")
            stdinWriter?.flush()
        } catch (_: Exception) {}
        try { stdinWriter?.close() } catch (_: Exception) {}
        try { shellProcess?.destroy() } catch (_: Exception) {}
        shellProcess = null
        stdinWriter = null
        _state.update { it.copy(isConnected = false) }
    }

    fun clear() = _state.update { it.copy(lines = emptyList()) }

    fun getAllOutputText(): String {
        return _state.value.lines.joinToString("\n") { AnsiParser.stripAnsi(it.text) }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }

    // ── Output Append Helpers ─────────────────────────────────────────────────

    private fun appendOutput(text: String) =
        appendLine(TerminalLine(id = nextLineId.getAndIncrement(), text = text, type = TerminalLineType.OUTPUT))

    private fun appendError(text: String) =
        appendLine(TerminalLine(id = nextLineId.getAndIncrement(), text = text, type = TerminalLineType.ERROR))

    private fun appendSystem(text: String) =
        appendLine(TerminalLine(id = nextLineId.getAndIncrement(), text = text, type = TerminalLineType.SYSTEM))

    private fun appendLine(line: TerminalLine) {
        _state.update { s ->
            val trimmed = if (s.lines.size >= 2500) s.lines.drop(150) else s.lines
            s.copy(lines = trimmed + line)
        }
    }
}
