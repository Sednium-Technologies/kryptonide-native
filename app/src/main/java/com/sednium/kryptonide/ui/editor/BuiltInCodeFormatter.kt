package com.sednium.kryptonide.ui.editor

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure-Kotlin native code formatter fallback for HTML, CSS, JSON, JS/TS, Kotlin, Java, and C-family files.
 * Ensures "Format Document" in the Command Palette and Auto-Format on Save always work out-of-the-box
 * on Android devices even when external CLI binaries (prettier, clang-format, black) are not installed.
 */
object BuiltInCodeFormatter {

    fun format(content: String, extOrLang: String): String {
        if (content.isBlank()) return content
        val lang = extOrLang.lowercase().trimStart('.')
        return runCatching {
            when (lang) {
                "json" -> formatJson(content)
                "html", "htm", "xhtml", "xml", "svg" -> formatHtml(content)
                "css", "scss", "less" -> formatBraceCode(content, indentSize = 2)
                "js", "javascript", "ts", "typescript", "jsx", "tsx" -> formatBraceCode(content, indentSize = 2)
                "kt", "kts", "kotlin", "java", "c", "cpp", "cs", "csharp", "go", "rs", "rust" -> formatBraceCode(content, indentSize = 4)
                else -> normalizeIndentation(content, indentSize = 2)
            }
        }.getOrDefault(content)
    }

    private fun formatJson(content: String): String {
        val trimmed = content.trim()
        return when {
            trimmed.startsWith("{") -> JSONObject(trimmed).toString(2) + "\n"
            trimmed.startsWith("[") -> JSONArray(trimmed).toString(2) + "\n"
            else -> content
        }
    }

    private fun formatHtml(content: String): String {
        val lines = content.replace("\r\n", "\n").lines()
        val out = StringBuilder()
        var indent = 0
        var inPreOrScriptOrStyle = false

        val voidTags = SpckCompletionEngine.VOID_HTML_TAGS

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                out.append('\n')
                continue
            }

            if (inPreOrScriptOrStyle) {
                if (trimmed.startsWith("</script", ignoreCase = true) ||
                    trimmed.startsWith("</style", ignoreCase = true) ||
                    trimmed.startsWith("</pre", ignoreCase = true)
                ) {
                    inPreOrScriptOrStyle = false
                    indent = (indent - 1).coerceAtLeast(0)
                }
                out.append("  ".repeat(indent)).append(trimmed).append('\n')
                continue
            }

            // Decrease indent before line if it starts with a closing tag `</tag>`
            val startsWithClosing = Regex("""^</[a-zA-Z0-9:-]+""").containsMatchIn(trimmed)
            if (startsWithClosing) {
                indent = (indent - 1).coerceAtLeast(0)
            }

            out.append("  ".repeat(indent)).append(trimmed).append('\n')

            // Compute net open tags on this line
            var netChange = 0
            val tagRegex = Regex("""<(/?)([!a-zA-Z0-9:-]+)([^>]*?)(/?)>""")
            for (match in tagRegex.findAll(trimmed)) {
                val isClose = match.groupValues[1] == "/"
                val tagName = match.groupValues[2].lowercase()
                val isSelfClose = match.groupValues[4] == "/"
                if (tagName.startsWith("!") || tagName in voidTags || isSelfClose) continue
                if (isClose) {
                    netChange--
                } else {
                    netChange++
                    if (tagName in setOf("script", "style", "pre") && !trimmed.contains("</$tagName", ignoreCase = true)) {
                        inPreOrScriptOrStyle = true
                    }
                }
            }

            if (startsWithClosing) {
                // Already decremented 1 for the leading closing tag
                netChange += 1
            }
            indent = (indent + netChange).coerceAtLeast(0)
        }

        return out.toString().trimEnd() + "\n"
    }

    private fun formatBraceCode(content: String, indentSize: Int): String {
        val step = " ".repeat(indentSize)
        val lines = content.replace("\r\n", "\n").lines()
        val out = StringBuilder()
        var indent = 0

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                out.append('\n')
                continue
            }

            val leadingCloses = trimmed.takeWhile { it == '}' || it == ')' || it == ']' }.length
            if (leadingCloses > 0) {
                indent = (indent - leadingCloses).coerceAtLeast(0)
            }

            out.append(step.repeat(indent)).append(trimmed).append('\n')

            var netBraces = 0
            var inString = false
            var quoteChar = '"'
            var i = 0
            while (i < trimmed.length) {
                val c = trimmed[i]
                if (inString) {
                    if (c == '\\' && i + 1 < trimmed.length) {
                        i += 2
                        continue
                    }
                    if (c == quoteChar) inString = false
                } else {
                    if (c == '/' && i + 1 < trimmed.length && trimmed[i + 1] == '/') break
                    if (c == '"' || c == '\'' || c == '`') {
                        inString = true
                        quoteChar = c
                    } else if (c == '{' || c == '[' || c == '(') {
                        netBraces++
                    } else if (c == '}' || c == ']' || c == ')') {
                        netBraces--
                    }
                }
                i++
            }

            indent = (indent + netBraces + leadingCloses).coerceAtLeast(0)
        }

        return out.toString().trimEnd() + "\n"
    }

    private fun normalizeIndentation(content: String, indentSize: Int): String {
        val spaces = " ".repeat(indentSize)
        return content
            .replace("\r\n", "\n")
            .lines()
            .joinToString("\n") { it.replace("\t", spaces).trimEnd() }
            .trimEnd() + "\n"
    }
}
