package com.sednium.kryptonide.ui.editor

import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AsyncIncrementalAnalyzeManager
import io.github.rosemoe.sora.lang.analysis.IncrementalAnalyzeManager
import io.github.rosemoe.sora.lang.styling.CodeBlock
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.SymbolPairMatch
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.util.Stack

/**
 * High-performance, native HTML language tokenizer and block-line analyzer
 * for KryptonIDE's Sora Editor, matching Spck/VS Code visual styling.
 */
data class HtmlState(
    val inComment: Boolean = false,
    val inScript: Boolean = false,
    val inStyle: Boolean = false
)

class HtmlLanguage : EmptyLanguage() {

    private val analyzeManager = HtmlAnalyzeManager()
    private val symbolPairs = SymbolPairMatch().apply {
        putPair('"', SymbolPairMatch.SymbolPair("\"", "\""))
        putPair('\'', SymbolPairMatch.SymbolPair("'", "'"))
        putPair('{', SymbolPairMatch.SymbolPair("{", "}"))
        putPair('(', SymbolPairMatch.SymbolPair("(", ")"))
        putPair('[', SymbolPairMatch.SymbolPair("[", "]"))
    }

    override fun getAnalyzeManager() = analyzeManager
    override fun getSymbolPairs() = symbolPairs
    override fun getInterruptionLevel() = INTERRUPTION_LEVEL_SLIGHT
    override fun useTab() = false

    override fun getIndentAdvance(content: io.github.rosemoe.sora.text.ContentReference, line: Int, column: Int): Int {
        return runCatching {
            val rawLine = content.getLine(line).toString()
            val before = rawLine.substring(0, column.coerceIn(0, rawLine.length)).trim()
            if (before.isEmpty()) return 0
            if (before.endsWith("{")) return 2

            // Check if the line ends with an opening non-void HTML tag `<tagName...>` not closed on this line
            val openMatch = Regex("""<([a-zA-Z][a-zA-Z0-9:-]*)(?:\s[^<>]*)?>$""").find(before)
            if (openMatch != null && !before.endsWith("/>")) {
                val tag = openMatch.groupValues[1].lowercase()
                if (tag !in SpckCompletionEngine.VOID_HTML_TAGS && !before.contains("</$tag>", ignoreCase = true)) {
                    return 2
                }
            }
            0
        }.getOrDefault(0)
    }

    override fun requireAutoComplete(
        content: io.github.rosemoe.sora.text.ContentReference,
        position: io.github.rosemoe.sora.text.CharPosition,
        publisher: io.github.rosemoe.sora.lang.completion.CompletionPublisher,
        extraArguments: android.os.Bundle
    ) {
        val prefix = runCatching {
            io.github.rosemoe.sora.lang.completion.CompletionHelper.computePrefix(content, position) { ch ->
                ch.isLetterOrDigit() || ch == '_' || ch == '$'
            }
        }.getOrDefault("")
        SpckCompletionEngine.populateCompletions("html", content, position, prefix, publisher)
    }
}

class HtmlAnalyzeManager : AsyncIncrementalAnalyzeManager<HtmlState, Any>() {

    override fun getInitialState() = HtmlState()

    override fun stateEquals(state1: HtmlState, state2: HtmlState) = state1 == state2

    override fun onAddState(state: HtmlState) {}
    override fun onAbandonState(state: HtmlState) {}

    override fun generateSpansForLine(result: IncrementalAnalyzeManager.LineTokenizeResult<HtmlState, Any>): List<Span> {
        return result.spans ?: emptyList()
    }

    override fun tokenizeLine(
        line: CharSequence,
        state: HtmlState,
        lineIndex: Int
    ): IncrementalAnalyzeManager.LineTokenizeResult<HtmlState, Any> {
        val spans = mutableListOf<Span>()
        val len = line.length
        if (len == 0) {
            return IncrementalAnalyzeManager.LineTokenizeResult(state, emptyList(), spans)
        }

        var inComment = state.inComment
        var inScript = state.inScript
        var inStyle = state.inStyle
        var i = 0

        fun addSpan(col: Int, colorId: Int) {
            spans.add(Span.obtain(col, TextStyle.makeStyle(colorId)))
        }

        // Multi-line comment continuation
        if (inComment) {
            addSpan(0, EditorColorScheme.COMMENT)
            val endIdx = line.indexOf("-->")
            if (endIdx != -1) {
                inComment = false
                i = endIdx + 3
                if (i < len) {
                    addSpan(i, EditorColorScheme.TEXT_NORMAL)
                }
            } else {
                return IncrementalAnalyzeManager.LineTokenizeResult(HtmlState(inComment = true), emptyList(), spans)
            }
        }

        while (i < len) {
            // Check for end of script / style tag
            if (inScript) {
                val closeScript = line.indexOf("</script", i, ignoreCase = true)
                if (closeScript != -1) {
                    if (closeScript > i) {
                        addSpan(i, EditorColorScheme.IDENTIFIER_NAME)
                    }
                    val tagEnd = line.indexOf('>', closeScript)
                    addSpan(closeScript, EditorColorScheme.HTML_TAG)
                    inScript = false
                    i = if (tagEnd != -1) tagEnd + 1 else len
                    if (i < len) addSpan(i, EditorColorScheme.TEXT_NORMAL)
                    continue
                } else {
                    tokenizeScriptSegment(line, i, len, ::addSpan)
                    break
                }
            }

            if (inStyle) {
                val closeStyle = line.indexOf("</style", i, ignoreCase = true)
                if (closeStyle != -1) {
                    if (closeStyle > i) {
                        addSpan(i, EditorColorScheme.ATTRIBUTE_NAME)
                    }
                    val tagEnd = line.indexOf('>', closeStyle)
                    addSpan(closeStyle, EditorColorScheme.HTML_TAG)
                    inStyle = false
                    i = if (tagEnd != -1) tagEnd + 1 else len
                    if (i < len) addSpan(i, EditorColorScheme.TEXT_NORMAL)
                    continue
                } else {
                    tokenizeStyleSegment(line, i, len, ::addSpan)
                    break
                }
            }

            // Standard HTML tokenization
            if (line.startsWith("<!--", i)) {
                addSpan(i, EditorColorScheme.COMMENT)
                val endIdx = line.indexOf("-->", i + 4)
                if (endIdx != -1) {
                    i = endIdx + 3
                    if (i < len) addSpan(i, EditorColorScheme.TEXT_NORMAL)
                } else {
                    inComment = true
                    break
                }
            } else if (line.startsWith("<!DOCTYPE", i, ignoreCase = true) || line.startsWith("<!doctype", i, ignoreCase = true)) {
                addSpan(i, EditorColorScheme.KEYWORD)
                val endIdx = line.indexOf('>', i)
                if (endIdx != -1) {
                    i = endIdx + 1
                    if (i < len) addSpan(i, EditorColorScheme.TEXT_NORMAL)
                } else {
                    break
                }
            } else if (line[i] == '<') {
                val tagBracketStart = i
                val isClosing = (i + 1 < len && line[i + 1] == '/')
                i += if (isClosing) 2 else 1

                while (i < len && line[i].isWhitespace()) i++

                val nameStart = i
                while (i < len && (line[i].isLetterOrDigit() || line[i] == '-' || line[i] == ':')) i++
                val tagName = if (nameStart < i) line.substring(nameStart, i).lowercase() else ""

                addSpan(tagBracketStart, EditorColorScheme.HTML_TAG)

                // Attributes within tag
                while (i < len && line[i] != '>') {
                    if (line[i].isWhitespace()) {
                        i++
                        continue
                    }
                    if (line[i] == '/' && i + 1 < len && line[i + 1] == '>') {
                        addSpan(i, EditorColorScheme.HTML_TAG)
                        i += 2
                        break
                    }
                    if (line[i].isLetter() || line[i] == '@' || line[i] == ':' || line[i] == '-' || line[i] == '_') {
                        val attrStart = i
                        while (i < len && (line[i].isLetterOrDigit() || line[i] == '-' || line[i] == '_' || line[i] == ':' || line[i] == '.' || line[i] == '@')) i++
                        addSpan(attrStart, EditorColorScheme.ATTRIBUTE_NAME)

                        while (i < len && line[i].isWhitespace()) i++
                        if (i < len && line[i] == '=') {
                            addSpan(i, EditorColorScheme.OPERATOR)
                            i++
                            while (i < len && line[i].isWhitespace()) i++
                            if (i < len && (line[i] == '"' || line[i] == '\'')) {
                                val quote = line[i]
                                val valStart = i
                                i++
                                while (i < len && line[i] != quote) i++
                                if (i < len && line[i] == quote) i++
                                addSpan(valStart, EditorColorScheme.ATTRIBUTE_VALUE)
                            } else if (i < len && line[i] != '>') {
                                val valStart = i
                                while (i < len && !line[i].isWhitespace() && line[i] != '>') i++
                                addSpan(valStart, EditorColorScheme.ATTRIBUTE_VALUE)
                            }
                        }
                    } else {
                        i++
                    }
                }

                if (i < len && line[i] == '>') {
                    addSpan(i, EditorColorScheme.HTML_TAG)
                    i++
                    if (tagName == "script" && !isClosing) inScript = true
                    if (tagName == "style" && !isClosing) inStyle = true
                    if (i < len && !inScript && !inStyle) {
                        addSpan(i, EditorColorScheme.TEXT_NORMAL)
                    }
                }
            } else {
                val textStart = i
                addSpan(textStart, EditorColorScheme.TEXT_NORMAL)
                while (i < len && line[i] != '<') i++
            }
        }

        return IncrementalAnalyzeManager.LineTokenizeResult(
            HtmlState(inComment = inComment, inScript = inScript, inStyle = inStyle),
            emptyList(),
            spans
        )
    }

    private fun tokenizeScriptSegment(
        line: CharSequence,
        start: Int,
        len: Int,
        addSpan: (Int, Int) -> Unit
    ) {
        val jsKeywords = setOf(
            "const", "let", "var", "function", "return", "if", "else",
            "for", "while", "do", "switch", "case", "break", "continue",
            "import", "from", "export", "default", "class", "extends",
            "this", "new", "super", "try", "catch", "finally", "throw",
            "async", "await", "yield", "true", "false", "null", "undefined"
        )
        var i = start
        while (i < len) {
            val c = line[i]
            if (c.isWhitespace()) {
                i++
            } else if (c == '/' && i + 1 < len && line[i + 1] == '/') {
                addSpan(i, EditorColorScheme.COMMENT)
                break
            } else if (c == '"' || c == '\'' || c == '`') {
                val valStart = i
                val quote = c
                i++
                while (i < len && line[i] != quote) {
                    if (line[i] == '\\' && i + 1 < len) i++
                    i++
                }
                if (i < len && line[i] == quote) i++
                addSpan(valStart, EditorColorScheme.LITERAL)
            } else if (c.isDigit()) {
                val numStart = i
                while (i < len && (line[i].isDigit() || line[i] == '.')) i++
                addSpan(numStart, EditorColorScheme.LITERAL)
            } else if (c.isLetter() || c == '_' || c == '$') {
                val wordStart = i
                while (i < len && (line[i].isLetterOrDigit() || line[i] == '_' || line[i] == '$')) i++
                val word = line.substring(wordStart, i)
                if (jsKeywords.contains(word)) {
                    addSpan(wordStart, EditorColorScheme.KEYWORD)
                } else {
                    addSpan(wordStart, EditorColorScheme.IDENTIFIER_NAME)
                }
            } else {
                addSpan(i, EditorColorScheme.OPERATOR)
                i++
            }
        }
    }

    private fun tokenizeStyleSegment(
        line: CharSequence,
        start: Int,
        len: Int,
        addSpan: (Int, Int) -> Unit
    ) {
        var i = start
        while (i < len) {
            val c = line[i]
            if (c.isWhitespace()) {
                i++
            } else if (c == '/' && i + 1 < len && line[i + 1] == '*') {
                addSpan(i, EditorColorScheme.COMMENT)
                val endC = line.indexOf("*/", i + 2)
                if (endC != -1) i = endC + 2 else break
            } else if (c == '{' || c == '}' || c == ':' || c == ';') {
                addSpan(i, EditorColorScheme.OPERATOR)
                i++
            } else if (c == '#' || c == '.' || c == '@') {
                addSpan(i, EditorColorScheme.KEYWORD)
                i++
            } else if (c == '"' || c == '\'') {
                val quote = c
                val valStart = i
                i++
                while (i < len && line[i] != quote) i++
                if (i < len && line[i] == quote) i++
                addSpan(valStart, EditorColorScheme.ATTRIBUTE_VALUE)
            } else if (c.isLetter() || c == '-') {
                val propStart = i
                while (i < len && (line[i].isLetterOrDigit() || line[i] == '-')) i++
                addSpan(propStart, EditorColorScheme.ATTRIBUTE_NAME)
            } else {
                addSpan(i, EditorColorScheme.TEXT_NORMAL)
                i++
            }
        }
    }

    override fun computeBlocks(
        content: Content,
        delegate: CodeBlockAnalyzeDelegate
    ): List<CodeBlock> {
        val blocks = mutableListOf<CodeBlock>()
        val lineCount = content.lineCount
        data class TagInfo(val name: String, val line: Int, val col: Int)
        val tagStack = Stack<TagInfo>()

        val voidTags = setOf(
            "area", "base", "br", "col", "embed", "hr", "img",
            "input", "link", "meta", "param", "source", "track", "wbr", "!doctype"
        )

        for (l in 0 until lineCount) {
            if (!delegate.isNotCancelled) break
            val lineStr = content.getLineString(l)
            val len = lineStr.length

            var idx = 0
            while (idx < len) {
                val openBracket = lineStr.indexOf('<', idx)
                if (openBracket == -1) break

                if (lineStr.startsWith("<!--", openBracket)) {
                    val endComment = lineStr.indexOf("-->", openBracket + 4)
                    if (endComment != -1) idx = endComment + 3 else break
                    continue
                }

                val isClose = (openBracket + 1 < len && lineStr[openBracket + 1] == '/')
                var tagStart = openBracket + if (isClose) 2 else 1
                while (tagStart < len && lineStr[tagStart].isWhitespace()) tagStart++

                var tagEnd = tagStart
                while (tagEnd < len && (lineStr[tagEnd].isLetterOrDigit() || lineStr[tagEnd] == '-' || lineStr[tagEnd] == ':')) tagEnd++

                if (tagEnd > tagStart) {
                    val tagName = lineStr.substring(tagStart, tagEnd).lowercase()
                    val closeBracket = lineStr.indexOf('>', tagEnd)
                    val isSelfClosing = closeBracket != -1 && closeBracket > 0 && lineStr[closeBracket - 1] == '/'

                    if (!voidTags.contains(tagName) && !isSelfClosing) {
                        if (isClose) {
                            var foundIdx = -1
                            for (si in tagStack.indices.reversed()) {
                                if (tagStack[si].name == tagName) {
                                    foundIdx = si
                                    break
                                }
                            }
                            if (foundIdx != -1) {
                                val opener = tagStack[foundIdx]
                                while (tagStack.size > foundIdx) tagStack.pop()
                                if (l > opener.line) {
                                    val block = CodeBlock().apply {
                                        startLine = opener.line
                                        startColumn = opener.col
                                        endLine = l
                                        endColumn = openBracket
                                    }
                                    blocks.add(block)
                                }
                            }
                        } else {
                            tagStack.push(TagInfo(tagName, l, openBracket))
                        }
                    }
                    idx = if (closeBracket != -1) closeBracket + 1 else len
                } else {
                    idx = openBracket + 1
                }
            }
        }
        return blocks
    }
}
