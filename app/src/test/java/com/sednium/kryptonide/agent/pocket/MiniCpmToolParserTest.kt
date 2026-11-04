package com.sednium.kryptonide.agent.pocket

import org.junit.Assert.*
import org.junit.Test

class MiniCpmToolParserTest {

    @Test
    fun testParseSimpleToolCall() {
        val raw = """
            <think>
            Need to read the build file to inspect dependencies.
            </think>
            <function name="read_file">
            <param name="path">build.gradle.kts</param>
            </function>
            I will now read the file.
        """.trimIndent()

        val parsed = MiniCpmToolParser.parse(raw)
        assertEquals("Need to read the build file to inspect dependencies.", parsed.thinkContent?.trim())
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("read_file", parsed.toolCalls[0].toolName)
        assertEquals("build.gradle.kts", parsed.toolCalls[0].arguments["path"])
        assertEquals("I will now read the file.", parsed.cleanText.trim())
    }

    @Test
    fun testParseCdataParameters() {
        val raw = """
            <function name="patch_file">
            <param name="path">src/Main.kt</param>
            <param name="search_string"><![CDATA[fun hello() {
                println("old")
            }]]></param>
            <param name="replace_string"><![CDATA[fun hello() {
                println("new <special> & value")
            }]]></param>
            </function>
        """.trimIndent()

        val parsed = MiniCpmToolParser.parse(raw)
        assertEquals(1, parsed.toolCalls.size)
        val call = parsed.toolCalls[0]
        assertEquals("patch_file", call.toolName)
        assertEquals("src/Main.kt", call.arguments["path"])
        assertTrue((call.arguments["search_string"] as String).contains("println(\"old\")"))
        assertTrue((call.arguments["replace_string"] as String).contains("new <special> & value"))
    }

    @Test
    fun testParseMultipleToolCalls() {
        val raw = """
            <think>
            Running multi-step check.
            </think>
            <function name="read_file">
            <param name="path">settings.gradle.kts</param>
            </function>
            <function name="read_file">
            <param name="path">gradle.properties</param>
            </function>
        """.trimIndent()

        val parsed = MiniCpmToolParser.parse(raw)
        assertEquals(2, parsed.toolCalls.size)
        assertEquals("settings.gradle.kts", parsed.toolCalls[0].arguments["path"])
        assertEquals("gradle.properties", parsed.toolCalls[1].arguments["path"])
    }

    @Test
    fun testFormatSystemPromptWithTools() {
        val systemPrompt = "You are Krypton Assistant."
        val formatted = MiniCpmToolParser.formatPromptWithTools(
            systemInstruction = systemPrompt,
            userQuery = "Check project dependencies"
        )

        assertTrue(formatted.contains("<|im_start|>system"))
        assertTrue(formatted.contains("# Tools"))
        assertTrue(formatted.contains("<tools>"))
        assertTrue(formatted.contains("read_file"))
        assertTrue(formatted.contains("edit_lines"))
        assertTrue(formatted.contains("<|im_start|>user"))
        assertTrue(formatted.contains("Check project dependencies"))
        assertTrue(formatted.contains("<|im_start|>assistant"))
        assertTrue(formatted.contains("<think>"))
    }
}
