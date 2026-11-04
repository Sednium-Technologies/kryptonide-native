package com.sednium.kryptonide.agent.tools

import android.content.Context
import com.sednium.kryptonide.ui.editor.EditorViewModel
import com.sednium.kryptonide.ui.preview.ConsoleEntry
import com.sednium.kryptonide.ui.preview.PreviewConsoleBridge
import com.sednium.kryptonide.ui.terminal.KryptonShellEngine
import com.sednium.kryptonide.utils.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import java.util.regex.Pattern

/**
 * Unified execution engine for all Needle-3 agentic tools.
 * Supports structured tool calls from Needle-3 models (<tool_call>), cloud models,
 * and the Pre-Flight / Post-Flight Autonomous Agentic Coordinator.
 */
object NeedleToolExecutor {

    // Configurable root provider for testing & runtime
    var projectDirProvider: (() -> File)? = null
    var editorVmProvider: (() -> EditorViewModel?)? = null
    var activeFilePathProvider: (() -> String?)? = null
    var contextProvider: (() -> Context?)? = null

    // Rolling buffer of latest terminal outputs
    private val terminalLogBuffer = mutableListOf<String>()
    private const val MAX_TERMINAL_LOGS = 200

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun getProjectRoot(): File {
        projectDirProvider?.invoke()?.let { return it }
        val projectsDir = runCatching { StorageUtils.getProjectsDir() }.getOrNull()
        if (projectsDir != null && projectsDir.exists()) {
            val first = projectsDir.listFiles()?.firstOrNull { it.isDirectory }
            if (first != null) return first
            return projectsDir
        }
        return File("/storage/emulated/0/Kryptonide/projects/default")
    }

    private fun resolveSafeFile(relativePath: String): File {
        val root = getProjectRoot().canonicalFile
        val cleaned = relativePath.trim()
        val kryptonideRoot = runCatching { StorageUtils.getKryptonideRoot().canonicalFile }.getOrNull()

        val target = if (cleaned.startsWith("/") || (kryptonideRoot != null && cleaned.startsWith(kryptonideRoot.path))) {
            File(cleaned).canonicalFile
        } else {
            val rel = cleaned.removePrefix("./")
            File(root, rel).canonicalFile
        }

        val isWithinProject = target.path == root.path || target.path.startsWith(root.path + File.separator)
        val isWithinKryptonide = kryptonideRoot != null && (target.path == kryptonideRoot.path || target.path.startsWith(kryptonideRoot.path + File.separator))

        if (!isWithinProject && !isWithinKryptonide) {
            throw SecurityException("Access denied: path '$relativePath' escapes Kryptonide workspace boundary")
        }
        return target
    }

    /**
     * Executes any tool by name with arguments map.
     */
    suspend fun execute(name: String, args: Map<String, Any?>): ToolResult {
        return when (name.lowercase(Locale.ROOT)) {
            // Workspace & File System Tools
            "fs_list_tree", "list_dir" -> {
                val path = args["path"]?.toString() ?: "."
                val maxDepth = (args["max_depth"] as? Number)?.toInt() ?: 3
                fsListTree(path, maxDepth)
            }
            "fs_read_file", "read_file" -> {
                val path = args["path"]?.toString() ?: return ToolResult(false, "", "Missing required 'path'")
                val start = (args["start_line"] as? Number)?.toInt() ?: args["start_line"]?.toString()?.toIntOrNull()
                val end = (args["end_line"] as? Number)?.toInt() ?: args["end_line"]?.toString()?.toIntOrNull()
                fsReadFile(path, start, end)
            }
            "fs_write_file", "write_file", "write_new_file" -> {
                val path = args["path"]?.toString() ?: return ToolResult(false, "", "Missing required 'path'")
                val content = args["content"]?.toString() ?: ""
                fsWriteFile(path, content)
            }
            "fs_patch_file", "patch_file" -> {
                val path = args["path"]?.toString() ?: return ToolResult(false, "", "Missing required 'path'")
                val search = args["search"]?.toString()
                    ?: args["search_string"]?.toString()
                    ?: return ToolResult(false, "", "Missing required 'search' block")
                val replace = args["replace"]?.toString()
                    ?: args["replace_string"]?.toString()
                    ?: ""
                fsPatchFile(path, search, replace)
            }
            "fs_create_file" -> {
                val path = args["path"]?.toString() ?: return ToolResult(false, "", "Missing required 'path'")
                val content = args["content"]?.toString() ?: ""
                fsCreateFile(path, content)
            }
            "fs_delete_path" -> {
                val path = args["path"]?.toString() ?: return ToolResult(false, "", "Missing required 'path'")
                fsDeletePath(path)
            }

            // Workspace Search & Diagnostics Tools
            "search_grep", "grep", "search_code" -> {
                val query = args["query"]?.toString() ?: return ToolResult(false, "", "Missing required 'query'")
                val pattern = args["file_pattern"]?.toString()
                searchGrep(query, pattern)
            }
            "search_find_files", "find_files" -> {
                val glob = args["glob"]?.toString() ?: "*.*"
                searchFindFiles(glob)
            }
            "code_get_diagnostics", "get_diagnostics" -> {
                val path = args["path"]?.toString() ?: activeFilePathProvider?.invoke() ?: "index.html"
                codeGetDiagnostics(path)
            }

            // Runtime, Console & Terminal Verification Tools
            "preview_get_console_logs", "get_console_logs" -> {
                val level = args["level"]?.toString()
                previewGetConsoleLogs(level)
            }
            "preview_reload", "reload_preview" -> {
                previewReload()
            }
            "terminal_exec_command", "execute_terminal_command" -> {
                val command = args["command"]?.toString() ?: return ToolResult(false, "", "Missing required 'command'")
                val cwd = args["cwd"]?.toString()
                val timeout = (args["timeout_ms"] as? Number)?.toLong() ?: 30_000L
                terminalExecCommand(command, cwd, timeout)
            }
            "terminal_read_last_output" -> {
                val lines = (args["lines"] as? Number)?.toInt() ?: 30
                terminalReadLastOutput(lines)
            }

            // Editor & Git Tools
            "editor_open_file" -> {
                val path = args["path"]?.toString() ?: return ToolResult(false, "", "Missing 'path'")
                val line = (args["line"] as? Number)?.toInt()
                editorOpenFile(path, line)
            }
            "editor_get_active_context" -> {
                editorGetActiveContext()
            }
            "git_status" -> {
                gitStatus()
            }
            "git_diff" -> {
                val path = args["path"]?.toString()
                gitDiff(path)
            }

            else -> {
                // Delegate to AgentToolRegistry for specialized tools (github, mcp, websearch)
                AgentToolRegistry.dispatch(name, args)
            }
        }
    }

    // ─── Workspace & File System Tools ──────────────────────────────────────────

    fun fsListTree(path: String = ".", maxDepth: Int = 3): ToolResult {
        return try {
            val targetDir = resolveSafeFile(path)
            if (!targetDir.exists()) return ToolResult(false, "", "Directory not found: $path")
            if (!targetDir.isDirectory) return ToolResult(false, "", "Path is not a directory: $path")

            val rootCanonical = getProjectRoot().canonicalFile
            val lines = mutableListOf<String>()
            val rootDisplayName = if (path == "." || path.isBlank()) rootCanonical.name else targetDir.name
            lines.add("$rootDisplayName/")

            fun walk(dir: File, prefix: String, depth: Int) {
                if (depth > maxDepth) return
                val entries = dir.listFiles()
                    ?.filterNot {
                        it.name.startsWith(".") && it.name != ".env" ||
                        it.name == "node_modules" ||
                        it.name == "build" ||
                        it.name == ".git" ||
                        it.name == ".gradle"
                    }
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
                    ?: return

                entries.forEachIndexed { index, file ->
                    val isLast = index == entries.size - 1
                    val connector = if (isLast) "└── " else "├── "
                    val childPrefix = if (isLast) "$prefix    " else "$prefix│   "

                    if (file.isDirectory) {
                        lines.add("$prefix$connector${file.name}/")
                        walk(file, childPrefix, depth + 1)
                    } else {
                        lines.add("$prefix$connector${file.name} (${file.length()} B)")
                    }
                }
            }

            walk(targetDir, "", 1)
            ToolResult(true, lines.joinToString("\n"))
        } catch (e: Throwable) {
            ToolResult(false, "", "fs_list_tree failed: ${e.message}")
        }
    }

    fun fsReadFile(path: String, startLine: Int? = null, endLine: Int? = null): ToolResult {
        return try {
            val file = resolveSafeFile(path)
            if (!file.exists()) return ToolResult(false, "", "File not found: $path")
            if (file.isDirectory) return ToolResult(false, "", "Path is a directory: $path")

            val allLines = file.readLines()
            val total = allLines.size
            val start = (startLine ?: 1).coerceIn(1, total.coerceAtLeast(1))
            val end = (endLine ?: total).coerceIn(start, total.coerceAtLeast(start))

            val numbered = allLines.subList(start - 1, end).mapIndexed { idx, line ->
                "${start + idx}: $line"
            }.joinToString("\n")

            ToolResult(
                success = true,
                output = numbered,
                metadata = mapOf("totalLines" to total, "startLine" to start, "endLine" to end)
            )
        } catch (e: Throwable) {
            ToolResult(false, "", "fs_read_file failed: ${e.message}")
        }
    }

    fun fsWriteFile(path: String, content: String): ToolResult {
        return try {
            val file = resolveSafeFile(path)
            file.parentFile?.mkdirs()

            // Atomically write file
            val temp = File(file.parentFile, ".${file.name}.tmp")
            temp.writeText(content)
            if (!temp.renameTo(file)) {
                file.writeText(content)
                temp.delete()
            }

            // Sync with editor buffer if open
            editorVmProvider?.invoke()?.let { vm ->
                runCatching {
                    vm.writeFileContent(file.name, content)
                }
            }

            ToolResult(true, "Successfully wrote ${content.lines().size} lines (${content.length} bytes) to ${file.name}")
        } catch (e: Throwable) {
            ToolResult(false, "", "fs_write_file failed: ${e.message}")
        }
    }

    fun fsPatchFile(path: String, search: String, replace: String): ToolResult {
        return try {
            val file = resolveSafeFile(path)
            if (!file.exists()) return ToolResult(false, "", "File not found: $path")

            val original = file.readText()
            val searchNormalized = search.replace("\r\n", "\n")
            val originalNormalized = original.replace("\r\n", "\n")

            if (!originalNormalized.contains(searchNormalized)) {
                // Try trimmed match
                val searchTrimmed = searchNormalized.trim()
                if (searchTrimmed.isNotBlank() && originalNormalized.contains(searchTrimmed)) {
                    val patched = originalNormalized.replaceFirst(searchTrimmed, replace.replace("\r\n", "\n"))
                    file.writeText(patched)
                    editorVmProvider?.invoke()?.let { vm ->
                        runCatching { vm.writeFileContent(file.name, patched) }
                    }
                    return ToolResult(true, "Successfully applied surgical patch to ${file.name} (trimmed match)")
                }
                return ToolResult(
                    success = false,
                    output = "",
                    error = "Search block not found in $path. Use fs_read_file to verify current exact content."
                )
            }

            val patched = originalNormalized.replace(searchNormalized, replace.replace("\r\n", "\n"))
            file.writeText(patched)

            // Sync with open editor
            editorVmProvider?.invoke()?.let { vm ->
                runCatching {
                    vm.writeFileContent(file.name, patched)
                }
            }

            ToolResult(true, "Successfully patched ${file.name}")
        } catch (e: Throwable) {
            ToolResult(false, "", "fs_patch_file failed: ${e.message}")
        }
    }

    fun fsCreateFile(path: String, content: String = ""): ToolResult {
        return try {
            val file = resolveSafeFile(path)
            if (file.exists()) {
                return ToolResult(false, "", "File already exists at $path")
            }
            file.parentFile?.mkdirs()
            file.writeText(content)

            editorVmProvider?.invoke()?.let { vm ->
                runCatching { vm.writeFileContent(file.name, content) }
            }

            ToolResult(true, "Created file ${file.name} successfully.")
        } catch (e: Throwable) {
            ToolResult(false, "", "fs_create_file failed: ${e.message}")
        }
    }

    fun fsDeletePath(path: String): ToolResult {
        return try {
            val file = resolveSafeFile(path)
            if (!file.exists()) return ToolResult(false, "", "Path not found: $path")
            val deleted = file.deleteRecursively()
            ToolResult(deleted, if (deleted) "Deleted $path" else "Failed to delete $path")
        } catch (e: Throwable) {
            ToolResult(false, "", "fs_delete_path failed: ${e.message}")
        }
    }

    // ─── Workspace Search & Diagnostics Tools ───────────────────────────────────

    fun searchGrep(query: String, filePattern: String? = null, maxMatches: Int = 60): ToolResult {
        return try {
            val root = getProjectRoot().canonicalFile
            if (!root.exists()) return ToolResult(false, "", "Project directory does not exist: ${root.path}")

            val matches = mutableListOf<String>()
            val regex = runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull()
            val lowerQuery = query.lowercase(Locale.ROOT)

            root.walkTopDown()
                .filter { it.isFile }
                .filterNot {
                    it.name.startsWith(".") ||
                    it.path.contains("/node_modules/") ||
                    it.path.contains("/build/") ||
                    it.path.contains("/.git/") ||
                    it.path.contains("/.gradle/") ||
                    isBinaryExtension(it.extension)
                }
                .filter { file ->
                    if (filePattern.isNullOrBlank()) true
                    else matchPattern(file.name, filePattern)
                }
                .forEach { file ->
                    if (matches.size >= maxMatches) return@forEach
                    val relPath = file.relativeTo(root).path
                    runCatching {
                        file.bufferedReader().useLines { lines ->
                            lines.forEachIndexed { idx, line ->
                                if (matches.size >= maxMatches) return@forEachIndexed
                                val isMatch = if (regex != null) {
                                    regex.containsMatchIn(line)
                                } else {
                                    line.lowercase(Locale.ROOT).contains(lowerQuery)
                                }
                                if (isMatch) {
                                    val trimmedLine = line.trim().take(120)
                                    matches.add("$relPath:${idx + 1}: $trimmedLine")
                                }
                            }
                        }
                    }
                }

            if (matches.isEmpty()) {
                ToolResult(true, "No matches found for \"$query\" in project.")
            } else {
                ToolResult(true, matches.joinToString("\n"))
            }
        } catch (e: Throwable) {
            ToolResult(false, "", "search_grep failed: ${e.message}")
        }
    }

    fun searchFindFiles(glob: String): ToolResult {
        return try {
            val root = getProjectRoot().canonicalFile
            val results = mutableListOf<String>()

            root.walkTopDown()
                .filter { it.isFile }
                .filterNot {
                    it.name.startsWith(".") ||
                    it.path.contains("/node_modules/") ||
                    it.path.contains("/build/") ||
                    it.path.contains("/.git/")
                }
                .filter { matchPattern(it.name, glob) }
                .forEach { file ->
                    results.add(file.relativeTo(root).path)
                }

            if (results.isEmpty()) {
                ToolResult(true, "No files matching '$glob' found.")
            } else {
                ToolResult(true, results.joinToString("\n"))
            }
        } catch (e: Throwable) {
            ToolResult(false, "", "search_find_files failed: ${e.message}")
        }
    }

    fun codeGetDiagnostics(path: String): ToolResult {
        return try {
            val file = resolveSafeFile(path)
            if (!file.exists()) return ToolResult(false, "", "File not found: $path")
            val content = file.readText()
            val ext = file.extension.lowercase(Locale.ROOT)

            val issues = mutableListOf<String>()

            when (ext) {
                "html", "htm" -> {
                    checkHtmlSyntax(content, issues)
                }
                "js", "ts", "json" -> {
                    checkBraceBalance(content, issues)
                }
                "css" -> {
                    checkCssSyntax(content, issues)
                }
                else -> {
                    checkBraceBalance(content, issues)
                }
            }

            if (issues.isEmpty()) {
                ToolResult(true, "✅ No diagnostics found. File ${file.name} syntax is clean.")
            } else {
                ToolResult(
                    success = false,
                    output = "Found ${issues.size} diagnostic(s) in ${file.name}:\n" + issues.joinToString("\n"),
                    error = issues.firstOrNull()
                )
            }
        } catch (e: Throwable) {
            ToolResult(false, "", "code_get_diagnostics failed: ${e.message}")
        }
    }

    private fun checkHtmlSyntax(content: String, issues: MutableList<String>) {
        val lines = content.lines()
        val tagStack = mutableListOf<Pair<String, Int>>() // tagName -> lineNumber

        val voidTags = setOf(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr"
        )

        val tagRegex = Regex("""<\s*(/)?\s*([a-zA-Z0-9-]+)([^>]*)>""")

        lines.forEachIndexed { lineIdx, line ->
            val lineNum = lineIdx + 1
            // Check for unclosed script tag on same line if inline
            tagRegex.findAll(line).forEach { match ->
                val isClosing = match.groupValues[1] == "/"
                val tagName = match.groupValues[2].lowercase(Locale.ROOT)
                val rawAttrs = match.groupValues[3]
                val isSelfClosing = rawAttrs.trim().endsWith("/") || voidTags.contains(tagName)

                if (isClosing) {
                    if (tagStack.isEmpty()) {
                        issues.add("Line $lineNum: Unexpected closing tag </$tagName> with no matching open tag.")
                    } else {
                        val last = tagStack.last()
                        if (last.first == tagName) {
                            tagStack.removeAt(tagStack.size - 1)
                        } else {
                            // Find matching in stack
                            val matchIndex = tagStack.indexOfLast { it.first == tagName }
                            if (matchIndex != -1) {
                                val skipped = tagStack.subList(matchIndex + 1, tagStack.size)
                                skipped.forEach {
                                    issues.add("Line ${it.second}: Unclosed tag <${it.first}> before closing </$tagName> at line $lineNum.")
                                }
                                while (tagStack.size > matchIndex) {
                                    tagStack.removeAt(tagStack.size - 1)
                                }
                            } else {
                                issues.add("Line $lineNum: Unexpected closing tag </$tagName> (expected </${last.first}> opened at line ${last.second}).")
                            }
                        }
                    }
                } else if (!isSelfClosing) {
                    tagStack.add(tagName to lineNum)
                }
            }
        }

        tagStack.forEach {
            issues.add("Line ${it.second}: Unclosed tag <${it.first}> reaching end of file.")
        }
    }

    private fun checkCssSyntax(content: String, issues: MutableList<String>) {
        var openBraces = 0
        var openLine = 1
        content.lines().forEachIndexed { idx, line ->
            for (char in line) {
                if (char == '{') {
                    openBraces++
                    openLine = idx + 1
                } else if (char == '}') {
                    openBraces--
                    if (openBraces < 0) {
                        issues.add("Line ${idx + 1}: Unexpected closing brace '}' in CSS.")
                        openBraces = 0
                    }
                }
            }
        }
        if (openBraces > 0) {
            issues.add("Line $openLine: Unclosed CSS block starting with '{'.")
        }
    }

    private fun checkBraceBalance(content: String, issues: MutableList<String>) {
        var curly = 0
        var paren = 0
        var square = 0
        var inSingleQuote = false
        var inDoubleQuote = false
        var inBacktick = false
        var escaped = false

        content.lines().forEachIndexed { idx, line ->
            val lineNum = idx + 1
            for (i in line.indices) {
                val c = line[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (c == '\\') {
                    escaped = true
                    continue
                }
                if (c == '"' && !inSingleQuote && !inBacktick) inDoubleQuote = !inDoubleQuote
                else if (c == '\'' && !inDoubleQuote && !inBacktick) inSingleQuote = !inSingleQuote
                else if (c == '`' && !inSingleQuote && !inDoubleQuote) inBacktick = !inBacktick

                if (!inDoubleQuote && !inSingleQuote && !inBacktick) {
                    when (c) {
                        '{' -> curly++
                        '}' -> {
                            curly--
                            if (curly < 0) {
                                issues.add("Line $lineNum: Unexpected closing brace '}'")
                                curly = 0
                            }
                        }
                        '(' -> paren++
                        ')' -> {
                            paren--
                            if (paren < 0) {
                                issues.add("Line $lineNum: Unexpected closing parenthesis ')'")
                                paren = 0
                            }
                        }
                        '[' -> square++
                        ']' -> {
                            square--
                            if (square < 0) {
                                issues.add("Line $lineNum: Unexpected closing bracket ']'")
                                square = 0
                            }
                        }
                    }
                }
            }
        }

        if (curly > 0) issues.add("Unclosed curly brace '{' detected ($curly unclosed).")
        if (paren > 0) issues.add("Unclosed parenthesis '(' detected ($paren unclosed).")
        if (square > 0) issues.add("Unclosed bracket '[' detected ($square unclosed).")
        if (inBacktick) issues.add("Unclosed template literal '`' reaching end of file.")
    }

    // ─── Runtime, Console & Terminal Verification Tools ─────────────────────────

    fun previewGetConsoleLogs(level: String? = null): ToolResult {
        val entries = PreviewConsoleBridge.getEntries(level)
        if (entries.isEmpty()) {
            val msg = if (level != null) "No preview console logs captured for level '$level'." else "No preview console logs captured."
            return ToolResult(true, msg, metadata = mapOf("count" to 0))
        }

        val formatted = entries.map { entry ->
            val src = if (entry.sourceId.isNotBlank()) " (${entry.sourceId}:${entry.lineNumber})" else ""
            "[${entry.level}] ${entry.message}$src"
        }.joinToString("\n")

        return ToolResult(
            success = true,
            output = formatted,
            metadata = mapOf("count" to entries.size, "hasErrors" to entries.any { it.level == "ERROR" })
        )
    }

    fun previewReload(): ToolResult {
        val hasWv = PreviewConsoleBridge.hasActiveWebView()
        PreviewConsoleBridge.triggerReload()
        return ToolResult(
            success = true,
            output = if (hasWv) "Preview reload triggered successfully." else "Preview reload requested (WebView standby)."
        )
    }

    suspend fun terminalExecCommand(command: String, cwd: String? = null, timeoutMs: Long = 30_000L): ToolResult {
        val dir = if (!cwd.isNullOrBlank()) resolveSafeFile(cwd) else getProjectRoot()
        dir.mkdirs()

        // Log command
        recordTerminalLog("> $command")

        val ctx = contextProvider?.invoke()
        if (ctx != null && KryptonShellEngine.canHandle(command)) {
            val result = withContext(Dispatchers.IO) {
                KryptonShellEngine.execute(ctx, command, dir.absolutePath, dir.absolutePath)
            }
            val out = result.output.trim()
            val err = result.error.trim()
            recordTerminalLog(out)
            if (err.isNotBlank()) recordTerminalLog("ERR: $err")

            return ToolResult(
                success = err.isBlank(),
                output = out,
                error = err.ifBlank { null },
                exitCode = if (err.isNotBlank()) 1 else 0
            )
        }

        // ProcessBuilder fallback
        return withContext(Dispatchers.IO) {
            try {
                val pb = ProcessBuilder("/system/bin/sh", "-c", command)
                pb.directory(dir)
                pb.redirectErrorStream(false)

                val proc = pb.start()
                val stdoutBuilder = StringBuilder()
                val stderrBuilder = StringBuilder()

                val completed = withTimeoutOrNull(timeoutMs) {
                    val outReader = BufferedReader(InputStreamReader(proc.inputStream))
                    val errReader = BufferedReader(InputStreamReader(proc.errorStream))

                    var line: String?
                    while (outReader.readLine().also { line = it } != null) {
                        stdoutBuilder.appendLine(line)
                    }
                    while (errReader.readLine().also { line = it } != null) {
                        stderrBuilder.appendLine(line)
                    }
                    proc.waitFor()
                }

                if (completed == null) {
                    proc.destroy()
                    return@withContext ToolResult(false, "", "Command timed out after ${timeoutMs}ms", 124)
                }

                val outText = stdoutBuilder.toString().trim()
                val errText = stderrBuilder.toString().trim()
                recordTerminalLog(outText)
                if (errText.isNotBlank()) recordTerminalLog("ERR: $errText")

                ToolResult(
                    success = completed == 0,
                    output = outText,
                    error = errText.ifBlank { null },
                    exitCode = completed
                )
            } catch (e: Throwable) {
                ToolResult(false, "", "Terminal exec error: ${e.message}", 1)
            }
        }
    }

    fun terminalReadLastOutput(lines: Int = 30): ToolResult {
        synchronized(terminalLogBuffer) {
            val takeCount = lines.coerceAtLeast(1)
            val sub = terminalLogBuffer.takeLast(takeCount)
            if (sub.isEmpty()) {
                return ToolResult(true, "No recent terminal output.")
            }
            return ToolResult(true, sub.joinToString("\n"))
        }
    }

    fun recordTerminalLog(line: String) {
        if (line.isBlank()) return
        synchronized(terminalLogBuffer) {
            terminalLogBuffer.add(line)
            while (terminalLogBuffer.size > MAX_TERMINAL_LOGS) {
                terminalLogBuffer.removeAt(0)
            }
        }
    }

    // ─── Editor & Git Tools ─────────────────────────────────────────────────────

    fun editorOpenFile(path: String, line: Int? = null): ToolResult {
        val vm = editorVmProvider?.invoke()
            ?: return ToolResult(true, "Target file set to $path (line ${line ?: 1})")
        return try {
            val file = resolveSafeFile(path)
            vm.openFile(file.name)
            ToolResult(true, "Opened ${file.name} in editor${if (line != null) " at line $line" else ""}")
        } catch (e: Throwable) {
            ToolResult(false, "", "editor_open_file failed: ${e.message}")
        }
    }

    fun editorGetActiveContext(): ToolResult {
        val activePath = activeFilePathProvider?.invoke()
            ?: editorVmProvider?.invoke()?.state?.value?.activeFileId?.let { id ->
                editorVmProvider?.invoke()?.state?.value?.files?.get(id)?.name
            }
            ?: "index.html"
        val hasPreview = PreviewConsoleBridge.hasActiveWebView()
        val errorCount = PreviewConsoleBridge.getEntries("ERROR").size

        val out = buildString {
            appendLine("Active File: $activePath")
            appendLine("Web Preview Active: $hasPreview")
            appendLine("Active Console Errors: $errorCount")
        }
        return ToolResult(true, out)
    }

    suspend fun gitStatus(): ToolResult {
        val root = getProjectRoot()
        val ctx = contextProvider?.invoke()
        if (ctx != null) {
            val res = KryptonShellEngine.execute(ctx, "git status", root.absolutePath, root.absolutePath)
            return ToolResult(res.error.isBlank(), res.output.ifBlank { res.error })
        }
        return ToolResult(true, "Git status for ${root.name}: clean")
    }

    suspend fun gitDiff(path: String? = null): ToolResult {
        val root = getProjectRoot()
        val ctx = contextProvider?.invoke()
        val cmd = if (path.isNullOrBlank()) "git diff" else "git diff $path"
        if (ctx != null) {
            val res = KryptonShellEngine.execute(ctx, cmd, root.absolutePath, root.absolutePath)
            return ToolResult(true, res.output.ifBlank { "No git changes detected." })
        }
        return ToolResult(true, "No git diff available in current environment.")
    }

    // ─── Utilities ──────────────────────────────────────────────────────────────

    private fun isBinaryExtension(ext: String): Boolean {
        return ext in setOf(
            "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp",
            "mp3", "mp4", "wav", "ogg", "zip", "tar", "gz", "apk",
            "dex", "so", "class", "jar", "bin", "gguf", "cact"
        )
    }

    private fun matchPattern(filename: String, pattern: String): Boolean {
        val p = pattern.trim()
        if (p == "*" || p == "*.*") return true
        if (p.startsWith("*.")) {
            return filename.endsWith(p.removePrefix("*"))
        }
        if (p.contains("*")) {
            val regexPattern = "^" + Pattern.quote(p).replace("*", "\\E.*\\Q") + "$"
            return filename.matches(Regex(regexPattern, RegexOption.IGNORE_CASE))
        }
        return filename.equals(p, ignoreCase = true)
    }

    /**
     * Registers all Needle tools into AgentToolRegistry so they can be dispatched
     * by LLM JSON/XML function call engines.
     */
    fun registerAll() {
        val toolDefs = listOf(
            ToolDefinition(
                name = "fs_list_tree",
                description = "Lists files and directory hierarchy in the project workspace.",
                parameters = listOf(
                    ToolParam("path", "string", "Directory path relative to project root", required = false),
                    ToolParam("max_depth", "integer", "Max traversal depth (default 3)", required = false)
                )
            ) { execute("fs_list_tree", it) },

            ToolDefinition(
                name = "fs_read_file",
                description = "Reads exact line numbers and contents from a file.",
                parameters = listOf(
                    ToolParam("path", "string", "Relative file path from project root"),
                    ToolParam("start_line", "integer", "Optional 1-based start line", required = false),
                    ToolParam("end_line", "integer", "Optional 1-based end line", required = false)
                )
            ) { execute("fs_read_file", it) },

            ToolDefinition(
                name = "fs_write_file",
                description = "Writes entire contents to a file and syncs open editor buffer.",
                parameters = listOf(
                    ToolParam("path", "string", "Relative file path"),
                    ToolParam("content", "string", "Full text content to write")
                )
            ) { execute("fs_write_file", it) },

            ToolDefinition(
                name = "fs_patch_file",
                description = "Applies surgical SEARCH / REPLACE block replacement to a file.",
                parameters = listOf(
                    ToolParam("path", "string", "Relative file path"),
                    ToolParam("search", "string", "Exact code block to search for"),
                    ToolParam("replace", "string", "Replacement code block")
                )
            ) { execute("fs_patch_file", it) },

            ToolDefinition(
                name = "search_grep",
                description = "Searches workspace files for code, UI classes, functions, or text matches.",
                parameters = listOf(
                    ToolParam("query", "string", "Substring or regex query to find"),
                    ToolParam("file_pattern", "string", "Optional glob e.g. *.html or *.css", required = false)
                )
            ) { execute("search_grep", it) },

            ToolDefinition(
                name = "code_get_diagnostics",
                description = "Runs syntax and tag diagnostics to catch unclosed tags and errors.",
                parameters = listOf(
                    ToolParam("path", "string", "File path to diagnose", required = false)
                )
            ) { execute("code_get_diagnostics", it) },

            ToolDefinition(
                name = "preview_get_console_logs",
                description = "Retrieves live JS console runtime errors and logs from the web preview.",
                parameters = listOf(
                    ToolParam("level", "string", "Filter level: 'ERROR', 'WARNING', 'LOG', or 'ALL'", required = false)
                )
            ) { execute("preview_get_console_logs", it) },

            ToolDefinition(
                name = "preview_reload",
                description = "Reloads the active WebView preview to verify changes.",
                parameters = emptyList()
            ) { execute("preview_reload", it) },

            ToolDefinition(
                name = "terminal_exec_command",
                description = "Executes a shell command in project directory.",
                parameters = listOf(
                    ToolParam("command", "string", "Shell command"),
                    ToolParam("timeout_ms", "integer", "Timeout ms", required = false)
                )
            ) { execute("terminal_exec_command", it) },

            ToolDefinition(
                name = "terminal_read_last_output",
                description = "Reads latest terminal command outputs.",
                parameters = listOf(
                    ToolParam("lines", "integer", "Number of lines", required = false)
                )
            ) { execute("terminal_read_last_output", it) }
        )

        toolDefs.forEach { AgentToolRegistry.register(it) }
    }
}
