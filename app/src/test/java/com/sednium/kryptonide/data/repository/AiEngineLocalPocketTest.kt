package com.sednium.kryptonide.data.repository

import com.sednium.kryptonide.agent.oorty.OortyBlock
import com.sednium.kryptonide.agent.oorty.OortyTagParser
import com.sednium.kryptonide.agent.pocket.LocalGgufServerManager
import com.sednium.kryptonide.data.model.ChatMessage
import com.sednium.kryptonide.data.model.MessageRole
import com.sednium.kryptonide.ui.editor.LarryBlock
import com.sednium.kryptonide.ui.editor.LarryTagParser
import org.junit.Assert.*
import org.junit.Test

class AiEngineLocalPocketTest {

    private val engine = AiEngine()

    @Test
    fun testParseTaggedFilesAndActiveFileFromSystemPrompt() {
        val systemPrompt = """
            You are Krypton AI.
            ## Active Editor File
            <active_file path="src/main.jsx">
            1: import React from 'react';
            2: export default function Main() { return <div>Hello</div>; }
            </active_file>
            
            ## Attached & Tagged Files
            <tagged_files>
            <file path="components/Button.jsx">
            1: import React from 'react';
            2: export function Button() { return <button>Click</button>; }
            </file>
            </tagged_files>
        """.trimIndent()

        val tagged = engine.parseTaggedFiles(systemPrompt)
        assertEquals(1, tagged.size)
        assertTrue(tagged.containsKey("components/Button.jsx"))
        val buttonContent = tagged["components/Button.jsx"]!!
        assertTrue(buttonContent.contains("export function Button()"))
        assertFalse(buttonContent.contains("1: "))

        val active = engine.parseActiveFile(systemPrompt)
        assertNotNull(active)
        assertEquals("src/main.jsx", active!!.first)
        assertTrue(active.second.contains("export default function Main()"))
        assertFalse(active.second.contains("1: "))
    }

    @Test
    fun testAnalyzeAndFixCodeDetectsUnclosedJsxAndBraces() {
        val brokenJsx = """
            import React from 'react';

            function App() {
                return (
                    <div className="container">
                        <img src="avatar.png">
                        <h1>Title</h1>
                        <p>Text without close tag
                    </div>
                );
            // missing closing brace for function App
        """.trimIndent()

        val result = engine.analyzeAndFixCode("main.jsx", brokenJsx, "Fix the syntax error")
        assertTrue(result.issues.isNotEmpty())
        // Should convert <img ...> to self-closing <img ... />
        assertTrue(result.fixedCode.contains("<img src=\"avatar.png\" />"))
        // Should close <p>
        assertTrue(result.fixedCode.contains("</p>"))
        // Should close function brace }
        assertTrue(result.fixedCode.trim().endsWith("}") || result.fixedCode.contains("export default App;\n"))
        // Should add export default
        assertTrue(result.fixedCode.contains("export default App;"))
    }

    @Test
    fun testGenerateLocalPocketResponseWithoutModelReturnsCleanGuide() {
        val response = engine.generateLocalPocketResponse(
            query = "Fix the syntax error",
            modelName = "minicpm5-1b-thinking-q4.gguf",
            systemPrompt = "You are Krypton AI.",
            model = null
        )

        assertTrue(response.contains("### No Active Model Loaded"))
        assertTrue(response.contains("On-Device Local Pocket Server"))
        assertTrue(response.contains("Cloud BYOK"))
        assertTrue(response.contains("/storage/emulated/0/Kryptonide/models/"))
    }

    @Test
    fun testBuildLocalServerMessagesLimitsHugeSystemPromptAndHistoryUnder4000Chars() {
        val hugeSystemPrompt = buildString {
            append("You are Krypton AI.\n")
            append("<project_file_tree>\n")
            repeat(150) { i -> append("src/components/component_$i.kt\n") }
            append("</project_file_tree>\n")
            repeat(500) { append("This is huge instructions repeating over and over to simulate 25k chars. ") }
        }
        assertTrue("Huge system prompt must be > 20000 chars", hugeSystemPrompt.length > 20000)

        val history = mutableListOf<ChatMessage>()
        repeat(10) { turn ->
            history.add(ChatMessage(
                id = "u_$turn",
                sessionId = "s",
                projectId = "p",
                role = MessageRole.USER,
                content = "Turn $turn user question with lots of repetitive text: " + "a".repeat(800)
            ))
            history.add(ChatMessage(
                id = "a_$turn",
                sessionId = "s",
                projectId = "p",
                role = MessageRole.ASSISTANT,
                content = "<think>internal reasoning...</think>Turn $turn assistant answer with code: " + "b".repeat(800)
            ))
        }

        history.add(ChatMessage(
            id = "u_latest",
            sessionId = "s",
            projectId = "p",
            role = MessageRole.USER,
            content = "How to add a table?\n<active_file name=\"App.jsx\">\n" + "console.log('code');\n".repeat(200) + "</active_file>"
        ))

        val messagesJson = engine.buildLocalServerMessages(history, hugeSystemPrompt)
        val jsonString = messagesJson.toString()

        assertTrue("Total messages JSON length (${jsonString.length}) must be strictly < 4000 chars", jsonString.length < 4000)
        assertTrue("Must include system message", jsonString.contains("\"role\":\"system\""))
        assertTrue("Must include user message", jsonString.contains("\"role\":\"user\""))
        assertTrue("Must include assistant message", jsonString.contains("\"role\":\"assistant\""))
        assertFalse("Old internal thinking should be stripped", jsonString.contains("<think>"))
    }

    @Test
    fun testBuildLocalServerMessagesExtractsSkillsMcpAndCustomInstructions() {
        val systemPrompt = """
            You are Krypton AI.
            
            # Custom System Instructions
            Always use TypeScript and TailwindCSS.
            
            ---
            # Available Skills
            Web Dev: Write clean accessible HTML.
            
            ---
            # Connected MCP Servers (Model Context Protocol)
            - **filesystem**: local file access
        """.trimIndent()

        val history = listOf(
            ChatMessage(
                id = "1",
                sessionId = "s",
                projectId = "p",
                role = MessageRole.USER,
                content = "Explain how to style index.html\n<active_file name=\"index.html\">\n<h1>Old Calculator</h1>\n</active_file>"
            )
        )

        val messagesJson = engine.buildLocalServerMessages(history, systemPrompt)
        val jsonString = messagesJson.toString()

        assertTrue(jsonString.contains("User Instructions:"))
        assertTrue(jsonString.contains("Always use TypeScript and TailwindCSS"))
        assertTrue(jsonString.contains("Skills:"))
        assertTrue(jsonString.contains("Web Dev: Write clean accessible HTML"))
        assertTrue(jsonString.contains("MCP:"))
        assertTrue(jsonString.contains("filesystem"))
        assertTrue(jsonString.contains("Note: The current active file (index.html) is shown below for reference"))
    }

    @Test
    fun testImplementHtmlTableReplacesPlaceholderAndInjectsCss() {
        val initialHtml = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Krypton App</title>
            </head>
            <body>
                <div id="app">
                    <h1>Krypton Application</h1>
                    <p>Make a table in here</p>
                </div>
            </body>
            </html>
        """.trimIndent()

        val updated = engine.implementFeatureInCode("index.html", initialHtml, "Bro i told you to create a table")

        // 1. Must replace placeholder <p>Make a table in here</p>
        assertFalse("Placeholder text must be replaced", updated.contains("<p>Make a table in here</p>"))

        // 2. Must contain real HTML table markup
        assertTrue("Must contain <table", updated.contains("<table"))
        assertTrue("Must contain <thead>", updated.contains("<thead>"))
        assertTrue("Must contain <tbody>", updated.contains("<tbody>"))
        assertTrue("Must contain <th>", updated.contains("<th>"))
        assertTrue("Must contain <td>", updated.contains("<td>"))

        // 3. Must inject CSS styling in <head>
        assertTrue("Must inject <style>", updated.contains("<style>"))
        assertTrue("Must contain table styling", updated.contains(".data-table") || updated.contains("border-collapse"))

        // 4. Must NOT contain lazy placeholder comment
        assertFalse("Must never output placeholder comment", updated.contains("// Added feature:"))
    }

    @Test
    fun testOortyTagParserIgnoresHtmlTagsInsideEditBlocks() {
        val rawStreaming = """
            <think>Reasoning...</think>
            Here is your app:
            <edit file="index.html">
            <!DOCTYPE html>
            <html>
            <head><title>Space</title></head>
            <body>
            <div class="glass-card">
                <h1>Hello</h1>
                <p>Welcome</p>
                <button>Click</button>
            </div>
            </body>
            </html>
            </edit>
        """.trimIndent()

        val parsed = OortyTagParser.parse(rawStreaming)
        val editBlocks = parsed.filterIsInstance<OortyBlock.Edit>()
        assertEquals("There should be exactly 1 edit block", 1, editBlocks.size)
        assertEquals("index.html", editBlocks[0].file)
        assertTrue("Edit block must contain inner HTML without fragmentation", editBlocks[0].content.contains("<div class=\"glass-card\">"))
        assertTrue("Edit block must contain button tag", editBlocks[0].content.contains("<button>Click</button>"))

        // Ensure no internal HTML tags like div or button were parsed as top-level blocks
        val ansBlocks = parsed.filterIsInstance<OortyBlock.Ans>()
        assertEquals("There should only be 1 ans block", 1, ansBlocks.size)
        assertEquals("Here is your app:", ansBlocks[0].text)
    }

    @Test
    fun testStorageUtilsRootFolderStructure() {
        val expectedDirs = setOf(
            ".checkpoints",
            ".metadata",
            "aif",
            "models",
            "mcp",
            "skills",
            "chats",
            "packages",
            "plugins",
            "projects"
        )
        assertEquals(expectedDirs, com.sednium.kryptonide.utils.StorageUtils.ROOT_SYSTEM_DIRECTORIES)
    }

    @Test
    fun testFormatConversationForLlamaProducesChatML() {
        val messages = listOf(
            com.sednium.kryptonide.data.model.ChatMessage(
                id = "1", sessionId = "s", projectId = "p",
                role = com.sednium.kryptonide.data.model.MessageRole.USER,
                content = "Make a calculator"
            ),
            com.sednium.kryptonide.data.model.ChatMessage(
                id = "2", sessionId = "s", projectId = "p",
                role = com.sednium.kryptonide.data.model.MessageRole.ASSISTANT,
                content = "Here is your calculator"
            ),
            com.sednium.kryptonide.data.model.ChatMessage(
                id = "3", sessionId = "s", projectId = "p",
                role = com.sednium.kryptonide.data.model.MessageRole.USER,
                content = "You didn't do what I said, add gravity calculation too"
            )
        )

        val formatted = engine.formatConversationForLlama("You are Krypton AI.", messages)
        assertTrue(formatted.contains("<|im_start|>system\nYou are Krypton AI.<|im_end|>"))
        assertTrue(formatted.contains("<|im_start|>user\nMake a calculator<|im_end|>"))
        assertTrue(formatted.contains("<|im_start|>assistant\nHere is your calculator<|im_end|>"))
        assertTrue(formatted.contains("<|im_start|>user\nYou didn't do what I said, add gravity calculation too<|im_end|>"))
        assertTrue(formatted.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun testOortyTagParserDefaultMarkdownStreaming() {
        val rawMarkdown = """
            ### Understanding Gravity Calculation
            
            Gravity on Earth accelerates objects at approximately **9.81 m/s²**. 
            On the Moon, gravity is only **1.62 m/s²**, which is about 16.6% of Earth's.
            
            ```python
            def calculate_weight(mass_kg, gravity_ms2=9.81):
                return mass_kg * gravity_ms2
            ```
            
            Let me know if you would like me to build a simulation website for this!
        """.trimIndent()

        val parsed = OortyTagParser.parse(rawMarkdown)
        assertEquals(1, parsed.size)
        assertTrue(parsed[0] is OortyBlock.Ans)
        val text = (parsed[0] as OortyBlock.Ans).text
        assertTrue(text.contains("### Understanding Gravity Calculation"))
        assertTrue(text.contains("def calculate_weight"))
        assertTrue(text.contains("9.81 m/s²"))
    }

    @Test
    fun testOortyTagParserStripsAnsWrapperTagsTransparently() {
        val rawWithAnsTags = """
            <ans>
            Here is the explanation for the React useEffect hook:
            
            `useEffect` handles side-effects like data fetching, subscriptions, and DOM mutations.
            </ans>
        """.trimIndent()

        val parsed = OortyTagParser.parse(rawWithAnsTags)
        assertEquals(1, parsed.size)
        assertTrue(parsed[0] is OortyBlock.Ans)
        val text = (parsed[0] as OortyBlock.Ans).text
        assertFalse(text.contains("<ans>"))
        assertFalse(text.contains("</ans>"))
        assertTrue(text.contains("Here is the explanation for the React useEffect hook:"))
    }

    @Test
    fun testOortyTagParserDecouplesEditBlocksFromConversationalText() {
        val fullTurn = """
            I've created the gravity calculator website for kids with custom glassmorphism UI.
            
            <plan>
            1. Create responsive index.html with interactive planetary gravity calculator.
            2. Add CSS animations and SVG planetary badges.
            </plan>
            
            <edit file="index.html">
            <!DOCTYPE html>
            <html>
            <head><title>Space Gravity</title></head>
            <body><h1>Explore the Solar System</h1></body>
            </html>
            </edit>
            
            You can now test it by tapping **Apply** above and clicking the ▶ Run button!
        """.trimIndent()

        val parsed = OortyTagParser.parse(fullTurn)
        assertTrue(parsed.any { it is OortyBlock.Plan })
        assertTrue(parsed.any { it is OortyBlock.Edit && it.file == "index.html" })
        
        val ansBlocks = parsed.filterIsInstance<OortyBlock.Ans>()
        assertTrue("Should have at least 2 conversational Ans segments", ansBlocks.size >= 2)
        assertTrue(ansBlocks[0].text.contains("I've created the gravity calculator"))
        assertTrue(ansBlocks[1].text.contains("You can now test it by tapping"))

        // LarryTagParser mapping test
        val larryBlocks = LarryTagParser.parse(fullTurn)
        assertTrue(larryBlocks.any { it is LarryBlock.Plan })
        assertTrue(larryBlocks.any { it is LarryBlock.Edit && it.file == "index.html" })
        assertTrue(larryBlocks.any { it is LarryBlock.Ans })
    }

    @Test
    fun testOortyTagParserHandlesStreamingUnclosedEditTag() {
        val streamingChunk = """
            Creating your file:
            <edit file="MainActivity.kt">
            package com.example
            import android.os.Bundle
        """.trimIndent()

        val parsed = OortyTagParser.parse(streamingChunk)
        assertTrue(parsed.any { it is OortyBlock.Ans })
        val editBlock = parsed.filterIsInstance<OortyBlock.Edit>().firstOrNull()
        assertNotNull("Should parse streaming unclosed edit block", editBlock)
        assertEquals("MainActivity.kt", editBlock!!.file)
        assertTrue(editBlock.content.contains("package com.example"))
    }

    @Test
    fun testLocalGgufServerManagerDefaultsAndPortCheck() {
        assertEquals(8765, LocalGgufServerManager.DEFAULT_PORT)
        assertEquals("127.0.0.1", LocalGgufServerManager.SERVER_HOST)
        assertEquals("http://127.0.0.1:8765/v1", LocalGgufServerManager.getBaseUrl())
    }

    @Test
    fun testFormatLocalPocketPromptCapsMassiveContextWithinBudget() {
        // Massive 25,000 character system prompt (simulating all MCP schemas + skills docs)
        val massiveSystemPrompt = "System instructions.\n" + "A".repeat(25_000)

        // Large user turn containing huge active HTML file
        val hugeHtmlContent = "<div>\n" + "<p>Item</p>\n".repeat(800) + "</div>"
        val userContent = """
            <active_file name="index.html">
            $hugeHtmlContent
            </active_file>
            
            Add a dark mode toggle button to the top of index.html
        """.trimIndent()

        val messages = listOf(
            com.sednium.kryptonide.data.model.ChatMessage(
                id = "1",
                sessionId = "s1",
                projectId = "p1",
                role = com.sednium.kryptonide.data.model.MessageRole.USER,
                content = userContent
            )
        )

        val formattedPrompt = engine.formatLocalPocketPrompt(
            systemPrompt = massiveSystemPrompt,
            messages = messages,
            maxTotalChars = 1400
        )

        // Strict assertion: Never overflow safe budget
        assertTrue("Formatted prompt must be <= 1400 characters, was ${formattedPrompt.length}", formattedPrompt.length <= 1400)
        assertTrue(formattedPrompt.contains("<|im_start|>system"))
        assertTrue(formattedPrompt.contains("<|im_start|>user"))
        assertTrue(formattedPrompt.contains("<|im_start|>assistant"))
        assertTrue("Must preserve user request", formattedPrompt.contains("Add a dark mode toggle button"))
    }

    @Test
    fun testZeroTelemetryInLocalPocketPrompt() {
        val engine = AiEngine()
        val messages = listOf(
            com.sednium.kryptonide.data.model.ChatMessage(
                id = "1",
                sessionId = "s1",
                projectId = "p1",
                role = com.sednium.kryptonide.data.model.MessageRole.USER,
                content = "Build a todo app"
            )
        )
        val formatted = engine.formatLocalPocketPrompt(
            systemPrompt = "System instruction",
            messages = messages
        )

        assertFalse("Prompt must never contain Hardware Topology telemetry", formatted.contains("Hardware Topology", ignoreCase = true))
        assertFalse("Prompt must never contain FAST PATH telemetry", formatted.contains("FAST PATH", ignoreCase = true))
        assertFalse("Prompt must never contain Route: telemetry", formatted.contains("Route:", ignoreCase = true))
        assertFalse("Prompt must never contain Min-Int telemetry", formatted.contains("Min-Int", ignoreCase = true))
    }

    @Test
    fun testRepetitionGuardLineRepetition() {
        val repeatingText = """
            Here is the plan:
            Min-Int-1: 4 S
            Min-Int-1: 4 S
            Min-Int-1: 4 S
            Min-Int-1: 4 S
            Min-Int-1: 4 S
            Min-Int-1: 4 S
        """.trimIndent()

        val cleaned = com.sednium.kryptonide.agent.pocket.RepetitionGuard.detectAndTrimRepetition(repeatingText, maxRepeats = 3)
        val occurrences = cleaned.lines().count { it.trim() == "Min-Int-1: 4 S" }
        assertTrue("Should truncate repeating lines so occurrences < 3, but was $occurrences", occurrences < 3)
        assertTrue("Should preserve initial context", cleaned.contains("Here is the plan:"))
    }

    @Test
    fun testRepetitionGuardPhraseRepetition() {
        val repeatingPhrase = "The answer is 42. " + "Hardware Topology: 8 B / ".repeat(10)
        val cleaned = com.sednium.kryptonide.agent.pocket.RepetitionGuard.detectAndTrimRepetition(repeatingPhrase, maxRepeats = 3)
        val occurrences = Regex("Hardware Topology: 8 B /").findAll(cleaned).count()
        assertTrue("Should truncate repeating phrase to at most 2, but was $occurrences", occurrences < 3)
        assertTrue(cleaned.startsWith("The answer is 42."))
    }

    @Test
    fun testRepetitionGuardDoesNotTruncateRepeatedCodeTags() {
        val legitimateCode = "<table><tr><td>Cell</td><td>Cell</td><td>Cell</td></tr></table>\n<button>Click</button><button>Click</button><button>Click</button>"
        val cleaned = com.sednium.kryptonide.agent.pocket.RepetitionGuard.detectAndTrimRepetition(legitimateCode)
        assertEquals("Legitimate short repeated tags must not be truncated", legitimateCode, cleaned)
    }

    @Test
    fun testParseActiveFileSupportsBothNameAndPathAttributes() {
        val promptWithName = "<active_file name=\"App.tsx\">\n1: const x = 1;\n</active_file>"
        val parsedName = engine.parseActiveFile(promptWithName)
        assertNotNull(parsedName)
        assertEquals("App.tsx", parsedName!!.first)
        assertEquals("const x = 1;", parsedName.second.trim())

        val promptWithPath = "<active_file path=\"src/index.js\">\n1: console.log('hello');\n</active_file>"
        val parsedPath = engine.parseActiveFile(promptWithPath)
        assertNotNull(parsedPath)
        assertEquals("src/index.js", parsedPath!!.first)
        assertEquals("console.log('hello');", parsedPath.second.trim())
    }

    @Test
    fun testAiNotificationHelperCleanPreview() {
        val rawAiOutput = """
            <think>
            Analyzing the user request...
            We need to add a button.
            </think>
            <status>⚡ Running on-device model...</status>
            I have updated your application with a new **dark mode toggle**.
            <edit file="src/App.jsx">
            export default function App() { return <button>Toggle</button> }
            </edit>
            Let me know if you need any adjustments!
        """.trimIndent()

        val cleanPreview = com.sednium.kryptonide.agent.notifications.AiNotificationHelper.cleanPreviewText(rawAiOutput)
        assertFalse("Must strip <think> tag", cleanPreview.contains("<think", ignoreCase = true))
        assertFalse("Must strip thinking content", cleanPreview.contains("Analyzing the user request"))
        assertFalse("Must strip <status> tag", cleanPreview.contains("<status", ignoreCase = true))
        assertFalse("Must strip <edit> block", cleanPreview.contains("<edit", ignoreCase = true))
        assertFalse("Must strip edit code", cleanPreview.contains("export default function App"))
        assertTrue("Must keep natural text", cleanPreview.contains("I have updated your application with a new dark mode toggle"))
        assertTrue("Preview must be <= 120 chars", cleanPreview.length <= 120)
    }

    @Test
    fun testIsCodeBlockUnclosedDetectsUnclosedTagsAndCodeFences() {
        // Unclosed <edit>
        val unclosedEdit = "Here is the code:\n<edit file=\"Main.kt\">\nfun main() {\n"
        assertTrue(engine.isCodeBlockUnclosed(unclosedEdit))

        // Closed <edit>
        val closedEdit = "Here is the code:\n<edit file=\"Main.kt\">\nfun main() {}\n</edit>\nDone!"
        assertFalse(engine.isCodeBlockUnclosed(closedEdit))

        // Unclosed markdown code fence
        val unclosedFence = "Here is Python code:\n```python\nprint('hello')\n"
        assertTrue(engine.isCodeBlockUnclosed(unclosedFence))

        // Closed markdown code fence
        val closedFence = "Here is Python code:\n```python\nprint('hello')\n```\nAll done."
        assertFalse(engine.isCodeBlockUnclosed(closedFence))

        // Plain text with no blocks
        assertFalse(engine.isCodeBlockUnclosed("Hello world, this is a plain message!"))
    }

    @Test
    fun testBuildLocalServerMessagesReplacesActiveFileWithTargetOverwriteOnCreateRequest() {
        val messages = listOf(
            ChatMessage(
                id = "u1",
                sessionId = "s",
                projectId = "p",
                role = MessageRole.USER,
                content = "make a website for calculating gravity on different planets"
            )
        )
        val systemPrompt = """
            You are Krypton AI.
            <active_file name="index.html">
            <style>
            .data-table { background: red; }
            </style>
            <table class="data-table"><tr><td>Old Content</td></tr></table>
            </active_file>
        """.trimIndent()

        val json = engine.buildLocalServerMessages(messages, systemPrompt).toString()
        assertFalse("Prompt must not inject old .data-table style into create request", json.contains(".data-table"))
        assertTrue("Prompt must include target file overwrite hint", json.contains("Target file to overwrite: index.html"))
    }

    @Test
    fun testBuildLocalServerMessagesHtmlCreateRequestFiltersCssAndJsFromTree() {
        val messages = listOf(
            ChatMessage(
                id = "u1",
                sessionId = "s",
                projectId = "p",
                role = MessageRole.USER,
                content = "build a website for planetary weight calculation"
            )
        )
        val systemPrompt = """
            You are Krypton AI.
            <project_file_tree>
            index.html
            style.css
            script.js
            README.md
            </project_file_tree>
            <active_file name="index.html">
            <h1>Old</h1>
            </active_file>
        """.trimIndent()

        val messagesJson = engine.buildLocalServerMessages(messages, systemPrompt)
        val json = messagesJson.toString()
        val systemMsg = messagesJson.first { it is kotlinx.serialization.json.JsonObject && it["role"]?.let { r -> (r as kotlinx.serialization.json.JsonPrimitive).content } == "system" }.toString()

        // Must filter style.css and script.js from project tree in system prompt
        assertFalse("Must filter style.css from project tree", systemMsg.contains("style.css"))
        assertFalse("Must filter script.js from project tree", systemMsg.contains("script.js"))
        assertTrue("Must keep README.md", systemMsg.contains("README.md"))
        val userMsg = (messagesJson.last() as kotlinx.serialization.json.JsonObject)["content"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        assertTrue("Must append strict HTML directive", userMsg.contains("IMPORTANT FOR index.html:"))
        assertTrue("Must instruct self-contained HTML", userMsg.contains("Output ONE single self-contained HTML5 file"))
        assertTrue("Must forbid external css/js links", userMsg.contains("Do NOT use <link rel=\"stylesheet\"> or <script src=\"script.js\">"))
    }

    @Test
    fun testBuildLocalServerMessagesConversationalModeOnWhy() {
        val messages = listOf(
            ChatMessage(
                id = "u1",
                sessionId = "s",
                projectId = "p",
                role = MessageRole.USER,
                content = "Why?"
            )
        )
        val systemPrompt = """
            You are Krypton AI.
            <active_file name="index.html">
            <h1>Existing Page</h1>
            </active_file>
        """.trimIndent()

        val messagesJson = engine.buildLocalServerMessages(messages, systemPrompt)
        val systemMsg = messagesJson.first { it is kotlinx.serialization.json.JsonObject && it["role"]?.let { r -> (r as kotlinx.serialization.json.JsonPrimitive).content } == "system" }.toString()
        assertTrue("Must set conversational chat mode", systemMsg.contains("OPERATIONAL MODE: CONVERSATIONAL CHAT (ACTIVE)"))
        assertFalse("Must not set code generation mode", systemMsg.contains("OPERATIONAL MODE: CODE GENERATION & EDITING (ACTIVE)"))

        val userMsg = (messagesJson.last() as kotlinx.serialization.json.JsonObject)["content"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        assertFalse("Must not inject active_file in chat mode", userMsg.contains("<active_file"))
        assertFalse("Must not append HTML directive in chat mode", userMsg.contains("IMPORTANT FOR"))
    }

    @Test
    fun testBuildLocalServerMessagesDoesNotInjectActiveFileOnGreetingHi() {
        val messages = listOf(
            ChatMessage(
                id = "u1",
                sessionId = "s",
                projectId = "p",
                role = MessageRole.USER,
                content = "Hi"
            )
        )
        val systemPrompt = """
            You are Krypton AI.
            <active_file name="style.css">
            #style {
              box-sizing: border-box;
            }
            body {
              background: #f4f4f5;
            }
            </active_file>
        """.trimIndent()

        val messagesJson = engine.buildLocalServerMessages(messages, systemPrompt)
        val systemMsg = messagesJson.first { it is kotlinx.serialization.json.JsonObject && it["role"]?.let { r -> (r as kotlinx.serialization.json.JsonPrimitive).content } == "system" }.toString()
        assertTrue("Must set casual greeting mode", systemMsg.contains("OPERATIONAL MODE: CASUAL GREETING & CONVERSATIONAL CHAT (ACTIVE)"))
        assertTrue("Must forbid code generation on greeting", systemMsg.contains("NEVER generate code"))

        val userMsg = (messagesJson.last() as kotlinx.serialization.json.JsonObject)["content"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        assertEquals("User prompt for greeting must remain clean without active file dump", "Hi", userMsg)
        assertFalse("Must not inject active_file on greeting Hi", userMsg.contains("<active_file"))
        assertFalse("Must not inject style.css content into greeting Hi", userMsg.contains("box-sizing"))
    }

    @Test
    fun testBuildLocalServerMessagesEnforcesCleanChatMlAndStrictSystemCap() {
        val messages = listOf(
            ChatMessage(
                id = "u1",
                sessionId = "s",
                projectId = "p",
                role = MessageRole.USER,
                content = "<|im_start|>user\nBuild me a basic counter app<|im_end|>"
            )
        )
        val bloatedPrompt = """
            You are MiniCPM base brain.
            <tools>[{"name": "tool1"}, {"name": "tool2"}, {"name": "tool3"}]</tools>
            <|im_start|>system
            Additional instructions that might double-wrap
            <|im_end|>
            # Custom System Instructions
            ${"Extra instructions for coding. ".repeat(60)}
        """.trimIndent()

        val messagesJson = engine.buildLocalServerMessages(messages, bloatedPrompt)
        val systemMsg = (messagesJson.first { (it as kotlinx.serialization.json.JsonObject)["role"]?.let { r -> (r as kotlinx.serialization.json.JsonPrimitive).content } == "system" } as kotlinx.serialization.json.JsonObject)["content"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content }

        assertTrue("System prompt must be <= 1200 characters to prevent 1B model attention saturation (actual: ${systemMsg.length})", systemMsg.length <= 1200)
        assertFalse("Must not contain raw <|im_start|>", systemMsg.contains("<|im_start|>"))
        assertFalse("Must not contain raw <|im_end|>", systemMsg.contains("<|im_end|>"))
        assertFalse("Must not dump <tools> schemas to local model", systemMsg.contains("<tools>"))

        val userMsg = (messagesJson.last() as kotlinx.serialization.json.JsonObject)["content"]!!.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        assertFalse("User message must not have raw ChatML tags", userMsg.contains("<|im_start|>"))
        assertFalse("User message must not have raw ChatML tags", userMsg.contains("<|im_end|>"))
        assertTrue("User message must contain clean user prompt", userMsg.contains("Build me a basic counter app"))
    }
}
