package com.sednium.kryptonide.agent.pocket

import org.junit.Assert.*
import org.junit.Test

class HuggingFaceModelInspectorTest {

    @Test
    fun testNormalizeUrlReplacesBlobWithResolve() {
        val blobUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/blob/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"
        val normalized = HuggingFaceModelInspector.normalizeUrl(blobUrl)
        assertEquals(
            "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            normalized
        )

        val directResolveUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/model.gguf"
        assertEquals(directResolveUrl, HuggingFaceModelInspector.normalizeUrl(directResolveUrl))
    }

    @Test
    fun testExtractRepoId() {
        assertEquals(
            "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF",
            HuggingFaceModelInspector.extractRepoId("Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF")
        )
        assertEquals(
            "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF",
            HuggingFaceModelInspector.extractRepoId("https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF")
        )
        assertEquals(
            "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF",
            HuggingFaceModelInspector.extractRepoId("https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/tree/main")
        )
        assertNull(HuggingFaceModelInspector.extractRepoId("just-a-single-word"))
    }

    @Test
    fun testExtractQuantization() {
        assertEquals("Q4_K_M", HuggingFaceModelInspector.extractQuantization("qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"))
        assertEquals("Q8_0", HuggingFaceModelInspector.extractQuantization("llama-3.2-1b-instruct-q8_0.gguf"))
        assertEquals("F16", HuggingFaceModelInspector.extractQuantization("model-f16.gguf"))
        assertEquals("IQ3_XXS", HuggingFaceModelInspector.extractQuantization("mistral-iq3_xxs.gguf"))
        assertEquals("GGUF", HuggingFaceModelInspector.extractQuantization("custom_weights.gguf"))
    }

    @Test
    fun testCalculateRequiredRamBytes() {
        // 1000MB file size -> (1000 * 1.25 + 300) = 1550 MB
        val fileSizeBytes = 1000L * 1024 * 1024
        val expectedRamBytes = 1550L * 1024 * 1024
        assertEquals(expectedRamBytes, HuggingFaceModelInspector.calculateRequiredRamBytes(fileSizeBytes))
    }

    @Test
    fun testEvaluateOomRisk() {
        val freeRamBytes = 2000L * 1024 * 1024

        // <= 70% of 2000MB (1400MB) -> LOW
        val lowRamBytes = 1000L * 1024 * 1024
        assertEquals(OomRiskLevel.LOW, HuggingFaceModelInspector.evaluateOomRisk(lowRamBytes, freeRamBytes))

        // > 70% and <= 95% of 2000MB (1401MB - 1900MB) -> MODERATE
        val modRamBytes = 1600L * 1024 * 1024
        assertEquals(OomRiskLevel.MODERATE, HuggingFaceModelInspector.evaluateOomRisk(modRamBytes, freeRamBytes))

        // > 95% of 2000MB (> 1900MB) -> HIGH
        val highRamBytes = 2100L * 1024 * 1024
        assertEquals(OomRiskLevel.HIGH, HuggingFaceModelInspector.evaluateOomRisk(highRamBytes, freeRamBytes))
    }
}
