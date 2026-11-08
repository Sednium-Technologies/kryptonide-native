package com.sednium.kryptonide.agent.pocket

enum class ModelFormat(val extension: String) {
    CACT(".cact"),
    GGUF(".gguf"),
    LITERT(".tflite"),
    ONNX(".onnx"),
    EMBEDDING(".bin")
}

enum class ModelCategory(
    val title: String,
    val subtitle: String
) {
    BASE_BRAIN(
        title = "Default Core Engine (Base Brains)",
        subtitle = "Pre-configured on-device reasoning and unified agent models"
    ),
    CODE_INTELLIGENCE(
        title = "Code Intelligence & Agents",
        subtitle = "Specialized code generation, surgical patches, and tool calling"
    ),
    GENERAL_REASONING(
        title = "General Reasoning & Chat",
        subtitle = "Fast instruction-following, summarization, and mobile logic"
    ),
    EMBEDDINGS(
        title = "Semantic Embeddings",
        subtitle = "Dense vector embeddings for project indexing and memory recall"
    )
}

data class LocalModelSpec(
    val id: String,
    val name: String,
    val description: String,
    val format: ModelFormat,
    val parameterCountBillion: Float,
    val quantization: String,
    val sizeBytesApprox: Long,
    val minRamBytesRequired: Long,
    val recommendedThreads: Int,
    val downloadUrl: String? = null,
    val isSystemOne: Boolean = false,
    val category: ModelCategory = ModelCategory.BASE_BRAIN
)

object PocketModels {
    const val MINICPM_MODEL_ID = "minicpm5-1b-thinking-q4"
    const val DEFAULT_MODEL_ID = MINICPM_MODEL_ID

    @Deprecated("Legacy Liquid model deprecated in favor of MiniCPM 1B base model")
    const val LFM_250M_MODEL_ID = "lfm-250m-q4"

    @Deprecated("Legacy Needle model deprecated in favor of MiniCPM 1B base model")
    const val NEEDLE_3_MODEL_ID = "needle-3-q4"

    val PREDEFINED_CATALOG = listOf(
        // ── 1. Default Core Engine (Base Brains) ───────────────────
        LocalModelSpec(
            id = MINICPM_MODEL_ID,
            name = "MiniCPM 1B (Base Brain)",
            description = "Unified on-device agent with <think> reasoning, Claude Opus persona distillation, code editing, and full tool calling (~650 MB).",
            format = ModelFormat.GGUF,
            parameterCountBillion = 1.08f,
            quantization = "Q4_K_M",
            sizeBytesApprox = 650L * 1024 * 1024,
            minRamBytesRequired = 1024L * 1024 * 1024,
            recommendedThreads = 4,
            downloadUrl = "https://huggingface.co/Naruto123321/MiniCPM5-1B-Claude-Opus-Fable5-V2-Thinking-Q4_K_M-GGUF/resolve/main/minicpm5-1b-claude-opus-fable5-v2-thinking-q4_k_m.gguf",
            isSystemOne = true,
            category = ModelCategory.BASE_BRAIN
        ),
        LocalModelSpec(
            id = "minicpm-2.4b-q4",
            name = "MiniCPM 2.4B (Deep Thinker)",
            description = "High-capacity on-device reasoning and language synthesis model from OpenBMB (~1.5 GB).",
            format = ModelFormat.GGUF,
            parameterCountBillion = 2.4f,
            quantization = "Q4_K_M",
            sizeBytesApprox = 1536L * 1024 * 1024,
            minRamBytesRequired = 2560L * 1024 * 1024,
            recommendedThreads = 4,
            downloadUrl = "https://huggingface.co/openbmb/MiniCPM-2B-sft-bf16-llama-format-gguf/resolve/main/minicpm-2b-sft-bf16-q4_k_m.gguf",
            isSystemOne = true,
            category = ModelCategory.BASE_BRAIN
        ),

        // ── 2. Code Intelligence & Agents ──────────────────────────
        LocalModelSpec(
            id = "qwen2.5-coder-1.5b-q4",
            name = "Qwen 2.5 Coder 1.5B",
            description = "Premier mobile coding & agent model. Exceptional syntax accuracy, tool calling, and compact footprint (~986 MB).",
            format = ModelFormat.GGUF,
            parameterCountBillion = 1.54f,
            quantization = "Q4_K_M",
            sizeBytesApprox = 986L * 1024 * 1024,
            minRamBytesRequired = 2048L * 1024 * 1024,
            recommendedThreads = 4,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            isSystemOne = true,
            category = ModelCategory.CODE_INTELLIGENCE
        ),
        LocalModelSpec(
            id = "qwen2.5-coder-0.5b-q4",
            name = "Qwen 2.5 Coder 0.5B",
            description = "Ultra-fast lightweight coding model (~395 MB). Rapid edits, completions, and instant responses on any device.",
            format = ModelFormat.GGUF,
            parameterCountBillion = 0.5f,
            quantization = "Q4_K_M",
            sizeBytesApprox = 395L * 1024 * 1024,
            minRamBytesRequired = 1024L * 1024 * 1024,
            recommendedThreads = 2,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
            isSystemOne = true,
            category = ModelCategory.CODE_INTELLIGENCE
        ),
        LocalModelSpec(
            id = "laya-s1-q4",
            name = "Laya System 1",
            description = "Sub-50ms reflex router, risk classifier, and context pruner in Q4 quantization (~360 MB).",
            format = ModelFormat.GGUF,
            parameterCountBillion = 0.5f,
            quantization = "Q4_K_M",
            sizeBytesApprox = 360L * 1024 * 1024,
            minRamBytesRequired = 1024L * 1024 * 1024,
            recommendedThreads = 2,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            isSystemOne = true,
            category = ModelCategory.CODE_INTELLIGENCE
        ),
        LocalModelSpec(
            id = "deepseek-r1-distill-qwen-1.5b-q4",
            name = "DeepSeek R1 Distill Qwen 1.5B",
            description = "Deep step-by-step reasoning, math, and algorithmic logic with internal <think> reflection (~1.1 GB).",
            format = ModelFormat.GGUF,
            parameterCountBillion = 1.78f,
            quantization = "Q4_K_M",
            sizeBytesApprox = 1100L * 1024 * 1024,
            minRamBytesRequired = 2048L * 1024 * 1024,
            recommendedThreads = 4,
            downloadUrl = "https://huggingface.co/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            isSystemOne = true,
            category = ModelCategory.CODE_INTELLIGENCE
        ),
        LocalModelSpec(
            id = "qwen2.5-coder-7b-q4",
            name = "Qwen 2.5 Coder 7B",
            description = "Full desktop-grade autonomous coding agent for 12GB+ RAM flagship devices.",
            format = ModelFormat.GGUF,
            parameterCountBillion = 7.0f,
            quantization = "Q4_K_M",
            sizeBytesApprox = 4300L * 1024 * 1024,
            minRamBytesRequired = 6144L * 1024 * 1024,
            recommendedThreads = 6,
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q4_k_m.gguf",
            isSystemOne = false,
            category = ModelCategory.CODE_INTELLIGENCE
        ),

        // ── 3. General Reasoning & Chat ────────────────────────────
        LocalModelSpec(
            id = "llama-3.2-1b-instruct-q4",
            name = "Llama 3.2 1B Instruct",
            description = "Fast general chat, summarization, and reasoning from Meta (~808 MB).",
            format = ModelFormat.GGUF,
            parameterCountBillion = 1.23f,
            quantization = "Q4_K_M",
            sizeBytesApprox = 808L * 1024 * 1024,
            minRamBytesRequired = 1536L * 1024 * 1024,
            recommendedThreads = 4,
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            category = ModelCategory.GENERAL_REASONING
        ),
        LocalModelSpec(
            id = "smollm2-1.7b-instruct-q4",
            name = "SmolLM2 1.7B Instruct",
            description = "Compact, high-performing reasoning & instruction model trained by Hugging Face (~1.05 GB).",
            format = ModelFormat.GGUF,
            parameterCountBillion = 1.71f,
            quantization = "Q4_K_M",
            sizeBytesApprox = 1050L * 1024 * 1024,
            minRamBytesRequired = 1800L * 1024 * 1024,
            recommendedThreads = 4,
            downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf",
            category = ModelCategory.GENERAL_REASONING
        ),
        LocalModelSpec(
            id = "gemma-2-2b-q4",
            name = "Gemma 2 2B Instruct",
            description = "Google instruction tuned model with deep reasoning and clean syntax generation.",
            format = ModelFormat.GGUF,
            parameterCountBillion = 2.0f,
            quantization = "Q4_K_M",
            sizeBytesApprox = 1600L * 1024 * 1024,
            minRamBytesRequired = 3072L * 1024 * 1024,
            recommendedThreads = 4,
            downloadUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            category = ModelCategory.GENERAL_REASONING
        ),

        // ── 4. Semantic Embeddings ─────────────────────────────────
        LocalModelSpec(
            id = "all-minilm-l6-v2",
            name = "All-MiniLM-L6-v2 Embeddings",
            description = "Dense vector embeddings for AIF semantic memory recall and search.",
            format = ModelFormat.EMBEDDING,
            parameterCountBillion = 0.08f,
            quantization = "FP16",
            sizeBytesApprox = 90L * 1024 * 1024,
            minRamBytesRequired = 256L * 1024 * 1024,
            recommendedThreads = 2,
            downloadUrl = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx",
            category = ModelCategory.EMBEDDINGS
        )
    )

    fun findCatalogSpec(id: String): LocalModelSpec? = PREDEFINED_CATALOG.firstOrNull { it.id == id }

    fun getDefaultModel(): LocalModelSpec = findCatalogSpec(DEFAULT_MODEL_ID) ?: PREDEFINED_CATALOG.first()

    fun getNeedleModel(): LocalModelSpec = getDefaultModel()

    fun getMiniCpmModel(): LocalModelSpec = findCatalogSpec(MINICPM_MODEL_ID) ?: PREDEFINED_CATALOG.first()

    fun getCatalogByCategory(): Map<ModelCategory, List<LocalModelSpec>> =
        PREDEFINED_CATALOG.groupBy { it.category }
}
