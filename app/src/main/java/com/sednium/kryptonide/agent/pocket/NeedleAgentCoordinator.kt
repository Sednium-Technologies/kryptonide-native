package com.sednium.kryptonide.agent.pocket

import com.sednium.kryptonide.agent.tools.NeedleToolExecutor
import com.sednium.kryptonide.agent.tools.ToolResult
import com.sednium.kryptonide.ui.preview.PreviewConsoleBridge
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

data class PreFlightResult(
    val statusEmitted: String?,
    val searchFindings: String?,
    val consoleErrors: String?,
    val terminalOutput: String?,
    val enrichedContextBlock: String,
    val targetFile: String?
)

data class PostFlightResult(
    val statusEmitted: String,
    val diagnosticsPassed: Boolean,
    val diagnosticsReport: String,
    val previewHealthy: Boolean,
    val selfHealingApplied: Boolean,
    val selfHealingDetails: String?,
    val summaryCard: String
)

/**
 * Coordinates Needle-3 Pre-Flight Workspace Discovery, Surgical Model Execution,
 * and Post-Flight Autonomous Runtime Verification & Self-Healing.
 *
 * Designed with a Smart RAM-Safe Routing Strategy:
 * When heavy local models (MiniCPM5 1B) are resident in libllama_server.so,
 * Pre-Flight tools run in-process with ZERO model-swap RAM lag.
 */
object NeedleAgentCoordinator {

    private val STOP_WORDS = setOf(
        "the", "a", "an", "and", "or", "in", "on", "at", "to", "for", "of", "with",
        "is", "are", "was", "were", "be", "this", "that", "it", "my", "our", "your",
        "please", "can", "you", "fix", "check", "run", "do", "make", "change", "update",
        "and", "then", "now", "here", "file", "code", "runtime", "error", "bug", "console"
    )

    /**
     * Extracts potential code identifiers, CSS classes, or UI keywords from the prompt.
     */
    fun extractSearchIdentifiers(prompt: String): List<String> {
        val identifiers = mutableListOf<String>()

        // 1. Quoted tokens e.g. "submitBtn", 'header', `.button`
        val quotedRegex = Regex("""['"`]([a-zA-Z0-9_.-]+)['"`]""")
        quotedRegex.findAll(prompt).forEach { match ->
            val token = match.groupValues[1].trim()
            if (token.length >= 2) identifiers.add(token)
        }

        // 2. Explicit filenames like index.html, styles.css, script.js
        val fileRegex = Regex("""\b([a-zA-Z0-9_-]+\.(html|css|js|ts|jsx|tsx|json|py|kt|java))\b""", RegexOption.IGNORE_CASE)
        fileRegex.findAll(prompt).forEach { match ->
            identifiers.add(match.groupValues[1])
        }

        // 3. UI and Code Keywords
        val uiKeywords = listOf(
            "button", "btn", "header", "navbar", "nav", "footer", "sidebar", "card",
            "modal", "dialog", "input", "form", "table", "menu", "title", "logo",
            "container", "wrapper", "hero", "alert", "badge", "icon", "counter",
            "theme", "dark", "light", "color", "background", "font", "margin", "padding"
        )
        val lowerPrompt = prompt.lowercase(Locale.ROOT)
        for (kw in uiKeywords) {
            if (lowerPrompt.contains(kw) && !identifiers.contains(kw)) {
                identifiers.add(kw)
            }
        }

        // 4. CamelCase / snake_case tokens
        val tokenRegex = Regex("""\b[a-zA-Z][a-zA-Z0-9]*[A-Z][a-zA-Z0-9]*\b|\b[a-z0-9]+_[a-z0-9_]+\b""")
        tokenRegex.findAll(prompt).forEach { match ->
            val word = match.value
            if (word.length >= 3 && !identifiers.contains(word)) {
                identifiers.add(word)
            }
        }

        return identifiers.distinct().take(4)
    }

    /**
     * STEP 1 — Pre-Flight Workspace Discovery Pass.
     * Searches workspace for code matches and inspects live runtime errors before generation.
     */
    suspend fun runPreFlight(
        userPrompt: String,
        projectDir: File? = null,
        activeFile: String? = null,
        onStatus: (suspend (String) -> Unit)? = null
    ): PreFlightResult {
        if (projectDir != null) {
            NeedleToolExecutor.projectDirProvider = { projectDir }
        }
        if (activeFile != null) {
            NeedleToolExecutor.activeFilePathProvider = { activeFile }
        }

        val lowerPrompt = userPrompt.lowercase(Locale.ROOT)
        val isChatOnly = listOf(
            "hello", "hi", "hey", "who are you", "what are you", "thank", "thanks"
        ).any { lowerPrompt.trim() == it }

        if (isChatOnly) {
            return PreFlightResult(
                statusEmitted = null,
                searchFindings = null,
                consoleErrors = null,
                terminalOutput = null,
                enrichedContextBlock = "",
                targetFile = activeFile
            )
        }

        val statusMsg = "Scanning workspace & runtime..."
        onStatus?.invoke("<status>$statusMsg</status>")

        // 1. Workspace Search (search_grep)
        val searchIdentifiers = extractSearchIdentifiers(userPrompt)
        val searchFindings = StringBuilder()
        var discoveredTargetFile: String? = activeFile

        for (id in searchIdentifiers) {
            val grepRes = NeedleToolExecutor.searchGrep(id, maxMatches = 15)
            if (grepRes.success && grepRes.output.isNotBlank() && !grepRes.output.startsWith("No matches")) {
                searchFindings.appendLine("Matches for '$id':")
                searchFindings.appendLine(grepRes.output)
                if (discoveredTargetFile == null) {
                    val firstMatchFile = grepRes.output.lines().firstOrNull()?.substringBefore(':')
                    if (!firstMatchFile.isNullOrBlank()) {
                        discoveredTargetFile = firstMatchFile
                    }
                }
            }
        }

        // 2. Live Runtime & Console Errors (preview_get_console_logs)
        val errorKeywords = setOf("error", "bug", "fix", "broken", "issue", "console", "runtime", "fail", "not working", "crash")
        val isErrorInspectionNeeded = errorKeywords.any { lowerPrompt.contains(it) } ||
            PreviewConsoleBridge.getEntries("ERROR").isNotEmpty()

        val consoleErrors = if (isErrorInspectionNeeded) {
            val consoleRes = NeedleToolExecutor.previewGetConsoleLogs("ERROR")
            if (consoleRes.success && consoleRes.output.isNotBlank() && !consoleRes.output.startsWith("No preview console")) {
                consoleRes.output
            } else null
        } else null

        // 3. Terminal Errors
        val terminalOut = if (isErrorInspectionNeeded) {
            val termRes = NeedleToolExecutor.terminalReadLastOutput(15)
            if (termRes.success && termRes.output.isNotBlank() && !termRes.output.startsWith("No recent")) {
                termRes.output
            } else null
        } else null

        // Build compact <needle_workspace_findings>
        val enriched = buildString {
            if (searchFindings.isNotBlank() || consoleErrors != null || terminalOut != null) {
                appendLine("<needle_workspace_findings>")
                if (searchFindings.isNotBlank()) {
                    appendLine("<!-- Workspace search results discovered for agent -->")
                    appendLine(searchFindings.toString().trim())
                }
                if (consoleErrors != null) {
                    appendLine("\n<!-- Active JS Preview Console Runtime Errors -->")
                    appendLine(consoleErrors.trim())
                }
                if (terminalOut != null) {
                    appendLine("\n<!-- Recent Terminal Output -->")
                    appendLine(terminalOut.trim())
                }
                append("</needle_workspace_findings>")
            }
        }

        return PreFlightResult(
            statusEmitted = statusMsg,
            searchFindings = searchFindings.toString().ifBlank { null },
            consoleErrors = consoleErrors,
            terminalOutput = terminalOut,
            enrichedContextBlock = enriched,
            targetFile = discoveredTargetFile
        )
    }

    /**
     * STEP 3 — Post-Flight Autonomous Runtime Verification & Multi-Pass Self-Healing.
     * Verifies file syntax, unclosed tags, missing asset/script references, and live preview console logs.
     * Automatically applies surgical self-healing patches across up to 3 passes.
     */
    suspend fun runPostFlightVerification(
        targetFilePath: String?,
        projectDir: File? = null,
        onStatus: (suspend (String) -> Unit)? = null
    ): PostFlightResult {
        if (projectDir != null) {
            NeedleToolExecutor.projectDirProvider = { projectDir }
        }

        val checkStatus = "Checking runtime & syntax..."
        onStatus?.invoke("<status>$checkStatus</status>")

        val fileToInspect = targetFilePath
            ?: NeedleToolExecutor.activeFilePathProvider?.invoke()
            ?: "index.html"

        val healingActions = mutableListOf<String>()
        val projectRoot = NeedleToolExecutor.getProjectRoot()
        var diagRes = NeedleToolExecutor.codeGetDiagnostics(fileToInspect)

        // Multi-Pass Self-Healing Loop (up to 3 passes)
        for (pass in 1..3) {
            var passRepairedSomething = false

            // Pass Step A: HTML / CSS Syntax Diagnostics Healing
            if (!diagRes.success) {
                val syntaxFixes = healSyntaxDiagnostics(fileToInspect, projectRoot, diagRes, onStatus)
                if (syntaxFixes.isNotEmpty()) {
                    healingActions.addAll(syntaxFixes)
                    passRepairedSomething = true
                    diagRes = NeedleToolExecutor.codeGetDiagnostics(fileToInspect)
                }
            }

            // Pass Step B: Missing Resource Links (Assets, Stylesheets, Scripts)
            val resourceFixes = healMissingResourcesAndLinks(fileToInspect, projectRoot, onStatus)
            if (resourceFixes.isNotEmpty()) {
                healingActions.addAll(resourceFixes)
                passRepairedSomething = true
            }

            // Pass Step C: Runtime JS & DOM Console Errors
            val runtimeFixes = healRuntimeConsoleErrors(projectRoot, onStatus)
            if (runtimeFixes.isNotEmpty()) {
                healingActions.addAll(runtimeFixes)
                passRepairedSomething = true
            }

            if (!passRepairedSomething) {
                break
            }
        }

        // Web Preview Verification
        val hasPreview = PreviewConsoleBridge.hasActiveWebView()
        var previewHealthy = true
        if (hasPreview) {
            NeedleToolExecutor.previewReload()
            delay(150)
            val consoleErrors = PreviewConsoleBridge.getEntries("ERROR")
            if (consoleErrors.isNotEmpty()) {
                previewHealthy = false
            }
        } else {
            val errors = PreviewConsoleBridge.getEntries("ERROR")
            if (errors.isNotEmpty()) {
                previewHealthy = false
            }
        }

        val selfHealingApplied = healingActions.isNotEmpty()
        val selfHealingDetails = if (selfHealingApplied) {
            healingActions.joinToString("; ")
        } else null

        // Generate Summary Card
        val summaryCard = buildString {
            append("\n\n> **Runtime Verification Summary**\n")
            if (diagRes.success) {
                append("> - **Syntax & Diagnostics**: Clean (0 errors detected)\n")
            } else {
                append("> - **Syntax & Diagnostics**: Issues detected: ${diagRes.error}\n")
            }
            if (previewHealthy) {
                append("> - **Preview Runtime**: Healthy (0 JS console errors)\n")
            } else {
                val count = PreviewConsoleBridge.getEntries("ERROR").size
                append("> - **Preview Runtime**: Warning ($count runtime console errors detected)\n")
            }
            if (selfHealingApplied && selfHealingDetails != null) {
                append("> - **Autonomous Self-Healing**: $selfHealingDetails\n")
            }
        }

        return PostFlightResult(
            statusEmitted = checkStatus,
            diagnosticsPassed = diagRes.success,
            diagnosticsReport = diagRes.output,
            previewHealthy = previewHealthy,
            selfHealingApplied = selfHealingApplied,
            selfHealingDetails = selfHealingDetails,
            summaryCard = summaryCard
        )
    }

    private suspend fun healSyntaxDiagnostics(
        fileToInspect: String,
        projectRoot: File,
        diagRes: ToolResult,
        onStatus: (suspend (String) -> Unit)?
    ): List<String> {
        val actions = mutableListOf<String>()
        val targetFile = File(projectRoot, fileToInspect)
        if (!targetFile.exists()) return actions

        val output = diagRes.output + "\n" + (diagRes.error ?: "")
        var content = targetFile.readText()

        // 1. Unclosed HTML tags
        val unclosedMatches = Regex("""Unclosed tag <([a-zA-Z0-9-]+)>""").findAll(output).toList()
        if (unclosedMatches.isNotEmpty()) {
            unclosedMatches.forEach { match ->
                val tagName = match.groupValues[1]
                onStatus?.invoke("<status>🩹 Needle: Self-healing unclosed <$tagName> tag...</status>")
                val closingBefore = Regex("""before closing </([a-zA-Z0-9-]+)>""").find(match.value)?.groupValues?.get(1)
                val targetClosing = if (closingBefore != null && content.contains("</$closingBefore>")) {
                    "</$closingBefore>"
                } else if (content.contains("</body>")) {
                    "</body>"
                } else if (content.contains("</html>")) {
                    "</html>"
                } else null

                content = if (targetClosing != null) {
                    content.replace(targetClosing, "  </$tagName>\n$targetClosing")
                } else {
                    "$content\n</$tagName>"
                }
                actions.add("Automatically closed dangling `<$tagName>` before ${targetClosing ?: "end of file"} in `$fileToInspect`.")
            }
            NeedleToolExecutor.fsWriteFile(fileToInspect, content)
        }

        // 2. Unclosed CSS blocks
        if (output.contains("Unclosed CSS block starting with '{'")) {
            val unclosedCount = output.split("Unclosed CSS block").size - 1
            var updatedCss = targetFile.readText()
            repeat(unclosedCount.coerceAtLeast(1)) {
                updatedCss += "\n}"
            }
            NeedleToolExecutor.fsWriteFile(fileToInspect, updatedCss)
            actions.add("Automatically closed unclosed CSS block(s) in `$fileToInspect`.")
        }

        return actions
    }

    private suspend fun healMissingResourcesAndLinks(
        fileToInspect: String,
        projectRoot: File,
        onStatus: (suspend (String) -> Unit)?
    ): List<String> {
        val actions = mutableListOf<String>()
        val htmlFile = when {
            fileToInspect.endsWith(".html") || fileToInspect.endsWith(".htm") -> File(projectRoot, fileToInspect)
            File(projectRoot, "index.html").exists() -> File(projectRoot, "index.html")
            else -> null
        } ?: return actions

        if (!htmlFile.exists()) return actions
        var htmlContent = htmlFile.readText()
        var htmlModified = false

        // A. Check referenced scripts in HTML
        val scriptSrcMatches = Regex("""<script[^>]+src=["']([^"']+)["']""").findAll(htmlContent)
        for (m in scriptSrcMatches) {
            val src = m.groupValues[1].trim()
            if (!src.startsWith("http://") && !src.startsWith("https://") && !src.startsWith("//") && !src.startsWith("data:")) {
                val scriptFile = File(projectRoot, src)
                if (!scriptFile.exists()) {
                    onStatus?.invoke("<status>🩹 Synthesizing missing script '$src'...</status>")
                    scriptFile.parentFile?.mkdirs()
                    scriptFile.writeText(
                        """
                        // Auto-generated by Self-Healing Pipeline
                        console.log('[$src] Initialized.');
                        """.trimIndent() + "\n"
                    )
                    actions.add("Created missing referenced script `$src`")
                }
            }
        }

        // B. Check referenced stylesheets in HTML
        val linkHrefMatches = Regex("""<link[^>]+href=["']([^"']+)["'][^>]*>""").findAll(htmlContent)
        for (m in linkHrefMatches) {
            val tag = m.value
            if (tag.contains("stylesheet")) {
                val href = m.groupValues[1].trim()
                if (!href.startsWith("http://") && !href.startsWith("https://") && !href.startsWith("//")) {
                    val cssFile = File(projectRoot, href)
                    if (!cssFile.exists()) {
                        onStatus?.invoke("<status>🩹 Synthesizing missing stylesheet '$href'...</status>")
                        cssFile.parentFile?.mkdirs()
                        cssFile.writeText(
                            """
                            /* Auto-generated by Self-Healing Pipeline */
                            * { box-sizing: border-box; }
                            body { font-family: system-ui, -apple-system, sans-serif; margin: 0; padding: 1rem; }
                            """.trimIndent() + "\n"
                        )
                        actions.add("Created missing referenced stylesheet `$href`")
                    }
                }
            }
        }

        // C. Auto-link existing orphaned CSS if not linked
        val candidateCssFiles = listOf("style.css", "styles.css", "main.css")
        for (cssName in candidateCssFiles) {
            val file = File(projectRoot, cssName)
            if (file.exists() && !htmlContent.contains(cssName)) {
                onStatus?.invoke("<status>🩹 Needle: Auto-linking $cssName to HTML...</status>")
                htmlContent = if (htmlContent.contains("</head>")) {
                    htmlContent.replace("</head>", "  <link rel=\"stylesheet\" href=\"$cssName\">\n</head>")
                } else if (htmlContent.contains("<body")) {
                    htmlContent.replace("<body", "<link rel=\"stylesheet\" href=\"$cssName\">\n<body")
                } else {
                    "<link rel=\"stylesheet\" href=\"$cssName\">\n$htmlContent"
                }
                htmlModified = true
                actions.add("Auto-linked existing stylesheet `$cssName` into `${htmlFile.name}`")
                break
            }
        }

        // D. Auto-link existing orphaned JS if not linked
        val candidateJsFiles = listOf("script.js", "app.js", "main.js", "index.js")
        for (jsName in candidateJsFiles) {
            val file = File(projectRoot, jsName)
            if (file.exists() && !htmlContent.contains(jsName)) {
                onStatus?.invoke("<status>🩹 Needle: Auto-linking $jsName to HTML...</status>")
                htmlContent = if (htmlContent.contains("</body>")) {
                    htmlContent.replace("</body>", "  <script src=\"$jsName\" defer></script>\n</body>")
                } else if (htmlContent.contains("</html>")) {
                    htmlContent.replace("</html>", "<script src=\"$jsName\" defer></script>\n</html>")
                } else {
                    "$htmlContent\n<script src=\"$jsName\" defer></script>"
                }
                htmlModified = true
                actions.add("Auto-linked existing script `$jsName` into `${htmlFile.name}`")
                break
            }
        }

        if (htmlModified) {
            htmlFile.writeText(htmlContent)
        }

        return actions
    }

    private suspend fun healRuntimeConsoleErrors(
        projectRoot: File,
        onStatus: (suspend (String) -> Unit)?
    ): List<String> {
        val actions = mutableListOf<String>()
        val errorEntries = PreviewConsoleBridge.getEntries("ERROR")
        if (errorEntries.isEmpty()) return actions

        val htmlFile = File(projectRoot, "index.html").takeIf { it.exists() }
            ?: projectRoot.walkTopDown().firstOrNull { it.name.endsWith(".html") }

        // Find JS files
        val jsFiles = projectRoot.walkTopDown().filter { it.extension == "js" }.toList()

        for (entry in errorEntries) {
            val msg = entry.message

            // Case 1: ReferenceError (e.g. "Uncaught ReferenceError: onCalculate is not defined" or "Can't find variable: foo")
            val refMatch = Regex("""(?:ReferenceError:\s*([a-zA-Z0-9_]+)\s+is\s+not\s+defined|Can't\s+find\s+variable:\s*([a-zA-Z0-9_]+))""")
                .find(msg)
            if (refMatch != null) {
                val varName = refMatch.groupValues[1].ifEmpty { refMatch.groupValues[2] }
                if (varName.isNotBlank()) {
                    onStatus?.invoke("<status>🩹 Resolving ReferenceError for '$varName'...</status>")
                    val targetJs = jsFiles.firstOrNull { it.name == entry.sourceId }
                        ?: jsFiles.firstOrNull()
                    if (targetJs != null && targetJs.exists()) {
                        val currentJs = targetJs.readText()
                        if (!currentJs.contains("function $varName") && !currentJs.contains("window.$varName")) {
                            val stub = "\n\n// Auto-Healed Reference\nfunction $varName(...args) { console.warn('Stub: $varName invoked', args); }\nwindow.$varName = $varName;\n"
                            targetJs.appendText(stub)
                            actions.add("Synthesized missing function `$varName()` in `${targetJs.name}` to resolve ReferenceError")
                        }
                    } else if (htmlFile != null && htmlFile.exists()) {
                        val htmlContent = htmlFile.readText()
                        if (!htmlContent.contains("function $varName")) {
                            val stub = "<script>\n  function $varName(...args) { console.warn('Stub: $varName invoked', args); }\n  window.$varName = $varName;\n</script>\n"
                            val fixedHtml = if (htmlContent.contains("</body>")) {
                                htmlContent.replace("</body>", "  $stub</body>")
                            } else {
                                "$htmlContent\n$stub"
                            }
                            htmlFile.writeText(fixedHtml)
                            actions.add("Synthesized missing function `$varName()` in `${htmlFile.name}` to resolve ReferenceError")
                        }
                    }
                }
            }

            // Case 2: Null DOM element access (e.g. "Cannot read properties of null (reading 'addEventListener')" or "Cannot set properties of null")
            val isNullAccess = msg.contains("Cannot read properties of null") ||
                msg.contains("Cannot set properties of null") ||
                msg.contains("null is not an object")

            if (isNullAccess && htmlFile != null && htmlFile.exists()) {
                val htmlText = htmlFile.readText()
                val accessedIds = mutableSetOf<String>()
                val scanTargets = jsFiles + listOfNotNull(htmlFile)
                scanTargets.forEach { f ->
                    val txt = f.readText()
                    Regex("""getElementById\(['"]([a-zA-Z0-9_-]+)['"]\)""").findAll(txt).forEach {
                        accessedIds.add(it.groupValues[1])
                    }
                    Regex("""querySelector\(['"]#([a-zA-Z0-9_-]+)['"]\)""").findAll(txt).forEach {
                        accessedIds.add(it.groupValues[1])
                    }
                }

                for (id in accessedIds) {
                    if (!htmlText.contains("id=\"$id\"") && !htmlText.contains("id='$id'")) {
                        onStatus?.invoke("<status>🩹 Needle: Synthesizing missing DOM element with id='$id'...</status>")
                        val elementHtml = if (id.contains("btn") || id.contains("button")) {
                            "<button id=\"$id\">$id</button>\n"
                        } else if (id.contains("input")) {
                            "<input type=\"text\" id=\"$id\" placeholder=\"$id\" />\n"
                        } else {
                            "<div id=\"$id\"></div>\n"
                        }
                        val fixedHtml = if (htmlText.contains("</body>")) {
                            htmlText.replace("</body>", "  $elementHtml</body>")
                        } else {
                            "$htmlText\n$elementHtml"
                        }
                        htmlFile.writeText(fixedHtml)
                        actions.add("Synthesized missing DOM element `<... id=\"$id\">` in `${htmlFile.name}` to resolve null property access")
                        break
                    }
                }
            }
        }

        if (actions.isNotEmpty()) {
            PreviewConsoleBridge.clear()
        }

        return actions
    }

    /**
     * Checks whether an on-device local model is already resident in RAM (e.g. MiniCPM in llama-server).
     * If so, returns true to prevent costly model-swap lag.
     */
    suspend fun isHeavyLocalModelWarm(): Boolean {
        return LocalGgufServerManager.isServerRunning()
    }

    /**
     * Executes Provider-Aware active discovery and returns the HandoverDecision for the turn.
     */
    fun orchestrateHandover(
        settingsStore: com.sednium.kryptonide.data.storage.SettingsStore? = null,
        activeSavedModel: com.sednium.kryptonide.data.model.SavedModel? = null,
        configuredProvider: String? = null,
        configuredModel: String? = null,
        configuredApiKey: String? = null,
        configuredGeminiKey: String? = null,
        configuredBaseUrl: String? = null,
        localModelsDir: File? = null
    ): HandoverDecision {
        return NeedleProviderOrchestrator.resolveActiveProvider(
            settingsStore = settingsStore,
            activeSavedModel = activeSavedModel,
            configuredProvider = configuredProvider,
            configuredModel = configuredModel,
            configuredApiKey = configuredApiKey,
            configuredGeminiKey = configuredGeminiKey,
            configuredBaseUrl = configuredBaseUrl,
            localModelsDir = localModelsDir
        )
    }
}
