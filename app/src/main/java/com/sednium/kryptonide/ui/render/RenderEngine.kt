package com.sednium.kryptonide.ui.render

import android.app.Application
import com.sednium.kryptonide.utils.StorageUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sednium.kryptonide.data.model.FileNode
import com.sednium.kryptonide.data.model.FileType
import com.sednium.kryptonide.data.model.ProjectTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object RenderTemplates {

    // Rendered in a WebView (HTML output, with or without a bundler)
    val WEB_PREVIEW = setOf(
        ProjectTemplate.HTML,        // legacy alias, same renderer as VANILLA
        ProjectTemplate.VANILLA,     // plain HTML/CSS/JS
        ProjectTemplate.REACT,       // React via Babel CDN
        ProjectTemplate.REACT_VITE,  // React + Vite — same CDN renderer
        ProjectTemplate.VITE,        // Vite (generic) — same CDN renderer
        ProjectTemplate.NEXTJS,      // Next.js page renderer
        ProjectTemplate.MARKDOWN,    // Markdown preview
        ProjectTemplate.JSON,        // JSON viewer
    )

    // Executed via a shell process (Termux runtime or system fallback)
    val SHELL_RUN = setOf(
        ProjectTemplate.PYTHON,
        ProjectTemplate.FASTAPI,
        ProjectTemplate.NODE,
        ProjectTemplate.NODE_EXPRESS,  // Node server — same runner as NODE
        ProjectTemplate.PHP,
        ProjectTemplate.GO,
        ProjectTemplate.RUST,
        ProjectTemplate.CPP,
        ProjectTemplate.C,
        ProjectTemplate.CSHARP,
        ProjectTemplate.KOTLIN,        // non-Android Kotlin script/CLI
        ProjectTemplate.JAVA,          // non-Android Java CLI
    )

    // Built into an APK via BuildEngine, never run directly in-app
    val ANDROID = setOf(
        ProjectTemplate.KOTLIN.takeIf { false } ?: ProjectTemplate.ANDROID_KOTLIN,
        ProjectTemplate.ANDROID_JAVA,
        ProjectTemplate.ANDROID_COMPOSE,
        ProjectTemplate.COMPOSE,      // legacy alias of ANDROID_COMPOSE
    )

    // Nothing to run — empty/unscaffolded project
    val NO_RUNTIME = setOf(
        ProjectTemplate.BLANK
    )

    fun isWebPreview(t: ProjectTemplate) = t in WEB_PREVIEW
    fun isWebPreview(t: ProjectTemplate, files: List<FileNode>, activeFileName: String? = null): Boolean {
        if (t in WEB_PREVIEW) return true
        if (!activeFileName.isNullOrBlank()) {
            val lower = activeFileName.lowercase()
            if (lower.endsWith(".html") || lower.endsWith(".htm") ||
                lower.endsWith(".md") || lower.endsWith(".markdown") ||
                lower.endsWith(".json") || lower.endsWith(".jsx") || lower.endsWith(".tsx")
            ) {
                return true
            }
        }
        return files.any {
            val name = it.name.lowercase()
            name == "index.html" || name == "index.htm" ||
            name.endsWith(".html") || name.endsWith(".htm") ||
            name.endsWith(".md") || name.endsWith(".markdown") ||
            name.endsWith(".json")
        }
    }

    fun isShellRun(t: ProjectTemplate)   = t in SHELL_RUN
    fun isAndroid(t: ProjectTemplate)    = t in ANDROID
    fun isBlank(t: ProjectTemplate)      = t in NO_RUNTIME

    fun isSupported(t: ProjectTemplate)  = isWebPreview(t) || isShellRun(t)

    // ── Run action type — used by the UI to decide button icon/label/behavior
    enum class RunAction { WEB_PREVIEW, SHELL_RUN, BUILD_APK, DISABLED }

    fun runActionFor(t: ProjectTemplate): RunAction = when {
        isWebPreview(t) -> RunAction.WEB_PREVIEW
        isShellRun(t)   -> RunAction.SHELL_RUN
        isAndroid(t)    -> RunAction.BUILD_APK
        else            -> RunAction.DISABLED
    }

    fun runActionFor(t: ProjectTemplate, files: List<FileNode>, activeFileName: String? = null): RunAction = when {
        isWebPreview(t, files, activeFileName) -> RunAction.WEB_PREVIEW
        isShellRun(t)   -> RunAction.SHELL_RUN
        isAndroid(t)    -> RunAction.BUILD_APK
        else            -> RunAction.DISABLED
    }
}

enum class RenderStatus { IDLE, PREPARING, RUNNING, PREVIEW_READY, SUCCESS, FAILED }

data class RenderLogLine(val text: String, val level: RenderLogLevel = RenderLogLevel.INFO)

enum class RenderLogLevel { INFO, WARN, ERROR, SUCCESS, CMD, SYSTEM }

data class RenderUiState(
    val status: RenderStatus         = RenderStatus.IDLE,
    val log: List<RenderLogLine>     = emptyList(),
    val previewHtml: String?         = null,
    val previewUrl: String?          = null,
    val errorSummary: String?        = null,
    val runtimeVersion: String?      = null,
    val runDurationMs: Long          = 0L,
    val isPortForwarded: Boolean     = false
)

class RenderEngine(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(RenderUiState())
    val state: StateFlow<RenderUiState> = _state.asStateFlow()
    private var runJob: Job? = null
    private var activeProcess: Process? = null

    fun run(
        template: ProjectTemplate,
        projectName: String,
        files: List<FileNode>,
        activeFileName: String? = null,
        force: Boolean = false
    ) {
        if (!force && (_state.value.status == RenderStatus.RUNNING ||
            _state.value.status == RenderStatus.PREPARING)) return
        runJob?.cancel()
        try {
            activeProcess?.destroyForcibly()
        } catch (_: Exception) {}
        activeProcess = null
        _state.update { it.copy(status = RenderStatus.PREPARING, errorSummary = null, log = emptyList()) }
        runJob = viewModelScope.launch {
            val start = System.currentTimeMillis()
            when {
                RenderTemplates.isWebPreview(template, files, activeFileName) ->
                    runWebPreview(template, projectName, files, start, activeFileName)
                RenderTemplates.isShellRun(template) ->
                    runShellProcess(template, projectName, files, start)
                else -> {
                    appendLog("Template '${template}' is not supported.", RenderLogLevel.ERROR)
                    _state.update { it.copy(status = RenderStatus.FAILED) }
                }
            }
        }
    }

    fun restart(
        template: ProjectTemplate,
        projectName: String,
        files: List<FileNode>,
        activeFileName: String? = null
    ) {
        run(template, projectName, files, activeFileName, force = true)
    }

    fun stop() {
        runJob?.cancel()
        try {
            activeProcess?.destroyForcibly()
        } catch (_: Exception) {}
        activeProcess = null
        _state.update { it.copy(status = RenderStatus.IDLE) }
        appendLog("Stopped.", RenderLogLevel.SYSTEM)
    }

    fun clearLog() {
        _state.update { it.copy(log = emptyList(), errorSummary = null) }
    }

    // ── Web preview ────────────────────────────────────────────────────────────

    private suspend fun runWebPreview(
        template: ProjectTemplate,
        projectName: String,
        files: List<FileNode>,
        startMs: Long,
        activeFileName: String? = null
    ) = withContext(Dispatchers.Default) {
        val resolvedTemplate = if (template in RenderTemplates.WEB_PREVIEW) template else {
            val activeExt = activeFileName?.substringAfterLast('.', "")?.lowercase() ?: ""
            when {
                activeExt in setOf("html", "htm") -> ProjectTemplate.HTML
                activeExt in setOf("md", "markdown") -> ProjectTemplate.MARKDOWN
                activeExt == "json" -> ProjectTemplate.JSON
                activeExt in setOf("jsx", "tsx") -> ProjectTemplate.REACT
                files.any { it.name.endsWith(".jsx") || it.name.endsWith(".tsx") } -> ProjectTemplate.REACT
                files.any { it.name.endsWith(".md") || it.name.endsWith(".markdown") } -> ProjectTemplate.MARKDOWN
                files.any { it.name.endsWith(".json") } -> ProjectTemplate.JSON
                else -> ProjectTemplate.HTML
            }
        }

        appendLog("[$projectName] Preparing ${resolvedTemplate.name.lowercase()} preview…", RenderLogLevel.SYSTEM)
        _state.update { it.copy(status = RenderStatus.RUNNING) }
        try {
            val html = when (resolvedTemplate) {
                ProjectTemplate.HTML,
                ProjectTemplate.VANILLA  -> buildHtmlPreview(files, activeFileName)
                ProjectTemplate.REACT    -> buildReactPreview(files, activeFileName)
                ProjectTemplate.REACT_VITE,
                ProjectTemplate.VITE     -> buildVitePreview(files, activeFileName)
                ProjectTemplate.NEXTJS   -> buildNextJsPreview(files, activeFileName)
                ProjectTemplate.MARKDOWN -> buildMarkdownPreview(files, activeFileName)
                ProjectTemplate.JSON     -> buildJsonPreview(files, activeFileName)
                else                     -> buildHtmlPreview(files, activeFileName)
            }
            val elapsed = System.currentTimeMillis() - startMs
            appendLog("Preview ready (${elapsed}ms)", RenderLogLevel.SUCCESS)
            _state.update { it.copy(status = RenderStatus.PREVIEW_READY, previewHtml = html, runDurationMs = elapsed) }
        } catch (e: Exception) {
            appendLog("Preview failed: ${e.message}", RenderLogLevel.ERROR)
            _state.update { it.copy(status = RenderStatus.FAILED, errorSummary = e.message) }
        }
    }

    // ── HTML / Vanilla Builder ────────────────────────────────────────────────

    private fun buildHtmlPreview(files: List<FileNode>, activeFileName: String? = null): String {
        val activeHtmlNode = if (!activeFileName.isNullOrBlank() &&
            (activeFileName.endsWith(".html", ignoreCase = true) || activeFileName.endsWith(".htm", ignoreCase = true))
        ) {
            files.firstOrNull { it.name.equals(activeFileName, ignoreCase = true) }
        } else null

        val targetNode = activeHtmlNode
            ?: files.firstOrNull { it.name.equals("index.html", ignoreCase = true) || it.name.equals("index.htm", ignoreCase = true) }
            ?: files.firstOrNull { it.name.endsWith(".html", ignoreCase = true) || it.name.endsWith(".htm", ignoreCase = true) }

        var rawHtml = targetNode?.content ?: defaultHtml()

        // Wrap in DOCTYPE and html/body if snippet
        val hasHtmlTag = rawHtml.contains("<html", ignoreCase = true)
        val hasHeadTag = rawHtml.contains("<head", ignoreCase = true)
        val hasBodyTag = rawHtml.contains("<body", ignoreCase = true)

        if (!hasHtmlTag && !hasBodyTag) {
            rawHtml = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0">
                  <title>${targetNode?.name ?: "Preview"}</title>
                </head>
                <body>
                $rawHtml
                </body>
                </html>
            """.trimIndent()
        } else if (!hasHeadTag) {
            val htmlMatch = Regex("<html[^>]*>", RegexOption.IGNORE_CASE).find(rawHtml)
            if (htmlMatch != null) {
                rawHtml = rawHtml.substring(0, htmlMatch.range.last + 1) +
                    "\n<head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=5.0\"></head>" +
                    rawHtml.substring(htmlMatch.range.last + 1)
            }
        }

        // Ensure viewport meta exists
        if (!rawHtml.contains("viewport", ignoreCase = true)) {
            val headMatch = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(rawHtml)
            if (headMatch != null) {
                rawHtml = rawHtml.substring(0, headMatch.range.last + 1) +
                    "\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=5.0\">" +
                    rawHtml.substring(headMatch.range.last + 1)
            }
        }

        // Auto-inline unlinked CSS/JS ONLY if the HTML file is a fragment (no <html> and no <body> tag).
        // If the user wrote a complete <html>...<body> document without linking style.css, do not
        // forcibly inject unlinked template CSS that would override e.g. <body bgcolor="black">.
        val isFragment = !hasHtmlTag && !hasBodyTag
        val cssToInline = files.filter { css ->
            css.name.endsWith(".css", ignoreCase = true) && (
                rawHtml.contains(css.name, ignoreCase = true) || isFragment
            )
        }
        val inlineCss = if (cssToInline.isNotEmpty()) {
            cssToInline.joinToString("\n\n") { "/* ${it.name} */\n${it.content ?: ""}" }
        } else ""

        val styleBlock = if (inlineCss.isNotBlank()) {
            "\n<style id=\"__krypton_styles__\">\n$inlineCss\n</style>\n"
        } else ""

        val unlinkedJs = if (isFragment) {
            files.filter { js ->
                js.name.endsWith(".js", ignoreCase = true) && !rawHtml.contains(js.name, ignoreCase = true)
            }
        } else emptyList()
        val wrappedJs = if (unlinkedJs.isNotEmpty()) {
            unlinkedJs.joinToString("\n\n") { file ->
                """
                // [Krypton] Inlined ${file.name}
                (function() {
                  try {
                ${file.content ?: ""}
                  } catch (e) {
                    console.error('[${file.name}] ' + (e.stack || e.message));
                  }
                })();
                """.trimIndent()
            }
        } else ""

        val scriptBlock = if (wrappedJs.isNotBlank()) {
            "\n<script id=\"__krypton_unlinked_scripts__\">\n$wrappedJs\n</script>\n"
        } else ""

        val headCloseRegex = Regex("</head>", RegexOption.IGNORE_CASE)
        rawHtml = if (headCloseRegex.containsMatchIn(rawHtml)) {
            rawHtml.replaceFirst(headCloseRegex, "$styleBlock</head>")
        } else {
            "$styleBlock$rawHtml"
        }

        val bridgeScript = consoleBridgeScript()
        val bodyCloseRegex = Regex("</body>", RegexOption.IGNORE_CASE)
        rawHtml = if (bodyCloseRegex.containsMatchIn(rawHtml)) {
            rawHtml.replaceFirst(bodyCloseRegex, "$scriptBlock$bridgeScript\n</body>")
        } else {
            "$rawHtml$scriptBlock$bridgeScript"
        }

        return rawHtml
    }

    // ── React / JSX / TSX Code Sanitizer ──────────────────────────────────────

    private fun sanitizeReactCode(raw: String, fileName: String): String {
        var code = raw
        // Strip CSS imports so browser doesn't attempt to load CSS as JS module
        code = code.replace(Regex("""import\s+['"][^'"]*\.css['"];?"""), "// [Krypton] CSS inlined")

        // Strip Next.js server/client directives & metadata exports
        code = code.replace(Regex("""['"]use client['"];?"""), "")
        code = code.replace(Regex("""['"]use server['"];?"""), "")
        code = code.replace(Regex("""export\s+const\s+metadata\s*=\s*\{[^}]*\};?"""), "")

        // Transform React imports into global hook destructuring
        code = code.replace(Regex("""import\s+React\s*,?\s*\{?([^}'"]*)\}?\s*from\s+['"]react['"];?""")) { m ->
            val hooks = m.groupValues[1].trim()
            if (hooks.isNotEmpty()) "const { $hooks } = React;" else "// [Krypton] React global"
        }
        code = code.replace(Regex("""import\s+\{([^}'"]*)\}\s*from\s+['"]react['"];?""")) { m ->
            "const { ${m.groupValues[1]} } = React;"
        }

        // Strip ReactDOM imports
        code = code.replace(Regex("""import\s+ReactDOM\s*from\s+['"]react-dom(/client)?['"];?"""), "// [Kryptonide] ReactDOM global")
        code = code.replace(Regex("""import\s*\{?\s*createRoot\s*\}?\s*from\s+['"]react-dom/client['"];?"""), "const createRoot = ReactDOM.createRoot;")

        // Handle framer-motion imports specifically
        code = code.replace(Regex("""import\s+\{([^}'"]*)\}\s*from\s+['"]framer-motion['"];?""")) { m ->
            "const { ${m.groupValues[1]} } = (window.Motion || window.framerMotion || { motion, AnimatePresence });"
        }
        code = code.replace(Regex("""import\s+([A-Za-z0-9_]+)\s+from\s+['"]framer-motion['"];?""")) { m ->
            "const ${m.groupValues[1]} = (window.Motion || window.framerMotion || { motion, AnimatePresence });"
        }

        // Handle lucide-react imports specifically
        code = code.replace(Regex("""import\s+\{([^}'"]*)\}\s*from\s+['"]lucide-react['"];?""")) { m ->
            "const { ${m.groupValues[1]} } = LucideIcons;"
        }

        // General external npm package imports or local modules
        code = code.replace(Regex("""import\s+([A-Za-z0-9_]+)\s+from\s+['"]([^'"]+)['"];?""")) { m ->
            val varName = m.groupValues[1]
            val modPath = m.groupValues[2]
            if (modPath.startsWith("./") || modPath.startsWith("../")) {
                "// [Kryptonide] local import $varName"
            } else {
                "const $varName = window['$modPath'] || window['$varName'] || {};"
            }
        }
        code = code.replace(Regex("""import\s+\{([^}'"]*)\}\s*from\s+['"]([^'"]+)['"];?""")) { m ->
            val bindings = m.groupValues[1]
            val modPath = m.groupValues[2]
            if (modPath.startsWith("./") || modPath.startsWith("../")) {
                "// [Kryptonide] local import { $bindings }"
            } else {
                "const { $bindings } = window['$modPath'] || {};"
            }
        }

        // Convert export default function Name -> function Name; window.Name = Name;
        code = code.replace(Regex("""export\s+default\s+function\s+([A-Za-z0-9_]+)""")) { m ->
            "window.${m.groupValues[1]} = ${m.groupValues[1]};\nfunction ${m.groupValues[1]}"
        }

        // Convert anonymous export default function(...) -> function InferredName(...)
        val inferredName = fileName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9]"), "")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }
            .ifBlank { "Component" }
        code = code.replace(Regex("""export\s+default\s+function\s*\(""")) {
            "window.$inferredName = $inferredName;\nfunction $inferredName("
        }

        // Convert export default Name; -> window.Name = Name;
        code = code.replace(Regex("""export\s+default\s+([A-Za-z0-9_]+);?""")) { m ->
            "window.${m.groupValues[1]} = ${m.groupValues[1]};"
        }

        // Convert named exports: export function Foo -> function Foo; window.Foo = Foo;
        code = code.replace(Regex("""export\s+function\s+([A-Za-z0-9_]+)""")) { m ->
            "window.${m.groupValues[1]} = ${m.groupValues[1]};\nfunction ${m.groupValues[1]}"
        }
        code = code.replace(Regex("""export\s+const\s+([A-Za-z0-9_]+)""")) { m ->
            "const ${m.groupValues[1]}"
        }
        code = code.replace(Regex("""export\s+\{[^}]*\};?"""), "")

        return code
    }

    // ── React (Babel CDN + Tailwind + Error Boundary) ─────────────────────────

    private fun buildReactPreview(files: List<FileNode>, activeFileName: String? = null): String {
        val css = files.filter { it.name.endsWith(".css", ignoreCase = true) }
            .joinToString("\n\n") { "/* ${it.name} */\n${it.content ?: ""}" }

        // Find primary entry / component files
        val primaryNames = setOf("App.jsx", "App.tsx", "App.js", "App.ts", "page.tsx", "page.jsx", "Home.jsx")
        val mainNames = setOf("main.jsx", "main.tsx", "index.js", "index.jsx", "index.tsx")

        val componentFiles = files.filter { f ->
            val ext = f.name.substringAfterLast('.', "").lowercase()
            (ext in setOf("jsx", "tsx", "js", "ts")) && f.name !in primaryNames && f.name !in mainNames
        }

        val primaryFiles = files.filter { it.name in primaryNames }
        val mainFiles = files.filter { it.name in mainNames }

        val codeChunks = mutableListOf<String>()

        // 1. Auxiliary components first
        componentFiles.forEach { f ->
            codeChunks.add("// ─── ${f.name} ───\n" + sanitizeReactCode(f.content ?: "", f.name))
        }

        // 2. Primary App / Page components
        if (primaryFiles.isNotEmpty()) {
            primaryFiles.forEach { f ->
                codeChunks.add("// ─── ${f.name} ───\n" + sanitizeReactCode(f.content ?: "", f.name))
            }
        } else {
            codeChunks.add("""
                function App() {
                    return (
                        <div className="p-8 text-center">
                            <h1 className="text-2xl font-bold text-indigo-500">Hello React!</h1>
                            <p className="text-gray-400 mt-2">Edit your components to get started.</p>
                        </div>
                    );
                }
                window.App = App;
            """.trimIndent())
        }

        // 3. Main entry if present
        mainFiles.forEach { f ->
            codeChunks.add("// ─── ${f.name} ───\n" + sanitizeReactCode(f.content ?: "", f.name))
        }

        val pkgJsonFile = files.firstOrNull { it.name == "package.json" }
        val dynamicScripts = StringBuilder()
        if (pkgJsonFile != null && !pkgJsonFile.content.isNullOrBlank()) {
            try {
                val root = org.json.JSONObject(pkgJsonFile.content)
                val deps = root.optJSONObject("dependencies")
                if (deps != null) {
                    val it = deps.keys()
                    while (it.hasNext()) {
                        val pkg = it.next()
                        if (pkg !in setOf("react", "react-dom", "framer-motion", "lucide-react", "tailwindcss")) {
                            dynamicScripts.append("  <script src=\"https://cdn.jsdelivr.net/npm/$pkg\" onerror=\"console.warn('Fallback CDN load for $pkg')\"></script>\n")
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val combinedCode = codeChunks.joinToString("\n\n")

        return """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0">
  <title>React Preview</title>
  <script src="https://cdn.tailwindcss.com"></script>
  <script src="https://unpkg.com/react@18/umd/react.development.js" crossorigin></script>
  <script src="https://unpkg.com/react-dom@18/umd/react-dom.development.js" crossorigin></script>
  <script src="https://unpkg.com/@babel/standalone/babel.min.js"></script>
  <script src="https://cdn.jsdelivr.net/npm/framer-motion@11.11.17/dist/framer-motion.js" crossorigin></script>
$dynamicScripts
  <style>
    * { box-sizing: border-box; }
    body { margin: 0; font-family: system-ui, -apple-system, sans-serif; }
    $css
  </style>
</head>
<body>
  <div id="root"></div>
  <script type="text/babel" data-presets="react,typescript,env">
    // Global React hooks destructuring
    const { useState, useEffect, useContext, useReducer, useCallback, useMemo, useRef, useLayoutEffect } = React;
    const createRoot = ReactDOM.createRoot;

    // Framer Motion global hooks & components
    const MotionPkg = window.Motion || window.framerMotion || {};
    const motion = MotionPkg.motion || (new Proxy({}, {
      get: (target, prop) => (props) => React.createElement(prop, props, props ? props.children : null)
    }));
    const AnimatePresence = MotionPkg.AnimatePresence || (({ children }) => children);
    const useAnimation = MotionPkg.useAnimation || (() => ({ start: () => {}, stop: () => {} }));
    const useMotionValue = MotionPkg.useMotionValue || ((init) => ({ get: () => init, set: () => {} }));
    const useTransform = MotionPkg.useTransform || ((v) => v);

    // Lucide Icons fallback proxy so icon imports never throw
    const LucideIcons = new Proxy({}, {
      get: (target, name) => (props) => (
        <svg width={props?.size || 20} height={props?.size || 20} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...props}>
          <circle cx="12" cy="12" r="10" />
        </svg>
      )
    });

    // Error Boundary to display clean error cards in preview
    class ErrorBoundary extends React.Component {
      constructor(props) { super(props); this.state = { hasError: false, error: null }; }
      static getDerivedStateFromError(error) { return { hasError: true, error }; }
      componentDidCatch(error, info) { console.error('[React Error]', error); }
      render() {
        if (this.state.hasError) {
          return (
            <div style={{ padding: '24px', background: '#0d1117', color: '#f85149', fontFamily: 'monospace', borderRadius: '12px', margin: '16px', border: '1px solid #30363d' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '12px' }}>
                <span style={{ fontSize: '20px' }}>⚠️</span>
                <strong style={{ fontSize: '15px', color: '#ff7b72' }}>React Runtime Error</strong>
              </div>
              <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontSize: '12px', background: '#161b22', padding: '12px', borderRadius: '6px', color: '#e6edf3' }}>
                {this.state.error?.stack || this.state.error?.message}
              </pre>
            </div>
          );
        }
        return this.props.children;
      }
    }

    try {
$combinedCode

      // Mount detected root component
      const container = document.getElementById('root');
      const RootComponent = window.App || window.Home || (typeof App !== 'undefined' ? App : (typeof Home !== 'undefined' ? Home : null));

      if (RootComponent && container && !container._reactRootContainer) {
        const root = ReactDOM.createRoot(container);
        root.render(
          <ErrorBoundary>
            <RootComponent />
          </ErrorBoundary>
        );
      }
    } catch (e) {
      console.error('[React Compile Error] ' + e.message);
      document.getElementById('root').innerHTML =
        '<div style="padding:24px;background:#0d1117;color:#f85149;font-family:monospace;border-radius:12px;margin:16px;border:1px solid #30363d">' +
        '<h3 style="margin:0 0 12px;color:#ff7b72">⚠️ React Compilation Error</h3>' +
        '<pre style="white-space:pre-wrap;word-break:break-all;font-size:12px;background:#161b22;padding:12px;border-radius:6px;color:#e6edf3">' +
        (e.stack || e.message) + '</pre></div>';
    }
  </script>
  ${consoleBridgeScript()}
</body>
</html>"""
    }

    // ── Vite Preview ──────────────────────────────────────────────────────────

    private fun buildVitePreview(files: List<FileNode>, activeFileName: String? = null): String {
        return buildReactPreview(files, activeFileName)
    }

    // ── Next.js Preview ───────────────────────────────────────────────────────

    private fun buildNextJsPreview(files: List<FileNode>, activeFileName: String? = null): String {
        val css = files.filter { it.name.endsWith(".css", ignoreCase = true) }
            .joinToString("\n\n") { "/* ${it.name} */\n${it.content ?: ""}" }

        val pageFile = files.firstOrNull { it.name in setOf("page.tsx", "page.jsx", "index.tsx", "index.jsx") }
        val layoutFile = files.firstOrNull { it.name in setOf("layout.tsx", "layout.jsx") }
        val otherFiles = files.filter {
            val ext = it.name.substringAfterLast('.', "").lowercase()
            ext in setOf("tsx", "jsx", "ts", "js") && it != pageFile && it != layoutFile
        }

        val codeChunks = mutableListOf<String>()
        otherFiles.forEach { codeChunks.add("// ─── ${it.name} ───\n" + sanitizeReactCode(it.content ?: "", it.name)) }
        if (layoutFile != null) codeChunks.add("// ─── ${layoutFile.name} ───\n" + sanitizeReactCode(layoutFile.content ?: "", layoutFile.name))
        if (pageFile != null) codeChunks.add("// ─── ${pageFile.name} ───\n" + sanitizeReactCode(pageFile.content ?: "", pageFile.name))

        val pkgJsonFile = files.firstOrNull { it.name == "package.json" }
        val dynamicScripts = StringBuilder()
        if (pkgJsonFile != null && !pkgJsonFile.content.isNullOrBlank()) {
            try {
                val root = org.json.JSONObject(pkgJsonFile.content)
                val deps = root.optJSONObject("dependencies")
                if (deps != null) {
                    val it = deps.keys()
                    while (it.hasNext()) {
                        val pkg = it.next()
                        if (pkg !in setOf("react", "react-dom", "framer-motion", "lucide-react", "tailwindcss")) {
                            dynamicScripts.append("  <script src=\"https://cdn.jsdelivr.net/npm/$pkg\" onerror=\"console.warn('Fallback CDN load for $pkg')\"></script>\n")
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val combinedCode = codeChunks.joinToString("\n\n")

        return """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0">
  <title>Next.js Preview</title>
  <script src="https://cdn.tailwindcss.com"></script>
  <script src="https://unpkg.com/react@18/umd/react.development.js" crossorigin></script>
  <script src="https://unpkg.com/react-dom@18/umd/react-dom.development.js" crossorigin></script>
  <script src="https://unpkg.com/@babel/standalone/babel.min.js"></script>
  <script src="https://cdn.jsdelivr.net/npm/framer-motion@11.11.17/dist/framer-motion.js" crossorigin></script>
$dynamicScripts
  <style>
    * { box-sizing: border-box; }
    body { margin: 0; font-family: system-ui, -apple-system, sans-serif; }
    $css
  </style>
</head>
<body>
  <div id="root"></div>
  <script type="text/babel" data-presets="react,typescript,env">
    const { useState, useEffect, useContext, useReducer, useCallback, useMemo, useRef } = React;

    const MotionPkg = window.Motion || window.framerMotion || {};
    const motion = MotionPkg.motion || (new Proxy({}, {
      get: (target, prop) => (props) => React.createElement(prop, props, props ? props.children : null)
    }));
    const AnimatePresence = MotionPkg.AnimatePresence || (({ children }) => children);
    const useAnimation = MotionPkg.useAnimation || (() => ({ start: () => {}, stop: () => {} }));
    const useMotionValue = MotionPkg.useMotionValue || ((init) => ({ get: () => init, set: () => {} }));
    const useTransform = MotionPkg.useTransform || ((v) => v);

    const LucideIcons = new Proxy({}, {
      get: (target, name) => (props) => (
        <svg width={props?.size || 20} height={props?.size || 20} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...props}>
          <circle cx="12" cy="12" r="10" />
        </svg>
      )
    });

    class ErrorBoundary extends React.Component {
      constructor(props) { super(props); this.state = { hasError: false, error: null }; }
      static getDerivedStateFromError(error) { return { hasError: true, error }; }
      componentDidCatch(error, info) { console.error('[Next.js Error]', error); }
      render() {
        if (this.state.hasError) {
          return (
            <div style={{ padding: '24px', background: '#0d1117', color: '#f85149', fontFamily: 'monospace', borderRadius: '12px', margin: '16px', border: '1px solid #30363d' }}>
              <strong style={{ fontSize: '15px', color: '#ff7b72' }}>⚠️ Next.js Runtime Error</strong>
              <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontSize: '12px', background: '#161b22', padding: '12px', borderRadius: '6px', color: '#e6edf3', marginTop: '12px' }}>
                {this.state.error?.stack || this.state.error?.message}
              </pre>
            </div>
          );
        }
        return this.props.children;
      }
    }

    try {
$combinedCode

      const container = document.getElementById('root');
      const PageComponent = window.Home || window.Page || (typeof Home !== 'undefined' ? Home : null);
      const LayoutComponent = window.RootLayout || window.Layout || (typeof RootLayout !== 'undefined' ? RootLayout : null);

      if (container) {
        const root = ReactDOM.createRoot(container);
        if (LayoutComponent && PageComponent) {
          root.render(
            <ErrorBoundary>
              <LayoutComponent>
                <PageComponent />
              </LayoutComponent>
            </ErrorBoundary>
          );
        } else if (PageComponent) {
          root.render(
            <ErrorBoundary>
              <PageComponent />
            </ErrorBoundary>
          );
        }
      }
    } catch (e) {
      console.error('[Next.js Compile Error] ' + e.message);
      document.getElementById('root').innerHTML =
        '<div style="padding:24px;background:#0d1117;color:#f85149;font-family:monospace;border-radius:12px;margin:16px;border:1px solid #30363d">' +
        '<h3 style="margin:0 0 12px;color:#ff7b72">⚠️ Next.js Compilation Error</h3>' +
        '<pre style="white-space:pre-wrap;word-break:break-all;font-size:12px;background:#161b22;padding:12px;border-radius:6px;color:#e6edf3">' +
        (e.stack || e.message) + '</pre></div>';
    }
  </script>
  ${consoleBridgeScript()}
</body>
</html>"""
    }

    // ── Markdown Preview (with Offline Fallback) ──────────────────────────────

    private fun buildMarkdownPreview(files: List<FileNode>, activeFileName: String? = null): String {
        val targetNode = if (!activeFileName.isNullOrBlank() &&
            (activeFileName.endsWith(".md", ignoreCase = true) || activeFileName.endsWith(".markdown", ignoreCase = true))
        ) {
            files.firstOrNull { it.name.equals(activeFileName, ignoreCase = true) }
        } else null
            ?: files.firstOrNull { it.name.equals("README.md", ignoreCase = true) }
            ?: files.firstOrNull { it.name.endsWith(".md", ignoreCase = true) || it.name.endsWith(".markdown", ignoreCase = true) }

        val rawMd = targetNode?.content ?: "# Markdown Preview\n\nNo markdown file found in project."
        val safeRaw = rawMd.replace("</script>", "<\\/script>")

        return """<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0">
  <title>${targetNode?.name ?: "Markdown Preview"}</title>
  <script src="https://unpkg.com/marked/marked.min.js"></script>
  <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/github-markdown-css/5.5.1/github-markdown-dark.min.css">
  <style>
    * { box-sizing: border-box; }
    body { background: #0d1117; padding: 1.25rem; margin: 0; color: #e6edf3; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif; }
    .markdown-body { max-width: 860px; margin: 0 auto; background: transparent; }
    pre { background: #161b22 !important; border: 1px solid #30363d; border-radius: 6px; }
  </style>
</head>
<body>
  <script type="text/markdown" id="__krypton_md__">$safeRaw</script>
  <article class="markdown-body" id="content"></article>
  <script>
    function parseSimpleMarkdown(text) {
      let html = text
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/^### (.*$)/gim, '<h3>$1</h3>')
        .replace(/^## (.*$)/gim, '<h2>$1</h2>')
        .replace(/^# (.*$)/gim, '<h1>$1</h1>')
        .replace(/\*\*(.*?)\*\*/gim, '<strong>$1</strong>')
        .replace(/\*(.*?)\*/gim, '<em>$1</em>')
        .replace(/`([^`]+)`/gim, '<code>$1</code>')
        .replace(/^\s*-\s+(.*$)/gim, '<li>$1</li>')
        .replace(/\n\n/gim, '<p></p>');
      return html;
    }

    try {
      const raw = document.getElementById('__krypton_md__').textContent;
      if (typeof marked !== 'undefined') {
        document.getElementById('content').innerHTML = marked.parse(raw);
      } else {
        document.getElementById('content').innerHTML = parseSimpleMarkdown(raw);
      }
    } catch (e) {
      document.getElementById('content').textContent = document.getElementById('__krypton_md__').textContent;
    }
  </script>
  ${consoleBridgeScript()}
</body>
</html>"""
    }

    // ── JSON Viewer (Collapsible Tree & Search) ───────────────────────────────

    private fun buildJsonPreview(files: List<FileNode>, activeFileName: String? = null): String {
        val targetNode = if (!activeFileName.isNullOrBlank() && activeFileName.endsWith(".json", ignoreCase = true)) {
            files.firstOrNull { it.name.equals(activeFileName, ignoreCase = true) }
        } else null
            ?: files.firstOrNull { it.name.equals("package.json", ignoreCase = true) }
            ?: files.firstOrNull { it.name.endsWith(".json", ignoreCase = true) }

        val rawJson = targetNode?.content ?: "{}"
        val safeRaw = rawJson.replace("</script>", "<\\/script>")

        return """<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>${targetNode?.name ?: "JSON Viewer"}</title>
  <style>
    * { box-sizing: border-box; }
    body { background: #0d1117; color: #e6edf3; font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, monospace; padding: 1rem; margin: 0; }
    .toolbar { display: flex; gap: 8px; margin-bottom: 12px; align-items: center; }
    .search-input { background: #161b22; border: 1px solid #30363d; color: #e6edf3; border-radius: 6px; padding: 6px 10px; font-size: 12px; flex: 1; outline: none; }
    .btn { background: #21262d; border: 1px solid #30363d; color: #58a6ff; border-radius: 6px; padding: 6px 12px; font-size: 12px; cursor: pointer; }
    .btn:active { background: #30363d; }
    .key  { color: #79c0ff; font-weight: 600; }
    .str  { color: #a5d6ff; }
    .num  { color: #f8c291; }
    .bool { color: #ff7b72; font-weight: bold; }
    .null { color: #8b949e; font-style: italic; }
    pre   { white-space: pre-wrap; word-break: break-all; font-size: 13px; line-height: 1.6; margin: 0; }
  </style>
</head>
<body>
  <div class="toolbar">
    <input type="text" id="filterInput" class="search-input" placeholder="Search JSON keys or values…" oninput="filterJson()">
    <button class="btn" onclick="copyJson()">Copy</button>
  </div>
  <script type="application/json" id="__krypton_json__">$safeRaw</script>
  <pre id="jsonDisplay"></pre>
  <script>
    let originalObj = null;

    function colorize(jsonStr) {
      return jsonStr.replace(/("(\\u[\da-fA-F]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d*)?(?:[eE][+\-]?\d+)?)/g, function(m) {
        let cls = 'num';
        if (/^"/.test(m)) {
          cls = /:$/.test(m) ? 'key' : 'str';
        } else if (/true|false/.test(m)) {
          cls = 'bool';
        } else if (/null/.test(m)) {
          cls = 'null';
        }
        return '<span class="' + cls + '">' + m + '</span>';
      });
    }

    function renderFormatted(obj) {
      const formatted = JSON.stringify(obj, null, 2);
      document.getElementById('jsonDisplay').innerHTML = colorize(formatted);
    }

    function filterJson() {
      const q = document.getElementById('filterInput').value.trim().toLowerCase();
      if (!q) { renderFormatted(originalObj); return; }
      const formatted = JSON.stringify(originalObj, null, 2);
      const lines = formatted.split('\n');
      const filtered = lines.filter(l => l.toLowerCase().includes(q) || l.trim() === '{' || l.trim() === '}' || l.trim() === '[' || l.trim() === ']');
      document.getElementById('jsonDisplay').innerHTML = colorize(filtered.join('\n'));
    }

    function copyJson() {
      navigator.clipboard.writeText(document.getElementById('__krypton_json__').textContent);
      alert('JSON copied to clipboard');
    }

    try {
      const raw = document.getElementById('__krypton_json__').textContent;
      originalObj = JSON.parse(raw);
      renderFormatted(originalObj);
    } catch (e) {
      document.getElementById('jsonDisplay').innerHTML = '<span style="color:#f85149">Invalid JSON: ' + e.message + '</span>';
    }
  </script>
  ${consoleBridgeScript()}
</body>
</html>"""
    }

    // ── Console bridge (postMessage — avoids JavascriptInterface cross-origin) ─
    // Uses postMessage to same window since loadDataWithBaseURL sets a base URL.
    // The WebView's WebChromeClient.onConsoleMessage() catches all console.* calls
    // natively without any JS bridge needed. We just enrich it with a clean style.

    private fun consoleBridgeStyle(): String = ""

    private fun consoleBridgeScript(): String = """
<script id="__krypton_console_bridge__">
(function() {
  if (window.__krypton_bridge_installed__) return;
  window.__krypton_bridge_installed__ = true;
  var ol = console.log, ow = console.warn, oe = console.error, oi = console.info, od = console.debug;
  function formatArg(a) {
    if (a === null) return 'null';
    if (a === undefined) return 'undefined';
    if (typeof a === 'object') {
      try { return JSON.stringify(a, null, 2); } catch(e) { return String(a); }
    }
    return String(a);
  }
  function relay(level, args, originalFn) {
    var msg = Array.prototype.slice.call(args).map(formatArg).join(' ');
    window.postMessage({ type: 'krypton-console', level: level, message: msg }, '*');
    if (originalFn) {
      try { originalFn.call(console, msg); } catch(_) { originalFn.apply(console, args); }
    }
  }
  console.log   = function() { relay('LOG', arguments, ol); };
  console.warn  = function() { relay('WARNING', arguments, ow); };
  console.error = function() { relay('ERROR', arguments, oe); };
  console.info  = function() { relay('INFO', arguments, oi); };
  console.debug = function() { relay('DEBUG', arguments, od); };
  window.onerror = function(message, source, lineno, colno, error) {
    var src = (source || '').split('/').pop();
    var loc = src ? ' (' + src + ':' + lineno + ':' + colno + ')' : '';
    relay('ERROR', [(message || 'Uncaught error') + loc]);
    return false;
  };
  window.addEventListener('error', function(e) {
    var src = (e.filename || '').split('/').pop();
    var loc = src ? ' (' + src + ':' + e.lineno + ':' + e.colno + ')' : '';
    relay('ERROR', [(e.message || 'Uncaught error') + loc]);
  });
  window.addEventListener('unhandledrejection', function(e) {
    var reason = e.reason;
    var msg = reason ? (reason.stack || reason.message || String(reason)) : 'Unhandled Promise Rejection';
    relay('ERROR', ['Unhandled Promise Rejection: ' + msg]);
  });
})();
</script>"""

    private fun defaultHtml(): String = """<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Preview</title>
  <style>body{background:#0d1117;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;font-family:system-ui,sans-serif;}</style>
</head>
<body>
  <h1 style="color:#818cf8;">Hello, World!</h1>
</body>
</html>"""

    // ── Shell process runner ──────────────────────────────────────────────────

    private suspend fun runShellProcess(
        template: ProjectTemplate,
        projectName: String,
        files: List<FileNode>,
        startMs: Long
    ) = withContext(Dispatchers.IO) {
        _state.update { it.copy(status = RenderStatus.RUNNING) }

        val projectDir = File(StorageUtils.getProjectsDir(), projectName).let {
            if (it.exists()) it else {
                val rootDir = File(StorageUtils.getKryptonideRoot(), projectName)
                if (rootDir.exists()) rootDir else {
                    val legacy = File(StorageUtils.getExternalStorageDir(), "KryptonIDE/$projectName")
                    if (legacy.exists()) legacy else it
                }
            }
        }

        val (runtime, entryArgs) = resolveRuntime(template, projectDir, files)
        if (runtime == null) {
            val hint = runtimeInstallHint(template)
            appendLog("[$projectName] Runtime not found for ${template.name.lowercase()}.", RenderLogLevel.ERROR)
            appendLog(hint, RenderLogLevel.SYSTEM)
            _state.update { it.copy(status = RenderStatus.FAILED, errorSummary = "Runtime not available. $hint") }
            return@withContext
        }

        val versionLine = getVersion(runtime)
        if (versionLine != null) {
            appendLog(versionLine, RenderLogLevel.SYSTEM)
            _state.update { it.copy(runtimeVersion = versionLine) }
        }

        appendLog("Running: ${(listOf(runtime) + entryArgs).joinToString(" ")}", RenderLogLevel.CMD)

        try {
            val pb = ProcessBuilder(listOf(runtime) + entryArgs)
                .directory(projectDir)
                .redirectErrorStream(true)
            pb.environment()["HOME"] = projectDir.absolutePath
            // Include Termux PATH so binaries installed via pkg are found
            pb.environment()["PATH"] =
                "/data/data/com.termux/files/usr/bin:" +
                "/data/data/com.termux/files/usr/local/bin:" +
                (pb.environment()["PATH"] ?: "")

            // Inject shared packages path to escape noexec/symlink restrictions
            val packagesDir = com.sednium.kryptonide.ui.packages.PackageInstaller.getPackagesDir()
            val nodeModules = File(packagesDir, "node_modules").absolutePath
            val pythonPkgs = File(packagesDir, "python").absolutePath
            val env = pb.environment()
            env["NODE_PATH"] = nodeModules + if (env["NODE_PATH"].isNullOrEmpty()) "" else ":${env["NODE_PATH"]}"
            env["PYTHONPATH"] = pythonPkgs + if (env["PYTHONPATH"].isNullOrEmpty()) "" else ":${env["PYTHONPATH"]}"
            val internalTmp = File(getApplication<android.app.Application>().cacheDir, "tmp").apply { mkdirs() }
            env["CARGO_TARGET_DIR"] = File(internalTmp, "cargo").apply { mkdirs() }.absolutePath
            env["GOTMPDIR"] = File(internalTmp, "go").apply { mkdirs() }.absolutePath
            env["TMPDIR"] = internalTmp.absolutePath

            val process = pb.start()
            activeProcess = process
            val reader  = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line!!
                appendLog(currentLine, RenderLogLevel.INFO)

                // Detect server listening URLs (e.g. Uvicorn, Express, etc.)
                val urlMatch = Regex("""https?://(?:0\.0\.0\.0|127\.0\.0\.1|localhost):(\d+)""").find(currentLine)
                if (urlMatch != null) {
                    val port = urlMatch.groupValues[1]
                    _state.update { it.copy(previewUrl = "http://localhost:$port", isPortForwarded = true) }
                }
            }

            val exitCode = process.waitFor()
            activeProcess = null
            val elapsed  = System.currentTimeMillis() - startMs

            if (exitCode == 0) {
                appendLog("\nProcess finished (${elapsed}ms) — exit code 0", RenderLogLevel.SUCCESS)
                _state.update { it.copy(status = RenderStatus.SUCCESS, runDurationMs = elapsed) }
            } else {
                appendLog("\nProcess exited with code $exitCode (${elapsed}ms)", RenderLogLevel.ERROR)
                _state.update { it.copy(status = RenderStatus.FAILED, runDurationMs = elapsed) }
            }
        } catch (e: Exception) {
            appendLog("Failed to start process: ${e.message}", RenderLogLevel.ERROR)
            _state.update { it.copy(status = RenderStatus.FAILED, errorSummary = e.message) }
        }
    }

    private fun resolveRuntime(
        template: ProjectTemplate,
        projectDir: File,
        files: List<FileNode>
    ): Pair<String?, List<String>> {
        return when (template) {
            ProjectTemplate.PYTHON ->
                Pair(findBinary("python3") ?: findBinary("python"),
                     listOf("-u", files.firstOrNull { it.name == "main.py" || it.name == "app.py" }?.name ?: "main.py"))
            ProjectTemplate.FASTAPI -> {
                val py = findBinary("python3") ?: findBinary("python")
                if (py != null) {
                    val appFolder = files.firstOrNull { it.name == "app" && it.type == FileType.FOLDER }
                    val hasAppMain = File(projectDir, "app/main.py").exists() ||
                        (appFolder != null && files.any { it.name == "main.py" && it.parentId == appFolder.id })
                    val targetModule = if (hasAppMain) "app.main:app" else "main:app"
                    Pair(py, listOf("-m", "uvicorn", targetModule, "--host", "0.0.0.0", "--port", "8000"))
                } else {
                    Pair(null, emptyList())
                }
            }
            ProjectTemplate.NODE, ProjectTemplate.NODE_EXPRESS ->
                Pair(findBinary("node"),
                     listOf(files.firstOrNull { it.name in setOf("index.js", "server.js", "app.js") }?.name ?: "index.js"))
            ProjectTemplate.PHP ->
                Pair(findBinary("php"),
                     listOf("-f", files.firstOrNull { it.name == "index.php" }?.name ?: "index.php"))
            ProjectTemplate.GO   -> Pair(findBinary("go"),    listOf("run", "."))
            ProjectTemplate.RUST -> Pair(findBinary("cargo"), listOf("run"))
            ProjectTemplate.CPP  -> {
                val cc  = findBinary("clang++") ?: findBinary("g++")
                val sh  = findBinary("sh") ?: "/bin/sh"
                val src = files.firstOrNull { it.name.endsWith(".cpp") }?.name ?: "main.cpp"
                val outBin = File(getApplication<android.app.Application>().cacheDir, "krypton_cpp_bin").absolutePath
                if (cc != null) Pair(sh, listOf("-c", "$cc -o $outBin $src && $outBin")) else Pair(null, emptyList())
            }
            ProjectTemplate.C -> {
                val cc  = findBinary("clang") ?: findBinary("gcc")
                val sh  = findBinary("sh") ?: "/bin/sh"
                val src = files.firstOrNull { it.name.endsWith(".c") }?.name ?: "main.c"
                val outBin = File(getApplication<android.app.Application>().cacheDir, "krypton_c_bin").absolutePath
                if (cc != null) Pair(sh, listOf("-c", "$cc -o $outBin $src && $outBin")) else Pair(null, emptyList())
            }
            ProjectTemplate.CSHARP -> Pair(findBinary("dotnet"), listOf("run"))
            ProjectTemplate.KOTLIN -> {
                // Run as a Kotlin script via kotlinc -script, or kscript if available
                val kscript = findBinary("kscript")
                val kotlinc = findBinary("kotlinc")
                val entry = files.firstOrNull { it.name.endsWith(".kts") || it.name == "Main.kt" }?.name
                    ?: "Main.kt"
                when {
                    kscript != null -> Pair(kscript, listOf(entry))
                    kotlinc != null -> {
                        val sh  = findBinary("sh") ?: "/bin/sh"
                        val out = File(projectDir, ".krypton_out").absolutePath
                        Pair(sh, listOf("-c", "$kotlinc -script $entry"))
                    }
                    else -> Pair(null, emptyList())
                }
            }

            ProjectTemplate.JAVA -> {
                val javac = findBinary("javac")
                val java  = findBinary("java")
                val entry = files.firstOrNull { it.name == "Main.java" }?.name ?: "Main.java"
                val className = entry.removeSuffix(".java")
                if (javac != null && java != null) {
                    val sh = findBinary("sh") ?: "/bin/sh"
                    Pair(sh, listOf("-c", "$javac $entry && $java $className"))
                } else {
                    Pair(null, emptyList())
                }
            }
            else -> Pair(null, emptyList())
        }
    }

    private fun findBinary(name: String): String? {
        val paths = listOf(
            "/data/data/com.termux/files/usr/bin/$name",
            "/data/data/com.termux/files/usr/local/bin/$name",
            "/usr/bin/$name",
            "/usr/local/bin/$name",
            "/bin/$name",
            "/system/bin/$name"
        )
        for (p in paths) if (File(p).canExecute()) return p
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("which", name))
            val res  = proc.inputStream.bufferedReader().readLine()?.trim()
            if (!res.isNullOrEmpty() && File(res).canExecute()) res else null
        } catch (e: Exception) { null }
    }

    private fun getVersion(runtime: String): String? = try {
        val p = Runtime.getRuntime().exec(arrayOf(runtime, "--version"))
        p.inputStream.bufferedReader().readLine()?.trim()
            ?: p.errorStream.bufferedReader().readLine()?.trim()
    } catch (e: Exception) { null }

    private fun runtimeInstallHint(template: ProjectTemplate): String = when (template) {
        ProjectTemplate.PYTHON, ProjectTemplate.FASTAPI -> "Use Kryptonide Standalone PyPI Engine: pip install <package>"
        ProjectTemplate.NODE, ProjectTemplate.NODE_EXPRESS -> "Use Kryptonide Standalone NPM Engine: npm install <package>"
        ProjectTemplate.PHP    -> "PHP runtime: Run via Kryptonide Cloud Preview or local binary"
        ProjectTemplate.GO     -> "Go workspace: Configured in packages/go"
        ProjectTemplate.RUST   -> "Rust workspace: Configured in packages/rust"
        ProjectTemplate.CPP, ProjectTemplate.C -> "C/C++ runtime: Configured in packages/cpp"
        ProjectTemplate.CSHARP -> ".NET runtime: Run via Cloud Build"
        ProjectTemplate.KOTLIN -> "Kotlin runtime: Run via Cloud Build"
        ProjectTemplate.JAVA   -> "Java runtime: Run via Cloud Build"
        else                   -> "Use Kryptonide Package Manager or Cloud Runner."
    }

    private fun appendLog(text: String, level: RenderLogLevel = RenderLogLevel.INFO) {
        _state.update { s -> s.copy(log = s.log + RenderLogLine(text, level)) }
    }
}
