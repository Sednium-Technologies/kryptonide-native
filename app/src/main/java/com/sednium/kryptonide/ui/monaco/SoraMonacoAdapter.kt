package com.sednium.kryptonide.ui.monaco

import android.graphics.Color
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.SimpleSnippetCompletionItem
import io.github.rosemoe.sora.lang.completion.SnippetDescription
import io.github.rosemoe.sora.lang.completion.snippet.parser.CodeSnippetParser
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.json.JSONArray
import org.json.JSONObject

/**
 * Translates Monaco and VS Code theme/completion structures into Sora Editor native objects.
 */
object SoraMonacoAdapter {

    /**
     * Strips single-line (`// ...`) and block (`/* ... */`) comments and trailing commas
     * from JSONC strings while strictly preserving quoted string literals.
     */
    fun cleanJson(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        val len = raw.length
        var inString = false
        var stringChar = '"'
        while (i < len) {
            val c = raw[i]
            if (inString) {
                sb.append(c)
                if (c == '\\' && i + 1 < len) {
                    sb.append(raw[i + 1])
                    i += 2
                    continue
                }
                if (c == stringChar) {
                    inString = false
                }
                i++
            } else {
                if (c == '"' || c == '\'') {
                    inString = true
                    stringChar = c
                    sb.append(c)
                    i++
                } else if (c == '/' && i + 1 < len && raw[i + 1] == '/') {
                    i += 2
                    while (i < len && raw[i] != '\n' && raw[i] != '\r') {
                        i++
                    }
                } else if (c == '/' && i + 1 < len && raw[i + 1] == '*') {
                    i += 2
                    while (i + 1 < len && !(raw[i] == '*' && raw[i + 1] == '/')) {
                        i++
                    }
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString().replace(Regex(",\\s*([}\\]])"), "$1")
    }

    /**
     * Converts a Monaco `editor.defineTheme(...)` or VS Code / TextMate theme JSON definition
     * into a fully-configured Sora [EditorColorScheme].
     */
    fun monacoThemeToEditorColorScheme(themeJson: String): EditorColorScheme {
        val scheme = EditorColorScheme()
        return runCatching {
            val cleaned = cleanJson(themeJson)
            val json = JSONObject(cleaned)
            val colors = json.optJSONObject("colors")

            if (colors != null) {
                val bg = colors.optString("editor.background").takeIf { it.isNotBlank() }?.let { hexColor(it) }
                if (bg != null) {
                    scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, bg)
                    scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, bg)
                    scheme.setColor(EditorColorScheme.LINE_NUMBER_PANEL, bg)
                }

                val gutterBg = colors.optString("editorGutter.background").takeIf { it.isNotBlank() }?.let { hexColor(it) }
                if (gutterBg != null) {
                    scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, gutterBg)
                    scheme.setColor(EditorColorScheme.LINE_NUMBER_PANEL, gutterBg)
                }

                val fg = colors.optString("editor.foreground").takeIf { it.isNotBlank() }?.let { hexColor(it) }
                if (fg != null) {
                    scheme.setColor(EditorColorScheme.TEXT_NORMAL, fg)
                    scheme.setColor(EditorColorScheme.SIGNATURE_TEXT_NORMAL, fg)
                }

                colors.optString("editorCursor.foreground").takeIf { it.isNotBlank() }?.let { hexColor(it) }?.let {
                    scheme.setColor(EditorColorScheme.SELECTION_INSERT, it)
                    scheme.setColor(EditorColorScheme.SELECTION_HANDLE, it)
                }

                colors.optString("editor.lineHighlightBackground").takeIf { it.isNotBlank() }?.let { hexColor(it) }?.let {
                    scheme.setColor(EditorColorScheme.CURRENT_LINE, it)
                }

                colors.optString("editorLineNumber.foreground").takeIf { it.isNotBlank() }?.let { hexColor(it) }?.let {
                    scheme.setColor(EditorColorScheme.LINE_NUMBER, it)
                    scheme.setColor(EditorColorScheme.LINE_NUMBER_PANEL_TEXT, it)
                }

                colors.optString("editorLineNumber.activeForeground").takeIf { it.isNotBlank() }?.let { hexColor(it) }?.let {
                    scheme.setColor(EditorColorScheme.LINE_NUMBER_CURRENT, it)
                }

                colors.optString("editor.selectionBackground").takeIf { it.isNotBlank() }?.let { hexColor(it) }?.let {
                    scheme.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, it)
                }

                colors.optString("editor.selectionForeground").takeIf { it.isNotBlank() }?.let { hexColor(it) }?.let {
                    scheme.setColor(EditorColorScheme.TEXT_SELECTED, it)
                }

                colors.optString("editorIndentGuide.background").takeIf { it.isNotBlank() }?.let { hexColor(it) }?.let {
                    scheme.setColor(EditorColorScheme.BLOCK_LINE, it)
                    scheme.setColor(EditorColorScheme.SIDE_BLOCK_LINE, it)
                }

                colors.optString("editorIndentGuide.activeBackground").takeIf { it.isNotBlank() }?.let { hexColor(it) }?.let {
                    scheme.setColor(EditorColorScheme.BLOCK_LINE_CURRENT, it)
                }

                val widgetBg = (colors.optString("editorSuggestWidget.background").takeIf { it.isNotBlank() }
                    ?: colors.optString("editorWidget.background").takeIf { it.isNotBlank() })?.let { hexColor(it) }
                if (widgetBg != null) {
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_BACKGROUND, widgetBg)
                    scheme.setColor(EditorColorScheme.DIAGNOSTIC_TOOLTIP_BACKGROUND, widgetBg)
                }

                val widgetFg = (colors.optString("editorSuggestWidget.foreground").takeIf { it.isNotBlank() }
                    ?: colors.optString("editorWidget.foreground").takeIf { it.isNotBlank() })?.let { hexColor(it) }
                if (widgetFg != null) {
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, widgetFg)
                    scheme.setColor(EditorColorScheme.DIAGNOSTIC_TOOLTIP_BRIEF_MSG, widgetFg)
                }

                colors.optString("editorSuggestWidget.selectedBackground").takeIf { it.isNotBlank() }?.let { hexColor(it) }?.let {
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT, it)
                }

                colors.optString("editorSuggestWidget.border").takeIf { it.isNotBlank() }?.let { hexColor(it) }?.let {
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_CORNER, it)
                }

                val matchBg = (colors.optString("editor.findMatchHighlightBackground").takeIf { it.isNotBlank() }
                    ?: colors.optString("editor.findMatchBackground").takeIf { it.isNotBlank() })?.let { hexColor(it) }
                if (matchBg != null) {
                    scheme.setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, matchBg)
                }

                colors.optString("scrollbarSlider.background").takeIf { it.isNotBlank() }?.let { hexColor(it) }?.let {
                    scheme.setColor(EditorColorScheme.SCROLL_BAR_THUMB, it)
                }
                colors.optString("scrollbarSlider.activeBackground").takeIf { it.isNotBlank() }?.let { hexColor(it) }?.let {
                    scheme.setColor(EditorColorScheme.SCROLL_BAR_THUMB_PRESSED, it)
                }
                scheme.setColor(EditorColorScheme.SCROLL_BAR_TRACK, android.graphics.Color.TRANSPARENT)
            }

            // 1. Monaco rules array: [{ token: "...", foreground: "..." }]
            val rules = json.optJSONArray("rules")
            if (rules != null) {
                for (i in 0 until rules.length()) {
                    val rule = rules.optJSONObject(i) ?: continue
                    val colorInt = rule.optString("foreground").takeIf { it.isNotBlank() }?.let { hexColor(it) } ?: continue
                    val token = rule.optString("token").lowercase()
                    when {
                        token.contains("comment") -> scheme.setColor(EditorColorScheme.COMMENT, colorInt)
                        token.contains("keyword") -> scheme.setColor(EditorColorScheme.KEYWORD, colorInt)
                        token.contains("string") || token.contains("number") || token.contains("regexp") -> {
                            scheme.setColor(EditorColorScheme.LITERAL, colorInt)
                            scheme.setColor(EditorColorScheme.ATTRIBUTE_VALUE, colorInt)
                        }
                        token.contains("function") -> scheme.setColor(EditorColorScheme.FUNCTION_NAME, colorInt)
                        token.contains("tag") -> scheme.setColor(EditorColorScheme.HTML_TAG, colorInt)
                        token.contains("attribute") -> scheme.setColor(EditorColorScheme.ATTRIBUTE_NAME, colorInt)
                        token.contains("operator") || token.contains("delimiter") -> scheme.setColor(EditorColorScheme.OPERATOR, colorInt)
                        token.contains("type") || token.contains("variable") -> {
                            scheme.setColor(EditorColorScheme.IDENTIFIER_NAME, colorInt)
                            scheme.setColor(EditorColorScheme.IDENTIFIER_VAR, colorInt)
                        }
                    }
                }
            }

            // 2. VS Code / TextMate tokenColors array: [{ scope: "..." or [...], settings: { foreground: "..." } }]
            val tokenColors = json.optJSONArray("tokenColors")
            if (tokenColors != null) {
                for (i in 0 until tokenColors.length()) {
                    val entry = tokenColors.optJSONObject(i) ?: continue
                    val settings = entry.optJSONObject("settings") ?: continue
                    val colorInt = settings.optString("foreground").takeIf { it.isNotBlank() }?.let { hexColor(it) } ?: continue

                    val scopes = mutableListOf<String>()
                    val scopeObj = entry.opt("scope")
                    when (scopeObj) {
                        is String -> scopes.addAll(scopeObj.split(",").map { it.trim().lowercase() })
                        is JSONArray -> {
                            for (j in 0 until scopeObj.length()) {
                                scopes.add(scopeObj.optString(j).trim().lowercase())
                            }
                        }
                    }

                    for (scope in scopes) {
                        when {
                            scope.contains("comment") -> scheme.setColor(EditorColorScheme.COMMENT, colorInt)
                            scope.contains("keyword") || scope.contains("storage.type") || scope.contains("storage.modifier") -> {
                                scheme.setColor(EditorColorScheme.KEYWORD, colorInt)
                            }
                            scope.contains("string") || scope.contains("constant.numeric") || scope.contains("number") || scope.contains("constant.language") -> {
                                scheme.setColor(EditorColorScheme.LITERAL, colorInt)
                                scheme.setColor(EditorColorScheme.ATTRIBUTE_VALUE, colorInt)
                            }
                            scope.contains("entity.name.function") || scope.contains("support.function") -> {
                                scheme.setColor(EditorColorScheme.FUNCTION_NAME, colorInt)
                            }
                            scope.contains("entity.name.tag") || scope == "tag" || scope.contains("meta.tag") -> {
                                scheme.setColor(EditorColorScheme.HTML_TAG, colorInt)
                            }
                            scope.contains("entity.other.attribute-name") || scope.contains("attribute-name") -> {
                                scheme.setColor(EditorColorScheme.ATTRIBUTE_NAME, colorInt)
                            }
                            scope.contains("keyword.operator") || scope.contains("operator") || scope.contains("punctuation.separator") -> {
                                scheme.setColor(EditorColorScheme.OPERATOR, colorInt)
                            }
                            scope.contains("variable") || scope.contains("entity.name.type") || scope.contains("support.type") -> {
                                scheme.setColor(EditorColorScheme.IDENTIFIER_NAME, colorInt)
                                scheme.setColor(EditorColorScheme.IDENTIFIER_VAR, colorInt)
                            }
                            scope.contains("annotation") -> {
                                scheme.setColor(EditorColorScheme.ANNOTATION, colorInt)
                            }
                        }
                    }
                }
            }

            scheme
        }.getOrDefault(scheme)
    }

    /**
     * Converts a [MonacoCompletionItem] into a Sora [CompletionItem], enabling
     * native tab-stop snippet expansion if the item contains placeholders.
     */
    fun monacoCompletionToSoraItem(prefixLength: Int, item: MonacoCompletionItem): CompletionItem {
        val label = item.label
        val detail = item.detail.ifBlank { "Monaco" }
        val insertText = item.insertText.ifBlank { label }
        val isSnippet = item.isSnippet || item.kind == 27 || insertText.contains("$")

        if (isSnippet) {
            runCatching {
                val parsed = CodeSnippetParser.parse(insertText)
                val desc = SnippetDescription(prefixLength, parsed, true)
                return SimpleSnippetCompletionItem(label, detail, desc)
            }
        }

        return object : CompletionItem(label, detail) {
            override fun performCompletion(editor: CodeEditor, text: Content, line: Int, column: Int) {
                if (prefixLength > 0 && column - prefixLength >= 0) {
                    text.delete(line, column - prefixLength, line, column)
                    text.insert(line, column - prefixLength, insertText)
                } else {
                    text.insert(line, column, insertText)
                }
            }
        }
    }

    /**
     * Applies a list of Monaco range edits to Sora Editor's native [Content] buffer.
     */
    fun applyMonacoEdits(editor: CodeEditor, edits: List<MonacoTextEdit>) {
        if (edits.isEmpty()) return
        val text = editor.text

        val sortedEdits = edits.sortedWith(
            compareByDescending<MonacoTextEdit> { it.range.startLineNumber }
                .thenByDescending { it.range.startColumn }
        )

        for (edit in sortedEdits) {
            runCatching {
                val startLine = (edit.range.startLineNumber - 1).coerceIn(0, (text.lineCount - 1).coerceAtLeast(0))
                val lineLength = text.getColumnCount(startLine)
                val startCol = (edit.range.startColumn - 1).coerceIn(0, lineLength)

                val endLine = (edit.range.endLineNumber - 1).coerceIn(0, (text.lineCount - 1).coerceAtLeast(0))
                val endLineLength = text.getColumnCount(endLine)
                val endCol = (edit.range.endColumn - 1).coerceIn(0, endLineLength)

                text.delete(startLine, startCol, endLine, endCol)
                text.insert(startLine, startCol, edit.text)
            }
        }
    }

    fun hexColor(colorStr: String): Int? {
        val s = colorStr.trim()
        val hex = if (s.startsWith("#")) s.substring(1) else s
        return runCatching {
            when (hex.length) {
                3 -> {
                    val r = hex[0]; val g = hex[1]; val b = hex[2]
                    Color.parseColor("#FF$r$r$g$g$b$b")
                }
                4 -> {
                    val r = hex[0]; val g = hex[1]; val b = hex[2]; val a = hex[3]
                    Color.parseColor("#$a$a$r$r$g$g$b$b")
                }
                6 -> Color.parseColor("#FF$hex")
                8 -> {
                    // VS Code / TextMate themes use RRGGBBAA. Android Color.parseColor expects AARRGGBB.
                    val rr = hex.substring(0, 2)
                    val gg = hex.substring(2, 4)
                    val bb = hex.substring(4, 6)
                    val aa = hex.substring(6, 8)
                    Color.parseColor("#$aa$rr$gg$bb")
                }
                else -> null
            }
        }.getOrNull()
    }
}
