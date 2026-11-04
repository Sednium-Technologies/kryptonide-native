package com.sednium.kryptonide.agent.tools

import java.io.File

class LspToolSet(private val projectRootDir: () -> File) {

    private fun resolveSafeFile(relativePath: String): File {
        val root = projectRootDir()
        val cleaned = relativePath.trim().removePrefix("./").removePrefix("/")
        val target = File(root, cleaned).canonicalFile
        val rootCanonical = root.canonicalFile
        if (!target.path.startsWith(rootCanonical.path)) {
            throw SecurityException("Access denied: path escapes project root")
        }
        return target
    }

    fun registerAll() {
        AgentToolRegistry.register(
            ToolDefinition(
                name = "lsp_get_symbols",
                description = "Extract high-level symbols (classes, functions, interfaces, objects, methods) from a source file.",
                parameters = listOf(
                    ToolParam("file_path", "string", "Relative path to code file")
                )
            ) { args ->
                val path = args["file_path"]?.toString()
                    ?: return@ToolDefinition ToolResult(false, "", "Missing 'file_path'")
                try {
                    val file = resolveSafeFile(path)
                    if (!file.exists()) return@ToolDefinition ToolResult(false, "", "File not found: $path")

                    val lines = file.readLines()
                    val symbols = mutableListOf<String>()

                    val classRegex = Regex("""^\s*(public\s+|private\s+|protected\s+|internal\s+|abstract\s+|data\s+|sealed\s+|open\s+)*(class|interface|object|enum\s+class|struct)\s+([A-Za-z0-9_]+)""")
                    val funRegex = Regex("""^\s*(public\s+|private\s+|protected\s+|internal\s+|suspend\s+|override\s+|static\s+|async\s+)*(fun|def|function)\s+([A-Za-z0-9_]+)""")
                    val varRegex = Regex("""^\s*(val|var|const|let)\s+([A-Za-z0-9_]+)""")

                    lines.forEachIndexed { idx, line ->
                        val lineNum = idx + 1
                        val classMatch = classRegex.find(line)
                        val funMatch = funRegex.find(line)
                        val varMatch = varRegex.find(line)

                        when {
                            classMatch != null -> {
                                val kind = classMatch.groupValues[2]
                                val name = classMatch.groupValues[3]
                                symbols.add("Line $lineNum: [$kind] $name")
                            }
                            funMatch != null -> {
                                val kind = funMatch.groupValues[2]
                                val name = funMatch.groupValues[3]
                                symbols.add("Line $lineNum: [$kind] $name()")
                            }
                            varMatch != null && (line.contains("=") || line.contains(":")) -> {
                                val kind = varMatch.groupValues[1]
                                val name = varMatch.groupValues[2]
                                if (!line.trim().startsWith("//")) {
                                    symbols.add("Line $lineNum: [$kind] $name")
                                }
                            }
                        }
                    }

                    ToolResult(
                        success = true,
                        output = symbols.joinToString("\n").ifBlank { "No top-level symbols found in $path" },
                        metadata = mapOf("symbolCount" to symbols.size)
                    )
                } catch (e: Throwable) {
                    ToolResult(false, "", e.message ?: "Symbol query failed")
                }
            }
        )

        AgentToolRegistry.register(
            ToolDefinition(
                name = "lsp_get_diagnostics",
                description = "Scan source file for structural, bracket, and syntax errors.",
                parameters = listOf(
                    ToolParam("file_path", "string", "Relative path to code file")
                )
            ) { args ->
                val path = args["file_path"]?.toString()
                    ?: return@ToolDefinition ToolResult(false, "", "Missing 'file_path'")
                try {
                    val file = resolveSafeFile(path)
                    if (!file.exists()) return@ToolDefinition ToolResult(false, "", "File not found: $path")

                    val text = file.readText()
                    val diagnostics = mutableListOf<String>()

                    // Bracket balance check
                    var braceCount = 0
                    var parenCount = 0
                    var bracketCount = 0

                    text.lines().forEachIndexed { idx, line ->
                        val lineNum = idx + 1
                        // Simple tokenizer ignoring strings
                        var inStr = false
                        var strChar = ' '
                        for (i in line.indices) {
                            val c = line[i]
                            if (inStr) {
                                if (c == strChar && (i == 0 || line[i - 1] != '\\')) {
                                    inStr = false
                                }
                            } else {
                                if (c == '"' || c == '\'') {
                                    inStr = true
                                    strChar = c
                                } else when (c) {
                                    '{' -> braceCount++
                                    '}' -> {
                                        braceCount--
                                        if (braceCount < 0) diagnostics.add("Line $lineNum: Unmatched closing brace '}'")
                                    }
                                    '(' -> parenCount++
                                    ')' -> {
                                        parenCount--
                                        if (parenCount < 0) diagnostics.add("Line $lineNum: Unmatched closing parenthesis ')'")
                                    }
                                    '[' -> bracketCount++
                                    ']' -> {
                                        bracketCount--
                                        if (bracketCount < 0) diagnostics.add("Line $lineNum: Unmatched closing bracket ']'")
                                    }
                                }
                            }
                        }
                        if (inStr) {
                            diagnostics.add("Line $lineNum: Unterminated string literal ($strChar)")
                        }
                    }

                    if (braceCount > 0) diagnostics.add("End of file: $braceCount unclosed brace(s) '{'")
                    if (parenCount > 0) diagnostics.add("End of file: $parenCount unclosed parenthesis '('")
                    if (bracketCount > 0) diagnostics.add("End of file: $bracketCount unclosed bracket(s) '['")

                    val resultMsg = if (diagnostics.isEmpty()) {
                        "No diagnostic errors found in $path (Brackets and strings balanced)."
                    } else {
                        "Found ${diagnostics.size} diagnostic issue(s):\n" + diagnostics.joinToString("\n")
                    }

                    ToolResult(
                        success = diagnostics.isEmpty(),
                        output = resultMsg,
                        error = if (diagnostics.isNotEmpty()) resultMsg else null,
                        metadata = mapOf("errorCount" to diagnostics.size)
                    )
                } catch (e: Throwable) {
                    ToolResult(false, "", e.message ?: "Diagnostics check failed")
                }
            }
        )
    }
}
