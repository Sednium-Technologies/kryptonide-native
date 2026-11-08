package com.sednium.kryptonide.ui.editor

import android.content.Context
import com.sednium.kryptonide.ui.lsp.LspManager
import io.github.rosemoe.sora.event.BuildEditorInfoEvent
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.KeyBindingEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.event.TextSizeChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.java.JavaLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import com.sednium.kryptonide.ui.monaco.MonacoRuntime
import com.sednium.kryptonide.ui.monaco.SoraMonacoAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Native Sora Editor host — the code-editing surface. Real Android View,
 * real touch selection handles, real long-press menus, no WebView
 * keyboard/selection bugs.
 *
 * Covers the core loop — open a file, type, get content back, save —
 * plus undo/redo, word wrap, font size, and cursor navigation.
 *
 * Syntax highlighting: `language-textmate` (which would've given proper
 * per-language grammars) hits a D8 "Record desugaring" dexing bug specific
 * to AGP 9.1.1 and had to be dropped — see app/build.gradle.kts. For now,
 * C-style languages (Kotlin/Java/JS/TS/C/C++/C#/Go/Rust) get Sora's real
 * JavaLanguage tokenizer, which is a reasonable approximation for
 * brace/keyword/string highlighting even though it's not grammar-accurate
 * for non-Java syntax. Everything else (Python, JSON, HTML, CSS, etc.) gets
 * no highlighting rather than incorrect highlighting from a mismatched
 * tokenizer. Revisit once AGP patches the dexing bug or another grammar
 * path is found.
 *
 * Formatter and theme integration are NOT wired to real UI yet (see
 * FormatterRunner and BuiltInThemes) — that remains a follow-up. LSP
 * completion is wired via [KryptonLspLanguage]; undo/redo, word wrap,
 * font size, and cursor navigation are wired directly below.
 */
class SoraEditorHost(
    private val context: Context,
    private val scope: CoroutineScope,
    val onContentChanged: (content: String) -> Unit = {},
    val onSaveTriggered: (content: String) -> Unit = {},
    val onCursorChanged: (line: Int, col: Int) -> Unit = { _, _ -> },
    val onFileLoaded: (language: String, filename: String) -> Unit = { _, _ -> },
    val onReady: () -> Unit = {},
    var onFontSizeChanged: (Float) -> Unit = {},
    private val lspManager: LspManager? = null,
    private val uriProvider: (() -> String)? = null,
) {
    private lateinit var editor: CodeEditor
    private var currentLanguageId: String = "text"
    private var saveDebounceJob: kotlinx.coroutines.Job? = null
    private var lspChangeDebounceJob: kotlinx.coroutines.Job? = null
    private var pendingLoad: Triple<String, String, String>? = null
    private var currentFontSizeSp: Float = 14f
    private var lastReportedFontSizeSp: Int = 14
    private var activeColorScheme: EditorColorScheme? = null
    private var pendingThemeAction: (() -> Unit)? = null
    private var isLoadingFile = false
    private var isAutoClosingTag = false
    var isInternalEdit: Boolean = false
        private set

    var keymapPreset: String = KeymapManager.PRESET_VSCODE
    var onSaveShortcut: (() -> Unit)? = null
    var onFindShortcut: (() -> Unit)? = null

    fun hidePopupsAndClearFocus() {
        if (!::editor.isInitialized) return
        runCatching {
            editor.getComponent(io.github.rosemoe.sora.widget.component.EditorAutoCompletion::class.java)?.hide()
        }
        runCatching {
            editor.clearFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(editor.windowToken, 0)
        }
    }

    fun createView(ctx: Context): CodeEditor {
        if (::editor.isInitialized) {
            (editor.parent as? android.view.ViewGroup)?.removeView(editor)
            return editor
        }

        val monoTypeface = runCatching {
            android.graphics.Typeface.createFromAsset(ctx.assets, "fonts/jetbrainsmono_regular.ttf")
        }.getOrDefault(android.graphics.Typeface.MONOSPACE)

        editor = CodeEditor(ctx).apply {
            isWordwrap = false
            setTextSize(currentFontSizeSp)
            typefaceText = monoTypeface
            typefaceLineNumber = monoTypeface
            setLigatureEnabled(false)
            setTextLetterSpacing(0f)
            setTextScaleX(1f)
            tabWidth = 4
            isBlockLineEnabled = true
            setHighlightCurrentBlock(true)
            setBlockLineWidth(1.5f)
            props.cancelCompletionNs = 0L
            props.autoCompletionOnComposing = false
            props.disallowSuggestions = true
            props.trackComposingTextOnCommit = false
            props.minimizeComposingTextUpdate = false
            props.cacheRenderNodeForLongLines = false

            // Sleek, slim modern scrollbars
            setScrollBarEnabled(true)
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = true
            setVerticalScrollbarTrackDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setHorizontalScrollbarTrackDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setVerticalScrollbarThumbDrawable(SleekScrollbarDrawable(this, isVertical = true))
            setHorizontalScrollbarThumbDrawable(SleekScrollbarDrawable(this, isVertical = false))

            // Standard code editor input type without composing spans or URI variation quirks.
            // TYPE_TEXT_VARIATION_URI causes soft keyboards (like Gboard) to enter URL mode, which segments words at dots and slashes,
            // corrupting cursor column offsets and causing edits to happen to the left of the caret.
            runCatching {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }.onFailure { e ->
                android.util.Log.e("SoraEditorHost", "Failed to set inputType: ${e.message}", e)
            }

            subscribeEvent(BuildEditorInfoEvent::class.java) { event, _ ->
                event.editorInfo.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                event.editorInfo.imeOptions = event.editorInfo.imeOptions or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            }
            activeColorScheme?.let { colorScheme = it } ?: run {
                colorScheme = EditorColorScheme()
            }

            subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
                if (isLoadingFile) return@subscribeEvent
                val content = text.toString()
                isInternalEdit = true
                try {
                    onContentChanged(content)
                } finally {
                    isInternalEdit = false
                }

                // Spck-style auto-close HTML tag on '>', smart block-split on Enter, & hide completion on statement-ending punctuation
                if (!isAutoClosingTag && event.action == ContentChangeEvent.ACTION_INSERT) {
                    val inserted = event.changedText.toString()
                    val lang = currentLanguageId.lowercase()
                    val editLine = event.changeEnd.line
                    val editCol = event.changeEnd.column

                    if (inserted == ">" && lang in setOf("html", "htm", "xhtml", "xml", "svg", "jsx", "tsx")) {
                        val lineStr = runCatching { text.getLineString(editLine) }.getOrDefault("")
                        val before = lineStr.substring(0, editCol.coerceAtMost(lineStr.length))
                        val after = lineStr.substring(editCol.coerceAtMost(lineStr.length))

                        if (after.startsWith(">")) {
                            // Step over existing '>' instead of producing '>>'
                            post {
                                if (isAutoClosingTag) return@post
                                isAutoClosingTag = true
                                try {
                                    if (editCol < text.getColumnCount(editLine) && text.getLineString(editLine)[editCol] == '>') {
                                        text.delete(editLine, editCol, editLine, editCol + 1)
                                    }
                                    getComponent(io.github.rosemoe.sora.widget.component.EditorAutoCompletion::class.java)?.hide()
                                } catch (_: Exception) {
                                } finally {
                                    isAutoClosingTag = false
                                }
                            }
                        } else {
                            val openTagMatch = Regex("""<([a-zA-Z][a-zA-Z0-9:-]*)(?:\s[^<>]*)?>$""").find(before)
                            if (openTagMatch != null && !before.endsWith("/>")) {
                                val tagName = openTagMatch.groupValues[1]
                                if (tagName.lowercase() !in SpckCompletionEngine.VOID_HTML_TAGS &&
                                    !after.trimStart().startsWith("</$tagName", ignoreCase = true)
                                ) {
                                    post {
                                        if (isAutoClosingTag) return@post
                                        isAutoClosingTag = true
                                        try {
                                            val curPos = cursor.left()
                                            if (curPos.line == editLine && curPos.column == editCol) {
                                                text.insert(editLine, editCol, "</$tagName>")
                                                setSelection(editLine, editCol)
                                            }
                                            getComponent(io.github.rosemoe.sora.widget.component.EditorAutoCompletion::class.java)?.hide()
                                        } catch (_: Exception) {
                                        } finally {
                                            isAutoClosingTag = false
                                        }
                                    }
                                } else {
                                    post {
                                        runCatching {
                                            getComponent(io.github.rosemoe.sora.widget.component.EditorAutoCompletion::class.java)?.hide()
                                        }
                                    }
                                }
                            } else {
                                post {
                                    runCatching {
                                        getComponent(io.github.rosemoe.sora.widget.component.EditorAutoCompletion::class.java)?.hide()
                                    }
                                }
                            }
                        }
                    } else if (inserted.startsWith("\n") && inserted.all { it == '\n' || it == ' ' || it == '\t' }) {
                        // Smart Enter between `<tag>|</tag>` or `{|}` / `(|)` / `[|]`
                        val prevLineIdx = event.changeStart.line
                        if (editLine == prevLineIdx + 1) {
                            val prevLineStr = runCatching { text.getLineString(prevLineIdx) }.getOrDefault("")
                            val currLineStr = runCatching { text.getLineString(editLine) }.getOrDefault("")
                            val baseIndent = prevLineStr.takeWhile { it == ' ' || it == '\t' }
                            val prevTrimmed = prevLineStr.trimEnd()
                            val afterCursor = currLineStr.substring(editCol.coerceAtMost(currLineStr.length)).trimStart()

                            val isBetweenMatchingBraces =
                                (prevTrimmed.endsWith("{") && afterCursor.startsWith("}")) ||
                                (prevTrimmed.endsWith("(") && afterCursor.startsWith(")")) ||
                                (prevTrimmed.endsWith("[") && afterCursor.startsWith("]"))

                            val openTagOnPrev = Regex("""<([a-zA-Z][a-zA-Z0-9:-]*)(?:\s[^<>]*)?>$""").find(prevTrimmed)
                            val isBetweenHtmlTags = openTagOnPrev != null &&
                                !prevTrimmed.endsWith("/>") &&
                                afterCursor.startsWith("</${openTagOnPrev.groupValues[1]}", ignoreCase = true)

                            if (isBetweenMatchingBraces || isBetweenHtmlTags) {
                                post {
                                    if (isAutoClosingTag) return@post
                                    isAutoClosingTag = true
                                    try {
                                        val curPos = cursor.left()
                                        if (curPos.line == editLine && curPos.column == editCol) {
                                            text.insert(editLine, editCol, "\n$baseIndent")
                                            setSelection(editLine, editCol)
                                        }
                                        getComponent(io.github.rosemoe.sora.widget.component.EditorAutoCompletion::class.java)?.hide()
                                    } catch (_: Exception) {
                                    } finally {
                                        isAutoClosingTag = false
                                    }
                                }
                            } else {
                                post {
                                    runCatching {
                                        getComponent(io.github.rosemoe.sora.widget.component.EditorAutoCompletion::class.java)?.hide()
                                    }
                                }
                            }
                        } else {
                            post {
                                runCatching {
                                    getComponent(io.github.rosemoe.sora.widget.component.EditorAutoCompletion::class.java)?.hide()
                                }
                            }
                        }
                    } else if (inserted in setOf(";", "}", ")", "]")) {
                        post {
                            runCatching {
                                getComponent(io.github.rosemoe.sora.widget.component.EditorAutoCompletion::class.java)?.hide()
                            }
                        }
                    }
                }

                saveDebounceJob?.cancel()
                saveDebounceJob = scope.launch(Dispatchers.Main) {
                    kotlinx.coroutines.delay(1500)
                    onSaveTriggered(content)
                }
                val uri = uriProvider?.invoke() ?: "inmemory://model/1"
                MonacoRuntime.syncBuffer(uri, currentLanguageId, content)

                val lm = lspManager
                val uriFn = uriProvider
                if (lm != null && uriFn != null) {
                    lspChangeDebounceJob?.cancel()
                    lspChangeDebounceJob = scope.launch(Dispatchers.Main) {
                        kotlinx.coroutines.delay(300)
                        runCatching {
                            lm.didChange(currentLanguageId, uriFn(), content, System.currentTimeMillis().toInt())
                        }.onFailure { e ->
                            android.util.Log.e("SoraEditorHost", "lsp didChange failed: ${e.message}", e)
                        }
                    }
                }
            }
            subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
                onCursorChanged(event.left.line + 1, event.left.column + 1)
            }
            subscribeEvent(KeyBindingEvent::class.java) { event, _ ->
                val handled = KeymapManager.handleKeyBinding(
                    event = event,
                    preset = keymapPreset,
                    actions = KeymapActions(
                        onSave = { onSaveShortcut?.invoke() },
                        onFind = { onFindShortcut?.invoke() ?: beginSearch() },
                        onUndo = { undo() },
                        onRedo = { redo() },
                        onDuplicateLine = { duplicateLine() },
                        onDeleteLine = { deleteLine() },
                        onToggleComment = { toggleLineComment() }
                    )
                )
                if (handled) {
                    event.intercept()
                    event.result(true)
                }
            }
            subscribeEvent(TextSizeChangeEvent::class.java) { event, _ ->
                val density = ctx.resources.displayMetrics.scaledDensity
                val sp = if (density > 0f) event.newTextSize / density else event.newTextSize
                val clamped = sp.coerceIn(10f, 32f)
                currentFontSizeSp = clamped
                val intSp = Math.round(clamped)
                if (intSp != lastReportedFontSizeSp) {
                    lastReportedFontSizeSp = intSp
                    onFontSizeChanged(intSp.toFloat())
                }
            }
        }

        // Initialize Monaco Compatibility Layer & Hook Listeners
        MonacoRuntime.init(ctx)
        MonacoRuntime.onThemeDefined = { _, json ->
            scope.launch(Dispatchers.Main) {
                val scheme = SoraMonacoAdapter.monacoThemeToEditorColorScheme(json)
                activeColorScheme = scheme
                if (::editor.isInitialized) {
                    editor.colorScheme = scheme
                    editor.isBlockLineEnabled = true
                }
            }
        }
        MonacoRuntime.onThemeChanged = { name ->
            val json = MonacoRuntime.registeredThemes[name]
            if (json != null) {
                scope.launch(Dispatchers.Main) {
                    val scheme = SoraMonacoAdapter.monacoThemeToEditorColorScheme(json)
                    activeColorScheme = scheme
                    if (::editor.isInitialized) {
                        editor.colorScheme = scheme
                        editor.isBlockLineEnabled = true
                    }
                }
            }
        }
        MonacoRuntime.onApplyEdits = { edits ->
            scope.launch(Dispatchers.Main) {
                if (::editor.isInitialized) {
                    SoraMonacoAdapter.applyMonacoEdits(editor, edits)
                }
            }
        }

        pendingThemeAction?.invoke()
        pendingThemeAction = null

        // Sora's View is ready synchronously here — no async module-load
        // round-trip needed, so this fires immediately.
        onReady()

        pendingLoad?.let { (content, language, filename) ->
            pendingLoad = null
            applyLoad(content, language, filename)
        }

        return editor
    }

    fun loadFile(content: String, language: String, filename: String = "") {
        if (!::editor.isInitialized) {
            pendingLoad = Triple(content, language, filename)
            return
        }
        applyLoad(content, language, filename)
    }

    private fun applyLoad(content: String, language: String, filename: String) {
        val resolvedLang = if (language.isBlank() || language == "plaintext" || language == "text") {
            com.sednium.kryptonide.data.model.languageFromFilename(filename).ifBlank { language }
        } else language
        currentLanguageId = resolvedLang
        saveDebounceJob?.cancel()
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        isLoadingFile = true
        try {
            runCatching {
                editor.setText(normalized)
            }.onFailure { e ->
                android.util.Log.e("SoraEditorHost", "setText failed for $filename: ${e.message}", e)
            }
        } finally {
            isLoadingFile = false
        }

        runCatching {
            setLanguage(resolvedLang)
        }.onFailure { e ->
            android.util.Log.e("SoraEditorHost", "setLanguage($resolvedLang) failed for $filename: ${e.message}", e)
        }

        val lm = lspManager
        val uriFn = uriProvider
        val uri = uriFn?.invoke() ?: "inmemory://$filename"
        MonacoRuntime.syncBuffer(uri, resolvedLang, normalized)

        if (lm != null && uriFn != null) {
            runCatching { lm.didOpen(resolvedLang, uriFn(), normalized) }.onFailure { e ->
                android.util.Log.e("SoraEditorHost", "lsp didOpen failed for $filename: ${e.message}", e)
            }
        }

        onFileLoaded(resolvedLang, filename)
    }

    fun getContent(): String = if (::editor.isInitialized) editor.text.toString() else ""

    private fun setLanguage(languageId: String) {
        if (!::editor.isInitialized) return
        val lang = languageId.lowercase()
        val base = when (lang) {
            "html", "htm", "xhtml", "xml", "svg" -> runCatching { HtmlLanguage() }.getOrElse { EmptyLanguage() }
            "css", "scss", "less" -> runCatching { CssLanguage() }.getOrElse { EmptyLanguage() }
            "plaintext", "text", "txt", "" -> EmptyLanguage()
            else -> runCatching { CodeLanguage(lang) }.getOrElse { EmptyLanguage() }
        }

        val lm = lspManager
        val uriFn = uriProvider
        val finalLanguage = if (lm != null && uriFn != null) {
            runCatching { KryptonLspLanguage(base, lm, languageId, uriFn) }
                .onFailure { e -> android.util.Log.e("SoraEditorHost", "KryptonLspLanguage wrap failed: ${e.message}", e) }
                .getOrDefault(base)
        } else base

        editor.setEditorLanguage(finalLanguage)
        activeColorScheme?.let {
            editor.colorScheme = it
            editor.isBlockLineEnabled = true
        }
    }

    // ── Real editor operations ───────────────────────────────────────────────

    fun undo() { if (::editor.isInitialized && editor.canUndo()) editor.undo() }
    fun redo() { if (::editor.isInitialized && editor.canRedo()) editor.redo() }
    fun canUndo(): Boolean = ::editor.isInitialized && editor.canUndo()
    fun canRedo(): Boolean = ::editor.isInitialized && editor.canRedo()
    fun selectAllText() { if (::editor.isInitialized) editor.selectAll() }
    fun focusEditor() { if (::editor.isInitialized) editor.requestFocus() }

    fun getCurrentLine(): Int = if (::editor.isInitialized) editor.cursor.leftLine + 1 else 1
    fun getCurrentColumn(): Int = if (::editor.isInitialized) editor.cursor.leftColumn + 1 else 1

    fun getWordAtCursor(): String {
        if (!::editor.isInitialized) return ""
        return runCatching {
            val pos = editor.cursor.left()
            val lineStr = editor.text.getLineString(pos.line)
            if (lineStr.isEmpty()) return ""
            var start = pos.column.coerceIn(0, lineStr.length)
            var end = start
            while (start > 0 && (lineStr[start - 1].isLetterOrDigit() || lineStr[start - 1] == '_' || lineStr[start - 1] == '-' || lineStr[start - 1] == '$')) {
                start--
            }
            while (end < lineStr.length && (lineStr[end].isLetterOrDigit() || lineStr[end] == '_' || lineStr[end] == '-' || lineStr[end] == '$')) {
                end++
            }
            lineStr.substring(start, end)
        }.getOrDefault("")
    }

    fun moveCursorLeft() {
        if (!::editor.isInitialized) return
        val pos = editor.cursor.left()
        if (pos.column > 0) {
            editor.setSelection(pos.line, pos.column - 1)
        } else if (pos.line > 0) {
            val prevCol = editor.text.getColumnCount(pos.line - 1)
            editor.setSelection(pos.line - 1, prevCol)
        }
    }

    fun moveCursorRight() {
        if (!::editor.isInitialized) return
        val pos = editor.cursor.left()
        val maxCol = editor.text.getColumnCount(pos.line)
        if (pos.column < maxCol) {
            editor.setSelection(pos.line, pos.column + 1)
        } else if (pos.line < editor.text.lineCount - 1) {
            editor.setSelection(pos.line + 1, 0)
        }
    }

    fun duplicateLine() {
        if (!::editor.isInitialized) return
        val cursor = editor.cursor
        val line = cursor.leftLine
        val col = cursor.leftColumn
        val lineText = editor.text.getLineString(line)
        editor.text.beginBatchEdit()
        try {
            editor.text.insert(line + 1, 0, lineText + "\n")
            editor.setSelection(line + 1, col.coerceAtMost(editor.text.getColumnCount(line + 1)))
        } finally {
            editor.text.endBatchEdit()
        }
    }

    fun deleteLine() {
        if (!::editor.isInitialized) return
        val cursor = editor.cursor
        val line = cursor.leftLine
        val lineCount = editor.text.lineCount
        editor.text.beginBatchEdit()
        try {
            if (lineCount <= 1) {
                val colCount = editor.text.getColumnCount(0)
                if (colCount > 0) editor.text.delete(0, 0, 0, colCount)
            } else if (line < lineCount - 1) {
                editor.text.delete(line, 0, line + 1, 0)
            } else {
                val prevCol = editor.text.getColumnCount(line - 1)
                val thisCol = editor.text.getColumnCount(line)
                editor.text.delete(line - 1, prevCol, line, thisCol)
            }
        } finally {
            editor.text.endBatchEdit()
        }
    }

    fun toggleLineComment() {
        if (!::editor.isInitialized) return
        val lang = currentLanguageId.lowercase()
        val cursor = editor.cursor
        val startLine = cursor.leftLine
        val endLine = cursor.rightLine

        editor.text.beginBatchEdit()
        try {
            when (lang) {
                "html", "htm", "xhtml", "xml", "svg" -> {
                    for (l in startLine..endLine) {
                        val lineStr = editor.text.getLineString(l)
                        val trimmed = lineStr.trim()
                        val indentLen = lineStr.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: 0
                        if (trimmed.startsWith("<!--") && trimmed.endsWith("-->")) {
                            val inner = trimmed.removePrefix("<!--").removeSuffix("-->").trim()
                            editor.text.delete(l, indentLen, l, lineStr.length)
                            editor.text.insert(l, indentLen, inner)
                        } else {
                            editor.text.delete(l, indentLen, l, lineStr.length)
                            editor.text.insert(l, indentLen, "<!-- $trimmed -->")
                        }
                    }
                }
                "css", "scss", "less" -> {
                    for (l in startLine..endLine) {
                        val lineStr = editor.text.getLineString(l)
                        val trimmed = lineStr.trim()
                        val indentLen = lineStr.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: 0
                        if (trimmed.startsWith("/*") && trimmed.endsWith("*/")) {
                            val inner = trimmed.removePrefix("/*").removeSuffix("*/").trim()
                            editor.text.delete(l, indentLen, l, lineStr.length)
                            editor.text.insert(l, indentLen, inner)
                        } else {
                            editor.text.delete(l, indentLen, l, lineStr.length)
                            editor.text.insert(l, indentLen, "/* $trimmed */")
                        }
                    }
                }
                else -> {
                    val prefix = if (lang in CodeLanguage.HASH_COMMENT_LANGS) "# " else "// "
                    for (l in startLine..endLine) {
                        val lineStr = editor.text.getLineString(l)
                        val trimmed = lineStr.trimStart()
                        val indentLen = lineStr.length - trimmed.length
                        if (trimmed.startsWith(prefix.trimEnd())) {
                            val commentLen = if (trimmed.startsWith(prefix)) prefix.length else prefix.trimEnd().length
                            editor.text.delete(l, indentLen, l, indentLen + commentLen)
                        } else {
                            editor.text.insert(l, indentLen, prefix)
                        }
                    }
                }
            }
        } finally {
            editor.text.endBatchEdit()
        }
    }

    fun beginSearch() {
        if (!::editor.isInitialized) return
        runCatching { editor.beginSearchMode() }
    }

    fun insertText(text: String) {
        if (!::editor.isInitialized) return
        val pos = editor.cursor.left()
        val lineStr = runCatching { editor.text.getLineString(pos.line) }.getOrDefault("")
        val nextChar = if (pos.column < lineStr.length) lineStr[pos.column] else null

        // 1. Smart Tab handling: advance snippet tab-stop, step out of closing quotes/brackets/tags, or insert spaces
        if (text == "\t") {
            val handledBySnippet = runCatching {
                if (editor.snippetController.isInSnippet()) {
                    editor.snippetController.shiftToNextTabStop()
                    true
                } else false
            }.getOrDefault(false)
            if (handledBySnippet) return

            if (nextChar != null && nextChar in setOf('"', '\'', '>', ')', '}', ']', ';')) {
                editor.setSelection(pos.line, pos.column + 1)
                return
            }
            val spaces = if (currentLanguageId.lowercase() in setOf("python", "py", "kotlin", "kt", "kts", "java")) "    " else "  "
            editor.text.insert(pos.line, pos.column, spaces)
            return
        }

        // 2. Step-over existing closing delimiter if user taps the same closing symbol
        if (text.length == 1 && nextChar == text[0] && text[0] in setOf('"', '\'', '}', ')', ']', '>', ';')) {
            editor.setSelection(pos.line, pos.column + 1)
            return
        }

        // 3. Auto-pair opening brackets and quotes when tapped from the shortcuts bar
        val pairMap = mapOf(
            "{" to "{}",
            "(" to "()",
            "[" to "[]",
            "\"" to "\"\"",
            "'" to "''"
        )
        val paired = pairMap[text]
        if (paired != null) {
            editor.text.insert(pos.line, pos.column, paired)
            editor.setSelection(pos.line, pos.column + 1)
            return
        }

        editor.text.insert(pos.line, pos.column, text)
    }

    /** 1-based line, matching the old Monaco-facing API's convention. */
    fun gotoLine(line: Int) {
        if (!::editor.isInitialized) return
        val target = (line - 1).coerceAtLeast(0).coerceAtMost((editor.text.lineCount - 1).coerceAtLeast(0))
        editor.setSelection(target, 0)
    }

    fun scrollToTop() {
        if (!::editor.isInitialized) return
        editor.setSelection(0, 0)
    }

    fun setWordWrap(enabled: Boolean) {
        if (::editor.isInitialized && editor.isWordwrap != enabled) {
            editor.isWordwrap = enabled
        }
    }
    fun toggleWordWrap() { if (::editor.isInitialized) editor.isWordwrap = !editor.isWordwrap }
    fun isWordWrapEnabled(): Boolean = if (::editor.isInitialized) editor.isWordwrap else false

    fun setLineNumbersEnabled(enabled: Boolean) {
        if (!::editor.isInitialized) return
        if (editor.isLineNumberEnabled == enabled) return
        runCatching { editor.isLineNumberEnabled = enabled }.onFailure { e ->
            android.util.Log.e("SoraEditorHost", "setLineNumbersEnabled failed: ${e.message}", e)
        }
    }

    fun setIndentationGuidesEnabled(enabled: Boolean) {
        if (!::editor.isInitialized) return
        if (editor.isBlockLineEnabled == enabled) return
        runCatching { editor.isBlockLineEnabled = enabled }.onFailure { e ->
            android.util.Log.e("SoraEditorHost", "setIndentationGuidesEnabled failed: ${e.message}", e)
        }
    }

    fun setFontSize(sp: Float) {
        val clamped = sp.coerceIn(10f, 32f)
        if (kotlin.math.abs(currentFontSizeSp - clamped) < 0.25f) return
        currentFontSizeSp = clamped
        lastReportedFontSizeSp = Math.round(clamped)
        if (::editor.isInitialized) {
            val targetPx = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                currentFontSizeSp,
                context.resources.displayMetrics
            )
            if (kotlin.math.abs(editor.textSizePx - targetPx) > 0.5f) {
                editor.setTextSize(currentFontSizeSp)
            }
        }
    }

    fun increaseFontSize() {
        val next = (currentFontSizeSp + 1f).coerceIn(10f, 32f)
        setFontSize(next)
        onFontSizeChanged(next)
    }

    fun decreaseFontSize() {
        val prev = (currentFontSizeSp - 1f).coerceIn(10f, 32f)
        setFontSize(prev)
        onFontSizeChanged(prev)
    }

    /** Replaces the whole live buffer — used e.g. when Oorty Lite's Apply button edits the file that's currently open. */
    /**
     * Expands an Emmet-style abbreviation sitting immediately before the cursor
     * (e.g. "div.card>ul>li*3") into real HTML, replacing the abbreviation text
     * in place. Returns true if something was expanded.
     *
     * UNVERIFIED API, same caveat as the theming/diagnostics methods elsewhere
     * in this file: `getLineString`/the 4-arg `delete` are my best-confidence
     * inference from the already-confirmed `insert(line, column, text)`
     * signature's naming convention, not checked against the real sora-editor
     * artifact — verify on a real build.
     */
    fun expandEmmetAtCursor(): Boolean {
        if (!::editor.isInitialized) return false
        return runCatching {
            val pos = editor.cursor.left()
            val lineText = editor.text.getLineString(pos.line)
            val beforeCursor = lineText.substring(0, pos.column.coerceAtMost(lineText.length))
            val abbrevStart = (beforeCursor.indexOfLast { it.isWhitespace() } + 1).coerceAtLeast(0)
            val abbreviation = beforeCursor.substring(abbrevStart)
            if (abbreviation.isBlank()) return false

            val baseIndent = lineText.takeWhile { it == ' ' || it == '\t' }
            val expanded = EmmetExpander.expand(abbreviation, baseIndent) ?: return false
            editor.text.delete(pos.line, abbrevStart, pos.line, pos.column)
            editor.text.insert(pos.line, abbrevStart, expanded.trimEnd('\n'))
            runCatching {
                editor.getComponent(io.github.rosemoe.sora.widget.component.EditorAutoCompletion::class.java)?.hide()
            }
            true
        }.onFailure { e ->
            android.util.Log.e("SoraEditorHost", "expandEmmetAtCursor failed: ${e.message}", e)
        }.getOrDefault(false)
    }

    fun setContent(newContent: String) = setContent(newContent, null)
    fun setContent(newContent: String, cursorLine: Int?) {
        if (!::editor.isInitialized) return
        val normalized = newContent.replace("\r\n", "\n").replace("\r", "\n")
        if (normalized == editor.text.toString()) return
        isLoadingFile = true
        try {
            editor.setText(normalized)
            if (cursorLine != null) gotoLine(cursorLine)
        } finally {
            isLoadingFile = false
        }
    }

    private var lastAppliedTypeface: android.graphics.Typeface? = null

    /**
     * Real native font application. Ensures the typeface is strictly fixed-width monospace,
     * disables font ligatures, resets artificial letter spacing, and recomputes text layout.
     */
    fun applyTypeface(typeface: android.graphics.Typeface) {
        if (!::editor.isInitialized) return
        val effectiveTypeface = if (isMonospaceTypeface(typeface)) {
            typeface
        } else {
            android.util.Log.w("SoraEditorHost", "Typeface is not monospace! Falling back to JetBrains Mono.")
            runCatching {
                android.graphics.Typeface.createFromAsset(context.assets, "fonts/jetbrainsmono_regular.ttf")
            }.getOrDefault(android.graphics.Typeface.MONOSPACE)
        }

        if (lastAppliedTypeface === effectiveTypeface && editor.typefaceText === effectiveTypeface) return
        lastAppliedTypeface = effectiveTypeface

        runCatching {
            editor.typefaceText = effectiveTypeface
            editor.typefaceLineNumber = effectiveTypeface
            editor.setLigatureEnabled(false)
            editor.setTextLetterSpacing(0f)
            editor.setTextScaleX(1f)
            editor.setTextSizePx(editor.textSizePx)
            editor.renderContext?.invalidateRenderNodes()
            editor.setWordwrap(editor.isWordwrap)
            editor.invalidate()
        }.onFailure { e ->
            android.util.Log.e("SoraEditorHost", "applyTypeface failed: ${e.message}", e)
        }
    }

    private fun isMonospaceTypeface(tf: android.graphics.Typeface): Boolean {
        return runCatching {
            val paint = android.graphics.Paint().apply {
                this.typeface = tf
                this.textSize = 24f
                this.letterSpacing = 0f
            }
            val w1 = paint.measureText("i")
            val w2 = paint.measureText("W")
            val w3 = paint.measureText(".")
            val w4 = paint.measureText("0")
            val w5 = paint.measureText(" ")
            kotlin.math.abs(w1 - w2) < 0.1f &&
                kotlin.math.abs(w2 - w3) < 0.1f &&
                kotlin.math.abs(w3 - w4) < 0.1f &&
                kotlin.math.abs(w4 - w5) < 0.1f
        }.getOrDefault(true)
    }

    /**
     * Displays LSP diagnostics (error/warning squiggles) fed from
     * LspManager.onDiagnostics. Takes the same 1-based-line/column marker
     * JSON array LspManager already produces (severity 8=Error, 4=Warning,
     * 2=Info, else=Hint — see LspManager.lspSeverityToMarkerSeverity).
     */
    fun setDiagnostics(markersJson: org.json.JSONArray) {
        if (!::editor.isInitialized) return
        runCatching {
            val container = io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer()
            for (i in 0 until markersJson.length()) {
                val m = markersJson.optJSONObject(i) ?: continue
                val startLine = (m.optInt("startLineNumber", 1) - 1).coerceAtLeast(0)
                val startCol  = (m.optInt("startColumn", 1) - 1).coerceAtLeast(0)
                val endLine   = (m.optInt("endLineNumber", 1) - 1).coerceAtLeast(0)
                val endCol    = (m.optInt("endColumn", 1) - 1).coerceAtLeast(0)
                val startIdx = editor.text.getCharIndex(startLine, startCol)
                val endIdx   = editor.text.getCharIndex(endLine, endCol).coerceAtLeast(startIdx + 1)
                val severity = when (m.optInt("severity", 8)) {
                    8 -> io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion.SEVERITY_ERROR
                    4 -> io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion.SEVERITY_WARNING
                    else -> io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion.SEVERITY_TYPO
                }
                val detail = io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail(m.optString("message"))
                container.addDiagnostic(
                    io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion(startIdx, endIdx, severity, 0, detail)
                )
            }
            editor.diagnostics = container
        }.onFailure { e ->
            android.util.Log.e("SoraEditorHost", "setDiagnostics failed: ${e.message}", e)
        }
    }

    // ── Theming ─────────────────────────────────────────────────────────────

    private fun hexColor(s: String): Int? = runCatching {
        android.graphics.Color.parseColor(if (s.startsWith("#")) s else "#$s")
    }.getOrNull()

    private data class Sextuple(val a: Int, val b: Int, val c: Int, val d: Int, val e: Int, val f: Int)

    /** The small built-in light/dark set from Settings — see [EditorTheme]. */
    fun applyBaseTheme(theme: EditorTheme) {
        if (!::editor.isInitialized) {
            pendingThemeAction = { applyBaseTheme(theme) }
            return
        }
        val scheme = EditorColorScheme()
        val (bg, fg, lineNum, currentLine, selection, cursor) = when (theme) {
            EditorTheme.LIGHT ->
                Sextuple(0xFFFFFFFF.toInt(), 0xFF1E1E1E.toInt(), 0xFF858585.toInt(), 0xFFF3F3F3.toInt(), 0xFFADD6FF.toInt(), 0xFF000000.toInt())
            EditorTheme.DARK ->
                Sextuple(0xFF1E1E1E.toInt(), 0xFFD4D4D4.toInt(), 0xFF858585.toInt(), 0xFF2A2A2A.toInt(), 0xFF264F78.toInt(), 0xFFAEAFAD.toInt())
            EditorTheme.KRYPTON_DARK ->
                Sextuple(0xFF16181D.toInt(), 0xFFE6E6E6.toInt(), 0xFF565C6B.toInt(), 0xFF1E2128.toInt(), 0xFF33455E.toInt(), 0xFFF3F0E7.toInt())
            EditorTheme.HIGH_CONTRAST ->
                Sextuple(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF0A0A0A.toInt(), 0xFF00539C.toInt(), 0xFFFFFF00.toInt())
        }
        runCatching {
            scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, bg)
            scheme.setColor(EditorColorScheme.TEXT_NORMAL, fg)
            scheme.setColor(EditorColorScheme.LINE_NUMBER, lineNum)
            scheme.setColor(EditorColorScheme.LINE_NUMBER_PANEL_TEXT, lineNum)
            scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, bg)
            scheme.setColor(EditorColorScheme.LINE_NUMBER_PANEL, bg)
            scheme.setColor(EditorColorScheme.CURRENT_LINE, currentLine)
            scheme.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, selection)
            scheme.setColor(EditorColorScheme.SELECTION_INSERT, cursor)
            scheme.setColor(EditorColorScheme.SELECTION_HANDLE, cursor)
            scheme.setColor(EditorColorScheme.SCROLL_BAR_TRACK, android.graphics.Color.TRANSPARENT)
            val isLight = theme == EditorTheme.LIGHT
            scheme.setColor(EditorColorScheme.SCROLL_BAR_THUMB, if (isLight) 0x40000000.toInt() else 0x4DFFFFFF.toInt())
            scheme.setColor(EditorColorScheme.SCROLL_BAR_THUMB_PRESSED, if (isLight) 0x80000000.toInt() else 0xA0FFFFFF.toInt())

            when (theme) {
                EditorTheme.LIGHT -> {
                    scheme.setColor(EditorColorScheme.HTML_TAG, 0xFF005CC5.toInt()) // Vibrant Blue
                    scheme.setColor(EditorColorScheme.ATTRIBUTE_NAME, 0xFF6F42C1.toInt()) // Purple
                    scheme.setColor(EditorColorScheme.ATTRIBUTE_VALUE, 0xFF22863A.toInt()) // Green
                    scheme.setColor(EditorColorScheme.KEYWORD, 0xFFD73A49.toInt()) // Red
                    scheme.setColor(EditorColorScheme.OPERATOR, 0xFF24292E.toInt())
                    scheme.setColor(EditorColorScheme.LITERAL, 0xFF032F62.toInt())
                    scheme.setColor(EditorColorScheme.COMMENT, 0xFF6A737D.toInt())
                    scheme.setColor(EditorColorScheme.FUNCTION_NAME, 0xFF6F42C1.toInt())
                    scheme.setColor(EditorColorScheme.IDENTIFIER_NAME, 0xFF24292E.toInt())
                    scheme.setColor(EditorColorScheme.BLOCK_LINE, 0x30000000.toInt())
                    scheme.setColor(EditorColorScheme.BLOCK_LINE_CURRENT, 0x90007ACC.toInt())
                    scheme.setColor(EditorColorScheme.SIDE_BLOCK_LINE, 0x20000000.toInt())
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_BACKGROUND, 0xFFF8F9FA.toInt())
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_CORNER, 0xFFD0D7DE.toInt())
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, 0xFF1F2328.toInt())
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY, 0xFF656D76.toInt())
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT, 0xFFE6F0FF.toInt())
                }
                EditorTheme.DARK -> {
                    scheme.setColor(EditorColorScheme.HTML_TAG, 0xFFE06C75.toInt()) // Coral Red
                    scheme.setColor(EditorColorScheme.ATTRIBUTE_NAME, 0xFFD19A66.toInt()) // Amber Orange
                    scheme.setColor(EditorColorScheme.ATTRIBUTE_VALUE, 0xFF98C379.toInt()) // Green
                    scheme.setColor(EditorColorScheme.KEYWORD, 0xFFC678DD.toInt()) // Purple
                    scheme.setColor(EditorColorScheme.OPERATOR, 0xFF56B6C2.toInt()) // Cyan
                    scheme.setColor(EditorColorScheme.LITERAL, 0xFF98C379.toInt())
                    scheme.setColor(EditorColorScheme.COMMENT, 0xFF7F848E.toInt())
                    scheme.setColor(EditorColorScheme.FUNCTION_NAME, 0xFF61AFEF.toInt())
                    scheme.setColor(EditorColorScheme.IDENTIFIER_NAME, 0xFFABB2BF.toInt())
                    scheme.setColor(EditorColorScheme.BLOCK_LINE, 0x25FFFFFF.toInt())
                    scheme.setColor(EditorColorScheme.BLOCK_LINE_CURRENT, 0x8061AFEF.toInt())
                    scheme.setColor(EditorColorScheme.SIDE_BLOCK_LINE, 0x15FFFFFF.toInt())
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_BACKGROUND, 0xFF252526.toInt())
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_CORNER, 0xFF454545.toInt())
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, 0xFFCCCCCC.toInt())
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY, 0xFF858585.toInt())
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT, 0xFF04395E.toInt())
                }
                EditorTheme.KRYPTON_DARK -> {
                    scheme.setColor(EditorColorScheme.HTML_TAG, 0xFFE06C75.toInt())
                    scheme.setColor(EditorColorScheme.ATTRIBUTE_NAME, 0xFFE5C07B.toInt())
                    scheme.setColor(EditorColorScheme.ATTRIBUTE_VALUE, 0xFF98C379.toInt())
                    scheme.setColor(EditorColorScheme.KEYWORD, 0xFFC678DD.toInt())
                    scheme.setColor(EditorColorScheme.OPERATOR, 0xFF56B6C2.toInt())
                    scheme.setColor(EditorColorScheme.LITERAL, 0xFF98C379.toInt())
                    scheme.setColor(EditorColorScheme.COMMENT, 0xFF6B7280.toInt())
                    scheme.setColor(EditorColorScheme.FUNCTION_NAME, 0xFF61AFEF.toInt())
                    scheme.setColor(EditorColorScheme.IDENTIFIER_NAME, 0xFFD4D4D4.toInt())
                    scheme.setColor(EditorColorScheme.BLOCK_LINE, 0x25FFFFFF.toInt())
                    scheme.setColor(EditorColorScheme.BLOCK_LINE_CURRENT, 0x40F3F0E7.toInt())
                    scheme.setColor(EditorColorScheme.SIDE_BLOCK_LINE, 0x15FFFFFF.toInt())
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_BACKGROUND, 0xFF1E222A.toInt())
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_CORNER, 0xFF333945.toInt())
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, 0xFFE6E6E6.toInt())
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY, 0xFF8B949E.toInt())
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT, 0xFF2C3E5D.toInt())
                }
                EditorTheme.HIGH_CONTRAST -> {
                    scheme.setColor(EditorColorScheme.HTML_TAG, 0xFF00FFFF.toInt())
                    scheme.setColor(EditorColorScheme.ATTRIBUTE_NAME, 0xFFFFFF00.toInt())
                    scheme.setColor(EditorColorScheme.ATTRIBUTE_VALUE, 0xFF00FF00.toInt())
                    scheme.setColor(EditorColorScheme.KEYWORD, 0xFFFF00FF.toInt())
                    scheme.setColor(EditorColorScheme.OPERATOR, 0xFFFFFFFF.toInt())
                    scheme.setColor(EditorColorScheme.LITERAL, 0xFF00FF00.toInt())
                    scheme.setColor(EditorColorScheme.COMMENT, 0xFF7F7F7F.toInt())
                    scheme.setColor(EditorColorScheme.FUNCTION_NAME, 0xFF00FFFF.toInt())
                    scheme.setColor(EditorColorScheme.IDENTIFIER_NAME, 0xFFFFFFFF.toInt())
                    scheme.setColor(EditorColorScheme.BLOCK_LINE, 0x40FFFFFF.toInt())
                    scheme.setColor(EditorColorScheme.BLOCK_LINE_CURRENT, 0xFF00FFFF.toInt())
                    scheme.setColor(EditorColorScheme.SIDE_BLOCK_LINE, 0x25FFFFFF.toInt())
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_BACKGROUND, 0xFF0A0A0A.toInt())
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_CORNER, 0xFFFFFFFF.toInt())
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, 0xFFFFFFFF.toInt())
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY, 0xFFFFFF00.toInt())
                    scheme.setColor(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT, 0xFF00539C.toInt())
                }
            }

            activeColorScheme = scheme
            editor.colorScheme = scheme
            editor.isBlockLineEnabled = true
        }.onFailure { e ->
            android.util.Log.e("SoraEditorHost", "applyBaseTheme(${theme.name}) failed: ${e.message}", e)
        }
    }

    /**
     * A richer marketplace theme from [com.sednium.kryptonide.ui.plugins.BuiltInThemes]
     * or third-party plugins. Parses with complete color & token mapping.
     */
    fun applyMarketplaceTheme(themeJson: String) {
        if (!::editor.isInitialized) {
            pendingThemeAction = { applyMarketplaceTheme(themeJson) }
            return
        }
        runCatching {
            val scheme = SoraMonacoAdapter.monacoThemeToEditorColorScheme(themeJson)
            scheme.setColor(EditorColorScheme.SCROLL_BAR_TRACK, android.graphics.Color.TRANSPARENT)
            activeColorScheme = scheme
            editor.colorScheme = scheme
            editor.isBlockLineEnabled = true
        }.onFailure { e ->
            android.util.Log.e("SoraEditorHost", "applyMarketplaceTheme failed: ${e.message}", e)
        }
    }

    /**
     * Applies a real VS Code TextMate theme from assets/textmate/<themeId>.json.
     */
    fun applyTextMateTheme(themeId: String) {
        if (!::editor.isInitialized) {
            pendingThemeAction = { applyTextMateTheme(themeId) }
            return
        }
        val success = ThemeAssetManager.applyTheme(editor, themeId, context)
        if (success) {
            activeColorScheme = editor.colorScheme
        }
        editor.isBlockLineEnabled = true
    }
}
