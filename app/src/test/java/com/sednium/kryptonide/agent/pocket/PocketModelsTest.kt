package com.sednium.kryptonide.agent.pocket

import com.sednium.kryptonide.data.repository.ProjectRepository
import org.junit.Assert.*
import org.junit.Test

class PocketModelsTest {

    @Test
    fun testQwen25CoderModelSpec() {
        val model = PocketModels.findCatalogSpec("qwen2.5-coder-1.5b-q4")
        assertNotNull("Qwen 2.5 Coder 1.5B should exist in catalog", model)
        assertEquals("Qwen 2.5 Coder 1.5B", model!!.name)
        assertEquals(ModelFormat.GGUF, model.format)
        assertEquals(1.54f, model.parameterCountBillion, 0.01f)
        assertEquals("Q4_K_M", model.quantization)
        assertTrue("Min RAM required should be <= 2.5GB", model.minRamBytesRequired <= 2560L * 1024 * 1024)
        assertTrue("Should be marked as SystemOne/Primary", model.isSystemOne)
        assertNotNull("Download URL must be present", model.downloadUrl)
        assertTrue("Download URL should point to HuggingFace GGUF", model.downloadUrl!!.contains("Qwen2.5-Coder-1.5B-Instruct-GGUF"))
    }

    @Test
    fun testDefaultModelIsNeedle3() {
        val defaultModel = PocketModels.getDefaultModel()
        assertEquals("needle-3-q4", defaultModel.id)
        assertEquals("Needle 3 (Mobile Agent)", defaultModel.name)
        assertTrue(defaultModel.isSystemOne)
    }

    @Test
    fun testMiniCpm5ThinkingModelSpec() {
        val model = PocketModels.findCatalogSpec("minicpm5-1b-thinking-q4")
        assertNotNull("MiniCPM5-1B Thinking should exist in catalog", model)
        assertEquals("MiniCPM5 1B (Claude Opus Thinking)", model!!.name)
        assertEquals(ModelFormat.GGUF, model.format)
        assertEquals(1.08f, model.parameterCountBillion, 0.01f)
        assertEquals("Q4_K_M", model.quantization)
        assertNotNull("Download URL must be present", model.downloadUrl)
        assertTrue("Download URL should point to HuggingFace GGUF", model.downloadUrl!!.contains("MiniCPM5-1B-Claude-Opus-Fable5-V2-Thinking"))
    }

    @Test
    fun testNeedle3SpecAndCactUrl() {
        val needle = PocketModels.findCatalogSpec("needle-3-q4")
        assertNotNull("Needle 3 should exist in catalog", needle)
        assertNotNull("Needle 3 must have downloadUrl", needle!!.downloadUrl)
        assertTrue("Needle 3 downloadUrl should point to needle3.cact", needle.downloadUrl!!.endsWith(".cact"))
        assertEquals(ModelFormat.CACT, needle.format)
        assertTrue("Needle 3 approx size should be ~34MB", needle.sizeBytesApprox in (30L * 1024 * 1024)..(40L * 1024 * 1024))
        assertTrue("Needle 3 should be marked SystemOne", needle.isSystemOne)
    }

    @Test
    fun testModelDownloadProgressSpeed() {
        val progress = ModelDownloadProgress(
            modelId = "needle-3-q4",
            status = DownloadStatus.DOWNLOADING,
            progress = 0.5f,
            downloadedBytes = 17L * 1024 * 1024,
            totalBytes = 34L * 1024 * 1024,
            speedMbPerSec = 4.2
        )
        assertEquals("needle-3-q4", progress.modelId)
        assertEquals(4.2, progress.speedMbPerSec, 0.01)
        assertEquals(0.5f, progress.progress, 0.01f)
    }

    @Test
    fun testLayaSystemOneHasDownloadUrl() {
        val laya = PocketModels.findCatalogSpec("laya-s1-q4")
        assertNotNull("Laya System 1 should exist in catalog", laya)
        assertNotNull("Laya System 1 must have downloadUrl so user can download it", laya!!.downloadUrl)
    }

    @Test
    fun testKryptonInternalDirsContainsAifAndModels() {
        val dirs = ProjectRepository.KRYPTON_INTERNAL_DIRS
        assertTrue("KRYPTON_INTERNAL_DIRS must contain 'aif'", dirs.contains("aif"))
        assertTrue("KRYPTON_INTERNAL_DIRS must contain 'models'", dirs.contains("models"))
        assertTrue("KRYPTON_INTERNAL_DIRS must contain 'model'", dirs.contains("model"))
        assertTrue("KRYPTON_INTERNAL_DIRS must contain 'checkpoints'", dirs.contains("checkpoints"))
        assertTrue("KRYPTON_INTERNAL_DIRS must contain '.checkpoints'", dirs.contains(".checkpoints"))
    }

    @Test
    fun testNeedleToolDispatcherGitHubPush() {
        val result = NeedleToolDispatcher.parseToolCall("push these to the linked GitHub repo")
        assertFalse("Should not be a refusal", result.isRefusal)
        assertNotNull("Should have a tool call", result.toolCall)
        assertEquals("github_push", result.toolCall!!.toolName)
        assertTrue("Latency should be fast (<100ms)", result.latencyMs < 100)
    }

    @Test
    fun testNeedleToolDispatcherGitHubConnect() {
        val result = NeedleToolDispatcher.parseToolCall("connect 'Sednium-Technologies/kryptonide-native' repo")
        assertFalse("Should not be a refusal", result.isRefusal)
        assertNotNull("Should have a tool call", result.toolCall)
        assertEquals("github_connect", result.toolCall!!.toolName)
        assertEquals("Sednium-Technologies/kryptonide-native", result.toolCall!!.arguments["repo_name"])
    }

    @Test
    fun testNeedleToolDispatcherConnectSingleRepoNameWithoutSlash() {
        val result = NeedleToolDispatcher.parseToolCall("Connect the KryptonideWeb repo")
        assertFalse("Should not be a refusal", result.isRefusal)
        assertNotNull("Should have a tool call", result.toolCall)
        assertEquals("github_connect", result.toolCall!!.toolName)
        assertEquals("KryptonideWeb", result.toolCall!!.arguments["repo_name"])
    }

    @Test
    fun testNeedleToolDispatcherConnectOwnerRepoWithoutWordRepo() {
        val result = NeedleToolDispatcher.parseToolCall("connect AnkushDas4/MyCoolProject")
        assertFalse("Should not be a refusal", result.isRefusal)
        assertNotNull("Should have a tool call", result.toolCall)
        assertEquals("github_connect", result.toolCall!!.toolName)
        assertEquals("AnkushDas4/MyCoolProject", result.toolCall!!.arguments["repo_name"])
    }

    @Test
    fun testNeedleToolDispatcherWebSearch() {
        val result = NeedleToolDispatcher.parseToolCall("search web for jetpack compose lazy column")
        assertFalse("Should not be a refusal", result.isRefusal)
        assertNotNull("Should have a tool call", result.toolCall)
        assertEquals("web_search", result.toolCall!!.toolName)
        assertEquals("jetpack compose lazy column", result.toolCall!!.arguments["query"])
    }

    @Test
    fun testNeedleToolDispatcherTerminalCommand() {
        val result = NeedleToolDispatcher.parseToolCall("run ./gradlew assembleDebug")
        assertFalse("Should not be a refusal", result.isRefusal)
        assertNotNull("Should have a tool call", result.toolCall)
        assertEquals("execute_terminal_command", result.toolCall!!.toolName)
        assertEquals("./gradlew assembleDebug", result.toolCall!!.arguments["command"])
    }

    @Test
    fun testNeedleToolDispatcherUnlinkRepo() {
        val result = NeedleToolDispatcher.parseToolCall("Unlink the repo")
        assertFalse("Should not be a refusal", result.isRefusal)
        assertNotNull("Should have a tool call", result.toolCall)
        assertEquals("github_disconnect", result.toolCall!!.toolName)
        assertNull("extractRepoName should return null for 'Unlink the repo'", NeedleToolDispatcher.extractRepoName("Unlink the repo"))
    }

    @Test
    fun testNeedleToolDispatcherDisconnectRepo() {
        val result = NeedleToolDispatcher.parseToolCall("Disconnect the linked repo")
        assertFalse("Should not be a refusal", result.isRefusal)
        assertNotNull("Should have a tool call", result.toolCall)
        assertEquals("github_disconnect", result.toolCall!!.toolName)
        assertNull("extractRepoName should return null for 'Disconnect the linked repo'", NeedleToolDispatcher.extractRepoName("Disconnect the linked repo"))
    }

    @Test
    fun testNeedleToolDispatcherCloneRepoToThisFolder() {
        val result = NeedleToolDispatcher.parseToolCall("clone this repo https://github.com/Sednium-Technologies/kryptonide-native to this folder")
        assertFalse("Should not be a refusal", result.isRefusal)
        assertNotNull("Should have a tool call", result.toolCall)
        assertEquals("github_clone", result.toolCall!!.toolName)
        assertEquals("https://github.com/Sednium-Technologies/kryptonide-native", result.toolCall!!.arguments["repo_url"])
        assertEquals("this folder", result.toolCall!!.arguments["target_folder"])
    }

    @Test
    fun testNeedleToolDispatcherCloneRepoIntoNamedFolder() {
        val result = NeedleToolDispatcher.parseToolCall("clone repo Sednium-Technologies/kryptonide-native into my-app")
        assertFalse("Should not be a refusal", result.isRefusal)
        assertNotNull("Should have a tool call", result.toolCall)
        assertEquals("github_clone", result.toolCall!!.toolName)
        assertEquals("Sednium-Technologies/kryptonide-native", result.toolCall!!.arguments["repo_url"])
        assertEquals("my-app", result.toolCall!!.arguments["target_folder"])
    }

    @Test
    fun testNeedleToolDispatcherDirectCloneUrlWithoutDestination() {
        val result = NeedleToolDispatcher.parseToolCall("clone https://github.com/Sednium-Technologies/kryptonide-native")
        assertFalse("Should not be a refusal", result.isRefusal)
        assertNotNull("Should have a tool call", result.toolCall)
        assertEquals("github_clone", result.toolCall!!.toolName)
        assertEquals("https://github.com/Sednium-Technologies/kryptonide-native", result.toolCall!!.arguments["repo_url"])
    }

    @Test
    fun testNeedleToolDispatcherCaseInsensitiveCloneUrl() {
        val result = NeedleToolDispatcher.parseToolCall("clone this repo https://GitHub.com/Sednium-Technologies/kryptonide-native to this folder")
        assertFalse("Should not be a refusal", result.isRefusal)
        assertNotNull("Should have a tool call", result.toolCall)
        assertEquals("github_clone", result.toolCall!!.toolName)
        assertEquals("https://GitHub.com/Sednium-Technologies/kryptonide-native", result.toolCall!!.arguments["repo_url"])
        assertEquals("this folder", result.toolCall!!.arguments["target_folder"])
    }

    @Test
    fun testNeedleToolDispatcherDetachRepo() {
        val result = NeedleToolDispatcher.parseToolCall("detach repo")
        assertFalse("Should not be a refusal", result.isRefusal)
        assertNotNull("Should have a tool call", result.toolCall)
        assertEquals("github_disconnect", result.toolCall!!.toolName)
    }

    @Test
    fun testNeedleToolDispatcherExtractModelToolCalls() {
        val modelOutput = """
            I will now push the files.
            <tool_call name="github_push">{"commit_message": "Add needle integration"}</tool_call>
            Done!
        """.trimIndent()
        val extracted = NeedleToolDispatcher.extractModelToolCalls(modelOutput)
        assertEquals(1, extracted.size)
        assertEquals("github_push", extracted[0].toolName)
        assertEquals("Add needle integration", extracted[0].arguments["commit_message"])
    }
}
