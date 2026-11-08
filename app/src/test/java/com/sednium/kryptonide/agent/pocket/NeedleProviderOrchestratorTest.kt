package com.sednium.kryptonide.agent.pocket

import com.sednium.kryptonide.data.model.SavedModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class NeedleProviderOrchestratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testCloudProviderWithApiKey() {
        val decision = NeedleProviderOrchestrator.resolveActiveProvider(
            configuredProvider = "groq",
            configuredModel = "llama-3.3-70b-versatile",
            configuredApiKey = "gsk_test_api_key_123",
            configuredBaseUrl = "https://api.groq.com/openai/v1"
        )

        assertEquals(CascadeTier.TIER_2_CLOUD_API, decision.targetTier)
        assertEquals("groq", decision.providerId)
        assertEquals("llama-3.3-70b-versatile", decision.modelId)
        assertEquals("gsk_test_api_key_123", decision.apiKey)
        assertFalse(decision.isLocalGguf)
        assertTrue(decision.reason.contains("Cloud provider 'groq' active"))
    }

    @Test
    fun testCloudProviderWithoutApiKeyFallbackToLocalCoder() {
        val modelsDir = tempFolder.newFolder("models")
        val fakeQwen = File(modelsDir, "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf")
        // Create 60MB mock file
        FileOutputStream(fakeQwen).use { fos ->
            val buf = ByteArray(1024 * 1024)
            for (i in 0 until 60) {
                fos.write(buf)
            }
        }

        val decision = NeedleProviderOrchestrator.resolveActiveProvider(
            configuredProvider = "openai",
            configuredModel = "gpt-4o",
            configuredApiKey = "",
            localModelsDir = modelsDir
        )

        assertEquals(CascadeTier.TIER_2_LOCAL_HEAVY_CODER, decision.targetTier)
        assertEquals("pocket", decision.providerId)
        assertTrue(decision.isLocalGguf)
        assertNotNull(decision.localModelFile)
        assertTrue(decision.reason.contains("Cloud API key unconfigured for 'openai'"))
        assertTrue(decision.reason.contains("Local Heavy Coder"))
    }

    @Test
    fun testCloudProviderWithoutApiKeyFallbackToTier1Supervisor() {
        val emptyModelsDir = tempFolder.newFolder("empty_models")

        val decision = NeedleProviderOrchestrator.resolveActiveProvider(
            configuredProvider = "nvidia",
            configuredModel = "meta/llama-3.1-405b-instruct",
            configuredApiKey = "",
            localModelsDir = emptyModelsDir
        )

        assertEquals(CascadeTier.TIER_1_ON_DEVICE_SUPERVISOR, decision.targetTier)
        assertEquals("pocket", decision.providerId)
        assertEquals(PocketModels.DEFAULT_MODEL_ID, decision.modelId)
        assertTrue(decision.reason.contains("MiniCPM 1B base brain"))
    }

    @Test
    fun testSavedModelOverride() {
        val savedModel = SavedModel(
            id = "custom-gemini",
            name = "My Gemini Flash",
            provider = "gemini",
            model = "gemini-2.0-flash",
            apiKey = "AIzaSy_fake_gemini_key",
            baseUrl = ""
        )

        val decision = NeedleProviderOrchestrator.resolveActiveProvider(
            activeSavedModel = savedModel,
            configuredProvider = "openai",
            configuredModel = "gpt-4o"
        )

        assertEquals(CascadeTier.TIER_2_CLOUD_API, decision.targetTier)
        assertEquals("gemini", decision.providerId)
        assertEquals("gemini-2.0-flash", decision.modelId)
        assertEquals("AIzaSy_fake_gemini_key", decision.apiKey)
    }

    @Test
    fun testCloudFailureSelfHealingFailover() {
        val modelsDir = tempFolder.newFolder("failover_models")
        val fakeDeepSeek = File(modelsDir, "deepseek-r1-distill-qwen-1.5b.gguf")
        FileOutputStream(fakeDeepSeek).use { fos ->
            val buf = ByteArray(1024 * 1024)
            for (i in 0 until 55) {
                fos.write(buf)
            }
        }

        val err = RuntimeException("HTTP 429: Too Many Requests / Rate limit exceeded")
        val failoverDecision = NeedleProviderOrchestrator.handleCloudFailure(
            error = err,
            configuredProvider = "groq",
            localModelsDir = modelsDir
        )

        assertEquals(CascadeTier.TIER_2_LOCAL_HEAVY_CODER, failoverDecision.targetTier)
        assertEquals("pocket", failoverDecision.providerId)
        assertTrue(failoverDecision.isLocalGguf)
        assertTrue(failoverDecision.reason.contains("Rate limit exceeded"))
    }

    @Test
    fun testBuildHandoverContext() {
        val decision = HandoverDecision(
            targetTier = CascadeTier.TIER_2_CLOUD_API,
            providerId = "openrouter",
            modelId = "anthropic/claude-3.5-sonnet",
            reason = "High reasoning task handover."
        )

        val preFlight = PreFlightResult(
            statusEmitted = "Scanning...",
            searchFindings = "Found Button in index.html",
            consoleErrors = null,
            terminalOutput = null,
            enrichedContextBlock = "<needle_workspace_findings>Buttons found</needle_workspace_findings>",
            targetFile = "index.html"
        )

        val ctx = NeedleProviderOrchestrator.buildHandoverContext(preFlight, decision)
        assertTrue(ctx.contains("<supervisor>LFM-250M + Needle-3</supervisor>"))
        assertTrue(ctx.contains("<tier>TIER_2_CLOUD_API</tier>"))
        assertTrue(ctx.contains("<handover_target>openrouter (anthropic/claude-3.5-sonnet)</handover_target>"))
        assertTrue(ctx.contains("<target_file>index.html</target_file>"))
        assertTrue(ctx.contains("<needle_workspace_findings>Buttons found</needle_workspace_findings>"))
    }

    @Test
    fun testFindBestDownloadedLocalCoderPrefersMiniCpmOverQwen() {
        val modelsDir = tempFolder.newFolder("multi_models")
        val fakeQwen = File(modelsDir, "qwen2.5-coder-1.5b.gguf")
        val fakeMiniCpm = File(modelsDir, "minicpm5-1b-claude-opus-fable5-v2-thinking-q4_k_m.gguf")

        FileOutputStream(fakeQwen).use { fos ->
            val buf = ByteArray(1024 * 1024)
            for (i in 0 until 55) fos.write(buf)
        }
        FileOutputStream(fakeMiniCpm).use { fos ->
            val buf = ByteArray(1024 * 1024)
            for (i in 0 until 55) fos.write(buf)
        }

        val best = NeedleProviderOrchestrator.findBestDownloadedLocalCoder(modelsDir)
        assertNotNull(best)
        assertTrue("MiniCPM should be preferred over Qwen in 3-tier architecture", best!!.name.contains("minicpm"))
    }
}
