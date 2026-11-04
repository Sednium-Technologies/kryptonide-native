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

data class CssState(
    val inComment: Boolean = false,
    val inBlock: Boolean = false
)

class CssLanguage : EmptyLanguage() {
    private val analyzeManager = CssAnalyzeManager()
    private val symbolPairs = SymbolPairMatch().apply {
        putPair('{', SymbolPairMatch.SymbolPair("{", "}"))
        putPair('(', SymbolPairMatch.SymbolPair("(", ")"))
        putPair('[', SymbolPairMatch.SymbolPair("[", "]"))
        putPair('"', SymbolPairMatch.SymbolPair("\"", "\""))
        putPair('\'', SymbolPairMatch.SymbolPair("'", "'"))
    }

    override fun getAnalyzeManager() = analyzeManager
    override fun getSymbolPairs() = symbolPairs
    override fun getInterruptionLevel() = INTERRUPTION_LEVEL_SLIGHT
    override fun useTab() = false

    override fun getIndentAdvance(content: io.github.rosemoe.sora.text.ContentReference, line: Int, column: Int): Int {
        return runCatching {
            val rawLine = content.getLine(line).toString()
            val before = rawLine.substring(0, column.coerceIn(0, rawLine.length)).trim()
            if (before.endsWith("{")) 2 else 0
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
                ch.isLetterOrDigit() || ch == '_' || ch == '-'
            }
        }.getOrDefault("")
        SpckCompletionEngine.populateCompletions("css", content, position, prefix, publisher)
    }
}

class CssAnalyzeManager : AsyncIncrementalAnalyzeManager<CssState, Any>() {
    override fun getInitialState() = CssState()
    override fun stateEquals(state1: CssState, state2: CssState) = state1 == state2
    override fun onAddState(state: CssState) {}
    override fun onAbandonState(state: CssState) {}

    override fun generateSpansForLine(result: IncrementalAnalyzeManager.LineTokenizeResult<CssState, Any>): List<Span> {
        return result.spans ?: emptyList()
    }

    override fun tokenizeLine(
        line: CharSequence,
        state: CssState,
        lineIndex: Int
    ): IncrementalAnalyzeManager.LineTokenizeResult<CssState, Any> {
        val spans = mutableListOf<Span>()
        val len = line.length
        if (len == 0) return IncrementalAnalyzeManager.LineTokenizeResult(state, emptyList(), spans)

        var inComment = state.inComment
        var inBlock = state.inBlock
        var i = 0

        fun addSpan(col: Int, colorId: Int) {
            spans.add(Span.obtain(col, TextStyle.makeStyle(colorId)))
        }

        if (inComment) {
            addSpan(0, EditorColorScheme.COMMENT)
            val endC = line.indexOf("*/")
            if (endC != -1) {
                inComment = false
                i = endC + 2
                if (i < len) addSpan(i, if (inBlock) EditorColorScheme.ATTRIBUTE_NAME else EditorColorScheme.HTML_TAG)
            } else {
                return IncrementalAnalyzeManager.LineTokenizeResult(CssState(inComment = true, inBlock = inBlock), emptyList(), spans)
            }
        }

        while (i < len) {
            val c = line[i]
            if (c.isWhitespace()) {
                i++
            } else if (c == '/' && i + 1 < len && line[i + 1] == '*') {
                addSpan(i, EditorColorScheme.COMMENT)
                val endC = line.indexOf("*/", i + 2)
                if (endC != -1) {
                    i = endC + 2
                    if (i < len) addSpan(i, if (inBlock) EditorColorScheme.ATTRIBUTE_NAME else EditorColorScheme.HTML_TAG)
                } else {
                    inComment = true
                    break
                }
            } else if (c == '{') {
                addSpan(i, EditorColorScheme.OPERATOR)
                inBlock = true
                i++
            } else if (c == '}') {
                addSpan(i, EditorColorScheme.OPERATOR)
                inBlock = false
                i++
            } else if (c == ':' || c == ';') {
                addSpan(i, EditorColorScheme.OPERATOR)
                i++
            } else if (c == '"' || c == '\'') {
                val quote = c
                val valStart = i
                i++
                while (i < len && line[i] != quote) {
                    if (line[i] == '\\' && i + 1 < len) i++
                    i++
                }
                if (i < len && line[i] == quote) i++
                addSpan(valStart, EditorColorScheme.ATTRIBUTE_VALUE)
            } else if (c == '#' || c == '.' || c == '@' || (!inBlock && (c.isLetter() || c == '*' || c == '['))) {
                val selStart = i
                while (i < len && !line[i].isWhitespace() && line[i] != '{' && line[i] != ';' && line[i] != ',') i++
                addSpan(selStart, if (c == '@') EditorColorScheme.KEYWORD else EditorColorScheme.HTML_TAG)
            } else if (inBlock && (c.isLetter() || c == '-')) {
                val propStart = i
                while (i < len && (line[i].isLetterOrDigit() || line[i] == '-')) i++
                addSpan(propStart, EditorColorScheme.ATTRIBUTE_NAME)
            } else if (inBlock && (c.isDigit() || c == '#')) {
                val valStart = i
                while (i < len && (line[i].isLetterOrDigit() || line[i] == '%' || line[i] == '.' || line[i] == '#')) i++
                addSpan(valStart, EditorColorScheme.LITERAL)
            } else {
                addSpan(i, EditorColorScheme.ATTRIBUTE_VALUE)
                i++
            }
        }

        return IncrementalAnalyzeManager.LineTokenizeResult(
            CssState(inComment = inComment, inBlock = inBlock),
            emptyList(),
            spans
        )
    }

    override fun computeBlocks(
        content: Content,
        delegate: CodeBlockAnalyzeDelegate
    ): List<CodeBlock> {
        val blocks = mutableListOf<CodeBlock>()
        val lineCount = content.lineCount
        val braceStack = Stack<Pair<Int, Int>>()

        for (l in 0 until lineCount) {
            if (!delegate.isNotCancelled) break
            val lineStr = content.getLineString(l)
            for (c in lineStr.indices) {
                if (lineStr[c] == '{') {
                    braceStack.push(Pair(l, c))
                } else if (lineStr[c] == '}') {
                    if (braceStack.isNotEmpty()) {
                        val (openLine, openCol) = braceStack.pop()
                        if (l > openLine) {
                            blocks.add(CodeBlock().apply {
                                startLine = openLine
                                startColumn = openCol
                                endLine = l
                                endColumn = c
                            })
                        }
                    }
                }
            }
        }
        return blocks
    }
}
