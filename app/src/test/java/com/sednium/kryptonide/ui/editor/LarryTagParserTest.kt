package com.sednium.kryptonide.ui.editor

import com.sednium.kryptonide.agent.oorty.OortyBlock
import com.sednium.kryptonide.agent.oorty.OortyTagParser
import org.junit.Assert.*
import org.junit.Test

class LarryTagParserTest {

    @Test
    fun testEmptyAndBlankStringsNeverThrow() {
        val emptyParsed = LarryTagParser.parse("")
        assertTrue(emptyParsed.isEmpty())

        val spacesParsed = LarryTagParser.parse("   \n\t  ")
        assertTrue(spacesParsed.isEmpty())

        val singleCharParsed = LarryTagParser.parse("<")
        assertEquals(1, singleCharParsed.size)
        assertTrue(singleCharParsed.first() is LarryBlock.Ans)
    }

    @Test
    fun testStreamingIncrementalChunksNeverLoopOrThrow() {
        val fullStream = """
            <think>
            Analyzing user request for an HTML button.
            I will generate index.html.
            </think>
            Here is your requested code:
            <edit file="index.html">
            <!DOCTYPE html>
            <html>
            <head><title>Test</title></head>
            <body><button>Click me</button></body>
            </html>
            </edit>
            Feel free to tap **Apply** to save the changes!
        """.trimIndent()

        // Simulate token-by-token or char-by-char streaming
        val sb = StringBuilder()
        for (ch in fullStream) {
            sb.append(ch)
            val blocks = LarryTagParser.parse(sb.toString())
            // Just verifying it executes instantaneously without exception or infinite loop
            assertNotNull(blocks)
        }
    }

    @Test
    fun testUnclosedEditTagStreamingState() {
        // 1. Just "<edit"
        val b1 = LarryTagParser.parse("<edit")
        assertEquals(1, b1.size)
        assertTrue(b1[0] is LarryBlock.Thinking)

        // 2. "<edit file=\"index.html\"" (no closing angle bracket yet)
        val b2 = LarryTagParser.parse("<edit file=\"index.html\"")
        assertEquals(1, b2.size)
        assertTrue(b2[0] is LarryBlock.Thinking)

        // 3. "<edit file=\"index.html\">" with partial content
        val b3 = LarryTagParser.parse("<edit file=\"index.html\">\n<!DOCTYPE html>\n")
        assertEquals(1, b3.size)
        assertTrue(b3[0] is LarryBlock.Edit)
        val edit = b3[0] as LarryBlock.Edit
        assertEquals("index.html", edit.file)
        assertTrue(edit.content.contains("<!DOCTYPE html>"))
    }

    @Test
    fun testUnclosedThinkingTagStreamingState() {
        val b1 = LarryTagParser.parse("<think")
        assertEquals(1, b1.size)
        assertTrue(b1[0] is LarryBlock.Thinking)

        val b2 = LarryTagParser.parse("<think>Reasoning about CSS grid")
        assertEquals(1, b2.size)
        assertTrue(b2[0] is LarryBlock.Thinking)
        assertEquals("Reasoning about CSS grid", (b2[0] as LarryBlock.Thinking).text)

        val b3 = LarryTagParser.parse("<thinking>Reasoning about flexbox")
        assertEquals(1, b3.size)
        assertTrue(b3[0] is LarryBlock.Thinking)
        assertEquals("Reasoning about flexbox", (b3[0] as LarryBlock.Thinking).text)
    }

    @Test
    fun testPrefixTextBeforeUnclosedEdit() {
        val raw = "Let's update your project:\n<edit file=\"script.js\">\nconsole.log('hi');"
        val blocks = LarryTagParser.parse(raw)
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is LarryBlock.Ans)
        assertTrue((blocks[0] as LarryBlock.Ans).text.contains("Let's update your project:"))
        assertTrue(blocks[1] is LarryBlock.Edit)
        assertEquals("script.js", (blocks[1] as LarryBlock.Edit).file)
        assertTrue((blocks[1] as LarryBlock.Edit).content.contains("console.log('hi');"))
    }

    @Test
    fun testMarkdownCodeFencesInsideEditAreCleaned() {
        val fencedEdit = """
            <edit file="style.css">
            ```css
            body { background: black; }
            ```
            </edit>
        """.trimIndent()
        val blocks = LarryTagParser.parse(fencedEdit)
        assertEquals(1, blocks.size)
        val edit = blocks[0] as LarryBlock.Edit
        assertEquals("style.css", edit.file)
        assertEquals("body { background: black; }", edit.content)
    }

    @Test
    fun testAdjacentAnsBlocksConsolidation() {
        val raw = "<ans>Hello</ans>\n<ans>world!</ans>"
        val blocks = OortyTagParser.parse(raw)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is OortyBlock.Ans)
        assertEquals("Hello\n\nworld!", (blocks[0] as OortyBlock.Ans).text)
    }

    @Test
    fun testParsePatchTagProducesEditBlock() {
        val raw = """
            I will apply a quick patch:
            <patch file="index.html">
            <<<<<<< SEARCH
            <meta charset="UTF-8">
            =======
            <meta charset="UTF-16">
            >>>>>>> REPLACE
            </patch>
        """.trimIndent()
        val blocks = LarryTagParser.parse(raw)
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is LarryBlock.Ans)
        assertTrue(blocks[1] is LarryBlock.Edit)
        val edit = blocks[1] as LarryBlock.Edit
        assertEquals("index.html", edit.file)
        assertTrue(PatchApplier.isSurgicalPatch(edit.content))
    }

    @Test
    fun testPatchApplierExactSearchReplace() {
        val existingHtml = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <title>Krypton App</title>
            </head>
            <body>
                <h1>Hello World</h1>
            </body>
            </html>
        """.trimIndent()

        val patch = """
            <<<<<<< SEARCH
                <meta charset="UTF-8">
            =======
                <meta charset="UTF-16">
            >>>>>>> REPLACE
        """.trimIndent()

        val result = PatchApplier.applyCodeOrPatch(existingHtml, patch)
        assertTrue("Result must contain UTF-16", result.contains("<meta charset=\"UTF-16\">"))
        assertFalse("Result must not contain UTF-8", result.contains("<meta charset=\"UTF-8\">"))
        assertTrue("Surrounding <!DOCTYPE html> must be preserved", result.contains("<!DOCTYPE html>"))
        assertTrue("Surrounding title must be preserved", result.contains("<title>Krypton App</title>"))
        assertTrue("Surrounding h1 must be preserved", result.contains("<h1>Hello World</h1>"))
    }

    @Test
    fun testPatchApplierTrimmedLineIndentationFallback() {
        val existingHtml = """
            <!DOCTYPE html>
            <html>
                <body>
                    <div class="card">
                        <button class="btn-old">Click Me</button>
                    </div>
                </body>
            </html>
        """.trimIndent()

        // Patch with 2 spaces instead of 12 spaces in existing file
        val patch = """
            <<<<<<< SEARCH
              <button class="btn-old">Click Me</button>
            =======
              <button class="btn-new">Click Me</button>
            >>>>>>> REPLACE
        """.trimIndent()

        val result = PatchApplier.applyCodeOrPatch(existingHtml, patch)
        assertTrue("Result must contain updated class", result.contains("btn-new"))
        assertFalse("Result must not contain old class", result.contains("btn-old"))
        assertTrue("Surrounding structure must be intact", result.contains("<div class=\"card\">"))
    }

    @Test
    fun testPatchApplierMultipleSearchReplaceBlocks() {
        val existingCode = """
            const a = 1;
            const b = 2;
            const c = 3;
        """.trimIndent()

        val patch = """
            <<<<<<< SEARCH
            const a = 1;
            =======
            const a = 100;
            >>>>>>> REPLACE
            <<<<<<< SEARCH
            const c = 3;
            =======
            const c = 300;
            >>>>>>> REPLACE
        """.trimIndent()

        val result = PatchApplier.applyCodeOrPatch(existingCode, patch)
        assertTrue(result.contains("const a = 100;"))
        assertTrue(result.contains("const b = 2;"))
        assertTrue(result.contains("const c = 300;"))
    }

    @Test
    fun testPatchApplierSmallSnippetHtmlMetaCharsetFallback() {
        val existingHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>Full App</title>
            </head>
            <body>Content</body>
            </html>
        """.trimIndent()

        val smallSnippet = "<meta charset=\"UTF-16\">"
        val result = PatchApplier.applyCodeOrPatch(existingHtml, smallSnippet)
        assertTrue("Must replace charset tag", result.contains("<meta charset=\"UTF-16\">"))
        assertFalse("Must remove old charset tag", result.contains("<meta charset=\"UTF-8\">"))
        assertTrue("Must not wipe out html skeleton", result.contains("<body>Content</body>"))
    }

    @Test
    fun testPatchApplierFullFileReplacementWhenNoPatch() {
        val existing = "old file content"
        val brandNew = "<!DOCTYPE html><html><body>New App</body></html>"
        val result = PatchApplier.applyCodeOrPatch(existing, brandNew)
        assertEquals(brandNew, result)
    }
}
