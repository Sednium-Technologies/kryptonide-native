package com.sednium.kryptonide.agent.pocket

import com.sednium.kryptonide.agent.tools.NeedleToolExecutor
import com.sednium.kryptonide.ui.preview.PreviewConsoleBridge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NeedleSelfHealingPipelineTest {

    private lateinit var tempProjectDir: File

    @Before
    fun setUp() {
        tempProjectDir = File(System.getProperty("java.io.tmpdir"), "krypton_healing_test_${System.currentTimeMillis()}")
        tempProjectDir.mkdirs()
        NeedleToolExecutor.projectDirProvider = { tempProjectDir }
        NeedleToolExecutor.activeFilePathProvider = { "index.html" }
        PreviewConsoleBridge.clear()
    }

    @After
    fun tearDown() {
        tempProjectDir.deleteRecursively()
        PreviewConsoleBridge.clear()
    }

    @Test
    fun testSelfHealUnclosedHtmlTagsMultiLevel() = runBlocking {
        val htmlFile = File(tempProjectDir, "index.html").apply {
            writeText(
                """
                <!DOCTYPE html>
                <html>
                <head><title>Test</title></head>
                <body>
                  <div class="card">
                    <section>
                      <p>Dangling paragraph
                  </div>
                </body>
                </html>
                """.trimIndent()
            )
        }

        val result = NeedleAgentCoordinator.runPostFlightVerification(
            targetFilePath = "index.html",
            projectDir = tempProjectDir
        )

        assertTrue("Self healing should have been applied", result.selfHealingApplied)
        assertNotNull(result.selfHealingDetails)
        assertTrue(result.selfHealingDetails!!.contains("<p>"))

        val content = htmlFile.readText()
        assertTrue("Fixed content should contain </p>", content.contains("</p>"))
    }

    @Test
    fun testSelfHealUnclosedCssBlocks() = runBlocking {
        val cssFile = File(tempProjectDir, "style.css").apply {
            writeText(
                """
                body {
                    margin: 0;
                    padding: 0;
                
                .card {
                    color: red;
                """.trimIndent()
            )
        }

        val result = NeedleAgentCoordinator.runPostFlightVerification(
            targetFilePath = "style.css",
            projectDir = tempProjectDir
        )

        assertTrue(result.selfHealingApplied)
        assertTrue(result.selfHealingDetails!!.contains("CSS block"))

        val content = cssFile.readText()
        assertTrue(content.endsWith("}"))
    }

    @Test
    fun testSelfHealMissingReferencedScriptAndCss() = runBlocking {
        File(tempProjectDir, "index.html").apply {
            writeText(
                """
                <!DOCTYPE html>
                <html>
                <head>
                  <link rel="stylesheet" href="theme.css">
                </head>
                <body>
                  <h1>App</h1>
                  <script src="bundle.js"></script>
                </body>
                </html>
                """.trimIndent()
            )
        }

        val result = NeedleAgentCoordinator.runPostFlightVerification(
            targetFilePath = "index.html",
            projectDir = tempProjectDir
        )

        assertTrue(result.selfHealingApplied)
        assertNotNull(result.selfHealingDetails)
        assertTrue(result.selfHealingDetails!!.contains("bundle.js"))
        assertTrue(result.selfHealingDetails!!.contains("theme.css"))

        // Verify files were synthesized
        val script = File(tempProjectDir, "bundle.js")
        val css = File(tempProjectDir, "theme.css")
        assertTrue("bundle.js should exist on disk", script.exists())
        assertTrue("theme.css should exist on disk", css.exists())
    }

    @Test
    fun testSelfHealAutoLinkOrphanAssets() = runBlocking {
        // Files exist on disk
        File(tempProjectDir, "style.css").writeText("body { background: #111; }")
        File(tempProjectDir, "script.js").writeText("console.log('Orphan script');")

        // HTML does NOT reference them
        val htmlFile = File(tempProjectDir, "index.html").apply {
            writeText(
                """
                <!DOCTYPE html>
                <html>
                <head>
                  <title>No Links</title>
                </head>
                <body>
                  <h1>Content</h1>
                </body>
                </html>
                """.trimIndent()
            )
        }

        val result = NeedleAgentCoordinator.runPostFlightVerification(
            targetFilePath = "index.html",
            projectDir = tempProjectDir
        )

        assertTrue(result.selfHealingApplied)
        assertTrue(result.selfHealingDetails!!.contains("style.css"))
        assertTrue(result.selfHealingDetails!!.contains("script.js"))

        val updatedHtml = htmlFile.readText()
        assertTrue("HTML should have linked style.css", updatedHtml.contains("""href="style.css""""))
        assertTrue("HTML should have linked script.js", updatedHtml.contains("""src="script.js""""))
    }

    @Test
    fun testSelfHealJsReferenceError() = runBlocking {
        File(tempProjectDir, "index.html").apply {
            writeText("<!DOCTYPE html><html><body><button onclick=\"startMission()\">Start</button></body></html>")
        }
        val jsFile = File(tempProjectDir, "app.js").apply {
            writeText("// Main application logic\n")
        }

        // Simulate preview console ReferenceError
        PreviewConsoleBridge.record(
            level = "ERROR",
            message = "Uncaught ReferenceError: startMission is not defined",
            sourceId = "app.js",
            lineNumber = 24
        )

        val result = NeedleAgentCoordinator.runPostFlightVerification(
            targetFilePath = "index.html",
            projectDir = tempProjectDir
        )

        assertTrue(result.selfHealingApplied)
        assertTrue(result.selfHealingDetails!!.contains("startMission"))

        val updatedJs = jsFile.readText()
        assertTrue("app.js should contain synthesized startMission function", updatedJs.contains("function startMission"))
        assertTrue(updatedJs.contains("window.startMission = startMission"))
    }

    @Test
    fun testSelfHealMissingDomElementForNullAccess() = runBlocking {
        // JS expects #submit-btn to exist
        File(tempProjectDir, "app.js").apply {
            writeText(
                """
                const btn = document.getElementById('submit-btn');
                btn.addEventListener('click', () => { console.log('submitted'); });
                """.trimIndent()
            )
        }

        // HTML does not have #submit-btn
        val htmlFile = File(tempProjectDir, "index.html").apply {
            writeText(
                """
                <!DOCTYPE html>
                <html>
                <body>
                  <h1>Form</h1>
                </body>
                </html>
                """.trimIndent()
            )
        }

        // Simulate preview error
        PreviewConsoleBridge.record(
            level = "ERROR",
            message = "Uncaught TypeError: Cannot read properties of null (reading 'addEventListener')",
            sourceId = "app.js",
            lineNumber = 2
        )

        val result = NeedleAgentCoordinator.runPostFlightVerification(
            targetFilePath = "index.html",
            projectDir = tempProjectDir
        )

        assertTrue(result.selfHealingApplied)
        assertTrue(result.selfHealingDetails!!.contains("submit-btn"))

        val fixedHtml = htmlFile.readText()
        assertTrue("index.html should have synthesized button with submit-btn id", fixedHtml.contains("""id="submit-btn""""))
    }

    @Test
    fun testMultiPassAutonomousEndToEndRepair() = runBlocking {
        // Multi-defect scenario:
        // 1. Unclosed HTML tag
        // 2. Orphan style.css needing auto-link
        // 3. Simulated ReferenceError in console
        File(tempProjectDir, "style.css").writeText("body { color: blue; }")
        val htmlFile = File(tempProjectDir, "index.html").apply {
            writeText(
                """
                <!DOCTYPE html>
                <html>
                <head><title>Multi Defect</title></head>
                <body>
                  <header>Header content
                </body>
                </html>
                """.trimIndent()
            )
        }

        PreviewConsoleBridge.record(
            level = "ERROR",
            message = "ReferenceError: renderChart is not defined",
            sourceId = "index.html",
            lineNumber = 10
        )

        val result = NeedleAgentCoordinator.runPostFlightVerification(
            targetFilePath = "index.html",
            projectDir = tempProjectDir
        )

        assertTrue(result.selfHealingApplied)
        val finalHtml = htmlFile.readText()

        // 1. Header was closed
        assertTrue(finalHtml.contains("</header>"))
        // 2. style.css was linked
        assertTrue(finalHtml.contains("style.css"))
        // 3. renderChart stub was synthesized
        assertTrue(finalHtml.contains("function renderChart"))

        // Verification summary includes details
        assertTrue(result.summaryCard.contains("Runtime Verification Summary"))
        assertTrue(result.summaryCard.contains("Autonomous Self-Healing"))
    }
}
