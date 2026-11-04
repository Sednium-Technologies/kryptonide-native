package com.sednium.kryptonide.agent.pocket

import com.sednium.kryptonide.agent.hardware.LocalKvCacheQuant
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LocalGgufRunnerTest {

    @Test
    fun testEstimateModelParamsForMiniCpm5() {
        // A ~650MB file should estimate as ~1.08B model
        val fakeMiniCpmFile = File("minicpm5-1b-thinking-q4_k_m.gguf") { 650L * 1024 * 1024 }
        val params = LocalGgufRunner.estimateModelParams(fakeMiniCpmFile.length())
        assertEquals(1.08f, params, 0.1f)
    }

    @Test
    fun testBuildFullPromptWithToolsAndThinking() {
        val prompt = LocalGgufRunner.formatFullPrompt(
            systemPrompt = "You are Krypton Assistant.",
            userPrompt = "Find all Kotlin files in src/"
        )

        assertTrue(prompt.contains("<|im_start|>system"))
        assertTrue(prompt.contains("<tools>"))
        assertTrue(prompt.contains("read_file"))
        assertTrue(prompt.contains("<|im_start|>user"))
        assertTrue(prompt.contains("Find all Kotlin files in src/"))
        assertTrue(prompt.contains("<|im_start|>assistant"))
        assertTrue(prompt.contains("<think>"))
    }

    // Helper subclass for testing file length without creating physical 650MB file
    private class FileWithFakeLength(name: String, private val lengthBytes: Long) : File(name) {
        override fun length(): Long = lengthBytes
    }

    companion object {
        fun File(name: String, lengthProvider: () -> Long): File {
            return FileWithFakeLength(name, lengthProvider())
        }
    }
}
