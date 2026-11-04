package com.sednium.kryptonide.ui.editor

/**
 * A minimal Emmet-style HTML abbreviation expander.
 *
 * Supports the flat-chain subset that covers the overwhelming majority of
 * everyday usage:
 *   - tag names:        div, span, ul, li, a, img, input...
 *   - class shorthand:  div.container.flex  -> <div class="container flex">
 *   - id shorthand:     div#app             -> <div id="app">
 *   - child nesting:    div>ul>li           -> <div><ul><li></li></ul></div>
 *   - siblings:         div+span            -> <div></div><span></span>
 *   - multiplication:   li*3                -> <li></li><li></li><li></li>
 *   - text content:     a{Click here}       -> <a>Click here</a>
 *
 * NOT supported: parenthetical grouping — e.g. `ul>(li.item*3>a)+footer` — a
 * real Emmet engine's climbing/grouping syntax. That's a meaningfully bigger
 * parser (proper operator precedence with backtracking), and this is meant
 * to be an honest, useful subset rather than a silently-incomplete "full"
 * implementation. Abbreviations using `(`/`)` are left unexpanded (returns
 * null) rather than producing something subtly wrong.
 *
 * `input`, `img`, `br`, `hr` and other void elements are emitted without a
 * closing tag / children, matching HTML5 void-element rules.
 */
object EmmetExpander {

    private val VOID_TAGS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr"
    )

    private data class Node(
        val tag: String,
        val classes: List<String> = emptyList(),
        val id: String? = null,
        val customAttrs: List<Pair<String, String>> = emptyList(),
        val text: String? = null,
        val count: Int = 1,
        val children: MutableList<Node> = mutableListOf(),
    )

    /** Returns the expanded HTML, or null if `abbreviation` doesn't look like valid Emmet
     *  syntax (including anything using grouping parens, which this subset doesn't support). */
    fun expand(abbreviation: String, baseIndent: String = ""): String? {
        val trimmed = abbreviation.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed == "!" || trimmed.equals("html:5", ignoreCase = true) || trimmed.equals("doc", ignoreCase = true)) {
            val lines = listOf(
                "<!DOCTYPE html>",
                "<html lang=\"en\">",
                "<head>",
                "    <meta charset=\"UTF-8\">",
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">",
                "    <title>Document</title>",
                "</head>",
                "<body>",
                "    ",
                "</body>",
                "</html>"
            )
            return lines.mapIndexed { idx, line -> if (idx == 0) line else "$baseIndent$line" }.joinToString("\n")
        }
        if (trimmed.contains('(') || trimmed.contains(')')) return null // grouping — unsupported, be honest
        if (!Regex("^[a-zA-Z.#][a-zA-Z0-9.#{}*+>:_\\[\\]=\"'-]*$").matches(trimmed)) return null

        return try {
            val siblingChains = splitTopLevel(trimmed, '+')
            val roots = siblingChains.map { parseChain(it) }
            val raw = roots.joinToString("") { renderNode(it, 0) }.ifEmpty { return null }
            if (baseIndent.isEmpty()) {
                raw
            } else {
                raw.trimEnd('\n')
                    .lines()
                    .mapIndexed { idx, line -> if (idx == 0) line else "$baseIndent$line" }
                    .joinToString("\n")
            }
        } catch (_: Exception) {
            null // malformed abbreviation — leave the user's text alone rather than guess
        }
    }

    private fun splitTopLevel(s: String, delimiter: Char): List<String> {
        // '+' or '>' inside a {text} or [attr] block shouldn't split the chain — track depth.
        val parts = mutableListOf<String>()
        var braceDepth = 0
        var bracketDepth = 0
        var start = 0
        s.forEachIndexed { i, c ->
            when (c) {
                '{' -> braceDepth++
                '}' -> (braceDepth - 1).coerceAtLeast(0).also { braceDepth = it }
                '[' -> bracketDepth++
                ']' -> (bracketDepth - 1).coerceAtLeast(0).also { bracketDepth = it }
                delimiter -> if (braceDepth == 0 && bracketDepth == 0) {
                    parts.add(s.substring(start, i))
                    start = i + 1
                }
            }
        }
        parts.add(s.substring(start))
        return parts
    }

    /** Parses one `tag.class#id{text}*N>child>child` chain into a linked Node tree. */
    private fun parseChain(chain: String): Node {
        val segments = splitTopLevel(chain, '>')
        val nodes = segments.map { parseSegment(it) }
        for (i in 0 until nodes.size - 1) nodes[i].children.add(nodes[i + 1])
        return nodes.first()
    }

    private fun parseSegment(segment: String): Node {
        var s = segment
        var count = 1
        var text: String? = null
        val customAttrs = mutableListOf<Pair<String, String>>()

        val braceIdx = s.indexOf('{')
        if (braceIdx != -1 && s.endsWith("}")) {
            text = s.substring(braceIdx + 1, s.length - 1)
            s = s.substring(0, braceIdx)
        }

        val starIdx = s.lastIndexOf('*')
        if (starIdx != -1 && starIdx > s.lastIndexOf(']')) {
            count = s.substring(starIdx + 1).toIntOrNull()?.coerceIn(1, 100) ?: 1
            s = s.substring(0, starIdx)
        }

        // Extract [attr=val] blocks
        val bracketStart = s.indexOf('[')
        val bracketEnd = s.lastIndexOf(']')
        if (bracketStart != -1 && bracketEnd > bracketStart) {
            val attrBlock = s.substring(bracketStart + 1, bracketEnd)
            s = s.substring(0, bracketStart) + s.substring(bracketEnd + 1)
            for (part in attrBlock.split(' ').filter { it.isNotBlank() }) {
                val eq = part.indexOf('=')
                if (eq != -1) {
                    val k = part.substring(0, eq).trim()
                    val v = part.substring(eq + 1).trim().trim('"', '\'')
                    customAttrs.add(k to v)
                } else {
                    customAttrs.add(part.trim() to "")
                }
            }
        }

        // Handle tag:shorthand (e.g., input:password, a:link, link:css, script:src)
        if (s.contains(':')) {
            val colonIdx = s.indexOf(':')
            val baseTag = s.substring(0, colonIdx)
            val subType = s.substring(colonIdx + 1)
            when (baseTag.lowercase()) {
                "input", "inp" -> {
                    s = "input"
                    customAttrs.add("type" to subType)
                }
                "link" -> {
                    s = "link"
                    if (subType == "css") {
                        customAttrs.add("rel" to "stylesheet")
                        customAttrs.add("href" to "style.css")
                    }
                }
                "script" -> {
                    s = "script"
                    if (subType == "src") {
                        customAttrs.add("src" to "main.js")
                    }
                }
                "a" -> {
                    s = "a"
                    customAttrs.add("href" to if (subType == "mail") "mailto:" else "https://")
                }
                else -> s = baseTag
            }
        }

        val classes = mutableListOf<String>()
        var id: String? = null
        val tagBuilder = StringBuilder()
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '.' -> {
                    val end = s.indexOfFirst2(i + 1, '.', '#')
                    val cls = s.substring(i + 1, end)
                    if (cls.isNotEmpty()) classes.add(cls)
                    i = end
                }
                '#' -> {
                    val end = s.indexOfFirst2(i + 1, '.', '#')
                    val idVal = s.substring(i + 1, end)
                    if (idVal.isNotEmpty()) id = idVal
                    i = end
                }
                else -> { tagBuilder.append(s[i]); i++ }
            }
        }
        val rawTag = tagBuilder.toString().ifBlank { "div" } // ".foo" alone implies a div, same as real Emmet
        val tag = when (rawTag.lowercase()) {
            "btn" -> "button"
            "inp" -> "input"
            "bq" -> "blockquote"
            "hdr" -> "header"
            "ftr" -> "footer"
            "sect" -> "section"
            "art" -> "article"
            "pic" -> "picture"
            else -> rawTag
        }

        return Node(tag = tag, classes = classes, id = id, customAttrs = customAttrs, text = text, count = count)
    }

    private fun String.indexOfFirst2(from: Int, a: Char, b: Char): Int {
        for (i in from until length) if (this[i] == a || this[i] == b) return i
        return length
    }

    private fun renderNode(node: Node, indent: Int): String {
        val pad = "    ".repeat(indent)
        val sb = StringBuilder()
        repeat(node.count) {
            val attrs = buildString {
                if (node.id != null) append(" id=\"${node.id}\"")
                if (node.classes.isNotEmpty()) append(" class=\"${node.classes.joinToString(" ")}\"")
                for ((k, v) in node.customAttrs) {
                    if (v.isEmpty()) append(" $k") else append(" $k=\"$v\"")
                }
            }
            if (node.tag.lowercase() in VOID_TAGS) {
                sb.append("$pad<${node.tag}$attrs>\n")
            } else if (node.children.isEmpty()) {
                val body = node.text ?: ""
                sb.append("$pad<${node.tag}$attrs>$body</${node.tag}>\n")
            } else {
                sb.append("$pad<${node.tag}$attrs>\n")
                node.children.forEach { sb.append(renderNode(it, indent + 1)) }
                sb.append("$pad</${node.tag}>\n")
            }
        }
        return sb.toString()
    }
}
