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
 * When heavy local models (Qwen 1.5B) are resident in libllama_server.so,
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

        val statusMsg = "🔍 Needle: Scanning workspace & runtime..."
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
                    appendLine("<!-- Workspace search results discovered by Needle-3 -->")
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
     * STEP 3 — Post-Flight Autonomous Runtime Verification & Self-Healing.
     * Verifies file syntax, unclosed tags, and live preview console logs.
     * Automatically applies a self-healing patch if an unclosed tag or syntax issue is detected.
     */
    suspend fun runPostFlightVerification(
        targetFilePath: String?,
        projectDir: File? = null,
        onStatus: (suspend (String) -> Unit)? = null
    ): PostFlightResult {
        if (projectDir != null) {
            NeedleToolExecutor.projectDirProvider = { projectDir }
        }

        val checkStatus = "🧪 Needle: Checking runtime & syntax..."
        onStatus?.invoke("<status>$checkStatus</status>")

        val fileToInspect = targetFilePath
            ?: NeedleToolExecutor.activeFilePathProvider?.invoke()
            ?: "index.html"

        // 1. Run Syntax & Tag Diagnostics
        var diagRes = NeedleToolExecutor.codeGetDiagnostics(fileToInspect)
        var selfHealingApplied = false
        var selfHealingDetails: String? = null

        // 2. Self-Healing Pass for Unclosed HTML Tags
        if (!diagRes.success && diagRes.error != null) {
            val err = diagRes.error!!
            if (err.contains("Unclosed tag <")) {
                val tagName = Regex("""Unclosed tag <([a-zA-Z0-9-]+)>""").find(err)?.groupValues?.get(1)
                if (tagName != null) {
                    onStatus?.invoke("<status>🩹 Needle: Self-healing unclosed <$tagName> tag...</status>")
                    val readRes = NeedleToolExecutor.fsReadFile(fileToInspect)
                    if (readRes.success) {
                        val file = File(NeedleToolExecutor.getProjectRoot(), fileToInspect)
                        if (file.exists()) {
                            val content = file.readText()
                            val closingBefore = Regex("""before closing </([a-zA-Z0-9-]+)>""").find(err)?.groupValues?.get(1)
                            val targetClosing = if (closingBefore != null) "</$closingBefore>" else if (content.contains("</body>")) "</body>" else null
                            val fixedContent = if (targetClosing != null && content.contains(targetClosing)) {
                                content.replace(targetClosing, "  </$tagName>\n$targetClosing")
                            } else {
                                "$content\n</$tagName>"
                            }
                            val writeRes = NeedleToolExecutor.fsWriteFile(fileToInspect, fixedContent)
                            if (writeRes.success) {
                                selfHealingApplied = true
                                selfHealingDetails = "Automatically closed dangling `<$tagName>` before ${targetClosing ?: "end of file"} in `$fileToInspect`."
                                // Re-verify diagnostics
                                diagRes = NeedleToolExecutor.codeGetDiagnostics(fileToInspect)
                            }
                        }
                    }
                }
            }
        }

        // 3. Web Preview Verification
        val hasPreview = PreviewConsoleBridge.hasActiveWebView()
        var previewHealthy = true
        if (hasPreview) {
            NeedleToolExecutor.previewReload()
            // Allow JS runtime to evaluate fresh reload
            delay(150)
            val consoleErrors = PreviewConsoleBridge.getEntries("ERROR")
            if (consoleErrors.isNotEmpty()) {
                previewHealthy = false
            }
        }

        // 4. Generate Summary Card
        val summaryCard = buildString {
            append("\n\n> 🧪 **Runtime Verification Summary**\n")
            if (diagRes.success) {
                append("> - **Syntax & Diagnostics**: Clean (0 errors detected)\n")
            } else {
                append("> - **Syntax & Diagnostics**: Issues detected: ${diagRes.error}\n")
            }
            if (hasPreview) {
                if (previewHealthy) {
                    append("> - **Preview Runtime**: Healthy (0 JS console errors)\n")
                } else {
                    val count = PreviewConsoleBridge.getEntries("ERROR").size
                    append("> - **Preview Runtime**: Warning ($count runtime console errors detected)\n")
                }
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

    /**
     * Checks whether an on-device local model is already resident in RAM (e.g. Qwen in llama-server).
     * If so, returns true to prevent costly model-swap lag.
     */
    suspend fun isHeavyLocalModelWarm(): Boolean {
        return LocalGgufServerManager.isServerRunning()
    }
}
