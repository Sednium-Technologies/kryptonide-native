package com.sednium.kryptonide.ui.plugins

/**
 * Theme payloads for every theme in the catalog, in the JSON format this
 * app's earlier Monaco-based editor used (`editor.defineTheme()`-style
 * token/color rules). Sora's CodeEditor (the current editor surface) uses
 * [io.github.rosemoe.sora.widget.schemes.EditorColorScheme] instead, so
 * these aren't applied to the editor anywhere right now — kept as the
 * source color data for a Sora EditorColorScheme port, which is a
 * follow-up, not guessed at here.
 *
 * Color values ported from the reference build of this product (the
 * React/Capacitor iteration), which had these properly defined — including
 * `variable`/`tag`/`attribute.name`/`attribute.value` token rules this
 * app's first pass at these themes didn't have.
 */
object BuiltInThemes {

    data class ThemeDef(val id: String, val json: String)

    val ALL: List<ThemeDef> = listOf(
        ThemeDef("one-dark-pro", """
            {
                "base": "vs-dark", "inherit": true,
                "rules": [
                    { "token": "comment", "foreground": "5c6370", "fontStyle": "italic" },
                    { "token": "keyword", "foreground": "c678dd" },
                    { "token": "string", "foreground": "98c379" },
                    { "token": "number", "foreground": "d19a66" },
                    { "token": "type", "foreground": "e5c07b" },
                    { "token": "function", "foreground": "61afef" },
                    { "token": "variable", "foreground": "e06c75" },
                    { "token": "tag", "foreground": "e06c75" },
                    { "token": "attribute.name", "foreground": "d19a66" },
                    { "token": "attribute.value", "foreground": "98c379" }
                ],
                "colors": {
                    "editor.background": "#282c34",
                    "editor.foreground": "#abb2bf",
                    "editorCursor.foreground": "#528bff",
                    "editor.lineHighlightBackground": "#2c313c",
                    "editorLineNumber.foreground": "#495162",
                    "editor.selectionBackground": "#3e4451",
                    "editorIndentGuide.background": "#3b4048"
                }
            }
        """.trimIndent()),
        ThemeDef("dracula", """
            {
                "base": "vs-dark", "inherit": true,
                "rules": [
                    { "token": "comment", "foreground": "6272a4", "fontStyle": "italic" },
                    { "token": "keyword", "foreground": "ff79c6" },
                    { "token": "string", "foreground": "f1fa8c" },
                    { "token": "number", "foreground": "bd93f9" },
                    { "token": "type", "foreground": "8be9fd", "fontStyle": "italic" },
                    { "token": "function", "foreground": "50fa7b" },
                    { "token": "variable", "foreground": "f8f8f2" },
                    { "token": "tag", "foreground": "ff79c6" },
                    { "token": "attribute.name", "foreground": "50fa7b" },
                    { "token": "attribute.value", "foreground": "f1fa8c" }
                ],
                "colors": {
                    "editor.background": "#282a36",
                    "editor.foreground": "#f8f8f2",
                    "editorCursor.foreground": "#f8f8f0",
                    "editor.lineHighlightBackground": "#44475a",
                    "editorLineNumber.foreground": "#6272a4",
                    "editor.selectionBackground": "#44475a"
                }
            }
        """.trimIndent()),
        ThemeDef("github-dark", """
            {
                "base": "vs-dark", "inherit": true,
                "rules": [
                    { "token": "comment", "foreground": "8b949e", "fontStyle": "italic" },
                    { "token": "keyword", "foreground": "ff7b72" },
                    { "token": "string", "foreground": "a5d6ff" },
                    { "token": "number", "foreground": "79c0ff" },
                    { "token": "type", "foreground": "ffa657" },
                    { "token": "function", "foreground": "d2a8ff" },
                    { "token": "variable", "foreground": "ffa657" },
                    { "token": "tag", "foreground": "7ee787" },
                    { "token": "attribute.name", "foreground": "79c0ff" },
                    { "token": "attribute.value", "foreground": "a5d6ff" }
                ],
                "colors": {
                    "editor.background": "#0d1117",
                    "editor.foreground": "#c9d1d9",
                    "editorCursor.foreground": "#58a6ff",
                    "editor.lineHighlightBackground": "#161b22",
                    "editorLineNumber.foreground": "#484f58",
                    "editor.selectionBackground": "#264f78"
                }
            }
        """.trimIndent()),
        ThemeDef("monokai", """
            {
                "base": "vs-dark", "inherit": true,
                "rules": [
                    { "token": "comment", "foreground": "75715e", "fontStyle": "italic" },
                    { "token": "keyword", "foreground": "f92672" },
                    { "token": "string", "foreground": "e6db74" },
                    { "token": "number", "foreground": "ae81ff" },
                    { "token": "type", "foreground": "66d9ef", "fontStyle": "italic" },
                    { "token": "function", "foreground": "a6e22e" },
                    { "token": "variable", "foreground": "f8f8f2" },
                    { "token": "tag", "foreground": "f92672" },
                    { "token": "attribute.name", "foreground": "a6e22e" },
                    { "token": "attribute.value", "foreground": "e6db74" }
                ],
                "colors": {
                    "editor.background": "#272822",
                    "editor.foreground": "#f8f8f2",
                    "editorCursor.foreground": "#f8f8f0",
                    "editor.lineHighlightBackground": "#3e3d32",
                    "editorLineNumber.foreground": "#75715e",
                    "editor.selectionBackground": "#49483e"
                }
            }
        """.trimIndent()),
        ThemeDef("nord", """
            {
                "base": "vs-dark", "inherit": true,
                "rules": [
                    { "token": "comment", "foreground": "616e88", "fontStyle": "italic" },
                    { "token": "keyword", "foreground": "81a1c1" },
                    { "token": "string", "foreground": "a3be8c" },
                    { "token": "number", "foreground": "b48ead" },
                    { "token": "type", "foreground": "8fbcbb" },
                    { "token": "function", "foreground": "88c0d0" },
                    { "token": "variable", "foreground": "d8dee9" }
                ],
                "colors": {
                    "editor.background": "#2e3440",
                    "editor.foreground": "#d8dee9",
                    "editorCursor.foreground": "#d8dee9",
                    "editor.lineHighlightBackground": "#3b4252",
                    "editorLineNumber.foreground": "#4c566a",
                    "editor.selectionBackground": "#434c5e"
                }
            }
        """.trimIndent()),
        ThemeDef("catppuccin-mocha", """
            {
                "base": "vs-dark", "inherit": true,
                "rules": [
                    { "token": "comment", "foreground": "6c7086", "fontStyle": "italic" },
                    { "token": "keyword", "foreground": "cba6f7" },
                    { "token": "string", "foreground": "a6e3a1" },
                    { "token": "number", "foreground": "fab387" },
                    { "token": "type", "foreground": "f9e2af" },
                    { "token": "function", "foreground": "89b4fa" },
                    { "token": "variable", "foreground": "cdd6f4" }
                ],
                "colors": {
                    "editor.background": "#1e1e2e",
                    "editor.foreground": "#cdd6f4",
                    "editorCursor.foreground": "#f5e0dc",
                    "editor.lineHighlightBackground": "#313244",
                    "editorLineNumber.foreground": "#585b70",
                    "editor.selectionBackground": "#45475a"
                }
            }
        """.trimIndent())
    )
}
