package com.sednium.kryptonide.agent.pocket

import com.sednium.kryptonide.data.model.SavedModel
import com.sednium.kryptonide.data.storage.SettingsStore
import com.sednium.kryptonide.utils.StorageUtils
import java.io.File
import java.util.Locale

/**
 * Execution tiers in the Provider-Aware 3-Tier Cooperative Cascade.
 */
enum class CascadeTier {
    /** Tier 1: Permanent on-device brain (LFM-250M + Needle-3) for reflex routing, search & tool dispatch */
    TIER_1_ON_DEVICE_SUPERVISOR,

    /** Tier 2: Cloud API provider (Groq, Gemini, OpenRouter, NVIDIA NIM, OpenAI, Anthropic, Ollama) */
    TIER_2_CLOUD_API,

    /** Tier 2: Downloaded on-device heavy coder GGUF (MiniCPM5 1B Thinking, DeepSeek R1, Qwen 2.5 Coder, Gemma 2) */
    TIER_2_LOCAL_HEAVY_CODER,

    /** Tier 3: Post-flight runtime verification, WebView JS console error interception & self-healing */
    TIER_3_POST_FLIGHT_HEALING
}

/**
 * Represents a resolved handover decision made by Needle-3 after inspecting SettingsStore and on-device storage.
 */
data class HandoverDecision(
    val targetTier: CascadeTier,
    val providerId: String,
    val modelId: String,
    val apiKey: String = "",
    val baseUrl: String = "",
    val reason: String,
    val isLocalGguf: Boolean = false,
    val localModelFile: File? = null,
    val isCloudFallbackAvailable: Boolean = false,
    val supervisorBrain: String = "LFM-250M + Needle-3"
)

/**
 * Provider-Aware 3-Tier Cooperative Cascade Orchestrator.
 *
 * LFM-250M (~150MB) and Needle-3 (~30MB) act as the permanent on-device brain and tool router.
 * Needle-3 directly inspects SettingsStore to see which model/provider the user has configured
 * (Cloud API key model like Groq / Gemini / OpenRouter / NVIDIA NIM vs. Downloaded Local Heavy Coder GGUF
 * like MiniCPM5 1B Thinking).
 */
object NeedleProviderOrchestrator {

    val CLOUD_PROVIDERS = setOf(
        "groq", "gemini", "openrouter", "nvidia", "openai", "anthropic", "ollama", "mistral", "deepseek"
    )

    /**
     * Resolves the active provider and makes an autonomous handover decision
     * by querying SettingsStore, saved models, and local disk assets.
     */
    fun resolveActiveProvider(
        settingsStore: SettingsStore? = null,
        activeSavedModel: SavedModel? = null,
        configuredProvider: String? = null,
        configuredModel: String? = null,
        configuredApiKey: String? = null,
        configuredGeminiKey: String? = null,
        configuredBaseUrl: String? = null,
        localModelsDir: File? = null
    ): HandoverDecision {
        // 1. Resolve Provider ID
        val provider = activeSavedModel?.provider
            ?: configuredProvider
            ?: "openai"

        // 2. Resolve Model ID
        val model = activeSavedModel?.model
            ?: configuredModel
            ?: if (provider == "pocket") (settingsStore?.getSelectedPocketModelId() ?: PocketModels.DEFAULT_MODEL_ID) else "gpt-4o"

        // 3. Resolve API Key
        val apiKey = when {
            activeSavedModel != null && activeSavedModel.apiKey.isNotBlank() -> activeSavedModel.apiKey
            configuredApiKey != null && configuredApiKey.isNotBlank() -> configuredApiKey
            provider == "gemini" -> {
                val gKey = configuredGeminiKey ?: settingsStore?.getGeminiKey() ?: ""
                gKey.ifBlank { settingsStore?.getApiKey() ?: "" }
            }
            else -> settingsStore?.getApiKey() ?: ""
        }

        // 4. Resolve Base URL
        val baseUrl = activeSavedModel?.baseUrl
            ?: configuredBaseUrl
            ?: ""

        // 5. Inspect Local Storage for Heavy Coder GGUF models
        val modelsDirectory = localModelsDir ?: runCatching { StorageUtils.getModelsDir() }.getOrNull()
        val downloadedHeavyCoder = findBestDownloadedLocalCoder(modelsDirectory)

        val isCloudProvider = CLOUD_PROVIDERS.contains(provider.lowercase(Locale.ROOT))
        val hasValidCloudKey = apiKey.isNotBlank() || provider.lowercase(Locale.ROOT) == "ollama"

        // 6. Architectural Decision Matrix
        return if (isCloudProvider && hasValidCloudKey) {
            // Handover to Tier 2: Cloud API
            HandoverDecision(
                targetTier = CascadeTier.TIER_2_CLOUD_API,
                providerId = provider,
                modelId = model,
                apiKey = apiKey,
                baseUrl = baseUrl,
                reason = "Cloud provider '$provider' active with valid key. Handing over code synthesis to $model with pre-flight context.",
                isLocalGguf = false,
                isCloudFallbackAvailable = false
            )
        } else if (provider.lowercase(Locale.ROOT) == "pocket" || !hasValidCloudKey) {
            // User requested local Pocket execution OR Cloud API key is missing
            if (downloadedHeavyCoder != null && downloadedHeavyCoder.exists()) {
                // Handover to Tier 2: Local Heavy Coder GGUF
                val coderSpec = PocketModels.PREDEFINED_CATALOG.firstOrNull {
                    downloadedHeavyCoder.name.contains(it.id, ignoreCase = true) ||
                    downloadedHeavyCoder.name.contains("minicpm", ignoreCase = true) ||
                    downloadedHeavyCoder.name.contains("qwen", ignoreCase = true)
                }
                val coderName = coderSpec?.name ?: downloadedHeavyCoder.nameWithoutExtension
                val reasonMsg = if (isCloudProvider && !hasValidCloudKey) {
                    "Cloud API key unconfigured for '$provider'; autonomously handing over to on-device Local Heavy Coder ($coderName)."
                } else {
                    "On-device local mode active. Handing over code synthesis to resident heavy coder GGUF ($coderName)."
                }

                HandoverDecision(
                    targetTier = CascadeTier.TIER_2_LOCAL_HEAVY_CODER,
                    providerId = "pocket",
                    modelId = coderSpec?.id ?: downloadedHeavyCoder.name,
                    apiKey = "",
                    baseUrl = "http://127.0.0.1:8080",
                    reason = reasonMsg,
                    isLocalGguf = true,
                    localModelFile = downloadedHeavyCoder,
                    isCloudFallbackAvailable = hasValidCloudKey
                )
            } else {
                // Tier 1: Fall back to on-device MiniCPM 1B base brain
                val reasonMsg = if (isCloudProvider && !hasValidCloudKey) {
                    "No cloud API key configured and no heavy local GGUF found. Utilizing on-device MiniCPM 1B base brain."
                } else {
                    "Direct on-device tool routing and execution via MiniCPM 1B base brain."
                }

                HandoverDecision(
                    targetTier = CascadeTier.TIER_1_ON_DEVICE_SUPERVISOR,
                    providerId = "pocket",
                    modelId = PocketModels.DEFAULT_MODEL_ID,
                    apiKey = "",
                    baseUrl = "",
                    reason = reasonMsg,
                    isLocalGguf = false,
                    isCloudFallbackAvailable = hasValidCloudKey
                )
            }
        } else {
            // General Cloud Handover
            HandoverDecision(
                targetTier = CascadeTier.TIER_2_CLOUD_API,
                providerId = provider,
                modelId = model,
                apiKey = apiKey,
                baseUrl = baseUrl,
                reason = "Active provider '$provider' selected.",
                isLocalGguf = false
            )
        }
    }

    /**
     * Finds the best available downloaded local coder GGUF on device.
     * Prefers MiniCPM5 1B Thinking, DeepSeek R1, Qwen 2.5 Coder, or Gemma 2.
     */
    fun findBestDownloadedLocalCoder(modelsDir: File?): File? {
        if (modelsDir == null || !modelsDir.exists()) return null
        val files = modelsDir.listFiles() ?: return null

        val preferenceOrder = listOf(
            "minicpm",
            "minicpm5",
            "deepseek-r1",
            "qwen2.5-coder-1.5b",
            "qwen2.5-coder-0.5b",
            "gemma-2-2b",
            "llama-3.2-1b",
            "qwen2.5-coder-7b"
        )

        for (pref in preferenceOrder) {
            val match = files.firstOrNull { it.isFile && it.extension == "gguf" && it.name.contains(pref, ignoreCase = true) }
            if (match != null && match.length() > 50L * 1024 * 1024) { // at least 50MB
                return match
            }
        }

        // Return first valid .gguf file over 100MB
        return files.firstOrNull { it.isFile && it.extension == "gguf" && it.length() > 100L * 1024 * 1024 }
    }

    /**
     * Handles transient Cloud API failures (429 Rate Limit, 500, network disconnects)
     * by seamlessly failing over to on-device Local GGUF or LFM-250M.
     */
    fun handleCloudFailure(
        error: Throwable,
        configuredProvider: String,
        localModelsDir: File? = null
    ): HandoverDecision {
        val modelsDirectory = localModelsDir ?: runCatching { StorageUtils.getModelsDir() }.getOrNull()
        val localCoder = findBestDownloadedLocalCoder(modelsDirectory)

        return if (localCoder != null && localCoder.exists()) {
            HandoverDecision(
                targetTier = CascadeTier.TIER_2_LOCAL_HEAVY_CODER,
                providerId = "pocket",
                modelId = localCoder.nameWithoutExtension,
                reason = "Cloud provider '$configuredProvider' failed (${error.message ?: "network issue"}). Autonomous self-healing handover to on-device GGUF: ${localCoder.name}.",
                isLocalGguf = true,
                localModelFile = localCoder,
                isCloudFallbackAvailable = false
            )
        } else {
            HandoverDecision(
                targetTier = CascadeTier.TIER_2_LOCAL_HEAVY_CODER,
                providerId = "pocket",
                modelId = PocketModels.MINICPM_MODEL_ID,
                reason = "Cloud provider '$configuredProvider' failed (${error.message ?: "network issue"}). Autonomous fallback to MiniCPM 1B base model.",
                isLocalGguf = true,
                isCloudFallbackAvailable = false
            )
        }
    }

    /**
     * Builds a structured handover prompt context block containing Tier 1 pre-flight findings
     * and metadata for the Tier 2 code synthesis model.
     */
    fun buildHandoverContext(
        preFlight: PreFlightResult,
        decision: HandoverDecision
    ): String {
        return buildString {
            appendLine("<!-- [Needle-3 Cooperative Cascade: ${decision.targetTier}] -->")
            appendLine("<needle_orchestration_metadata>")
            appendLine("  <supervisor>${decision.supervisorBrain}</supervisor>")
            appendLine("  <tier>${decision.targetTier}</tier>")
            appendLine("  <handover_target>${decision.providerId} (${decision.modelId})</handover_target>")
            appendLine("  <handover_reason>${decision.reason}</handover_reason>")
            if (preFlight.targetFile != null) {
                appendLine("  <target_file>${preFlight.targetFile}</target_file>")
            }
            appendLine("</needle_orchestration_metadata>")
            if (preFlight.enrichedContextBlock.isNotBlank()) {
                appendLine("\n" + preFlight.enrichedContextBlock)
            }
        }
    }
}
