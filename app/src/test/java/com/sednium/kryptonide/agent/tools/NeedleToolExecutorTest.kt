package com.sednium.kryptonide.agent.tools

import com.sednium.kryptonide.agent.pocket.NeedleAgentCoordinator
import com.sednium.kryptonide.agent.pocket.NeedleToolDispatcher
import com.sednium.kryptonide.ui.preview.PreviewConsoleBridge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NeedleToolExecutorTest {

    private lateinit var tempProjectDir: File

    @Before
    fun setUp() {
        tempProjectDir = Files.createTempDirectory("krypton_needle_test").toFile()
        NeedleToolExecutor.projectDirProvider = { tempProjectDir }
        NeedleToolExecutor.editorVmProvider = null
        NeedleToolExecutor.contextProvider = null
        PreviewConsoleBridge.clear()
    }

    @After
    fun tearDown() {
        tempProjectDir.deleteRecursively()
        PreviewConsoleBridge.clear()
    }

    @Test
    fun testPreviewConsoleBridgeRollingBufferAndFilter() {
        PreviewConsoleBridge.record("LOG", "App initialized", "main.js", 1)
        PreviewConsoleBridge.record("WARNING", "Slow network detected", "network.js", 25)
        PreviewConsoleBridge.record("ERROR", "Uncaught TypeError: btn is null", "app.js", 42)

        val all = PreviewConsoleBridge.getEntries()
        assertEquals(3, all.size)

        val errors = PreviewConsoleBridge.getEntries("ERROR")
        assertEquals(1, errors.size)
        assertEquals("Uncaught TypeError: btn is null", errors[0].message)
        assertEquals("app.js", errors[0].sourceId)
        assertEquals(42, errors[0].lineNumber)

        val warnings = PreviewConsoleBridge.getEntries("WARNING")
        assertEquals(1, warnings.size)

        // Test rolling buffer cap at 100 entries
        for (i in 1..110) {
            PreviewConsoleBridge.record("LOG", "Message $i")
        }
        val capped = PreviewConsoleBridge.getEntries()
        assertEquals(100, capped.size)
        assertEquals("Message 110", capped.last().message)
    }

    @Test
    fun testFsListTreeAndFsReadFile() = runBlocking {
        val srcDir = File(tempProjectDir, "src").apply { mkdirs() }
        val indexHtml = File(tempProjectDir, "index.html").apply {
            writeText("<!DOCTYPE html>\n<html>\n<head><title>Test</title></head>\n<body>\n<h1>Hello</h1>\n</body>\n</html>")
        }
        File(srcDir, "app.js").apply {
            writeText("console.log('started');\nconst x = 10;")
        }

        val treeRes = NeedleToolExecutor.fsListTree(".", 3)
        assertTrue(treeRes.success)
        assertTrue(treeRes.output.contains("index.html"))
        assertTrue(treeRes.output.contains("src/"))
        assertTrue(treeRes.output.contains("app.js"))

        // Read specific line range
        val readRes = NeedleToolExecutor.fsReadFile("index.html", 3, 5)
        assertTrue(readRes.success)
        val readLines = readRes.output.lines()
        assertEquals(3, readLines.size)
        assertTrue(readLines[0].startsWith("3: <head>"))
        assertTrue(readLines[2].startsWith("5: <h1>Hello</h1>"))
    }

    @Test
    fun testSearchGrepAcrossWorkspace() = runBlocking {
        File(tempProjectDir, "index.html").apply {
            writeText("<div>\n  <button class=\"btn btn-primary\" id=\"actionBtn\">Click Me</button>\n</div>")
        }
        File(tempProjectDir, "styles.css").apply {
            writeText(".btn { padding: 8px 16px; }\n.btn-primary { background: #6366f1; }")
        }
        File(tempProjectDir, "script.js").apply {
            writeText("const btn = document.getElementById('actionBtn');\nbtn.addEventListener('click', () => {});")
        }

        val grepRes = NeedleToolExecutor.searchGrep("actionBtn")
        assertTrue(grepRes.success)
        val output = grepRes.output
        assertTrue(output.contains("index.html:2:"))
        assertTrue(output.contains("script.js:1:"))

        val cssGrep = NeedleToolExecutor.searchGrep("btn-primary")
        assertTrue(cssGrep.success)
        assertTrue(cssGrep.output.contains("styles.css:2:"))
        assertTrue(cssGrep.output.contains("index.html:2:"))
    }

    @Test
    fun testFsPatchFileSurgicalReplacement() = runBlocking {
        val target = File(tempProjectDir, "index.html").apply {
            writeText(
                """
                <!DOCTYPE html>
                <html>
                <body>
                  <button class="btn btn-small">Old Button</button>
                </body>
                </html>
                """.trimIndent()
            )
        }

        val searchBlock = "<button class=\"btn btn-small\">Old Button</button>"
        val replaceBlock = "<button class=\"btn btn-large primary\">New Shiny Button</button>"

        val patchRes = NeedleToolExecutor.fsPatchFile("index.html", searchBlock, replaceBlock)
        assertTrue(patchRes.success)
        assertTrue(patchRes.output.contains("Successfully patched"))

        val updatedContent = target.readText()
        assertTrue(updatedContent.contains("New Shiny Button"))
        assertFalse(updatedContent.contains("Old Button"))
    }

    @Test
    fun testCodeDiagnosticsCatchesUnclosedTags() = runBlocking {
        val brokenHtml = File(tempProjectDir, "broken.html").apply {
            writeText(
                """
                <!DOCTYPE html>
                <html>
                <body>
                  <div class="card">
                    <h2>Title</h2>
                    <button>Click
                  </div>
                </body>
                </html>
                """.trimIndent()
            )
        }

        val diagRes = NeedleToolExecutor.codeGetDiagnostics("broken.html")
        assertFalse(diagRes.success)
        assertTrue(diagRes.output.contains("Unclosed tag <button>"))

        // Fix it
        brokenHtml.writeText(
            """
            <!DOCTYPE html>
            <html>
            <body>
              <div class="card">
                <h2>Title</h2>
                <button>Click</button>
              </div>
            </body>
            </html>
            """.trimIndent()
        )

        val cleanRes = NeedleToolExecutor.codeGetDiagnostics("broken.html")
        assertTrue(cleanRes.success)
        assertTrue(cleanRes.output.contains("syntax is clean"))
    }

    @Test
    fun testNeedleAgentCoordinatorPreFlightExtractionAndFindings() = runBlocking {
        File(tempProjectDir, "index.html").apply {
            writeText("<button id=\"navButton\" class=\"btn\">Navigation</button>")
        }

        PreviewConsoleBridge.record("ERROR", "Uncaught ReferenceError: initNav is not defined", "index.html", 15)

        val prompt = "Fix this button size and check the runtime console"
        val preFlight = NeedleAgentCoordinator.runPreFlight(
            userPrompt = prompt,
            projectDir = tempProjectDir,
            activeFile = "index.html"
        )

        assertNotNull(preFlight.statusEmitted)
        assertTrue(preFlight.statusEmitted!!.contains("Scanning workspace & runtime"))
        assertTrue(preFlight.enrichedContextBlock.contains("<needle_workspace_findings>"))
        assertTrue(preFlight.enrichedContextBlock.contains("Matches for 'button'"))
        assertTrue(preFlight.enrichedContextBlock.contains("Uncaught ReferenceError: initNav is not defined"))
    }

    @Test
    fun testNeedleAgentCoordinatorPostFlightSelfHealing() = runBlocking {
        val testFile = File(tempProjectDir, "index.html").apply {
            writeText(
                """
                <!DOCTYPE html>
                <html>
                <body>
                  <h1>Title</h1>
                  <button>Dangling Button
                </body>
                </html>
                """.trimIndent()
            )
        }

        val postFlight = NeedleAgentCoordinator.runPostFlightVerification(
            targetFilePath = "index.html",
            projectDir = tempProjectDir
        )

        // Self-healing should have caught the unclosed <button> and closed it before </body>!
        assertTrue(postFlight.selfHealingApplied)
        assertNotNull(postFlight.selfHealingDetails)
        assertTrue(postFlight.selfHealingDetails!!.contains("<button>"))

        // Verify the file was fixed on disk
        val fixedContent = testFile.readText()
        assertTrue(fixedContent.contains("</button>"))

        // Summary card contains verification report
        assertTrue(postFlight.summaryCard.contains("Runtime Verification Summary"))
        assertTrue(postFlight.summaryCard.contains("Autonomous Self-Healing"))
    }

    @Test
    fun testNeedleToolDispatcherExtractsModelToolCallsBothFormats() {
        // Format 1: <tool_call name="fs_patch_file">{"path": "index.html", "search": "a", "replace": "b"}</tool_call>
        val text1 = """
            I will patch the button size now:
            <tool_call name="fs_patch_file">{"path": "index.html", "search": "btn-sm", "replace": "btn-lg"}</tool_call>
        """.trimIndent()

        val calls1 = NeedleToolDispatcher.extractModelToolCalls(text1)
        assertEquals(1, calls1.size)
        assertEquals("fs_patch_file", calls1[0].toolName)
        assertEquals("index.html", calls1[0].arguments["path"])
        assertEquals("btn-sm", calls1[0].arguments["search"])
        assertEquals("btn-lg", calls1[0].arguments["replace"])

        // Format 2: <tool_call>{"name": "search_grep", "arguments": {"query": "button"}}</tool_call>
        val text2 = """
            Let me search for button occurrences:
            <tool_call>{"name": "search_grep", "arguments": {"query": "button"}}</tool_call>
        """.trimIndent()

        val calls2 = NeedleToolDispatcher.extractModelToolCalls(text2)
        assertEquals(1, calls2.size)
        assertEquals("search_grep", calls2[0].toolName)
        assertEquals("button", calls2[0].arguments["query"])
    }

    @Test
    fun testFsCreateFileAndFsDeletePath() {
        val createRes = NeedleToolExecutor.fsCreateFile("src/utils/calc.js", "export function add(a, b) { return a + b; }")
        assertTrue(createRes.success)

        val createdFile = File(tempProjectDir, "src/utils/calc.js")
        assertTrue(createdFile.exists())
        assertEquals("export function add(a, b) { return a + b; }", createdFile.readText())

        val deleteRes = NeedleToolExecutor.fsDeletePath("src/utils/calc.js")
        assertTrue(deleteRes.success)
        assertFalse(createdFile.exists())
    }

    @Test
    fun testSearchFindFiles() {
        File(tempProjectDir, "index.html").writeText("<html></html>")
        File(tempProjectDir, "styles.css").writeText("body {}")
        File(tempProjectDir, "theme.css").writeText(":root {}")
        File(tempProjectDir, "main.js").writeText("console.log(1);")

        val cssFiles = NeedleToolExecutor.searchFindFiles("*.css")
        assertTrue(cssFiles.success)
        assertTrue(cssFiles.output.contains("styles.css"))
        assertTrue(cssFiles.output.contains("theme.css"))
        assertFalse(cssFiles.output.contains("main.js"))
    }

    @Test
    fun testPreviewGetConsoleLogsAndReload() {
        PreviewConsoleBridge.record("ERROR", "Uncaught SyntaxError: Unexpected token", "app.js", 12)
        PreviewConsoleBridge.record("LOG", "App ready", "app.js", 1)

        val errRes = NeedleToolExecutor.previewGetConsoleLogs("ERROR")
        assertTrue(errRes.success)
        assertTrue(errRes.output.contains("[ERROR] Uncaught SyntaxError: Unexpected token (app.js:12)"))
        assertFalse(errRes.output.contains("App ready"))

        val reloadRes = NeedleToolExecutor.previewReload()
        assertTrue(reloadRes.success)
        assertTrue(reloadRes.output.contains("reload"))
    }

    @Test
    fun testTerminalLogsAndReadLastOutput() {
        NeedleToolExecutor.recordTerminalLog("build step 1")
        NeedleToolExecutor.recordTerminalLog("build step 2")
        NeedleToolExecutor.recordTerminalLog("build step 3 - done")

        val last2 = NeedleToolExecutor.terminalReadLastOutput(2)
        assertTrue(last2.success)
        val lines = last2.output.lines()
        assertEquals(2, lines.size)
        assertEquals("build step 2", lines[0])
        assertEquals("build step 3 - done", lines[1])
    }

    @Test
    fun testEditorGetActiveContext() {
        NeedleToolExecutor.activeFilePathProvider = { "src/main.ts" }
        val ctxRes = NeedleToolExecutor.editorGetActiveContext()
        assertTrue(ctxRes.success)
        assertTrue(ctxRes.output.contains("Active File: src/main.ts"))
    }

    @Test
    fun testExecuteGeneralDispatcher() = runBlocking {
        val writeRes = NeedleToolExecutor.execute(
            "fs_write_file",
            mapOf("path" to "notes.txt", "content" to "Line 1\nLine 2\nLine 3")
        )
        assertTrue(writeRes.success)

        val readRes = NeedleToolExecutor.execute(
            "fs_read_file",
            mapOf("path" to "notes.txt", "start_line" to 2, "end_line" to 3)
        )
        assertTrue(readRes.success)
        val lines = readRes.output.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("2: Line 2"))
        assertTrue(lines[1].startsWith("3: Line 3"))
    }

    @Test
    fun testCssAndJsDiagnosticsErrors() {
        val brokenCss = File(tempProjectDir, "bad.css").apply {
            writeText(".header { color: red; \n.footer { color: blue; }")
        }
        val cssRes = NeedleToolExecutor.codeGetDiagnostics("bad.css")
        assertFalse(cssRes.success)
        assertTrue(cssRes.output.contains("Unclosed CSS block"))

        val brokenJs = File(tempProjectDir, "bad.js").apply {
            writeText("function test() { if (true) { console.log(1); }")
        }
        val jsRes = NeedleToolExecutor.codeGetDiagnostics("bad.js")
        assertFalse(jsRes.success)
        assertTrue(jsRes.output.contains("Unclosed curly brace"))
    }
}
