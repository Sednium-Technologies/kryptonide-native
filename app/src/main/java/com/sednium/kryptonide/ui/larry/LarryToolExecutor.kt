package com.sednium.kryptonide.ui.larry

import com.sednium.kryptonide.data.repository.ProjectRepository
import java.io.File
import kotlin.math.max
import kotlin.math.min

class LarryToolExecutor(private val rootDir: File) {

    private fun resolveFile(path: String): File {
        return File(rootDir, path)
    }

    fun readFile(path: String, startLine: Int? = null, endLine: Int? = null): String {
        val file = resolveFile(path)
        if (!file.exists()) throw Exception("File not found: ${file.absolutePath}")
        
        val lines = file.readLines()
        val start = startLine?.let { max(1, it) } ?: 1
        val end = endLine?.let { min(lines.size, it) } ?: lines.size
        
        val numbered = lines.subList(start - 1, end).mapIndexed { i, line ->
            "${start + i}: $line"
        }.joinToString("\n")
        
        return "File: $path (${lines.size} lines total, showing ${start}-${end})\n$numbered"
    }

    fun editLines(path: String, startLine: Int, endLine: Int, newContent: String): String {
        val file = resolveFile(path)
        if (!file.exists()) throw Exception("File not found: ${file.absolutePath}")
        
        val lines = file.readLines()
        if (startLine < 1 || startLine > lines.size) throw Exception("start_line out of bounds")
        val fixedEndLine = min(endLine, lines.size)
        if (fixedEndLine < startLine) throw Exception("end_line cannot be less than start_line")
        
        val newLines = if (newContent.isEmpty()) emptyList() else newContent.split("\n")
        
        val before = lines.subList(0, startLine - 1)
        val after = lines.subList(fixedEndLine, lines.size)
        val result = before + newLines + after
        
        file.writeText(result.joinToString("\n"))
        
        return "Success: Replaced lines $startLine-$fixedEndLine (${fixedEndLine - startLine + 1} removed, ${newLines.size} inserted). File now has ${result.size} lines."
    }

    fun insertLines(path: String, atLine: Int, content: String): String {
        val file = resolveFile(path)
        if (!file.exists()) throw Exception("File not found: ${file.absolutePath}")
        
        val lines = file.readLines().toMutableList()
        val insertIdx = min(max(atLine - 1, 0), lines.size)
        val newLines = content.split("\n")
        lines.addAll(insertIdx, newLines)
        
        file.writeText(lines.joinToString("\n"))
        return "Success: Inserted ${newLines.size} lines at line $atLine. File now has ${lines.size} lines."
    }

    fun patchFile(path: String, searchString: String, replaceString: String): String {
        val file = resolveFile(path)
        if (!file.exists()) throw Exception("File not found: ${file.absolutePath}")
        
        val content = file.readText()
        if (!content.contains(searchString)) return "Error: Search string not found."
        
        // replace first occurrence
        val newContent = content.replaceFirst(searchString, replaceString)
        file.writeText(newContent)
        return "Success: File patched."
    }

    fun createDirectory(path: String): String {
        val dir = resolveFile(path)
        if (dir.exists()) return "Directory already exists: $path"
        if (dir.mkdirs()) return "Success: Directory created at $path"
        throw Exception("Failed to create directory at $path")
    }

    fun writeNewFile(path: String, content: String): String {
        val file = resolveFile(path)
        if (file.exists()) throw Exception("File already exists: $path. Use edit_lines instead.")
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "Success: File created at $path"
    }

}
