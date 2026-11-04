package com.sednium.kryptonide.ui.editor

import android.os.Bundle
import com.sednium.kryptonide.ui.lsp.LspManager
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.completion.CompletionHelper
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleSnippetCompletionItem
import io.github.rosemoe.sora.lang.completion.SnippetDescription
import io.github.rosemoe.sora.lang.completion.snippet.parser.CodeSnippetParser
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wraps a base Sora [Language] and layers real LSP-backed autocomplete
 * and native tab-stop snippet expansion on top.
 */
data class SnippetItem(val prefix: String, val label: String, val insertText: String, val detail: String)

private val REACT_SNIPPETS = listOf(
    SnippetItem("rfc", "rfc (React Function Component)", "export default function \${1:Component}() {\n  return (\n    <div>\n      \$0\n    </div>\n  );\n}", "React Component"),
    SnippetItem("useState", "useState", "const [\${1:state}, set\${2:State}] = useState(\${3:initialState});\$0", "React Hook"),
    SnippetItem("useEffect", "useEffect", "useEffect(() => {\n  \$0\n}, [\${1}]);", "React Hook"),
    SnippetItem("useCallback", "useCallback", "const \${1:callback} = useCallback(() => {\n  \$0\n}, [\${2}]);", "React Hook"),
    SnippetItem("useMemo", "useMemo", "const \${1:memoized} = useMemo(() => \${2:compute()}, [\${3}]);", "React Hook"),
    SnippetItem("clg", "clg (console.log)", "console.log(\$0);", "Console Log"),
)

private val KOTLIN_SNIPPETS = listOf(
    SnippetItem("comp", "comp (Composable)", "@Composable\nfun \${1:Component}() {\n    \$0\n}", "Compose Snippet"),
    SnippetItem("dataclass", "dataclass", "data class \${1:MyData}(\n    val \${2:id}: \${3:String}\n)", "Kotlin Data Class"),
    SnippetItem("launch", "launch (Coroutine)", "scope.launch {\n    \$0\n}", "Coroutine Scope"),
    SnippetItem("flow", "flow", "flow {\n    emit(\${1:value})\n}", "Kotlin Flow"),
    SnippetItem("state", "state (mutableStateOf)", "var \${1:state} by remember { mutableStateOf(\${2:\"\"}) }", "Compose State"),
)

private val PYTHON_SNIPPETS = listOf(
    SnippetItem("def", "def (Function)", "def \${1:function_name}(\${2:args}):\n    \${0:pass}", "Python Function"),
    SnippetItem("class", "class (Class Definition)", "class \${1:ClassName}:\n    def __init__(self\${2:, args}):\n        \${0:pass}", "Python Class"),
    SnippetItem("main", "main (if __name__ == '__main__')", "if __name__ == \"__main__\":\n    \${0:main()}", "Python Main Guard"),
    SnippetItem("for", "for (For Loop)", "for \${1:item} in \${2:iterable}:\n    \${0:pass}", "Python Loop"),
    SnippetItem("try", "try/except (Try Block)", "try:\n    \${1:pass}\nexcept \${2:Exception} as e:\n    \${0:pass}", "Python Try-Except"),
    SnippetItem("pr", "pr (print)", "print(\$0)", "Python Print"),
)

private val WEB_SNIPPETS = listOf(
    SnippetItem("html5", "html5 (HTML5 Skeleton)", "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n    <meta charset=\"UTF-8\">\n    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n    <title>\${1:Document}</title>\n</head>\n<body>\n    \$0\n</body>\n</html>", "HTML5 Template"),
    SnippetItem("script", "script (Script Tag)", "<script src=\"\${1:main.js}\"></script>\$0", "HTML Element"),
    SnippetItem("link", "link (CSS Stylesheet)", "<link rel=\"stylesheet\" href=\"\${1:style.css}\">\$0", "HTML Element"),
    SnippetItem("div", "div (DIV Tag)", "<div class=\"\${1}\">\$0</div>", "HTML Element"),
)

class KryptonLspLanguage(
    private val base: Language,
    private val lspManager: LspManager,
    private val languageId: String,
    private val uriProvider: () -> String
) : Language by base {

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
        val prefixLen = prefix.length

        // 1. Populate Spck-style tag, closing tag, attribute, Emmet, CSS, and code/line suggestions
        val hasSpckCompletions = runCatching {
            SpckCompletionEngine.populateCompletions(
                languageId = languageId,
                content = content,
                position = position,
                prefix = prefix,
                publisher = publisher
            )
        }.getOrDefault(false)

        // If prefix is empty and no contextual trigger (<, </, attr="", property:) fired, stop here!
        // This prevents completion popups from appearing on spaces, semicolons, braces, or cursor moves.
        if (prefix.isEmpty() && !hasSpckCompletions) {
            return
        }

        // 2. Include base language completions if base is an external language implementation
        if (prefix.isNotEmpty() && base !is HtmlLanguage && base !is CssLanguage && base !is CodeLanguage) {
            runCatching {
                base.requireAutoComplete(content, position, publisher, extraArguments)
            }
        }

        // 3. Inject Monaco JS Completion Providers (filtered by prefix so plugins don't spam on empty prefix)
        if (prefix.isNotEmpty()) {
            val monacoItems = runCatching {
                runBlocking {
                    withTimeoutOrNull(180L) {
                        com.sednium.kryptonide.ui.monaco.MonacoRuntime.requestCompletions(
                            languageId = languageId,
                            line = position.line + 1,
                            column = position.column + 1,
                            word = prefix
                        )
                    }
                }
            }.getOrNull() ?: emptyList()

            for (mItem in monacoItems) {
                if (mItem.label.startsWith(prefix, ignoreCase = true)) {
                    val soraItem = com.sednium.kryptonide.ui.monaco.SoraMonacoAdapter.monacoCompletionToSoraItem(prefixLen, mItem)
                    publisher.addItem(soraItem)
                }
            }
        }

        // 4. Query active LSP server (only if already READY and prefix is non-empty)
        if (prefix.isNotEmpty()) {
            val items = runCatching {
                runBlocking {
                    withTimeoutOrNull(250L) {
                        lspManager.completion(languageId, uriProvider(), position.line + 1, position.column + 1)
                    }
                }
            }.getOrNull() ?: return

            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val label = item.optString("label")
                if (label.isBlank() || !label.startsWith(prefix, ignoreCase = true)) continue
                val insertText = item.optString("insertText", label)
                val detail = item.optString("detail", "")
                val isSnippet = item.optInt("insertTextFormat", 1) == 2

                if (isSnippet) {
                    var added = false
                    runCatching {
                        val parsed = CodeSnippetParser.parse(insertText)
                        val desc = SnippetDescription(prefixLen, parsed, true)
                        publisher.addItem(SimpleSnippetCompletionItem(label, detail, desc))
                        added = true
                    }
                    if (added) continue
                }

                publisher.addItem(object : CompletionItem(label, detail) {
                    override fun performCompletion(editor: CodeEditor, text: Content, line: Int, column: Int) {
                        if (prefixLen > 0 && column - prefixLen >= 0) {
                            text.delete(line, column - prefixLen, line, column)
                            text.insert(line, column - prefixLen, insertText)
                        } else {
                            text.insert(line, column, insertText)
                        }
                    }
                })
            }
        }
    }
}


