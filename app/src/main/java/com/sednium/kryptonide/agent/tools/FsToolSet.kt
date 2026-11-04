package com.sednium.kryptonide.agent.tools

import java.io.File

class FsToolSet(private val projectRootDir: () -> File) {

    private fun resolveSafeFile(relativePath: String): File {
        val root = projectRootDir()
        val cleaned = relativePath.trim()
        val kryptonideRoot = com.sednium.kryptonide.utils.StorageUtils.getKryptonideRoot().canonicalFile

        val target = if (cleaned.startsWith("/") || cleaned.startsWith(kryptonideRoot.path)) {
            File(cleaned).canonicalFile
        } else {
            val rel = cleaned.removePrefix("./")
            File(root, rel).canonicalFile
        }

        // Strict Kryptonide storage boundary guard:
        // Must never escape /storage/emulated/0/Kryptonide
        if (!target.path.startsWith(kryptonideRoot.path)) {
            throw SecurityException("Access denied: path '$relativePath' escapes Kryptonide boundary '$kryptonideRoot'")
        }
        return target
    }

    fun registerAll() {
        AgentToolRegistry.register(
            ToolDefinition(
                name = "read_file",
                description = "Read contents of a file in the project with optional 1-based start_line and end_line bounds.",
                parameters = listOf(
                    ToolParam("path", "string", "Relative file path from project root"),
                    ToolParam("start_line", "integer", "Optional 1-based start line (inclusive)", required = false),
                    ToolParam("end_line", "integer", "Optional 1-based end line (inclusive)", required = false)
                )
            ) { args ->
                val path = args["path"]?.toString() ?: return@ToolDefinition ToolResult(false, "", "Missing required 'path'")
                try {
                    val file = resolveSafeFile(path)
                    if (!file.exists()) {
                        return@ToolDefinition ToolResult(false, "", "File not found: $path")
                    }
                    if (file.isDirectory) {
                        return@ToolDefinition ToolResult(false, "", "Target is a directory, use list_dir instead: $path")
                    }

                    val lines = file.readLines()
                    val totalLines = lines.size
                    val start = (args["start_line"] as? Number)?.toInt()?.coerceIn(1, totalLines.coerceAtLeast(1)) ?: 1
                    val end = (args["end_line"] as? Number)?.toInt()?.coerceIn(start, totalLines.coerceAtLeast(start)) ?: totalLines

                    val selected = lines.subList((start - 1).coerceAtLeast(0), end.coerceAtMost(totalLines))
                    val numbered = selected.mapIndexed { idx, line ->
                        "${start + idx}: $line"
                    }.joinToString("\n")

                    ToolResult(
                        success = true,
                        output = numbered,
                        metadata = mapOf("totalLines" to totalLines, "startLine" to start, "endLine" to end)
                    )
                } catch (e: Throwable) {
                    ToolResult(false, "", e.message ?: "Failed reading file")
                }
            }
        )

        AgentToolRegistry.register(
            ToolDefinition(
                name = "write_file",
                description = "Atomically write or overwrite full contents of a file.",
                parameters = listOf(
                    ToolParam("path", "string", "Relative file path from project root"),
                    ToolParam("content", "string", "Complete text content to write")
                )
            ) { args ->
                val path = args["path"]?.toString() ?: return@ToolDefinition ToolResult(false, "", "Missing 'path'")
                val content = args["content"]?.toString() ?: ""
                try {
                    val file = resolveSafeFile(path)
                    file.parentFile?.mkdirs()
                    val temp = File(file.parentFile, ".${file.name}.tmp")
                    temp.writeText(content)
                    if (!temp.renameTo(file)) {
                        // Fallback direct write
                        file.writeText(content)
                        temp.delete()
                    }
                    ToolResult(true, "Successfully wrote ${content.length} characters to $path")
                } catch (e: Throwable) {
                    ToolResult(false, "", e.message ?: "Failed writing file")
                }
            }
        )

        AgentToolRegistry.register(
            ToolDefinition(
                name = "edit_lines",
                description = "Replace a range of lines (1-based, inclusive) with new content.",
                parameters = listOf(
                    ToolParam("path", "string", "Relative file path"),
                    ToolParam("start_line", "integer", "1-based start line number to replace"),
                    ToolParam("end_line", "integer", "1-based end line number to replace"),
                    ToolParam("new_content", "string", "New text replacing the specified line range")
                )
            ) { args ->
                val path = args["path"]?.toString() ?: return@ToolDefinition ToolResult(false, "", "Missing 'path'")
                val start = (args["start_line"] as? Number)?.toInt() ?: return@ToolDefinition ToolResult(false, "", "Missing 'start_line'")
                val end = (args["end_line"] as? Number)?.toInt() ?: return@ToolDefinition ToolResult(false, "", "Missing 'end_line'")
                val newContent = args["new_content"]?.toString() ?: ""

                try {
                    val file = resolveSafeFile(path)
                    if (!file.exists()) return@ToolDefinition ToolResult(false, "", "File not found: $path")

                    val lines = file.readLines().toMutableList()
                    val startIdx = (start - 1).coerceIn(0, lines.size)
                    val endIdx = end.coerceIn(startIdx, lines.size)

                    val newLines = if (newContent.isEmpty()) emptyList() else newContent.lines()
                    // Remove old range
                    for (i in endIdx - 1 downTo startIdx) {
                        lines.removeAt(i)
                    }
                    // Insert new lines
                    lines.addAll(startIdx, newLines)

                    file.writeText(lines.joinToString("\n"))
                    ToolResult(true, "Replaced lines $start..$end in $path (${lines.size} total lines)")
                } catch (e: Throwable) {
                    ToolResult(false, "", e.message ?: "Failed editing lines")
                }
            }
        )

        AgentToolRegistry.register(
            ToolDefinition(
                name = "insert_lines",
                description = "Insert content before a specific 1-based line number.",
                parameters = listOf(
                    ToolParam("path", "string", "Relative file path"),
                    ToolParam("at_line", "integer", "1-based line number where content should be inserted"),
                    ToolParam("content", "string", "Content to insert")
                )
            ) { args ->
                val path = args["path"]?.toString() ?: return@ToolDefinition ToolResult(false, "", "Missing 'path'")
                val atLine = (args["at_line"] as? Number)?.toInt() ?: return@ToolDefinition ToolResult(false, "", "Missing 'at_line'")
                val content = args["content"]?.toString() ?: ""

                try {
                    val file = resolveSafeFile(path)
                    if (!file.exists()) return@ToolDefinition ToolResult(false, "", "File not found: $path")

                    val lines = file.readLines().toMutableList()
                    val insertIdx = (atLine - 1).coerceIn(0, lines.size)
                    val newLines = content.lines()
                    lines.addAll(insertIdx, newLines)

                    file.writeText(lines.joinToString("\n"))
                    ToolResult(true, "Inserted ${newLines.size} lines at line $atLine in $path")
                } catch (e: Throwable) {
                    ToolResult(false, "", e.message ?: "Failed inserting lines")
                }
            }
        )

        AgentToolRegistry.register(
            ToolDefinition(
                name = "patch_file",
                description = "Precise search-and-replace block replacement inside a file.",
                parameters = listOf(
                    ToolParam("path", "string", "Relative file path"),
                    ToolParam("search", "string", "Exact string block to search for"),
                    ToolParam("replace", "string", "Exact replacement string")
                )
            ) { args ->
                val path = args["path"]?.toString() ?: return@ToolDefinition ToolResult(false, "", "Missing 'path'")
                val search = args["search"]?.toString() ?: return@ToolDefinition ToolResult(false, "", "Missing 'search'")
                val replace = args["replace"]?.toString() ?: ""

                try {
                    val file = resolveSafeFile(path)
                    if (!file.exists()) return@ToolDefinition ToolResult(false, "", "File not found: $path")

                    val text = file.readText()
                    if (!text.contains(search)) {
                        return@ToolDefinition ToolResult(false, "", "Target search block not found in $path")
                    }
                    val occurrences = text.split(search).size - 1
                    val patched = text.replace(search, replace)
                    file.writeText(patched)
                    ToolResult(true, "Successfully patched $occurrences occurrence(s) in $path")
                } catch (e: Throwable) {
                    ToolResult(false, "", e.message ?: "Failed patching file")
                }
            }
        )

        AgentToolRegistry.register(
            ToolDefinition(
                name = "list_dir",
                description = "List files and directories under a given relative directory path.",
                parameters = listOf(
                    ToolParam("path", "string", "Relative directory path (e.g. '.' or 'src')", required = false),
                    ToolParam("recursive", "boolean", "Whether to list subdirectories recursively", required = false),
                    ToolParam("max_depth", "integer", "Max depth for recursion (default 3)", required = false)
                )
            ) { args ->
                val path = args["path"]?.toString() ?: "."
                val recursive = (args["recursive"] as? Boolean) ?: false
                val maxDepth = (args["max_depth"] as? Number)?.toInt() ?: 3

                try {
                    val dir = resolveSafeFile(path)
                    if (!dir.exists()) return@ToolDefinition ToolResult(false, "", "Directory not found: $path")
                    if (!dir.isDirectory) return@ToolDefinition ToolResult(false, "", "Path is not a directory: $path")

                    val rootCanonical = projectRootDir().canonicalFile
                    val results = mutableListOf<String>()

                    fun walk(current: File, depth: Int) {
                        if (depth > maxDepth) return
                        val entries = current.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
                        for (entry in entries) {
                            if (entry.name.startsWith(".") && entry.name != ".env") continue
                            if (entry.name == "node_modules" || entry.name == "build" || entry.name == ".git") {
                                results.add("${entry.relativeTo(rootCanonical).path}/ [skipped]")
                                continue
                            }
                            val rel = entry.relativeTo(rootCanonical).path
                            if (entry.isDirectory) {
                                results.add("$rel/")
                                if (recursive) walk(entry, depth + 1)
                            } else {
                                results.add("$rel (${entry.length()} bytes)")
                            }
                        }
                    }

                    walk(dir, 1)
                    ToolResult(true, results.joinToString("\n").ifBlank { "(empty directory)" })
                } catch (e: Throwable) {
                    ToolResult(false, "", e.message ?: "Failed listing directory")
                }
            }
        )
    }
}
