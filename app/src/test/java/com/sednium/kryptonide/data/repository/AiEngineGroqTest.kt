package com.sednium.kryptonide.data.repository

import com.sednium.kryptonide.data.model.ChatMessage
import com.sednium.kryptonide.data.model.MessageRole
import com.sednium.kryptonide.ui.editor.LarryBlock
import com.sednium.kryptonide.ui.editor.LarryTagParser
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AiEngineGroqTest {

    private val engine = AiEngine()

    @Test
    fun testGroqBuildRequestHeadersAndUrl() {
        val messages = listOf(
            ChatMessage(
                id = "m1",
                sessionId = "s1",
                projectId = "p1",
                role = MessageRole.USER,
                content = "Fix bug in main.kt"
            )
        )
        val (url, headers, body) = engine.buildRequest(
            provider = "groq",
            apiKey = "gsk_test_sample_groq_key_123",
            model = "llama-3.3-70b-versatile",
            baseUrl = "",
            systemPrompt = "You are AI assistant in agentic mode.",
            messages = messages
        )

        assertEquals("https://api.groq.com/openai/v1/chat/completions", url)
        assertEquals("Bearer gsk_test_sample_groq_key_123", headers["Authorization"])
        assertEquals("application/json", headers["content-type"])

        assertEquals("llama-3.3-70b-versatile", body["model"]?.jsonPrimitive?.content)
        assertTrue(body["stream"]?.jsonPrimitive?.boolean == true)

        val tools = body["tools"]?.jsonArray
        assertNotNull(tools)
        assertEquals(6, tools!!.size)
        val toolNames = tools.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(toolNames.containsAll(listOf("read_file", "edit_lines", "insert_lines", "patch_file", "write_new_file", "create_directory")))

        val bodyMessages = body["messages"]?.jsonArray
        assertNotNull(bodyMessages)
        assertEquals(2, bodyMessages!!.size) // 1 system + 1 user
        assertEquals("system", bodyMessages[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("user", bodyMessages[1].jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun testGroqAssistantMessageWithToolCallsHasJsonNullContentWhenEmpty() {
        val toolCallsJson = """[{"id":"call_01","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"app.kt\"}"}}]"""
        val messages = listOf(
            ChatMessage(
                id = "m1",
                sessionId = "s1",
                projectId = "p1",
                role = MessageRole.USER,
                content = "Read app.kt"
            ),
            ChatMessage(
                id = "m2",
                sessionId = "s1",
                projectId = "p1",
                role = MessageRole.ASSISTANT,
                content = "",
                toolCallsJson = toolCallsJson
            )
        )

        val openAiMessages = engine.buildOpenAiMessages("system prompt", messages)
        assertEquals(3, openAiMessages.size) // system + user + assistant

        val assistantMsg = openAiMessages[2].jsonObject
        assertEquals("assistant", assistantMsg["role"]?.jsonPrimitive?.content)
        // Critical for Groq: content MUST be JsonNull when empty and tool_calls is provided
        assertTrue("Content should be JsonNull for Groq compatibility", assistantMsg["content"] is JsonNull)
        assertNotNull(assistantMsg["tool_calls"]?.jsonArray)
        assertEquals(1, assistantMsg["tool_calls"]?.jsonArray?.size)
    }

    @Test
    fun testGroqStreamingToolCallChunkAccumulation() {
        val accumulated = StringBuilder()
        val toolAcc = mutableMapOf<Int, Triple<String, String, StringBuilder>>()

        // Chunk 1: Function name and call ID
        val chunk1 = buildJsonObject {
            put("choices", buildJsonArray {
                addJsonObject {
                    put("delta", buildJsonObject {
                        put("tool_calls", buildJsonArray {
                            addJsonObject {
                                put("index", 0)
                                put("id", "call_groq_abc")
                                put("function", buildJsonObject {
                                    put("name", "read_file")
                                    put("arguments", "{\"path\":")
                                })
                            }
                        })
                    })
                }
            })
        }

        val token1 = engine.parseChunk("groq", chunk1, accumulated, toolAcc)
        assertNull(token1)
        assertEquals(1, toolAcc.size)
        assertEquals("call_groq_abc", toolAcc[0]?.first)
        assertEquals("read_file", toolAcc[0]?.second)
        assertEquals("{\"path\":", toolAcc[0]?.third.toString())

        // Chunk 2: Stream argument continuation
        val chunk2 = buildJsonObject {
            put("choices", buildJsonArray {
                addJsonObject {
                    put("delta", buildJsonObject {
                        put("tool_calls", buildJsonArray {
                            addJsonObject {
                                put("index", 0)
                                put("function", buildJsonObject {
                                    put("arguments", " \"Main.kt\"}")
                                })
                            }
                        })
                    })
                }
            })
        }

        val token2 = engine.parseChunk("groq", chunk2, accumulated, toolAcc)
        assertNull(token2)
        assertEquals(1, toolAcc.size)
        assertEquals("call_groq_abc", toolAcc[0]?.first)
        assertEquals("read_file", toolAcc[0]?.second)
        assertEquals("{\"path\": \"Main.kt\"}", toolAcc[0]?.third.toString())
    }

    @Test
    fun testAgenticModeThinkingAndPlanTagParsing() {
        val rawResponse = """
            <thinking>
            The user wants to add a button to MainActivity.kt.
            First, read the file, then patch the onCreate method.
            </thinking>
            <plan>
            1. Read MainActivity.kt
            2. Add Button click listener
            3. Verify build
            </plan>
            <ans>
            I will now read MainActivity.kt and prepare the changes.
            </ans>
        """.trimIndent()

        val blocks = LarryTagParser.parse(rawResponse)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is LarryBlock.Thinking)
        assertTrue(blocks[1] is LarryBlock.Plan)
        assertTrue(blocks[2] is LarryBlock.Ans)

        val thinking = (blocks[0] as LarryBlock.Thinking).text
        assertTrue(thinking.contains("The user wants to add a button"))

        val plan = (blocks[1] as LarryBlock.Plan).text
        assertTrue(plan.contains("1. Read MainActivity.kt"))

        val ans = (blocks[2] as LarryBlock.Ans).text
        assertTrue(ans.contains("I will now read MainActivity.kt"))
    }

    @Test
    fun testPocketServerStreamChatFallbackToOnDevice() = kotlinx.coroutines.runBlocking {
        val messages = listOf(
            ChatMessage(
                id = "m1",
                sessionId = "s1",
                projectId = "p1",
                role = MessageRole.USER,
                content = "Hi, what model are you?"
            )
        )
        val chunks = mutableListOf<AiChunk>()
        engine.streamChat(
            provider = "pocket",
            apiKey = "",
            model = "needle-3-q4",
            baseUrl = "http://127.0.0.1:59999/v1", // unused port to trigger fallback
            systemPrompt = "You are Oorty Lite.",
            messages = messages
        ).collect { chunk ->
            chunks.add(chunk)
        }

        assertTrue("Expected streamed tokens", chunks.any { it is AiChunk.Token })
        assertTrue("Expected completion", chunks.any { it is AiChunk.Done })
        val fullText = (chunks.last { it is AiChunk.Done } as AiChunk.Done).fullText
        assertTrue("Expected guide message or on-device response", fullText.contains("No Active Model Loaded") || fullText.contains("On-Device"))
    }
}
