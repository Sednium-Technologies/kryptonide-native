package com.sednium.kryptonide.ui.git

import com.sednium.kryptonide.agent.pocket.MiniCpmToolParser
import com.sednium.kryptonide.agent.tools.AgentToolRegistry
import com.sednium.kryptonide.agent.tools.ToolDefinition
import com.sednium.kryptonide.agent.tools.ToolParam
import com.sednium.kryptonide.agent.tools.ToolResult
import org.junit.Assert.*
import org.junit.Test

class GitDiffAndLogsTest {

    @Test
    fun testComputeUnifiedDiff_AddedFile() {
        val diff = GitViewModel.computeUnifiedDiff(
            relativePath = "app/src/main/NewFile.kt",
            oldContent = null,
            newContent = "package com.example\n\nclass NewFile"
        )

        assertEquals("app/src/main/NewFile.kt", diff.relativePath)
        assertEquals(ChangeStatus.ADDED, diff.status)
        assertEquals(3, diff.addedLines)
        assertEquals(0, diff.removedLines)
        assertTrue(diff.diffPatch.contains("--- /dev/null"))
        assertTrue(diff.diffPatch.contains("+++ b/app/src/main/NewFile.kt"))
        assertTrue(diff.diffPatch.contains("+class NewFile"))
    }

    @Test
    fun testComputeUnifiedDiff_DeletedFile() {
        val diff = GitViewModel.computeUnifiedDiff(
            relativePath = "app/src/main/OldFile.kt",
            oldContent = "package com.example\nclass OldFile",
            newContent = null
        )

        assertEquals("app/src/main/OldFile.kt", diff.relativePath)
        assertEquals(ChangeStatus.DELETED, diff.status)
        assertEquals(0, diff.addedLines)
        assertEquals(2, diff.removedLines)
        assertTrue(diff.diffPatch.contains("--- a/app/src/main/OldFile.kt"))
        assertTrue(diff.diffPatch.contains("+++ /dev/null"))
        assertTrue(diff.diffPatch.contains("-class OldFile"))
    }

    @Test
    fun testComputeUnifiedDiff_ModifiedFile() {
        val oldContent = """
            fun calculate(x: Int): Int {
                return x * 2
            }
        """.trimIndent()

        val newContent = """
            fun calculate(x: Int): Int {
                val factor = 3
                return x * factor
            }
        """.trimIndent()

        val diff = GitViewModel.computeUnifiedDiff(
            relativePath = "Calculator.kt",
            oldContent = oldContent,
            newContent = newContent
        )

        assertEquals("Calculator.kt", diff.relativePath)
        assertEquals(ChangeStatus.MODIFIED, diff.status)
        assertTrue(diff.addedLines >= 2)
        assertTrue(diff.removedLines >= 1)
        assertTrue(diff.diffPatch.contains("--- a/Calculator.kt"))
        assertTrue(diff.diffPatch.contains("+++ b/Calculator.kt"))
        assertTrue(diff.diffPatch.contains("+    val factor = 3"))
    }

    @Test
    fun testExtractFailureContext_CapturesGradleErrors() {
        val fullLog = buildString {
            for (i in 1..20) appendLine("Task :app:compile$i UP-TO-DATE")
            appendLine("e: file:///home/runner/work/Krypton/app/src/main/MainActivity.kt:42:15 Unresolved reference 'Foobar'")
            appendLine("FAILURE: Build failed with an exception.")
            appendLine("* What went wrong:")
            appendLine("Execution failed for task ':app:compileReleaseKotlin'.")
            for (i in 1..30) appendLine("Normal post-build step $i")
        }

        val extracted = GitViewModel.extractFailureContext(fullLog, maxLines = 50)
        assertTrue(extracted.contains("Unresolved reference 'Foobar'"))
        assertTrue(extracted.contains("FAILURE: Build failed with an exception"))
        assertTrue(extracted.contains("Execution failed for task ':app:compileReleaseKotlin'"))
    }

    @Test
    fun testMiniCpmToolParser_DynamicToolsSchema() {
        val defaultSchema = MiniCpmToolParser.getDynamicToolsJson()
        assertNotNull(defaultSchema)
        assertTrue(defaultSchema.contains("\"read_file\""))

        // Register custom tool dynamically
        val customTool = ToolDefinition(
            name = "custom_dynamic_tool",
            description = "Custom test tool",
            parameters = listOf(ToolParam("query", "string", "Test query", required = true)),
            handler = { ToolResult(true, "ok") }
        )
        AgentToolRegistry.register(customTool)
        try {
            val dynamicSchema = MiniCpmToolParser.getDynamicToolsJson()
            assertTrue(dynamicSchema.contains("custom_dynamic_tool"))
            assertEquals(AgentToolRegistry.getToolsForPrompt(), dynamicSchema)
        } finally {
            AgentToolRegistry.unregister("custom_dynamic_tool")
        }
    }
}
