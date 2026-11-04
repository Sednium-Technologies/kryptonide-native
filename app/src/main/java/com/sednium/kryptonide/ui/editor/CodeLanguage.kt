package com.sednium.kryptonide.ui.editor

import android.os.Bundle
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AsyncIncrementalAnalyzeManager
import io.github.rosemoe.sora.lang.analysis.IncrementalAnalyzeManager
import io.github.rosemoe.sora.lang.completion.CompletionHelper
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.styling.CodeBlock
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.SymbolPairMatch
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.util.Stack

data class CodeLexState(
    val inBlockComment: Boolean = false,
    val inTripleQuote: Boolean = false,
    val tripleQuoteChar: Char = '"'
)

/**
 * Native syntax highlighter, auto-indenter, symbol-pair matcher, and block-guide analyzer
 * for JavaScript, TypeScript, JSX/TSX, Python, Kotlin, Java, JSON, C/C++, Go, Rust, PHP, and Shell.
 */
class CodeLanguage(private val languageId: String) : EmptyLanguage() {

    private val lang = languageId.lowercase()
    private val analyzeManager = CodeAnalyzeManager(lang)

    private val symbolPairs = SymbolPairMatch().apply {
        putPair('{', SymbolPairMatch.SymbolPair("{", "}"))
        putPair('(', SymbolPairMatch.SymbolPair("(", ")"))
        putPair('[', SymbolPairMatch.SymbolPair("[", "]"))
        putPair('"', SymbolPairMatch.SymbolPair("\"", "\""))
        putPair('\'', SymbolPairMatch.SymbolPair("'", "'"))
        putPair('`', SymbolPairMatch.SymbolPair("`", "`"))
    }

    override fun getAnalyzeManager() = analyzeManager
    override fun getSymbolPairs() = symbolPairs
    override fun getInterruptionLevel() = INTERRUPTION_LEVEL_SLIGHT
    override fun useTab() = false

    private val indentStep: Int
        get() = when (lang) {
            "python", "py", "kotlin", "kt", "kts", "java", "rust", "rs" -> 4
            else -> 2
        }

    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int {
        return runCatching {
            val rawLine = content.getLine(line).toString()
            val before = rawLine.substring(0, column.coerceIn(0, rawLine.length))
                .replace(Regex("""//.*$"""), "")
                .let { if (lang in HASH_COMMENT_LANGS) it.replace(Regex("""#.*$"""), "") else it }
                .trim()
            if (before.isEmpty()) return 0

            if (before.endsWith("{") || before.endsWith("(") || before.endsWith("[")) {
                return indentStep
            }
            if (lang in setOf("python", "py", "yaml", "yml") && before.endsWith(":")) {
                return indentStep
            }
            if (lang in setOf("jsx", "tsx")) {
                val openMatch = Regex("""<([a-zA-Z][a-zA-Z0-9._:-]*)(?:\s[^<>]*)?>$""").find(before)
                if (openMatch != null && !before.endsWith("/>")) {
                    val tag = openMatch.groupValues[1]
                    if (tag.lowercase() !in SpckCompletionEngine.VOID_HTML_TAGS && !before.contains("</$tag>")) {
                        return indentStep
                    }
                }
            }
            0
        }.getOrDefault(0)
    }

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle
    ) {
        val prefix = runCatching {
            CompletionHelper.computePrefix(content, position) { ch ->
                ch.isLetterOrDigit() || ch == '_' || ch == '$'
            }
        }.getOrDefault("")
        SpckCompletionEngine.populateCompletions(lang, content, position, prefix, publisher)
    }

    companion object {
        val HASH_COMMENT_LANGS = setOf("python", "py", "sh", "bash", "shell", "yaml", "yml", "toml", "ruby", "rb")
    }
}

class CodeAnalyzeManager(private val lang: String) : AsyncIncrementalAnalyzeManager<CodeLexState, Any>() {

    private val isHashComment = lang in CodeLanguage.HASH_COMMENT_LANGS
    private val isJson = lang == "json"
    private val isJsx = lang in setOf("jsx", "tsx")
    private val keywords: Set<String> = when (lang) {
        "python", "py" -> PYTHON_KEYWORDS
        "kotlin", "kt", "kts" -> KOTLIN_KEYWORDS
        "java" -> JAVA_KEYWORDS
        "rust", "rs" -> RUST_KEYWORDS
        "go", "golang" -> GO_KEYWORDS
        "c", "cpp", "csharp", "cs" -> C_CPP_KEYWORDS
        "sh", "bash", "shell" -> SHELL_KEYWORDS
        else -> JS_TS_KEYWORDS
    }
    private val builtins: Set<String> = when (lang) {
        "python", "py" -> PYTHON_BUILTINS
        "kotlin", "kt", "kts", "java" -> KOTLIN_JAVA_BUILTINS
        else -> JS_TS_BUILTINS
    }

    override fun getInitialState() = CodeLexState()
    override fun stateEquals(state1: CodeLexState, state2: CodeLexState) = state1 == state2
    override fun onAddState(state: CodeLexState) {}
    override fun onAbandonState(state: CodeLexState) {}

    override fun generateSpansForLine(result: IncrementalAnalyzeManager.LineTokenizeResult<CodeLexState, Any>): List<Span> {
        return result.spans ?: emptyList()
    }

    override fun tokenizeLine(
        line: CharSequence,
        state: CodeLexState,
        lineIndex: Int
    ): IncrementalAnalyzeManager.LineTokenizeResult<CodeLexState, Any> {
        val spans = mutableListOf<Span>()
        val len = line.length
        if (len == 0) {
            return IncrementalAnalyzeManager.LineTokenizeResult(state, emptyList(), spans)
        }

        var inBlockComment = state.inBlockComment
        var inTripleQuote = state.inTripleQuote
        var tripleQuoteChar = state.tripleQuoteChar
        var i = 0

        fun addSpan(col: Int, colorId: Int) {
            spans.add(Span.obtain(col, TextStyle.makeStyle(colorId)))
        }

        if (inBlockComment) {
            addSpan(0, EditorColorScheme.COMMENT)
            val endIdx = line.indexOf("*/")
            if (endIdx != -1) {
                inBlockComment = false
                i = endIdx + 2
                if (i < len) addSpan(i, EditorColorScheme.TEXT_NORMAL)
            } else {
                return IncrementalAnalyzeManager.LineTokenizeResult(
                    state.copy(inBlockComment = true),
                    emptyList(),
                    spans
                )
            }
        }

        if (inTripleQuote) {
            addSpan(0, EditorColorScheme.LITERAL)
            val delim = "$tripleQuoteChar$tripleQuoteChar$tripleQuoteChar"
            val endIdx = line.indexOf(delim)
            if (endIdx != -1) {
                inTripleQuote = false
                i = endIdx + 3
                if (i < len) addSpan(i, EditorColorScheme.TEXT_NORMAL)
            } else {
                return IncrementalAnalyzeManager.LineTokenizeResult(
                    state.copy(inTripleQuote = true, tripleQuoteChar = tripleQuoteChar),
                    emptyList(),
                    spans
                )
            }
        }

        while (i < len) {
            val c = line[i]
            when {
                c.isWhitespace() -> {
                    i++
                }
                !isJson && c == '/' && i + 1 < len && line[i + 1] == '/' -> {
                    addSpan(i, EditorColorScheme.COMMENT)
                    break
                }
                !isJson && c == '/' && i + 1 < len && line[i + 1] == '*' -> {
                    addSpan(i, EditorColorScheme.COMMENT)
                    val endIdx = line.indexOf("*/", i + 2)
                    if (endIdx != -1) {
                        i = endIdx + 2
                        if (i < len) addSpan(i, EditorColorScheme.TEXT_NORMAL)
                    } else {
                        inBlockComment = true
                        break
                    }
                }
                isHashComment && c == '#' -> {
                    addSpan(i, EditorColorScheme.COMMENT)
                    break
                }
                (c == '"' || c == '\'') && i + 2 < len && line[i + 1] == c && line[i + 2] == c -> {
                    addSpan(i, EditorColorScheme.LITERAL)
                    val delim = "$c$c$c"
                    val endIdx = line.indexOf(delim, i + 3)
                    if (endIdx != -1) {
                        i = endIdx + 3
                        if (i < len) addSpan(i, EditorColorScheme.TEXT_NORMAL)
                    } else {
                        inTripleQuote = true
                        tripleQuoteChar = c
                        break
                    }
                }
                c == '"' || c == '\'' || c == '`' -> {
                    val strStart = i
                    val quote = c
                    i++
                    while (i < len && line[i] != quote) {
                        if (line[i] == '\\' && i + 1 < len) i++
                        i++
                    }
                    if (i < len && line[i] == quote) i++
                    // In JSON, keys before ':' get ATTRIBUTE_NAME color
                    var probe = i
                    while (probe < len && line[probe].isWhitespace()) probe++
                    if (isJson && probe < len && line[probe] == ':') {
                        addSpan(strStart, EditorColorScheme.ATTRIBUTE_NAME)
                    } else {
                        addSpan(strStart, EditorColorScheme.LITERAL)
                    }
                }
                c == '@' && i + 1 < len && (line[i + 1].isLetter() || line[i + 1] == '_') -> {
                    val annStart = i
                    i++
                    while (i < len && (line[i].isLetterOrDigit() || line[i] == '_' || line[i] == '.')) i++
                    addSpan(annStart, EditorColorScheme.FUNCTION_NAME)
                }
                isJsx && c == '<' && i + 1 < len && (line[i + 1].isLetter() || line[i + 1] == '/' || line[i + 1] == '>') -> {
                    val tagStart = i
                    i++
                    if (i < len && line[i] == '/') i++
                    while (i < len && (line[i].isLetterOrDigit() || line[i] == '.' || line[i] == '_' || line[i] == '-')) i++
                    addSpan(tagStart, EditorColorScheme.HTML_TAG)
                }
                c.isDigit() -> {
                    val numStart = i
                    while (i < len && (line[i].isLetterOrDigit() || line[i] == '.' || line[i] == '_')) i++
                    addSpan(numStart, EditorColorScheme.LITERAL)
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    val wordStart = i
                    while (i < len && (line[i].isLetterOrDigit() || line[i] == '_' || line[i] == '$')) i++
                    val word = line.substring(wordStart, i)
                    var nextNonSpace = i
                    while (nextNonSpace < len && line[nextNonSpace].isWhitespace()) nextNonSpace++
                    val isCall = nextNonSpace < len && line[nextNonSpace] == '('

                    when {
                        word in keywords -> addSpan(wordStart, EditorColorScheme.KEYWORD)
                        word in builtins -> addSpan(wordStart, EditorColorScheme.ATTRIBUTE_NAME)
                        isCall -> addSpan(wordStart, EditorColorScheme.FUNCTION_NAME)
                        word.first().isUpperCase() -> addSpan(wordStart, EditorColorScheme.ATTRIBUTE_NAME)
                        else -> addSpan(wordStart, EditorColorScheme.IDENTIFIER_NAME)
                    }
                }
                else -> {
                    addSpan(i, EditorColorScheme.OPERATOR)
                    i++
                }
            }
        }

        if (spans.isEmpty()) {
            addSpan(0, EditorColorScheme.TEXT_NORMAL)
        }

        return IncrementalAnalyzeManager.LineTokenizeResult(
            CodeLexState(inBlockComment, inTripleQuote, tripleQuoteChar),
            emptyList(),
            spans
        )
    }

    override fun computeBlocks(content: Content, delegate: CodeBlockAnalyzeDelegate): List<CodeBlock> {
        val blocks = mutableListOf<CodeBlock>()
        val lineCount = content.lineCount
        val braceStack = Stack<Pair<Int, Int>>()

        for (l in 0 until lineCount) {
            if (!delegate.isNotCancelled) break
            val lineStr = content.getLineString(l)
            for (c in lineStr.indices) {
                val ch = lineStr[c]
                if (ch == '{') {
                    braceStack.push(Pair(l, c))
                } else if (ch == '}') {
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

    companion object {
        private val JS_TS_KEYWORDS = setOf(
            "const", "let", "var", "function", "return", "if", "else", "for", "while", "do",
            "switch", "case", "break", "continue", "default", "import", "from", "export", "as",
            "class", "extends", "implements", "interface", "type", "enum", "new", "try", "catch",
            "finally", "throw", "async", "await", "yield", "typeof", "instanceof", "in", "of",
            "delete", "void", "static", "get", "set", "public", "private", "protected", "readonly"
        )
        private val JS_TS_BUILTINS = setOf(
            "true", "false", "null", "undefined", "NaN", "Infinity", "this", "super",
            "console", "window", "document", "localStorage", "sessionStorage", "navigator",
            "fetch", "Promise", "Array", "Object", "String", "Number", "Boolean", "Math", "JSON",
            "Date", "Map", "Set", "RegExp", "Error", "Symbol", "BigInt",
            "useState", "useEffect", "useRef", "useMemo", "useCallback", "useContext"
        )
        private val PYTHON_KEYWORDS = setOf(
            "def", "class", "return", "if", "elif", "else", "for", "while", "break", "continue",
            "pass", "import", "from", "as", "try", "except", "finally", "raise", "with", "async",
            "await", "yield", "lambda", "and", "or", "not", "in", "is", "global", "nonlocal",
            "assert", "del", "match", "case"
        )
        private val PYTHON_BUILTINS = setOf(
            "True", "False", "None", "self", "cls", "print", "len", "range", "enumerate", "zip",
            "map", "filter", "sorted", "reversed", "sum", "min", "max", "abs", "round", "open",
            "str", "int", "float", "bool", "list", "dict", "set", "tuple", "type", "isinstance",
            "super", "Exception", "ValueError", "TypeError", "KeyError", "IndexError"
        )
        private val KOTLIN_KEYWORDS = setOf(
            "package", "import", "class", "interface", "object", "fun", "val", "var", "return",
            "if", "else", "when", "for", "while", "do", "break", "continue", "try", "catch",
            "finally", "throw", "is", "in", "as", "by", "companion", "constructor", "init",
            "data", "sealed", "enum", "open", "abstract", "override", "private", "protected",
            "internal", "public", "suspend", "inline", "reified", "lateinit", "const", "typealias"
        )
        private val JAVA_KEYWORDS = setOf(
            "package", "import", "class", "interface", "enum", "extends", "implements",
            "public", "private", "protected", "static", "final", "abstract", "synchronized",
            "volatile", "transient", "native", "void", "return", "if", "else", "switch", "case",
            "default", "for", "while", "do", "break", "continue", "try", "catch", "finally",
            "throw", "throws", "new", "instanceof", "assert", "record", "var"
        )
        private val KOTLIN_JAVA_BUILTINS = setOf(
            "true", "false", "null", "this", "super", "it", "Unit", "Nothing", "Any",
            "String", "Int", "Long", "Float", "Double", "Boolean", "Char", "Byte", "Short",
            "List", "MutableList", "Map", "MutableMap", "Set", "MutableSet", "Array",
            "println", "print", "listOf", "mutableListOf", "mapOf", "setOf", "emptyList",
            "remember", "mutableStateOf", "LaunchedEffect", "DisposableEffect", "Modifier"
        )
        private val RUST_KEYWORDS = setOf(
            "fn", "let", "mut", "const", "static", "struct", "enum", "impl", "trait", "type",
            "mod", "use", "pub", "crate", "self", "super", "where", "for", "in", "while", "loop",
            "if", "else", "match", "return", "break", "continue", "async", "await", "move",
            "ref", "unsafe", "extern", "true", "false", "Some", "None", "Ok", "Err"
        )
        private val GO_KEYWORDS = setOf(
            "package", "import", "func", "var", "const", "type", "struct", "interface", "map",
            "chan", "go", "defer", "select", "case", "default", "if", "else", "switch", "for",
            "range", "return", "break", "continue", "fallthrough", "true", "false", "nil"
        )
        private val C_CPP_KEYWORDS = setOf(
            "int", "long", "short", "char", "float", "double", "void", "bool", "auto", "const",
            "static", "extern", "struct", "class", "enum", "union", "namespace", "template",
            "typename", "public", "private", "protected", "virtual", "override", "return",
            "if", "else", "switch", "case", "default", "for", "while", "do", "break", "continue",
            "new", "delete", "try", "catch", "throw", "sizeof", "typedef", "using", "true", "false", "nullptr", "NULL"
        )
        private val SHELL_KEYWORDS = setOf(
            "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "until",
            "case", "esac", "function", "return", "exit", "export", "local", "readonly",
            "echo", "cd", "pwd", "ls", "mkdir", "rm", "cp", "mv", "cat", "grep", "sed", "awk"
        )
    }
}
