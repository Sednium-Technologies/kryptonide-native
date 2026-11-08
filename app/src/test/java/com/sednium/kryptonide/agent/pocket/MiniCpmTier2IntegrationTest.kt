package com.sednium.kryptonide.agent.pocket

import com.sednium.kryptonide.data.model.ChatMessage
import com.sednium.kryptonide.data.model.MessageRole
import com.sednium.kryptonide.data.repository.AiEngine
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class MiniCpmTier2IntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testMiniCpmModelCatalogTier2Setup() {
        val minicpm = PocketModels.getMiniCpmModel()
        assertEquals("minicpm5-1b-thinking-q4", minicpm.id)
        assertTrue("Must be marked as SystemOne model for Tier 2 resident execution", minicpm.isSystemOne)
        assertEquals(ModelFormat.GGUF, minicpm.format)
        assertNotNull(minicpm.downloadUrl)
        assertTrue(minicpm.downloadUrl!!.contains("MiniCPM5-1B-Claude-Opus-Fable5-V2-Thinking"))
    }

    @Test
    fun testTier2HandoverPrefersMiniCpmOverQwen() {
        val modelsDir = tempFolder.newFolder("tier2_models")
        val qwenFile = File(modelsDir, "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf")
        val minicpmFile = File(modelsDir, "minicpm5-1b-claude-opus-fable5-v2-thinking-q4_k_m.gguf")

        FileOutputStream(qwenFile).use { fos ->
            val buf = ByteArray(1024 * 1024)
            for (i in 0 until 55) fos.write(buf)
        }
        FileOutputStream(minicpmFile).use { fos ->
            val buf = ByteArray(1024 * 1024)
            for (i in 0 until 55) fos.write(buf)
        }

        val decision = NeedleProviderOrchestrator.resolveActiveProvider(
            configuredProvider = "pocket",
            localModelsDir = modelsDir
        )

        assertEquals(CascadeTier.TIER_2_LOCAL_HEAVY_CODER, decision.targetTier)
        assertEquals("pocket", decision.providerId)
        assertTrue(decision.isLocalGguf)
        assertNotNull(decision.localModelFile)
        assertTrue("Local model chosen must be MiniCPM", decision.localModelFile!!.name.contains("minicpm"))
    }

    @Test
    fun testMiniCpmPromptFormattingUsesCallerSystemPromptWithoutHardcoding() {
        val customSystemPrompt = "You are a specialized WebAssembly compiler expert. Strictly adhere to Rust 2021 edition."
        val userQuery = "Explain wasm memory allocation."

        val formatted = MiniCpmToolParser.formatPromptWithTools(
            systemInstruction = customSystemPrompt,
            userQuery = userQuery
        )

        // Must contain caller's exact system prompt
        assertTrue(formatted.contains("<|im_start|>system\n$customSystemPrompt"))
        // Must NOT contain hardcoded fallback strings
        assertFalse(formatted.contains("You are Krypton AI, an on-device coding assistant"))

        // Must contain proper ChatML tags
        assertTrue(formatted.contains("<|im_end|>\n<|im_start|>user\n$userQuery<|im_end|>"))
        // Must prime assistant with <think> tag
        assertTrue(formatted.endsWith("<|im_start|>assistant\n<think>\n"))
    }

    @Test
    fun testMiniCpmToolParsingWithXmlAndCdata() {
        val rawModelResponse = """
            <think>
            The user wants to replace line 10 with new styles. I will invoke edit_lines.
            </think>
            <function name="edit_lines">
                <param name="path">src/main.css</param>
                <param name="start_line">10</param>
                <param name="end_line">12</param>
                <param name="new_content"><![CDATA[
                body {
                    background: #111;
                    color: #eee;
                }
                ]]></param>
            </function>
            I have scheduled the edit for src/main.css.
        """.trimIndent()

        val parsed = MiniCpmToolParser.parse(rawModelResponse)

        assertNotNull(parsed.thinkContent)
        assertTrue(parsed.thinkContent!!.contains("The user wants to replace line 10"))

        assertEquals(1, parsed.toolCalls.size)
        val call = parsed.toolCalls.first()
        assertEquals("edit_lines", call.toolName)
        assertEquals("src/main.css", call.arguments["path"])
        assertEquals("10", call.arguments["start_line"])
        assertEquals("12", call.arguments["end_line"])
        assertTrue((call.arguments["new_content"] as String).contains("background: #111;"))

        // Clean text should have think and function tags removed
        assertFalse(parsed.cleanText.contains("<think>"))
        assertFalse(parsed.cleanText.contains("<function"))
        assertTrue(parsed.cleanText.contains("I have scheduled the edit for src/main.css."))
    }

    @Test
    fun testMiniCpmToolParsingWithJsonToolCallTag() {
        val rawResponse = """
            <think>
            Reading project structure.
            </think>
            <tool_call>
            {"name": "read_file", "arguments": {"path": "index.html"}}
            </tool_call>
            Inspecting HTML.
        """.trimIndent()

        val parsed = MiniCpmToolParser.parse(rawResponse)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("read_file", parsed.toolCalls.first().toolName)
        assertEquals("index.html", parsed.toolCalls.first().arguments["path"])
        assertTrue(parsed.cleanText.contains("Inspecting HTML."))
    }

    @Test
    fun testAiEngineFormatLocalPocketPromptAlwaysUsesSystemPrompt() {
        val engine = AiEngine()
        val customSys = "Custom System Instructions: Be hyper-concise and format tables with markdown."
        val messages = listOf(
            ChatMessage(id = "1", sessionId = "s", projectId = "p", role = MessageRole.USER, content = "Hello world")
        )

        val prompt = engine.formatLocalPocketPrompt(
            systemPrompt = customSys,
            messages = messages
        )

        // Must contain user's custom system prompt
        assertTrue(prompt.contains("<|im_start|>system\n$customSys\n<|im_end|>"))
        // Must NOT contain hardcoded string
        assertFalse(prompt.contains("You are Krypton AI, an on-device coding assistant"))
        // Must contain user turn and assistant generation prompt
        assertTrue(prompt.contains("<|im_start|>user\nHello world<|im_end|>"))
        assertTrue(prompt.contains("<|im_start|>assistant\n"))
    }
}
