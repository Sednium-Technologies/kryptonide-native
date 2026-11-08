package com.sednium.kryptonide.ui.editor

import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleSnippetCompletionItem
import io.github.rosemoe.sora.lang.completion.SnippetDescription
import io.github.rosemoe.sora.lang.completion.snippet.parser.CodeSnippetParser
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.CodeEditor
import java.util.Stack

/**
 * Spck Editor-style intelligent code completion engine for KryptonIDE.
 *
 * Provides:
 * 1. Context-aware HTML5 tag completions (`<div`, `div`, `<!DOCTYPE html>`, etc.)
 * 2. Smart auto-closing tag suggestions (`</parentTag>`) based on unclosed tag stack
 * 3. Tag-specific and global HTML attribute & attribute-value suggestions (`bgcolor=""`, `class=""`, `href=""`, etc.)
 * 4. Live Emmet abbreviation preview and instant expansion (`div.card`, `ul>li*3`, `!`, etc.)
 * 5. Embedded `<style>` (CSS) and `<script>` (JS) context switching inside HTML files
 * 6. Full CSS property, shorthand line, and property-specific value suggestions
 * 7. Rich line/statement templates for JS/TS/React, Python, Kotlin, Java, C/C++
 * 8. Fast in-document word/identifier completion from the active buffer
 */
object SpckCompletionEngine {

    @Volatile
    var emmetEnabled: Boolean = true

    val VOID_HTML_TAGS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img",
        "input", "link", "meta", "param", "source", "track", "wbr", "!doctype"
    )

    data class HtmlTagSpec(
        val tag: String,
        val detail: String,
        val snippetBody: String? = null, // Custom snippet inside/including tag when expanded as full element
        val isVoid: Boolean = tag.lowercase() in VOID_HTML_TAGS
    )

    data class AttrSpec(
        val name: String,
        val detail: String,
        val isBoolean: Boolean = false,
        val defaultValue: String = ""
    )

    data class CssPropSpec(
        val property: String,
        val detail: String,
        val commonValues: List<String> = emptyList(),
        val defaultSnippetValue: String = "\$1"
    )

    // ── Complete HTML5 + Legacy Tag Catalog (Spck style) ─────────────────────
    private val HTML_TAGS = listOf(
        HtmlTagSpec("!DOCTYPE", "HTML5 Document Type", "<!DOCTYPE html>\n\$0", isVoid = true),
        HtmlTagSpec("html", "HTML Root Element", "<html lang=\"\${1:en}\">\n  \$0\n</html>"),
        HtmlTagSpec("head", "Document Metadata Header", "<head>\n  \$0\n</head>"),
        HtmlTagSpec("title", "Document Title", "<title>\$0</title>"),
        HtmlTagSpec("body", "Document Body", "<body>\n  \$0\n</body>"),
        HtmlTagSpec("div", "Generic Flow Container", "<div>\$0</div>"),
        HtmlTagSpec("span", "Inline Container", "<span>\$0</span>"),
        HtmlTagSpec("p", "Paragraph", "<p>\$0</p>"),
        HtmlTagSpec("a", "Anchor / Hyperlink", "<a href=\"\${1:#}\">\$0</a>"),
        HtmlTagSpec("button", "Clickable Button", "<button type=\"\${1:button}\">\$0</button>"),
        HtmlTagSpec("input", "Input Field", "<input type=\"\${1:text}\" placeholder=\"\$2\">", isVoid = true),
        HtmlTagSpec("img", "Image Embed", "<img src=\"\$1\" alt=\"\$2\">", isVoid = true),
        HtmlTagSpec("form", "Interactive Form", "<form action=\"\$1\" method=\"\${2:post}\">\n  \$0\n</form>"),
        HtmlTagSpec("label", "Input Control Label", "<label for=\"\$1\">\$0</label>"),
        HtmlTagSpec("select", "Dropdown Select", "<select name=\"\$1\" id=\"\$2\">\n  <option value=\"\$3\">\$0</option>\n</select>"),
        HtmlTagSpec("option", "Select Option", "<option value=\"\$1\">\$0</option>"),
        HtmlTagSpec("textarea", "Multiline Text Input", "<textarea name=\"\$1\" rows=\"\${2:4}\">\$0</textarea>"),
        HtmlTagSpec("ul", "Unordered List", "<ul>\n  <li>\$0</li>\n</ul>"),
        HtmlTagSpec("ol", "Ordered List", "<ol>\n  <li>\$0</li>\n</ol>"),
        HtmlTagSpec("li", "List Item", "<li>\$0</li>"),
        HtmlTagSpec("h1", "Heading Level 1", "<h1>\$0</h1>"),
        HtmlTagSpec("h2", "Heading Level 2", "<h2>\$0</h2>"),
        HtmlTagSpec("h3", "Heading Level 3", "<h3>\$0</h3>"),
        HtmlTagSpec("h4", "Heading Level 4", "<h4>\$0</h4>"),
        HtmlTagSpec("h5", "Heading Level 5", "<h5>\$0</h5>"),
        HtmlTagSpec("h6", "Heading Level 6", "<h6>\$0</h6>"),
        HtmlTagSpec("header", "Introductory / Navigation Header", "<header>\n  \$0\n</header>"),
        HtmlTagSpec("nav", "Navigation Section", "<nav>\n  \$0\n</nav>"),
        HtmlTagSpec("main", "Main Content Area", "<main>\n  \$0\n</main>"),
        HtmlTagSpec("section", "Standalone Section", "<section>\n  \$0\n</section>"),
        HtmlTagSpec("article", "Self-Contained Composition", "<article>\n  \$0\n</article>"),
        HtmlTagSpec("aside", "Sidebar / Indirect Content", "<aside>\n  \$0\n</aside>"),
        HtmlTagSpec("footer", "Footer Section", "<footer>\n  \$0\n</footer>"),
        HtmlTagSpec("script", "Embedded / External Script", "<script>\n  \$0\n</script>"),
        HtmlTagSpec("script:src", "External JavaScript File", "<script src=\"\${1:main.js}\"></script>\$0"),
        HtmlTagSpec("style", "Embedded CSS Styles", "<style>\n  \$0\n</style>"),
        HtmlTagSpec("link", "External Resource Link", "<link rel=\"\${1:stylesheet}\" href=\"\${2:style.css}\">", isVoid = true),
        HtmlTagSpec("meta", "Document Metadata", "<meta name=\"\$1\" content=\"\$2\">", isVoid = true),
        HtmlTagSpec("meta:vp", "Viewport Meta Tag", "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">", isVoid = true),
        HtmlTagSpec("br", "Line Break", "<br>", isVoid = true),
        HtmlTagSpec("hr", "Thematic Break / Horizontal Rule", "<hr>", isVoid = true),
        HtmlTagSpec("table", "Data Table", "<table>\n  <thead>\n    <tr>\n      <th>\$1</th>\n    </tr>\n  </thead>\n  <tbody>\n    <tr>\n      <td>\$0</td>\n    </tr>\n  </tbody>\n</table>"),
        HtmlTagSpec("thead", "Table Header Group", "<thead>\n  \$0\n</thead>"),
        HtmlTagSpec("tbody", "Table Body Group", "<tbody>\n  \$0\n</tbody>"),
        HtmlTagSpec("tfoot", "Table Footer Group", "<tfoot>\n  \$0\n</tfoot>"),
        HtmlTagSpec("tr", "Table Row", "<tr>\n  \$0\n</tr>"),
        HtmlTagSpec("th", "Table Header Cell", "<th>\$0</th>"),
        HtmlTagSpec("td", "Table Data Cell", "<td>\$0</td>"),
        HtmlTagSpec("strong", "Strong Importance (Bold)", "<strong>\$0</strong>"),
        HtmlTagSpec("em", "Stress Emphasis (Italic)", "<em>\$0</em>"),
        HtmlTagSpec("b", "Bring Attention To (Bold)", "<b>\$0</b>"),
        HtmlTagSpec("i", "Idiomatic / Icon Text (Italic)", "<i>\$0</i>"),
        HtmlTagSpec("u", "Unarticulated Annotation (Underline)", "<u>\$0</u>"),
        HtmlTagSpec("small", "Side Comment / Small Print", "<small>\$0</small>"),
        HtmlTagSpec("mark", "Highlighted / Marked Text", "<mark>\$0</mark>"),
        HtmlTagSpec("code", "Inline Code", "<code>\$0</code>"),
        HtmlTagSpec("pre", "Preformatted Text", "<pre>\$0</pre>"),
        HtmlTagSpec("blockquote", "Block Quotation", "<blockquote>\$0</blockquote>"),
        HtmlTagSpec("font", "Font Face, Size, and Color", "<font color=\"\${1:white}\">\$0</font>"),
        HtmlTagSpec("center", "Centered Content Block", "<center>\$0</center>"),
        HtmlTagSpec("canvas", "Graphics Canvas", "<canvas id=\"\${1:canvas}\" width=\"\${2:400}\" height=\"\${3:300}\"></canvas>"),
        HtmlTagSpec("svg", "Scalable Vector Graphics", "<svg viewBox=\"0 0 \${1:24} \${2:24}\" width=\"\${3:24}\" height=\"\${4:24}\">\n\t\$0\n</svg>"),
        HtmlTagSpec("path", "SVG Path", "<path d=\"\$1\" fill=\"\${2:currentColor}\"/>", isVoid = true),
        HtmlTagSpec("circle", "SVG Circle", "<circle cx=\"\$1\" cy=\"\$2\" r=\"\$3\" />", isVoid = true),
        HtmlTagSpec("rect", "SVG Rectangle", "<rect x=\"\$1\" y=\"\$2\" width=\"\$3\" height=\"\$4\" />", isVoid = true),
        HtmlTagSpec("video", "Video Player", "<video src=\"\$1\" controls width=\"\${2:100%}\"></video>"),
        HtmlTagSpec("audio", "Audio Player", "<audio src=\"\$1\" controls></audio>"),
        HtmlTagSpec("source", "Media Source", "<source src=\"\$1\" type=\"\$2\">", isVoid = true),
        HtmlTagSpec("iframe", "Nested Browsing Context", "<iframe src=\"\$1\" frameborder=\"0\"></iframe>"),
        HtmlTagSpec("details", "Disclosure Details Widget", "<details>\n\t<summary>\${1:Details}</summary>\n\t\$0\n</details>"),
        HtmlTagSpec("summary", "Disclosure Summary Heading", "<summary>\$0</summary>"),
        HtmlTagSpec("dialog", "Modal / Dialog Box", "<dialog id=\"\$1\">\n\t\$0\n</dialog>"),
        HtmlTagSpec("figure", "Self-Contained Figure", "<figure>\n\t\$0\n</figure>"),
        HtmlTagSpec("figcaption", "Figure Caption", "<figcaption>\$0</figcaption>"),
        HtmlTagSpec("picture", "Responsive Picture Container", "<picture>\n\t\$0\n</picture>"),
        HtmlTagSpec("template", "Content Template Fragment", "<template id=\"\$1\">\n\t\$0\n</template>"),
        HtmlTagSpec("progress", "Progress Indicator", "<progress value=\"\${1:50}\" max=\"\${2:100}\"></progress>"),
        HtmlTagSpec("meter", "Scalar Gauge Meter", "<meter value=\"\${1:0.6}\"></meter>"),
        HtmlTagSpec("fieldset", "Form Control Group", "<fieldset>\n\t<legend>\$1</legend>\n\t\$0\n</fieldset>"),
        HtmlTagSpec("legend", "Fieldset Caption", "<legend>\$0</legend>"),
        HtmlTagSpec("datalist", "Predefined Input Options", "<datalist id=\"\$1\">\n\t<option value=\"\$0\">\n</datalist>"),
        HtmlTagSpec("output", "Calculation Output", "<output name=\"\$1\">\$0</output>"),
        HtmlTagSpec("sub", "Subscript Text", "<sub>\$0</sub>"),
        HtmlTagSpec("sup", "Superscript Text", "<sup>\$0</sup>"),
        HtmlTagSpec("del", "Deleted Text", "<del>\$0</del>"),
        HtmlTagSpec("ins", "Inserted Text", "<ins>\$0</ins>"),
        HtmlTagSpec("time", "Date / Time Value", "<time datetime=\"\$1\">\$0</time>"),
        HtmlTagSpec("abbr", "Abbreviation", "<abbr title=\"\$1\">\$0</abbr>"),
        HtmlTagSpec("address", "Contact Address", "<address>\$0</address>"),
        HtmlTagSpec("dl", "Description List", "<dl>\n\t<dt>\$1</dt>\n\t<dd>\$0</dd>\n</dl>"),
        HtmlTagSpec("dt", "Description Term", "<dt>\$0</dt>"),
        HtmlTagSpec("dd", "Description Details", "<dd>\$0</dd>"),
        HtmlTagSpec("noscript", "No-Script Fallback", "<noscript>\$0</noscript>"),
        HtmlTagSpec("marquee", "Scrolling Text Marquee", "<marquee>\$0</marquee>")
    )

    // ── Global & Tag-Specific HTML Attributes ────────────────────────────────
    private val GLOBAL_HTML_ATTRS = listOf(
        AttrSpec("class", "CSS class names"),
        AttrSpec("id", "Unique element ID"),
        AttrSpec("style", "Inline CSS style declarations"),
        AttrSpec("title", "Advisory tooltip text"),
        AttrSpec("onclick", "Click event handler"),
        AttrSpec("onchange", "Change event handler"),
        AttrSpec("oninput", "Input event handler"),
        AttrSpec("onsubmit", "Form submit handler"),
        AttrSpec("onkeydown", "Keydown event handler"),
        AttrSpec("onkeyup", "Keyup event handler"),
        AttrSpec("onload", "Load event handler"),
        AttrSpec("hidden", "Hide element", isBoolean = true),
        AttrSpec("tabindex", "Tab focus order", defaultValue = "0"),
        AttrSpec("draggable", "Draggable state", defaultValue = "true"),
        AttrSpec("contenteditable", "Editable content", defaultValue = "true"),
        AttrSpec("spellcheck", "Spellcheck toggle", defaultValue = "false"),
        AttrSpec("dir", "Text direction (ltr/rtl)", defaultValue = "ltr"),
        AttrSpec("lang", "Language code", defaultValue = "en"),
        AttrSpec("role", "ARIA role"),
        AttrSpec("aria-label", "Accessible label"),
        AttrSpec("aria-hidden", "Hide from screen readers", defaultValue = "true"),
        AttrSpec("data-id", "Custom data attribute")
    )

    private val TAG_SPECIFIC_ATTRS: Map<String, List<AttrSpec>> = mapOf(
        "html" to listOf(
            AttrSpec("lang", "Document language", defaultValue = "en"),
            AttrSpec("dir", "Text direction", defaultValue = "ltr")
        ),
        "body" to listOf(
            AttrSpec("bgcolor", "Background color"),
            AttrSpec("text", "Foreground text color"),
            AttrSpec("background", "Background image URL"),
            AttrSpec("onload", "Page load handler")
        ),
        "font" to listOf(
            AttrSpec("color", "Text color (e.g. white, #ff0000)"),
            AttrSpec("size", "Font size (1-7)"),
            AttrSpec("face", "Font family name")
        ),
        "a" to listOf(
            AttrSpec("href", "Target URL or anchor"),
            AttrSpec("target", "Browsing context (_blank, _self)", defaultValue = "_blank"),
            AttrSpec("rel", "Relationship", defaultValue = "noopener noreferrer"),
            AttrSpec("download", "Download filename")
        ),
        "img" to listOf(
            AttrSpec("src", "Image URL or path"),
            AttrSpec("alt", "Alternative description"),
            AttrSpec("width", "Image width"),
            AttrSpec("height", "Image height"),
            AttrSpec("loading", "Lazy loading", defaultValue = "lazy"),
            AttrSpec("decoding", "Image decoding hint", defaultValue = "async")
        ),
        "input" to listOf(
            AttrSpec("type", "Input control type", defaultValue = "text"),
            AttrSpec("name", "Form field name"),
            AttrSpec("value", "Current value"),
            AttrSpec("placeholder", "Placeholder hint text"),
            AttrSpec("required", "Required field", isBoolean = true),
            AttrSpec("disabled", "Disabled state", isBoolean = true),
            AttrSpec("checked", "Checked state", isBoolean = true),
            AttrSpec("readonly", "Read-only field", isBoolean = true),
            AttrSpec("autofocus", "Auto focus on load", isBoolean = true),
            AttrSpec("autocomplete", "Autocomplete hint", defaultValue = "off"),
            AttrSpec("min", "Minimum value"),
            AttrSpec("max", "Maximum value"),
            AttrSpec("step", "Numeric step"),
            AttrSpec("pattern", "Validation regex"),
            AttrSpec("maxlength", "Maximum character length"),
            AttrSpec("accept", "Accepted file types")
        ),
        "button" to listOf(
            AttrSpec("type", "Button behavior (button, submit, reset)", defaultValue = "button"),
            AttrSpec("disabled", "Disable button", isBoolean = true),
            AttrSpec("name", "Button form name"),
            AttrSpec("value", "Button form value")
        ),
        "form" to listOf(
            AttrSpec("action", "Submission URL"),
            AttrSpec("method", "HTTP method (get, post)", defaultValue = "post"),
            AttrSpec("enctype", "Form encoding type"),
            AttrSpec("target", "Submission target"),
            AttrSpec("novalidate", "Skip validation", isBoolean = true),
            AttrSpec("autocomplete", "Autocomplete toggle", defaultValue = "on")
        ),
        "label" to listOf(
            AttrSpec("for", "Target input ID")
        ),
        "select" to listOf(
            AttrSpec("name", "Field name"),
            AttrSpec("multiple", "Allow multiple selection", isBoolean = true),
            AttrSpec("required", "Required selection", isBoolean = true),
            AttrSpec("disabled", "Disabled state", isBoolean = true)
        ),
        "option" to listOf(
            AttrSpec("value", "Submitted option value"),
            AttrSpec("selected", "Default selected option", isBoolean = true),
            AttrSpec("disabled", "Disabled option", isBoolean = true)
        ),
        "textarea" to listOf(
            AttrSpec("name", "Field name"),
            AttrSpec("rows", "Visible line count", defaultValue = "4"),
            AttrSpec("cols", "Visible width"),
            AttrSpec("placeholder", "Placeholder text"),
            AttrSpec("required", "Required field", isBoolean = true),
            AttrSpec("readonly", "Read-only", isBoolean = true)
        ),
        "link" to listOf(
            AttrSpec("rel", "Relationship", defaultValue = "stylesheet"),
            AttrSpec("href", "Resource URL", defaultValue = "style.css"),
            AttrSpec("type", "MIME type"),
            AttrSpec("media", "Media query"),
            AttrSpec("crossorigin", "CORS mode", defaultValue = "anonymous")
        ),
        "script" to listOf(
            AttrSpec("src", "External script URL", defaultValue = "main.js"),
            AttrSpec("type", "Script type (module, text/javascript)", defaultValue = "module"),
            AttrSpec("defer", "Defer execution until parsed", isBoolean = true),
            AttrSpec("async", "Execute asynchronously", isBoolean = true),
            AttrSpec("crossorigin", "CORS setting", defaultValue = "anonymous")
        ),
        "meta" to listOf(
            AttrSpec("charset", "Character encoding", defaultValue = "UTF-8"),
            AttrSpec("name", "Metadata name (viewport, description, theme-color)"),
            AttrSpec("content", "Metadata value"),
            AttrSpec("http-equiv", "Pragma directive")
        ),
        "canvas" to listOf(
            AttrSpec("width", "Canvas width in px", defaultValue = "400"),
            AttrSpec("height", "Canvas height in px", defaultValue = "300")
        ),
        "video" to listOf(
            AttrSpec("src", "Video URL"),
            AttrSpec("controls", "Show playback controls", isBoolean = true),
            AttrSpec("autoplay", "Auto-start playback", isBoolean = true),
            AttrSpec("loop", "Loop video", isBoolean = true),
            AttrSpec("muted", "Mute audio", isBoolean = true),
            AttrSpec("playsinline", "Play inline on mobile", isBoolean = true),
            AttrSpec("poster", "Poster image URL"),
            AttrSpec("width", "Player width"),
            AttrSpec("height", "Player height")
        ),
        "audio" to listOf(
            AttrSpec("src", "Audio URL"),
            AttrSpec("controls", "Show audio controls", isBoolean = true),
            AttrSpec("autoplay", "Autoplay", isBoolean = true),
            AttrSpec("loop", "Loop audio", isBoolean = true),
            AttrSpec("muted", "Muted", isBoolean = true)
        ),
        "iframe" to listOf(
            AttrSpec("src", "Embedded page URL"),
            AttrSpec("width", "Frame width", defaultValue = "100%"),
            AttrSpec("height", "Frame height", defaultValue = "400"),
            AttrSpec("allow", "Feature policy permissions"),
            AttrSpec("allowfullscreen", "Allow fullscreen", isBoolean = true),
            AttrSpec("loading", "Lazy load", defaultValue = "lazy"),
            AttrSpec("sandbox", "Sandbox restrictions")
        ),
        "td" to listOf(
            AttrSpec("colspan", "Columns to span"),
            AttrSpec("rowspan", "Rows to span"),
            AttrSpec("align", "Horizontal alignment")
        ),
        "th" to listOf(
            AttrSpec("scope", "Header scope (col, row)", defaultValue = "col"),
            AttrSpec("colspan", "Columns to span"),
            AttrSpec("rowspan", "Rows to span")
        ),
        "table" to listOf(
            AttrSpec("border", "Table border width", defaultValue = "1"),
            AttrSpec("cellpadding", "Cell padding"),
            AttrSpec("cellspacing", "Cell spacing"),
            AttrSpec("width", "Table width")
        )
    )

    private val HTML_ATTR_VALUES: Map<String, List<String>> = mapOf(
        "type" to listOf("text", "password", "email", "number", "tel", "url", "search", "date", "time", "datetime-local", "month", "week", "color", "checkbox", "radio", "file", "range", "submit", "button", "reset", "hidden", "module", "text/javascript", "text/css"),
        "rel" to listOf("stylesheet", "icon", "apple-touch-icon", "manifest", "preconnect", "preload", "noopener noreferrer", "nofollow", "canonical"),
        "target" to listOf("_blank", "_self", "_parent", "_top"),
        "method" to listOf("post", "get", "dialog"),
        "enctype" to listOf("application/x-www-form-urlencoded", "multipart/form-data", "text/plain"),
        "loading" to listOf("lazy", "eager"),
        "charset" to listOf("UTF-8", "ISO-8859-1"),
        "name" to listOf("viewport", "description", "keywords", "author", "theme-color", "robots"),
        "color" to listOf("white", "black", "red", "blue", "green", "yellow", "cyan", "magenta", "orange", "purple", "gray", "#ffffff", "#000000", "#3b82f6", "#10b981", "#ef4444"),
        "bgcolor" to listOf("black", "white", "#000000", "#ffffff", "#121212", "#1e293b", "navy", "darkslategray"),
        "align" to listOf("center", "left", "right", "justify"),
        "dir" to listOf("ltr", "rtl", "auto"),
        "autocomplete" to listOf("on", "off", "name", "email", "username", "current-password", "new-password")
    )

    // ── CSS Properties & Values (Spck style) ─────────────────────────────────
    private val CSS_PROPERTIES = listOf(
        CssPropSpec("display", "Box display type", listOf("flex", "grid", "block", "inline-block", "inline-flex", "inline", "none", "contents"), "flex"),
        CssPropSpec("flex-direction", "Flex main axis direction", listOf("row", "column", "row-reverse", "column-reverse"), "column"),
        CssPropSpec("justify-content", "Main-axis alignment", listOf("center", "space-between", "space-around", "space-evenly", "flex-start", "flex-end", "start", "end"), "center"),
        CssPropSpec("align-items", "Cross-axis alignment", listOf("center", "flex-start", "flex-end", "stretch", "baseline"), "center"),
        CssPropSpec("align-content", "Multi-line cross-axis alignment", listOf("center", "space-between", "space-around", "stretch", "flex-start", "flex-end"), "center"),
        CssPropSpec("align-self", "Single item cross-axis alignment", listOf("auto", "center", "flex-start", "flex-end", "stretch"), "center"),
        CssPropSpec("flex-wrap", "Flex item wrapping", listOf("wrap", "nowrap", "wrap-reverse"), "wrap"),
        CssPropSpec("flex", "Flex grow, shrink, basis shorthand", listOf("1", "1 1 auto", "0 0 auto", "none"), "1"),
        CssPropSpec("flex-grow", "Flex grow factor", listOf("0", "1", "2"), "1"),
        CssPropSpec("flex-shrink", "Flex shrink factor", listOf("0", "1"), "0"),
        CssPropSpec("gap", "Row and column gap", listOf("8px", "12px", "16px", "24px", "1rem"), "12px"),
        CssPropSpec("row-gap", "Gap between rows", listOf("8px", "12px", "16px"), "12px"),
        CssPropSpec("column-gap", "Gap between columns", listOf("8px", "12px", "16px"), "12px"),
        CssPropSpec("grid-template-columns", "Grid track columns", listOf("repeat(auto-fit, minmax(240px, 1fr))", "repeat(3, 1fr)", "repeat(2, 1fr)", "1fr 1fr", "auto 1fr"), "repeat(auto-fit, minmax(240px, 1fr))"),
        CssPropSpec("grid-template-rows", "Grid track rows", listOf("auto", "1fr", "repeat(2, auto)"), "auto"),
        CssPropSpec("grid-column", "Grid column span", listOf("1 / -1", "span 2", "1 / 3"), "1 / -1"),
        CssPropSpec("place-items", "Align + justify items shorthand", listOf("center", "start", "stretch"), "center"),
        CssPropSpec("position", "Positioning scheme", listOf("relative", "absolute", "fixed", "sticky", "static"), "relative"),
        CssPropSpec("top", "Top offset", listOf("0", "50%", "auto", "16px"), "0"),
        CssPropSpec("left", "Left offset", listOf("0", "50%", "auto", "16px"), "0"),
        CssPropSpec("right", "Right offset", listOf("0", "auto", "16px"), "0"),
        CssPropSpec("bottom", "Bottom offset", listOf("0", "auto", "16px"), "0"),
        CssPropSpec("inset", "All sides offset shorthand", listOf("0", "auto", "16px"), "0"),
        CssPropSpec("z-index", "Stacking order", listOf("1", "10", "50", "100", "999", "-1"), "10"),
        CssPropSpec("width", "Element width", listOf("100%", "100vw", "auto", "fit-content", "max-content", "min-content"), "100%"),
        CssPropSpec("min-width", "Minimum width", listOf("0", "100%", "280px"), "0"),
        CssPropSpec("max-width", "Maximum width", listOf("100%", "1200px", "768px", "480px", "none"), "1200px"),
        CssPropSpec("height", "Element height", listOf("100%", "100vh", "auto", "fit-content"), "100%"),
        CssPropSpec("min-height", "Minimum height", listOf("100vh", "100%", "0", "48px"), "100vh"),
        CssPropSpec("max-height", "Maximum height", listOf("100vh", "100%", "none"), "100%"),
        CssPropSpec("aspect-ratio", "Width-to-height ratio", listOf("16 / 9", "1 / 1", "4 / 3", "auto"), "16 / 9"),
        CssPropSpec("margin", "Outer margin shorthand", listOf("0", "0 auto", "8px", "16px", "24px", "auto"), "0"),
        CssPropSpec("margin-top", "Top outer margin", listOf("0", "8px", "16px", "24px", "auto"), "16px"),
        CssPropSpec("margin-bottom", "Bottom outer margin", listOf("0", "8px", "16px", "24px"), "16px"),
        CssPropSpec("margin-left", "Left outer margin", listOf("0", "8px", "16px", "auto"), "0"),
        CssPropSpec("margin-right", "Right outer margin", listOf("0", "8px", "16px", "auto"), "0"),
        CssPropSpec("padding", "Inner padding shorthand", listOf("0", "8px 16px", "12px", "16px", "24px", "1rem"), "16px"),
        CssPropSpec("padding-top", "Top inner padding", listOf("8px", "16px", "24px"), "16px"),
        CssPropSpec("padding-bottom", "Bottom inner padding", listOf("8px", "16px", "24px"), "16px"),
        CssPropSpec("padding-left", "Left inner padding", listOf("8px", "16px", "24px"), "16px"),
        CssPropSpec("padding-right", "Right inner padding", listOf("8px", "16px", "24px"), "16px"),
        CssPropSpec("box-sizing", "Box model sizing", listOf("border-box", "content-box"), "border-box"),
        CssPropSpec("background", "Background shorthand", listOf("#1e1e1e", "transparent", "none", "linear-gradient(135deg, #6366f1, #8b5cf6)"), "#1e1e1e"),
        CssPropSpec("background-color", "Background fill color", listOf("#ffffff", "#000000", "#1e293b", "#f8fafc", "transparent", "rgba(0, 0, 0, 0.5)"), "#ffffff"),
        CssPropSpec("background-image", "Background image / gradient", listOf("url('')", "linear-gradient(135deg, #6366f1 0%, #a855f7 100%)", "radial-gradient(circle, #fff, #000)", "none"), "url('\$1')"),
        CssPropSpec("background-size", "Background image sizing", listOf("cover", "contain", "100% 100%", "auto"), "cover"),
        CssPropSpec("background-position", "Background alignment", listOf("center", "top", "bottom", "left", "right"), "center"),
        CssPropSpec("background-repeat", "Background tiling", listOf("no-repeat", "repeat", "repeat-x", "repeat-y"), "no-repeat"),
        CssPropSpec("color", "Text foreground color", listOf("#ffffff", "#000000", "#1e293b", "#64748b", "#3b82f6", "inherit", "currentColor"), "#ffffff"),
        CssPropSpec("font-family", "Font typeface stack", listOf("system-ui, -apple-system, sans-serif", "'Inter', sans-serif", "monospace", "serif", "inherit"), "system-ui, -apple-system, sans-serif"),
        CssPropSpec("font-size", "Text size", listOf("12px", "14px", "16px", "18px", "20px", "24px", "32px", "1rem", "1.25rem", "1.5rem", "2rem"), "16px"),
        CssPropSpec("font-weight", "Font boldness", listOf("400", "500", "600", "700", "bold", "normal", "300"), "600"),
        CssPropSpec("font-style", "Font style", listOf("normal", "italic", "oblique"), "italic"),
        CssPropSpec("line-height", "Line spacing height", listOf("1", "1.25", "1.5", "1.75", "normal"), "1.5"),
        CssPropSpec("letter-spacing", "Character tracking", listOf("-0.02em", "0", "0.05em", "0.1em", "1px"), "0.02em"),
        CssPropSpec("text-align", "Horizontal text alignment", listOf("left", "center", "right", "justify"), "center"),
        CssPropSpec("text-decoration", "Text decoration line", listOf("none", "underline", "line-through"), "none"),
        CssPropSpec("text-transform", "Text case transformation", listOf("uppercase", "lowercase", "capitalize", "none"), "uppercase"),
        CssPropSpec("white-space", "Whitespace wrapping", listOf("nowrap", "normal", "pre", "pre-wrap", "pre-line"), "nowrap"),
        CssPropSpec("text-overflow", "Overflowing text indicator", listOf("ellipsis", "clip"), "ellipsis"),
        CssPropSpec("word-break", "Word breaking rule", listOf("break-word", "break-all", "keep-all", "normal"), "break-word"),
        CssPropSpec("border", "Border width, style, color", listOf("1px solid #e2e8f0", "1px solid rgba(255, 255, 255, 0.1)", "none", "2px solid #3b82f6"), "1px solid #e2e8f0"),
        CssPropSpec("border-top", "Top border", listOf("1px solid #e2e8f0", "none"), "1px solid #e2e8f0"),
        CssPropSpec("border-bottom", "Bottom border", listOf("1px solid #e2e8f0", "none"), "1px solid #e2e8f0"),
        CssPropSpec("border-left", "Left border", listOf("1px solid #e2e8f0", "none"), "1px solid #e2e8f0"),
        CssPropSpec("border-right", "Right border", listOf("1px solid #e2e8f0", "none"), "1px solid #e2e8f0"),
        CssPropSpec("border-radius", "Corner rounding", listOf("4px", "8px", "12px", "16px", "24px", "50%", "9999px", "0"), "8px"),
        CssPropSpec("outline", "Focus outline", listOf("none", "2px solid #3b82f6"), "none"),
        CssPropSpec("box-shadow", "Drop shadow", listOf("0 4px 6px -1px rgba(0, 0, 0, 0.1)", "0 10px 25px -5px rgba(0, 0, 0, 0.25)", "none", "inset 0 2px 4px rgba(0,0,0,0.06)"), "0 4px 12px rgba(0, 0, 0, 0.15)"),
        CssPropSpec("opacity", "Element transparency (0-1)", listOf("0", "0.5", "0.75", "0.9", "1"), "1"),
        CssPropSpec("overflow", "Overflow clipping/scrolling", listOf("hidden", "auto", "scroll", "visible"), "hidden"),
        CssPropSpec("overflow-x", "Horizontal overflow", listOf("hidden", "auto", "scroll", "visible"), "hidden"),
        CssPropSpec("overflow-y", "Vertical overflow", listOf("auto", "hidden", "scroll", "visible"), "auto"),
        CssPropSpec("cursor", "Mouse pointer cursor", listOf("pointer", "default", "grab", "not-allowed", "text", "move", "none"), "pointer"),
        CssPropSpec("pointer-events", "Mouse/touch hit testing", listOf("none", "auto"), "none"),
        CssPropSpec("user-select", "Text selection behavior", listOf("none", "text", "all", "auto"), "none"),
        CssPropSpec("transition", "Smooth property transition", listOf("all 0.2s ease", "all 0.3s cubic-bezier(0.4, 0, 0.2, 1)", "transform 0.2s ease, opacity 0.2s ease", "none"), "all 0.2s ease"),
        CssPropSpec("transform", "2D/3D geometric transform", listOf("translate(-50%, -50%)", "translateY(-2px)", "scale(1.05)", "rotate(45deg)", "none"), "translateY(-2px)"),
        CssPropSpec("animation", "Keyframe animation", listOf("spin 1s linear infinite", "fadeIn 0.3s ease-out forwards", "none"), "\${1:fadeIn} 0.3s ease"),
        CssPropSpec("filter", "Visual filter effects", listOf("blur(8px)", "brightness(1.1)", "grayscale(100%)", "drop-shadow(0 4px 6px rgba(0,0,0,0.2))", "none"), "blur(8px)"),
        CssPropSpec("backdrop-filter", "Frosted glass backdrop filter", listOf("blur(12px)", "blur(16px) saturate(180%)", "none"), "blur(12px)"),
        CssPropSpec("object-fit", "Media replacement sizing", listOf("cover", "contain", "fill", "scale-down", "none"), "cover"),
        CssPropSpec("list-style", "List bullet styling", listOf("none", "disc", "decimal"), "none"),
        CssPropSpec("visibility", "Visibility toggle", listOf("visible", "hidden", "collapse"), "hidden"),
        CssPropSpec("fill", "SVG fill color", listOf("currentColor", "none", "#ffffff"), "currentColor"),
        CssPropSpec("stroke", "SVG stroke color", listOf("currentColor", "none", "#ffffff"), "currentColor"),
        CssPropSpec("stroke-width", "SVG stroke thickness", listOf("1.5", "2", "1"), "2")
    )

    private val CSS_TOP_LEVEL_SNIPPETS = listOf(
        SnippetItem("reset", "* { box-sizing: border-box; margin: 0; padding: 0; }", "* {\n\tbox-sizing: border-box;\n\tmargin: 0;\n\tpadding: 0;\n}\n\$0", "CSS Reset"),
        SnippetItem("body", "body { ... }", "body {\n\tmargin: 0;\n\tfont-family: system-ui, -apple-system, sans-serif;\n\t\$0\n}", "Body Rule"),
        SnippetItem("@media", "@media (max-width: 768px) { ... }", "@media (max-width: \${1:768px}) {\n\t\$0\n}", "Media Query"),
        SnippetItem("@keyframes", "@keyframes name { ... }", "@keyframes \${1:fadeIn} {\n\tfrom {\n\t\topacity: 0;\n\t}\n\tto {\n\t\topacity: 1;\n\t}\n}", "Keyframes Animation"),
        SnippetItem(":root", ":root { --var: value; }", ":root {\n\t--\${1:primary}: \${2:#3b82f6};\n\t\$0\n}", "CSS Variables Root"),
        SnippetItem("flex-center", "Flexbox Center Snippet", "display: flex;\njustify-content: center;\nalign-items: center;", "CSS Flex Center")
    )

    // ── Rich Language Line & Statement Suggestions ───────────────────────────
    private val JS_TS_SNIPPETS = listOf(
        SnippetItem("clg", "console.log(...)", "console.log(\$0);", "JS Console Log"),
        SnippetItem("console.log", "console.log(...)", "console.log(\$0);", "JS Console Log"),
        SnippetItem("cerr", "console.error(...)", "console.error(\$0);", "JS Console Error"),
        SnippetItem("cwarn", "console.warn(...)", "console.warn(\$0);", "JS Console Warn"),
        SnippetItem("qs", "document.querySelector(...)", "document.querySelector('\${1:selector}')\$0", "DOM Query Selector"),
        SnippetItem("qsa", "document.querySelectorAll(...)", "document.querySelectorAll('\${1:selector}')\$0", "DOM Query Selector All"),
        SnippetItem("geid", "document.getElementById(...)", "document.getElementById('\${1:id}')\$0", "DOM Get Element By ID"),
        SnippetItem("ce", "document.createElement(...)", "const \${1:el} = document.createElement('\${2:div}');\$0", "DOM Create Element"),
        SnippetItem("ael", "addEventListener('click', ...)", "\${1:element}.addEventListener('\${2:click}', (\${3:e}) => {\n\t\$0\n});", "DOM Event Listener"),
        SnippetItem("domready", "DOMContentLoaded Listener", "window.addEventListener('DOMContentLoaded', () => {\n\t\$0\n});", "DOM Ready"),
        SnippetItem("fetch", "fetch(url) async/await", "const response = await fetch('\${1:/api/data}');\nconst data = await response.json();\n\$0", "Fetch API (Async)"),
        SnippetItem("fetchthen", "fetch(url).then(...)", "fetch('\${1:/api/data}')\n\t.then(res => res.json())\n\t.then(data => {\n\t\t\$0\n\t})\n\t.catch(err => console.error(err));", "Fetch API (Promise)"),
        SnippetItem("function", "function name(params) { ... }", "function \${1:name}(\${2:params}) {\n\t\$0\n}", "JS Function"),
        SnippetItem("afn", "async function name(params) { ... }", "async function \${1:name}(\${2:params}) {\n\t\$0\n}", "JS Async Function"),
        SnippetItem("arrow", "const name = (params) => { ... }", "const \${1:name} = (\${2:params}) => {\n\t\$0\n};", "Arrow Function"),
        SnippetItem("const", "const name = value;", "const \${1:name} = \${0:value};", "Const Declaration"),
        SnippetItem("let", "let name = value;", "let \${1:name} = \${0:value};", "Let Declaration"),
        SnippetItem("if", "if (condition) { ... }", "if (\${1:condition}) {\n\t\$0\n}", "If Statement"),
        SnippetItem("ifelse", "if (condition) { ... } else { ... }", "if (\${1:condition}) {\n\t\$2\n} else {\n\t\$0\n}", "If / Else Statement"),
        SnippetItem("for", "for (let i = 0; i < length; i++)", "for (let \${1:i} = 0; \${1:i} < \${2:array}.length; \${1:i}++) {\n\t\$0\n}", "For Loop"),
        SnippetItem("forof", "for (const item of items)", "for (const \${1:item} of \${2:items}) {\n\t\$0\n}", "For...Of Loop"),
        SnippetItem("foreach", "array.forEach(item => { ... })", "\${1:array}.forEach((\${2:item}) => {\n\t\$0\n});", "Array forEach"),
        SnippetItem("map", "array.map(item => ...)", "\${1:array}.map((\${2:item}) => \$0)", "Array map"),
        SnippetItem("filter", "array.filter(item => ...)", "\${1:array}.filter((\${2:item}) => \$0)", "Array filter"),
        SnippetItem("try", "try { ... } catch (err) { ... }", "try {\n\t\$0\n} catch (\${1:err}) {\n\tconsole.error(\${1:err});\n}", "Try / Catch Block"),
        SnippetItem("settimeout", "setTimeout(() => { ... }, ms)", "setTimeout(() => {\n\t\$0\n}, \${1:1000});", "Set Timeout"),
        SnippetItem("setinterval", "setInterval(() => { ... }, ms)", "setInterval(() => {\n\t\$0\n}, \${1:1000});", "Set Interval"),
        SnippetItem("switch", "switch (value) { case ... }", "switch (\${1:value}) {\n\tcase \${2:target}:\n\t\t\$0\n\t\tbreak;\n\tdefault:\n\t\tbreak;\n}", "Switch Statement"),
        SnippetItem("class", "class Name { constructor() { ... } }", "class \${1:ClassName} {\n\tconstructor(\${2:params}) {\n\t\t\$0\n\t}\n}", "ES6 Class"),
        SnippetItem("import", "import { ... } from '...'", "import { \${2:module} } from '\${1:package}';\$0", "ES Module Import"),
        SnippetItem("export", "export default ...", "export default \$0;", "ES Module Export"),
        SnippetItem("rfc", "React Functional Component", "export default function \${1:Component}() {\n\treturn (\n\t\t<div className=\"\${2:container}\">\n\t\t\t\$0\n\t\t</div>\n\t);\n}", "React Component"),
        SnippetItem("useState", "const [state, setState] = useState(...)", "const [\${1:state}, set\${2:State}] = useState(\${3:initialValue});\$0", "React Hook"),
        SnippetItem("useEffect", "useEffect(() => { ... }, [])", "useEffect(() => {\n\t\$0\n}, [\${1}]);", "React Hook"),
        SnippetItem("useRef", "const ref = useRef(null)", "const \${1:ref} = useRef(\${2:null});\$0", "React Hook"),
        SnippetItem("useCallback", "useCallback(() => { ... }, [])", "const \${1:handler} = useCallback(() => {\n\t\$0\n}, [\${2}]);", "React Hook"),
        SnippetItem("useMemo", "useMemo(() => ..., [])", "const \${1:value} = useMemo(() => \${2:compute()}, [\${3}]);\$0", "React Hook")
    )

    private val PYTHON_SNIPPETS = listOf(
        SnippetItem("def", "def function_name(args):", "def \${1:function_name}(\${2:args}):\n    \${0:pass}", "Python Function"),
        SnippetItem("adef", "async def function_name(args):", "async def \${1:function_name}(\${2:args}):\n    \${0:pass}", "Python Async Function"),
        SnippetItem("class", "class ClassName:", "class \${1:ClassName}:\n    def __init__(self\${2:, args}):\n        \${0:pass}", "Python Class"),
        SnippetItem("main", "if __name__ == '__main__':", "if __name__ == \"__main__\":\n    \${0:main()}", "Python Main Guard"),
        SnippetItem("for", "for item in iterable:", "for \${1:item} in \${2:iterable}:\n    \${0:pass}", "Python For Loop"),
        SnippetItem("while", "while condition:", "while \${1:condition}:\n    \${0:pass}", "Python While Loop"),
        SnippetItem("if", "if condition:", "if \${1:condition}:\n    \${0:pass}", "Python If Block"),
        SnippetItem("ifelse", "if / else:", "if \${1:condition}:\n    \${2:pass}\nelse:\n    \${0:pass}", "Python If/Else"),
        SnippetItem("try", "try / except Exception as e:", "try:\n    \${1:pass}\nexcept \${2:Exception} as e:\n    print(f\"Error: {e}\")\n    \${0:pass}", "Python Try/Except"),
        SnippetItem("with", "with open(file) as f:", "with open(\"\${1:file.txt}\", \"\${2:r}\", encoding=\"utf-8\") as \${3:f}:\n    \${0:content = f.read()}", "Python Context Manager"),
        SnippetItem("print", "print(...)", "print(\$0)", "Python Print"),
        SnippetItem("printf", "print(f\"...\")", "print(f\"\${1:label}: {\$0}\")", "Python F-String Print"),
        SnippetItem("import", "import module", "import \${0:os}", "Python Import"),
        SnippetItem("from", "from module import name", "from \${1:module} import \${0:name}", "Python From Import"),
        SnippetItem("lambda", "lambda x: expr", "lambda \${1:x}: \${0:x}", "Python Lambda"),
        SnippetItem("listcomp", "[x for x in iterable]", "[\${1:x} for \${1:x} in \${2:iterable}]\$0", "List Comprehension"),
        SnippetItem("dataclass", "@dataclass class ...", "@dataclass\nclass \${1:Model}:\n    \${2:id}: \${3:int}\n    \${4:name}: \${0:str}", "Python Dataclass")
    )

    private val KOTLIN_JAVA_SNIPPETS = listOf(
        SnippetItem("fun", "fun name(params) { ... }", "fun \${1:name}(\${2:params}) {\n    \$0\n}", "Kotlin Function"),
        SnippetItem("sfun", "suspend fun name(params) { ... }", "suspend fun \${1:name}(\${2:params}) {\n    \$0\n}", "Suspend Function"),
        SnippetItem("comp", "@Composable fun Component()", "@Composable\nfun \${1:Component}() {\n    \$0\n}", "Compose Function"),
        SnippetItem("dataclass", "data class Name(...)", "data class \${1:Model}(\n    val \${2:id}: \${3:String}\n)", "Kotlin Data Class"),
        SnippetItem("sealed", "sealed interface / class", "sealed interface \${1:UiState} {\n    data object Loading : \${1:UiState}\n    data class Success(val \${2:data}: \${3:String}) : \${1:UiState}\n}", "Sealed Hierarchy"),
        SnippetItem("state", "var state by remember { mutableStateOf(...) }", "var \${1:state} by remember { mutableStateOf(\${2:\"\"}) }\$0", "Compose State"),
        SnippetItem("launch", "scope.launch { ... }", "scope.launch {\n    \$0\n}", "Coroutine Launch"),
        SnippetItem("if", "if (condition) { ... }", "if (\${1:condition}) {\n    \$0\n}", "If Statement"),
        SnippetItem("when", "when (value) { ... }", "when (\${1:value}) {\n    \${2:target} -> {\n        \$0\n    }\n    else -> {}\n}", "When Expression"),
        SnippetItem("for", "for (item in items) { ... }", "for (\${1:item} in \${2:items}) {\n    \$0\n}", "For Loop"),
        SnippetItem("try", "try { ... } catch (e: Exception)", "try {\n    \$0\n} catch (e: Exception) {\n    e.printStackTrace()\n}", "Try / Catch"),
        SnippetItem("println", "println(...)", "println(\$0)", "Print Line"),
        SnippetItem("main", "fun main() { ... }", "fun main() {\n    \$0\n}", "Main Entry")
    )

    // ── Main Entry Point Called from KryptonLspLanguage ─────────────────────
    fun populateCompletions(
        languageId: String,
        content: ContentReference,
        position: CharPosition,
        prefix: String,
        publisher: CompletionPublisher
    ): Boolean {
        val lang = languageId.lowercase()
        val lineIdx = position.line
        val colIdx = position.column
        val currentLine = runCatching { content.getLine(lineIdx).toString() }.getOrDefault("")
        val beforeCursor = currentLine.substring(0, colIdx.coerceAtMost(currentLine.length))
        val afterCursor = currentLine.substring(colIdx.coerceAtMost(currentLine.length))

        val addedLabels = HashSet<String>()

        if (lang in setOf("html", "htm", "xhtml", "xml", "svg", "jsx", "tsx")) {
            val htmlContext = detectHtmlEmbeddedContext(content, lineIdx, colIdx)
            when (htmlContext) {
                EmbeddedContext.STYLE -> {
                    return populateCssCompletions(content, beforeCursor, prefix, publisher, addedLabels, isInlineStyle = false)
                }
                EmbeddedContext.INLINE_STYLE -> {
                    return populateCssCompletions(content, beforeCursor, prefix, publisher, addedLabels, isInlineStyle = true)
                }
                EmbeddedContext.SCRIPT -> {
                    return populateCodeCompletions("javascript", content, lineIdx, beforeCursor, prefix, publisher, addedLabels)
                }
                EmbeddedContext.HTML -> {
                    return populateHtmlCompletions(content, lineIdx, colIdx, beforeCursor, afterCursor, prefix, publisher, addedLabels)
                }
            }
        }

        if (lang in setOf("css", "scss", "less")) {
            return populateCssCompletions(content, beforeCursor, prefix, publisher, addedLabels, isInlineStyle = false)
        }

        return populateCodeCompletions(lang, content, lineIdx, beforeCursor, prefix, publisher, addedLabels)
    }

    private enum class EmbeddedContext { HTML, STYLE, SCRIPT, INLINE_STYLE }

    /**
     * Checks if the cursor inside an HTML document is currently within a `<style>...</style>` block,
     * `<script>...</script>` block, or an inline `style="..."` attribute.
     */
    private fun detectHtmlEmbeddedContext(content: ContentReference, targetLine: Int, targetCol: Int): EmbeddedContext {
        val currentLine = runCatching { content.getLine(targetLine).toString() }.getOrDefault("")
        val beforeCursor = currentLine.substring(0, targetCol.coerceAtMost(currentLine.length))

        // Check inline style="...|
        if (Regex("""\bstyle\s*=\s*["'][^"']*$""", RegexOption.IGNORE_CASE).containsMatchIn(beforeCursor)) {
            return EmbeddedContext.INLINE_STYLE
        }

        val startScanLine = (targetLine - 150).coerceAtLeast(0)
        var inStyle = false
        var inScript = false
        for (l in startScanLine..targetLine) {
            val raw = runCatching { content.getLine(l).toString() }.getOrDefault("")
            val text = if (l == targetLine) beforeCursor else raw
            val lower = text.lowercase()
            var i = 0
            while (i < lower.length) {
                if (!inStyle && !inScript) {
                    val sIdx = lower.indexOf("<style", i)
                    val jIdx = lower.indexOf("<script", i)
                    if (sIdx != -1 && (jIdx == -1 || sIdx < jIdx)) {
                        val gt = lower.indexOf('>', sIdx)
                        if (gt != -1) {
                            inStyle = true
                            i = gt + 1
                            continue
                        } else break
                    } else if (jIdx != -1) {
                        val gt = lower.indexOf('>', jIdx)
                        if (gt != -1) {
                            inScript = true
                            i = gt + 1
                            continue
                        } else break
                    } else break
                } else if (inStyle) {
                    val closeIdx = lower.indexOf("</style", i)
                    if (closeIdx != -1) {
                        inStyle = false
                        i = closeIdx + 7
                    } else break
                } else if (inScript) {
                    val closeIdx = lower.indexOf("</script", i)
                    if (closeIdx != -1) {
                        inScript = false
                        i = closeIdx + 8
                    } else break
                }
            }
        }
        return when {
            inStyle -> EmbeddedContext.STYLE
            inScript -> EmbeddedContext.SCRIPT
            else -> EmbeddedContext.HTML
        }
    }

    // ── HTML Tag, Closing Tag, Attribute & Emmet Completions ─────────────────
    private fun populateHtmlCompletions(
        content: ContentReference,
        lineIdx: Int,
        colIdx: Int,
        beforeCursor: String,
        afterCursor: String,
        prefix: String,
        publisher: CompletionPublisher,
        addedLabels: MutableSet<String>
    ): Boolean {
        // 0. Suppress HTML tag completions if cursor is inside plain text content between '>' and '<'
        // ONLY trigger HTML tag completions when preceding token has '<' or '</'
        val lastGt = beforeCursor.lastIndexOf('>')
        val lastLt = beforeCursor.lastIndexOf('<')
        val isInsideTextContent = if (lastGt != -1 || lastLt != -1) {
            lastGt > lastLt
        } else {
            var inside = false
            for (l in (lineIdx - 1) downTo (lineIdx - 10).coerceAtLeast(0)) {
                val prevLine = runCatching { content.getLine(l).toString() }.getOrNull() ?: continue
                val pGt = prevLine.lastIndexOf('>')
                val pLt = prevLine.lastIndexOf('<')
                if (pGt != -1 || pLt != -1) {
                    inside = pGt > pLt
                    break
                }
            }
            inside
        }
        if (isInsideTextContent) {
            return false
        }

        // 1. Check if cursor is typing a closing tag: `</` or `</tagPrefix`
        val closeTagMatch = Regex("""<\s*/\s*([a-zA-Z0-9:-]*)$""").find(beforeCursor)
        if (closeTagMatch != null) {
            val typedTag = closeTagMatch.groupValues[1].lowercase()
            val replaceLen = closeTagMatch.value.length
            val hasTrailingGt = afterCursor.startsWith(">")
            val unclosedStack = findUnclosedHtmlTags(content, lineIdx, colIdx - replaceLen)

            // Prioritize unclosed parent tags in LIFO order
            for (tag in unclosedStack.reversed()) {
                if (typedTag.isEmpty() || tag.startsWith(typedTag, ignoreCase = true)) {
                    val label = "</$tag>"
                    if (addedLabels.add(label)) {
                        val insertStr = if (hasTrailingGt) "</$tag" else "</$tag>"
                        addReplacementItem(
                            publisher = publisher,
                            label = label,
                            detail = "Close <$tag> tag",
                            replaceLength = replaceLen,
                            insertText = insertStr,
                            isSnippet = false
                        )
                    }
                }
            }
            // Also suggest any other matching non-void HTML tags
            for (spec in HTML_TAGS) {
                if (spec.isVoid || spec.tag.contains(':')) continue
                val t = spec.tag.lowercase()
                if (typedTag.isNotEmpty() && t.startsWith(typedTag, ignoreCase = true)) {
                    val label = "</$t>"
                    if (addedLabels.add(label)) {
                        val insertStr = if (hasTrailingGt) "</$t" else "</$t>"
                        addReplacementItem(
                            publisher = publisher,
                            label = label,
                            detail = "Closing Tag",
                            replaceLength = replaceLen,
                            insertText = insertStr,
                            isSnippet = false
                        )
                    }
                }
            }
            return addedLabels.isNotEmpty()
        }

        // 2. Check if cursor is inside an opening tag's attributes: `<tagName ... |`
        val attrValMatch = Regex("""<([a-zA-Z][a-zA-Z0-9:-]*)\b[^>]*\s+([a-zA-Z0-9_-]+)\s*=\s*["']([^"']*)$""").find(beforeCursor)
        if (attrValMatch != null) {
            val attrName = attrValMatch.groupValues[2].lowercase()
            val valPrefix = attrValMatch.groupValues[3]
            val values = HTML_ATTR_VALUES[attrName] ?: emptyList()
            for (v in values) {
                if (valPrefix.isEmpty() || v.startsWith(valPrefix, ignoreCase = true)) {
                    if (addedLabels.add(v)) {
                        addReplacementItem(
                            publisher = publisher,
                            label = v,
                            detail = "$attrName value",
                            replaceLength = valPrefix.length,
                            insertText = v,
                            isSnippet = false
                        )
                    }
                }
            }
            return addedLabels.isNotEmpty()
        }

        val insideTagMatch = Regex("""<([a-zA-Z][a-zA-Z0-9:-]*)\s+([^<>]*)$""").find(beforeCursor)
        if (insideTagMatch != null) {
            val tagName = insideTagMatch.groupValues[1].lowercase()
            val attrFragment = insideTagMatch.groupValues[2]
            // Make sure we're not inside quotes
            val quoteCount = attrFragment.count { it == '"' }
            if (quoteCount % 2 == 0) {
                val attrPrefixMatch = Regex("""([a-zA-Z0-9_:@-]+)$""").find(attrFragment)
                val attrPrefix = attrPrefixMatch?.value ?: ""
                val tagAttrs = (TAG_SPECIFIC_ATTRS[tagName] ?: emptyList()) + GLOBAL_HTML_ATTRS
                for (attr in tagAttrs) {
                    if (attrPrefix.isEmpty() || attr.name.startsWith(attrPrefix, ignoreCase = true)) {
                        if (addedLabels.add(attr.name)) {
                            val snippet = if (attr.isBoolean) {
                                attr.name
                            } else if (attr.defaultValue.isNotEmpty()) {
                                "${attr.name}=\"\${1:${attr.defaultValue}}\"\$0"
                            } else {
                                "${attr.name}=\"\$1\"\$0"
                            }
                            addReplacementItem(
                                publisher = publisher,
                                label = if (attr.isBoolean) attr.name else "${attr.name}=\"...\"",
                                detail = attr.detail,
                                replaceLength = attrPrefix.length,
                                insertText = snippet,
                                isSnippet = !attr.isBoolean
                            )
                        }
                    }
                }
                return addedLabels.isNotEmpty()
            }
        }

        // 3. Check if cursor is right after `<` or `<tagPrefix`
        val openTagMatch = Regex("""<(!?[a-zA-Z0-9:-]*)$""").find(beforeCursor)
        if (openTagMatch != null) {
            val tagPrefix = openTagMatch.groupValues[1]
            val replaceLen = openTagMatch.value.length // includes '<'
            val trailingGtLen = if (afterCursor.startsWith(">")) 1 else 0

            // First: if there is an unclosed parent tag, suggest closing it right at the top (Spck behavior!)
            val unclosed = findUnclosedHtmlTags(content, lineIdx, colIdx - replaceLen)
            if (unclosed.isNotEmpty()) {
                val nearestParent = unclosed.last()
                if (tagPrefix.isEmpty() || nearestParent.startsWith(tagPrefix, ignoreCase = true)) {
                    val closeLabel = "</$nearestParent>"
                    if (addedLabels.add(closeLabel)) {
                        addTagReplacementItem(
                            publisher = publisher,
                            label = closeLabel,
                            detail = "Close <$nearestParent> tag",
                            replaceBeforeLen = replaceLen,
                            replaceAfterLen = trailingGtLen,
                            insertText = "</$nearestParent>",
                            isSnippet = false
                        )
                    }
                }
            }

            // Suggest matching HTML tags
            for (spec in HTML_TAGS) {
                if (tagPrefix.isEmpty() || spec.tag.startsWith(tagPrefix, ignoreCase = true) ||
                    (tagPrefix == "!" && spec.tag.startsWith("!"))
                ) {
                    val displayLabel = "<${spec.tag}>"
                    if (addedLabels.add(displayLabel)) {
                        val snippet = spec.snippetBody ?: if (spec.isVoid) {
                            "<${spec.tag}>"
                        } else {
                            "<${spec.tag}>\$0</${spec.tag}>"
                        }
                        addTagReplacementItem(
                            publisher = publisher,
                            label = displayLabel,
                            detail = spec.detail,
                            replaceBeforeLen = replaceLen,
                            replaceAfterLen = trailingGtLen,
                            insertText = snippet,
                            isSnippet = snippet.contains('$')
                        )
                    }
                }
            }
            return addedLabels.isNotEmpty()
        }

        // 4. Bare word or Emmet expression in HTML body (not preceded by `<`)
        // Check for Emmet abbreviation token immediately before cursor (e.g. `!`, `div.card`, `ul>li*3`)
        val emmetTokenMatch = if (emmetEnabled) {
            Regex("""([!a-zA-Z.#][a-zA-Z0-9.#{}*+>:_\[\]="'-]*)$""").find(beforeCursor)
        } else null
        val emmetToken = emmetTokenMatch?.value ?: ""

        if (emmetToken.isEmpty() && prefix.isEmpty()) {
            // Do NOT show random completions when cursor is on whitespace/punctuation
            return false
        }

        // If user typed a multi-part Emmet abbreviation (contains ., #, >, +, *, {, :, or is `!`), show live Emmet expansion first!
        if (emmetEnabled && emmetToken.isNotEmpty() && (emmetToken == "!" || emmetToken.any { it in ".#>+*{:[" })) {
            val expanded = EmmetExpander.expand(emmetToken)
            if (expanded != null) {
                val previewSingleLine = expanded.trim().replace(Regex("\\s+"), " ").take(45)
                val label = "Emmet: $emmetToken"
                if (addedLabels.add(label)) {
                    addReplacementItem(
                        publisher = publisher,
                        label = label,
                        detail = "→ $previewSingleLine",
                        replaceLength = emmetToken.length,
                        insertText = expanded.trimEnd('\n'),
                        isSnippet = false
                    )
                }
            }
        }

        // Only allow HTML5 boilerplate snippet when typing 'html5' on an empty line
        if (prefix.isNotEmpty() && beforeCursor.trim() == prefix) {
            if ("html5".startsWith(prefix, ignoreCase = true) && addedLabels.add("html5 (HTML5 Boilerplate)")) {
                val html5 = "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n\t<meta charset=\"UTF-8\">\n\t<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n\t<title>\${1:Document}</title>\n</head>\n<body>\n\t\$0\n</body>\n</html>"
                addReplacementItem(publisher, "html5 (HTML5 Boilerplate)", "Full HTML5 Page", prefix.length, html5, isSnippet = true)
            }
        }

        return addedLabels.isNotEmpty()
    }

    private val CSS_PSEUDO_SELECTORS = listOf(
        SnippetItem(":hover", ":hover", ":hover {\n  \$0\n}", "CSS Pseudo-Class"),
        SnippetItem(":focus", ":focus", ":focus {\n  \$0\n}", "CSS Pseudo-Class"),
        SnippetItem(":active", ":active", ":active {\n  \$0\n}", "CSS Pseudo-Class"),
        SnippetItem(":visited", ":visited", ":visited {\n  \$0\n}", "CSS Pseudo-Class"),
        SnippetItem(":first-child", ":first-child", ":first-child {\n  \$0\n}", "CSS Pseudo-Class"),
        SnippetItem(":last-child", ":last-child", ":last-child {\n  \$0\n}", "CSS Pseudo-Class"),
        SnippetItem(":nth-child", ":nth-child(n)", ":nth-child(\${1:2n}) {\n  \$0\n}", "CSS Pseudo-Class"),
        SnippetItem(":not", ":not(selector)", ":not(\${1:.disabled}) {\n  \$0\n}", "CSS Pseudo-Class"),
        SnippetItem(":disabled", ":disabled", ":disabled {\n  \$0\n}", "CSS Pseudo-Class"),
        SnippetItem(":checked", ":checked", ":checked {\n  \$0\n}", "CSS Pseudo-Class"),
        SnippetItem(":focus-visible", ":focus-visible", ":focus-visible {\n  \$0\n}", "CSS Pseudo-Class"),
        SnippetItem("::before", "::before", "::before {\n  content: '';\n  \$0\n}", "CSS Pseudo-Element"),
        SnippetItem("::after", "::after", "::after {\n  content: '';\n  \$0\n}", "CSS Pseudo-Element"),
        SnippetItem("::placeholder", "::placeholder", "::placeholder {\n  \$0\n}", "CSS Pseudo-Element"),
        SnippetItem("::selection", "::selection", "::selection {\n  \$0\n}", "CSS Pseudo-Element")
    )

    // ── CSS Property & Value Completions ─────────────────────────────────────
    private fun populateCssCompletions(
        content: ContentReference,
        beforeCursor: String,
        prefix: String,
        publisher: CompletionPublisher,
        addedLabels: MutableSet<String>,
        isInlineStyle: Boolean
    ): Boolean {
        // Check if cursor is after `property: |` typing a CSS value
        // Only treat as property value if propSpec is a known CSS property OR there is whitespace after ':'
        val propValueMatch = Regex("""([a-zA-Z-]+)\s*:(\s*)([^;:{}]*)$""").find(beforeCursor)
        if (propValueMatch != null && !beforeCursor.contains("::")) {
            val propName = propValueMatch.groupValues[1].lowercase()
            val spaceAfterColon = propValueMatch.groupValues[2]
            val valPrefix = propValueMatch.groupValues[3].trimStart()
            val propSpec = CSS_PROPERTIES.firstOrNull { it.property.equals(propName, ignoreCase = true) }
            if (propSpec != null || spaceAfterColon.isNotEmpty()) {
                val candidates = (propSpec?.commonValues ?: emptyList()) + listOf("inherit", "initial", "unset", "var(--\$1)", "!important")
                for (v in candidates) {
                    if (valPrefix.isEmpty() || v.startsWith(valPrefix, ignoreCase = true)) {
                        if (addedLabels.add(v)) {
                            val needsSemicolon = !beforeCursor.endsWith(";")
                            val insertVal = if (needsSemicolon) "$v;" else v
                            addReplacementItem(
                                publisher = publisher,
                                label = v,
                                detail = "$propName value",
                                replaceLength = valPrefix.length,
                                insertText = insertVal,
                                isSnippet = insertVal.contains('$')
                            )
                        }
                    }
                }
                if (addedLabels.isNotEmpty()) return true
            }
        }

        // Check for CSS pseudo-class / pseudo-element (`:hov`, `::bef`, `button:f`)
        if (!isInlineStyle) {
            val pseudoMatch = Regex("""(::?[a-zA-Z-]*)$""").find(beforeCursor)
            if (pseudoMatch != null) {
                val pseudoPrefix = pseudoMatch.value
                for (ps in CSS_PSEUDO_SELECTORS) {
                    if (ps.prefix.startsWith(pseudoPrefix, ignoreCase = true)) {
                        if (addedLabels.add(ps.label)) {
                            addReplacementItem(
                                publisher = publisher,
                                label = ps.label,
                                detail = ps.detail,
                                replaceLength = pseudoPrefix.length,
                                insertText = ps.insertText,
                                isSnippet = true
                            )
                        }
                    }
                }
                if (addedLabels.isNotEmpty()) return true
            }
        }

        // Compute CSS hyphenated prefix (e.g. `background-c`, `justify-`, `@med`)
        val cssPrefixMatch = Regex("""([@:a-zA-Z_-][a-zA-Z0-9_-]*)$""").find(beforeCursor)
        val cssPrefix = cssPrefixMatch?.value ?: prefix
        if (cssPrefix.isEmpty()) return false

        // Top-level CSS snippets (@media, @keyframes, :root, body, reset)
        if (!isInlineStyle) {
            for (snip in CSS_TOP_LEVEL_SNIPPETS) {
                if (snip.prefix.startsWith(cssPrefix, ignoreCase = true)) {
                    if (addedLabels.add(snip.label)) {
                        addReplacementItem(
                            publisher = publisher,
                            label = snip.label,
                            detail = snip.detail,
                            replaceLength = cssPrefix.length,
                            insertText = snip.insertText,
                            isSnippet = true
                        )
                    }
                }
            }
        }

        // Match CSS properties by prefix or shorthand initials (e.g. `jc` -> `justify-content`, `df` -> `display: flex`)
        for (prop in CSS_PROPERTIES) {
            val matchesPrefix = prop.property.startsWith(cssPrefix, ignoreCase = true) ||
                prop.property.split('-').mapNotNull { it.firstOrNull() }.joinToString("").startsWith(cssPrefix, ignoreCase = true)
            if (matchesPrefix) {
                val propLabel = "${prop.property}: ${prop.defaultSnippetValue};"
                if (addedLabels.add(prop.property)) {
                    val snippet = "${prop.property}: \${1:${prop.defaultSnippetValue}};\$0"
                    addReplacementItem(
                        publisher = publisher,
                        label = propLabel,
                        detail = prop.detail,
                        replaceLength = cssPrefix.length,
                        insertText = snippet,
                        isSnippet = true
                    )
                }
                // Also offer top 2 concrete property: value; lines directly like Spck
                for (cv in prop.commonValues.take(2)) {
                    val concreteLine = "${prop.property}: $cv;"
                    if (concreteLine.startsWith(cssPrefix, ignoreCase = true) || prop.property.startsWith(cssPrefix, ignoreCase = true)) {
                        if (addedLabels.add(concreteLine)) {
                            addReplacementItem(
                                publisher = publisher,
                                label = concreteLine,
                                detail = "CSS Declaration",
                                replaceLength = cssPrefix.length,
                                insertText = concreteLine,
                                isSnippet = false
                            )
                        }
                    }
                }
            }
        }

        return addedLabels.isNotEmpty()
    }

    // ── General Code / Line & Identifier Completions (JS/TS/Python/Kotlin/etc.) ─
    private fun populateCodeCompletions(
        lang: String,
        content: ContentReference,
        lineIdx: Int,
        beforeCursor: String,
        prefix: String,
        publisher: CompletionPublisher,
        addedLabels: MutableSet<String>
    ): Boolean {
        // Check member access trigger after `.` (e.g., `document.`, `console.`, `window.`, `array.`)
        val dotMatch = Regex("""([a-zA-Z_$][a-zA-Z0-9_$]*)\.([a-zA-Z0-9_$]*)$""").find(beforeCursor)
        if (dotMatch != null) {
            val receiver = dotMatch.groupValues[1]
            val memberPrefix = dotMatch.groupValues[2]
            val members = memberCompletionsForReceiver(receiver)
            for (m in members) {
                if (memberPrefix.isEmpty() || m.prefix.startsWith(memberPrefix, ignoreCase = true)) {
                    if (addedLabels.add(m.label)) {
                        addReplacementItem(
                            publisher = publisher,
                            label = m.label,
                            detail = m.detail,
                            replaceLength = memberPrefix.length,
                            insertText = m.insertText,
                            isSnippet = m.insertText.contains('$')
                        )
                    }
                }
            }
            if (addedLabels.isNotEmpty()) return true
        }

        if (prefix.isEmpty()) return false

        val snippets = when (lang) {
            "javascript", "typescript", "jsx", "tsx", "js", "ts" -> JS_TS_SNIPPETS
            "python", "py" -> PYTHON_SNIPPETS
            "kotlin", "kt", "kts", "java" -> KOTLIN_JAVA_SNIPPETS
            else -> JS_TS_SNIPPETS
        }

        for (snip in snippets) {
            if (snip.prefix.startsWith(prefix, ignoreCase = true) || snip.label.startsWith(prefix, ignoreCase = true)) {
                if (addedLabels.add(snip.label)) {
                    addReplacementItem(
                        publisher = publisher,
                        label = snip.label,
                        detail = snip.detail,
                        replaceLength = prefix.length,
                        insertText = snip.insertText,
                        isSnippet = snip.insertText.contains('$')
                    )
                }
            }
        }

        // Standard language keywords & built-in symbols
        val keywordsAndBuiltins = when (lang) {
            "python", "py" -> listOf(
                "return", "yield", "raise", "assert", "break", "continue", "pass", "global", "nonlocal",
                "True", "False", "None", "self", "len", "range", "enumerate", "zip", "sorted", "reversed",
                "isinstance", "super", "open", "input", "Exception", "ValueError", "TypeError", "KeyError"
            )
            "kotlin", "kt", "kts", "java" -> listOf(
                "val", "var", "return", "break", "continue", "object", "companion", "interface", "enum",
                "override", "private", "protected", "internal", "public", "lateinit", "suspend", "inline",
                "true", "false", "null", "this", "super", "String", "Int", "Long", "Float", "Double", "Boolean",
                "List", "MutableList", "Map", "MutableMap", "Set", "listOf", "mutableListOf", "mapOf", "setOf",
                "emptyList", "remember", "mutableStateOf", "LaunchedEffect", "DisposableEffect", "Modifier",
                "Column", "Row", "Box", "Text", "Button", "Icon", "Surface", "Scaffold", "LazyColumn",
                "runCatching", "withContext", "Dispatchers"
            )
            else -> listOf(
                "return", "break", "continue", "async", "await", "typeof", "instanceof", "new", "throw",
                "true", "false", "null", "undefined", "this", "super", "console", "document", "window",
                "localStorage", "sessionStorage", "navigator", "Promise", "Array", "Object", "String",
                "Number", "Boolean", "Math", "JSON", "Date", "Map", "Set", "Error", "parseInt", "parseFloat",
                "requestAnimationFrame", "clearTimeout", "clearInterval", "alert"
            )
        }
        for (kw in keywordsAndBuiltins) {
            if (kw.startsWith(prefix, ignoreCase = true) && !kw.equals(prefix, ignoreCase = false)) {
                if (addedLabels.add(kw)) {
                    addReplacementItem(
                        publisher = publisher,
                        label = kw,
                        detail = "Keyword / Global",
                        replaceLength = prefix.length,
                        insertText = kw,
                        isSnippet = false
                    )
                }
            }
        }

        populateDocumentWords(content, lineIdx, prefix, publisher, addedLabels)
        return addedLabels.isNotEmpty()
    }

    private fun memberCompletionsForReceiver(receiver: String): List<SnippetItem> = when (receiver) {
        "console" -> listOf(
            SnippetItem("log", "log(...)", "log(\$0)", "console.log"),
            SnippetItem("error", "error(...)", "error(\$0)", "console.error"),
            SnippetItem("warn", "warn(...)", "warn(\$0)", "console.warn"),
            SnippetItem("table", "table(...)", "table(\$0)", "console.table"),
            SnippetItem("time", "time(label)", "time('\${1:timer}')", "console.time"),
            SnippetItem("timeEnd", "timeEnd(label)", "timeEnd('\${1:timer}')", "console.timeEnd")
        )
        "document" -> listOf(
            SnippetItem("getElementById", "getElementById(id)", "getElementById('\${1:id}')\$0", "DOM Element by ID"),
            SnippetItem("querySelector", "querySelector(selector)", "querySelector('\${1:selector}')\$0", "DOM Query Selector"),
            SnippetItem("querySelectorAll", "querySelectorAll(selector)", "querySelectorAll('\${1:selector}')\$0", "DOM Query All"),
            SnippetItem("createElement", "createElement(tagName)", "createElement('\${1:div}')\$0", "Create DOM Element"),
            SnippetItem("addEventListener", "addEventListener(event, fn)", "addEventListener('\${1:click}', (\${2:e}) => {\n\t\$0\n})", "Document Event Listener"),
            SnippetItem("body", "body", "body", "Document Body"),
            SnippetItem("title", "title", "title", "Document Title")
        )
        "window" -> listOf(
            SnippetItem("addEventListener", "addEventListener(event, fn)", "addEventListener('\${1:load}', () => {\n\t\$0\n})", "Window Event Listener"),
            SnippetItem("localStorage", "localStorage", "localStorage", "Web Storage"),
            SnippetItem("location", "location", "location", "Window Location"),
            SnippetItem("requestAnimationFrame", "requestAnimationFrame(fn)", "requestAnimationFrame(\${1:step})", "Animation Frame"),
            SnippetItem("innerWidth", "innerWidth", "innerWidth", "Viewport Width"),
            SnippetItem("innerHeight", "innerHeight", "innerHeight", "Viewport Height")
        )
        "localStorage", "sessionStorage" -> listOf(
            SnippetItem("getItem", "getItem(key)", "getItem('\${1:key}')", "Storage Get"),
            SnippetItem("setItem", "setItem(key, value)", "setItem('\${1:key}', \${2:value})", "Storage Set"),
            SnippetItem("removeItem", "removeItem(key)", "removeItem('\${1:key}')", "Storage Remove"),
            SnippetItem("clear", "clear()", "clear()", "Storage Clear")
        )
        "JSON" -> listOf(
            SnippetItem("stringify", "stringify(value)", "stringify(\${1:obj}, null, 2)", "JSON Serialize"),
            SnippetItem("parse", "parse(text)", "parse(\${1:str})", "JSON Parse")
        )
        "Math" -> listOf(
            SnippetItem("floor", "floor(x)", "floor(\$0)", "Math.floor"),
            SnippetItem("ceil", "ceil(x)", "ceil(\$0)", "Math.ceil"),
            SnippetItem("round", "round(x)", "round(\$0)", "Math.round"),
            SnippetItem("random", "random()", "random()", "Math.random"),
            SnippetItem("max", "max(a, b)", "max(\$0)", "Math.max"),
            SnippetItem("min", "min(a, b)", "min(\$0)", "Math.min"),
            SnippetItem("abs", "abs(x)", "abs(\$0)", "Math.abs")
        )
        else -> listOf(
            SnippetItem("addEventListener", "addEventListener(type, listener)", "addEventListener('\${1:click}', (\${2:e}) => {\n\t\$0\n})", "Event Listener"),
            SnippetItem("classList", "classList.toggle/add/remove", "classList.\${1:toggle}('\${2:active}')", "Element ClassList"),
            SnippetItem("style", "style.property", "style.\$0", "Inline Style"),
            SnippetItem("innerHTML", "innerHTML", "innerHTML = \${0:''}", "Element HTML"),
            SnippetItem("textContent", "textContent", "textContent = \${0:''}", "Element Text"),
            SnippetItem("value", "value", "value", "Input Value"),
            SnippetItem("forEach", "forEach(item => ...)", "forEach((\${1:item}) => {\n\t\$0\n})", "Array forEach"),
            SnippetItem("map", "map(item => ...)", "map((\${1:item}) => \$0)", "Array map"),
            SnippetItem("filter", "filter(item => ...)", "filter((\${1:item}) => \$0)", "Array filter"),
            SnippetItem("push", "push(item)", "push(\$0)", "Array push"),
            SnippetItem("includes", "includes(item)", "includes(\$0)", "Includes check"),
            SnippetItem("length", "length", "length", "Length property")
        )
    }

    /**
     * Extracts identifiers from the active buffer so any variable, function, class, or ID
     * already in the file is suggested immediately.
     */
    private fun populateDocumentWords(
        content: ContentReference,
        cursorLine: Int,
        prefix: String,
        publisher: CompletionPublisher,
        addedLabels: MutableSet<String>
    ) {
        if (prefix.length < 2) return
        val wordRegex = Regex("""[a-zA-Z_$][a-zA-Z0-9_$]{2,40}""")
        val lineCount = content.lineCount.coerceAtMost(600)
        var count = 0
        for (l in 0 until lineCount) {
            val lineStr = runCatching { content.getLine(l).toString() }.getOrDefault("")
            for (match in wordRegex.findAll(lineStr)) {
                val word = match.value
                if (word.equals(prefix, ignoreCase = false)) continue
                if (word.startsWith(prefix, ignoreCase = true) && addedLabels.add(word)) {
                    addReplacementItem(
                        publisher = publisher,
                        label = word,
                        detail = if (l == cursorLine) "Current Line" else "Line ${l + 1}",
                        replaceLength = prefix.length,
                        insertText = word,
                        isSnippet = false
                    )
                    count++
                    if (count >= 15) return
                }
            }
        }
    }

    /**
     * Scans HTML lines up to `(maxLine, maxColOnLastLine)` and returns the stack of unclosed
     * non-void HTML tag names.
     */
    fun findUnclosedHtmlTags(content: ContentReference, maxLine: Int, maxColOnLastLine: Int): List<String> {
        val stack = Stack<String>()
        val startLine = (maxLine - 300).coerceAtLeast(0)
        var inComment = false

        for (l in startLine..maxLine) {
            val rawLine = runCatching { content.getLine(l).toString() }.getOrDefault("")
            val lineStr = if (l == maxLine) rawLine.substring(0, maxColOnLastLine.coerceIn(0, rawLine.length)) else rawLine
            val len = lineStr.length
            var idx = 0

            while (idx < len) {
                if (inComment) {
                    val endC = lineStr.indexOf("-->", idx)
                    if (endC != -1) {
                        inComment = false
                        idx = endC + 3
                    } else break
                } else {
                    val openBracket = lineStr.indexOf('<', idx)
                    if (openBracket == -1) break
                    if (lineStr.startsWith("<!--", openBracket)) {
                        val endC = lineStr.indexOf("-->", openBracket + 4)
                        if (endC != -1) {
                            idx = endC + 3
                        } else {
                            inComment = true
                            break
                        }
                        continue
                    }
                    if (lineStr.startsWith("<!", openBracket) || lineStr.startsWith("<?", openBracket)) {
                        val closeB = lineStr.indexOf('>', openBracket + 2)
                        idx = if (closeB != -1) closeB + 1 else len
                        continue
                    }

                    val isClose = openBracket + 1 < len && lineStr[openBracket + 1] == '/'
                    var nameStart = openBracket + if (isClose) 2 else 1
                    while (nameStart < len && lineStr[nameStart].isWhitespace()) nameStart++
                    var nameEnd = nameStart
                    while (nameEnd < len && (lineStr[nameEnd].isLetterOrDigit() || lineStr[nameEnd] == '-' || lineStr[nameEnd] == ':')) nameEnd++

                    if (nameEnd > nameStart) {
                        val tagName = lineStr.substring(nameStart, nameEnd).lowercase()
                        val closeBracket = lineStr.indexOf('>', nameEnd)
                        if (closeBracket == -1) {
                            // Incomplete tag at end of line
                            break
                        }
                        val isSelfClosing = closeBracket > 0 && lineStr[closeBracket - 1] == '/'
                        if (tagName !in VOID_HTML_TAGS && !isSelfClosing) {
                            if (isClose) {
                                val matchIdx = stack.lastIndexOf(tagName)
                                if (matchIdx != -1) {
                                    while (stack.size > matchIdx) stack.pop()
                                }
                            } else {
                                stack.push(tagName)
                            }
                        }
                        idx = closeBracket + 1
                    } else {
                        idx = openBracket + 1
                    }
                }
            }
        }
        return stack.toList()
    }

    // ── Helper CompletionItem Builders ───────────────────────────────────────
    private fun addReplacementItem(
        publisher: CompletionPublisher,
        label: String,
        detail: String,
        replaceLength: Int,
        insertText: String,
        isSnippet: Boolean
    ) {
        if (isSnippet) {
            val added = runCatching {
                val parsed = CodeSnippetParser.parse(insertText)
                val desc = SnippetDescription(replaceLength, parsed, true)
                publisher.addItem(SimpleSnippetCompletionItem(label, detail, desc))
                true
            }.getOrDefault(false)
            if (added) return
        }

        val cleanText = insertText
            .replace(Regex("""\$\{\d+:([^}]*)\}"""), "$1")
            .replace(Regex("""\$\d+"""), "")

        publisher.addItem(object : CompletionItem(label, detail) {
            override fun performCompletion(editor: CodeEditor, text: Content, line: Int, column: Int) {
                val startCol = (column - replaceLength).coerceAtLeast(0)
                if (startCol < column) {
                    text.delete(line, startCol, line, column)
                }
                text.insert(line, startCol, cleanText)
            }
        })
    }

    private fun addTagReplacementItem(
        publisher: CompletionPublisher,
        label: String,
        detail: String,
        replaceBeforeLen: Int,
        replaceAfterLen: Int,
        insertText: String,
        isSnippet: Boolean
    ) {
        if (isSnippet && replaceAfterLen == 0) {
            val added = runCatching {
                val parsed = CodeSnippetParser.parse(insertText)
                val desc = SnippetDescription(replaceBeforeLen, parsed, true)
                publisher.addItem(SimpleSnippetCompletionItem(label, detail, desc))
                true
            }.getOrDefault(false)
            if (added) return
        }

        publisher.addItem(object : CompletionItem(label, detail) {
            override fun performCompletion(editor: CodeEditor, text: Content, line: Int, column: Int) {
                val startCol = (column - replaceBeforeLen).coerceAtLeast(0)
                val maxCol = text.getColumnCount(line)
                val endCol = (column + replaceAfterLen).coerceAtMost(maxCol)
                if (startCol < endCol) {
                    text.delete(line, startCol, line, endCol)
                }
                if (isSnippet) {
                    val cursorMarker = "\$0"
                    val resolved = insertText
                        .replace(Regex("""\$\{1:([^}]*)\}"""), "$1")
                        .replace(Regex("""\$\{\d+:([^}]*)\}"""), "$1")
                        .replace("\$1", "")
                        .replace("\$2", "")
                        .replace("\$3", "")
                    val markerIdx = resolved.indexOf(cursorMarker)
                    val finalStr = resolved.replace(cursorMarker, "")
                    text.insert(line, startCol, finalStr)
                    if (markerIdx != -1) {
                        val beforeMarker = finalStr.substring(0, markerIdx)
                        val lines = beforeMarker.split('\n')
                        val targetLine = line + lines.size - 1
                        val targetCol = if (lines.size == 1) startCol + lines[0].length else lines.last().length
                        editor.setSelection(targetLine, targetCol)
                    }
                } else {
                    text.insert(line, startCol, insertText)
                }
            }
        })
    }
}
