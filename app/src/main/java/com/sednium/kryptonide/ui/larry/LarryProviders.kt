package com.sednium.kryptonide.ui.larry

/**
 * A provider's connection details. `models` is a *fallback* list only — used
 * when live fetching from `modelsEndpoint` fails or isn't supported. Model
 * lineups shift too often (multiple times a month for the fast-moving
 * providers) to hand-maintain a "latest models" list here; the real UI lets
 * people fetch live, search, or just type a model ID manually — see the
 * Add Model dialog in SettingsScreen.kt.
 *
 * `keyPrefix` is used only for a soft client-side sanity check before ever
 * making a network call (catches "pasted the wrong key" typos early) — it is
 * NOT real validation. Real validation is the live "Test connection" call
 * in SettingsScreen.kt.
 */
data class ProviderConfig(
    val id: String,
    val name: String,
    val placeholder: String,
    val defaultModel: String = "",
    val baseUrl: String,
    val models: List<String>,
    /** Relative path off baseUrl for GET-ing the live model list, or null if
     *  this provider doesn't expose one (falls back to `models` + manual entry). */
    val modelsEndpoint: String? = "/models",
    /** Soft prefix check only — see class doc. Null means "don't check." */
    val keyPrefix: String? = null,
    val docsUrl: String? = null,
    /** true for providers whose auth header is "Authorization: Bearer <key>"
     *  in the standard OpenAI-compatible way; false for providers with a
     *  different scheme (e.g. Anthropic's x-api-key, Gemini's query param). */
    val usesBearerAuth: Boolean = true,
)

val LARRY_PROVIDERS = listOf(
    ProviderConfig(
        id = "pocket", name = "Pocket Server (Local)", placeholder = "Localhost inference (no key needed)",
        defaultModel = "minicpm5-1b-thinking-q4", baseUrl = "http://127.0.0.1:8765/v1",
        models = listOf("minicpm5-1b-thinking-q4", "qwen2.5-coder-1.5b-q4", "qwen2.5-coder-0.5b-q4", "llama-3.2-1b-instruct-q4"),
        modelsEndpoint = "/models",
        docsUrl = "https://github.com/Sednium-Technologies/OORTY",
        usesBearerAuth = false
    ),
    ProviderConfig(
        id = "gemini", name = "Google Gemini", placeholder = "AIzaSy...",
        defaultModel = "", baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        models = listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite", "gemini-1.5-pro", "gemini-1.5-flash"),
        modelsEndpoint = "/models", keyPrefix = "AIza",
        docsUrl = "https://ai.google.dev/api", usesBearerAuth = false, // key goes in ?key= query param
    ),
    ProviderConfig(
        id = "openai", name = "OpenAI", placeholder = "sk-...",
        defaultModel = "", baseUrl = "https://api.openai.com/v1",
        models = listOf("gpt-4o", "gpt-4o-mini", "o1", "o1-mini", "o3-mini", "gpt-4-turbo", "gpt-3.5-turbo"),
        modelsEndpoint = "/models", keyPrefix = "sk-",
        docsUrl = "https://platform.openai.com/docs/api-reference",
    ),
    ProviderConfig(
        id = "anthropic", name = "Anthropic Claude", placeholder = "sk-ant-...",
        defaultModel = "", baseUrl = "https://api.anthropic.com/v1",
        models = listOf("claude-3-7-sonnet-latest", "claude-3-5-sonnet-latest", "claude-3-5-haiku-latest", "claude-3-opus-latest"),
        modelsEndpoint = "/models", keyPrefix = "sk-ant-",
        docsUrl = "https://docs.claude.com/en/api", usesBearerAuth = false, // x-api-key header, not Bearer
    ),
    ProviderConfig(
        id = "deepseek", name = "DeepSeek", placeholder = "sk-...",
        defaultModel = "", baseUrl = "https://api.deepseek.com",
        models = listOf("deepseek-chat", "deepseek-reasoner"),
        modelsEndpoint = "/models", keyPrefix = "sk-",
        docsUrl = "https://api-docs.deepseek.com",
    ),
    ProviderConfig(
        id = "moonshot", name = "Moonshot (Kimi)", placeholder = "sk-...",
        defaultModel = "", baseUrl = "https://api.moonshot.ai/v1",
        models = listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k", "kimi-latest"),
        modelsEndpoint = "/models", keyPrefix = "sk-",
        docsUrl = "https://platform.moonshot.ai/docs",
    ),
    ProviderConfig(
        id = "groq", name = "Groq", placeholder = "gsk_...",
        defaultModel = "", baseUrl = "https://api.groq.com/openai/v1",
        models = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "openai/gpt-oss-120b", "openai/gpt-oss-20b", "mixtral-8x7b-32768", "gemma2-9b-it", "qwen/qwen-2.5-coder-32b"),
        modelsEndpoint = "/models", keyPrefix = "gsk_",
        docsUrl = "https://console.groq.com/docs",
    ),
    ProviderConfig(
        id = "bazaarlink", name = "Bazaarlink", placeholder = "sk-bl-...",
        defaultModel = "", baseUrl = "https://api.bazaarlink.ai/v1",
        models = listOf("qwen3.8-max", "deepseek-r1", "deepseek-v3"),
        modelsEndpoint = "/models", keyPrefix = "sk-bl-",
        docsUrl = "https://bazaarlink.ai",
        usesBearerAuth = true,
    ),
    ProviderConfig(
        id = "huggingface", name = "Hugging Face", placeholder = "hf_...",
        defaultModel = "", baseUrl = "https://router.huggingface.co/v1",
        models = listOf("deepseek-ai/DeepSeek-R1", "deepseek-ai/DeepSeek-V3", "meta-llama/Llama-3.3-70B-Instruct", "meta-llama/Llama-3.1-8B-Instruct", "Qwen/Qwen2.5-Coder-32B-Instruct"),
        modelsEndpoint = "/models", keyPrefix = "hf_",
        docsUrl = "https://huggingface.co",
        usesBearerAuth = true,
    ),
    ProviderConfig(
        id = "mistral", name = "Mistral AI", placeholder = "...",
        defaultModel = "", baseUrl = "https://api.mistral.ai/v1",
        models = listOf("codestral-latest", "mistral-large-latest", "mistral-small-latest", "pixtral-large-latest", "open-mistral-nemo"),
        modelsEndpoint = "/models",
        docsUrl = "https://docs.mistral.ai/api",
    ),
    ProviderConfig(
        id = "xai", name = "xAI Grok", placeholder = "xai-...",
        defaultModel = "", baseUrl = "https://api.x.ai/v1",
        models = listOf("grok-2-latest", "grok-2-vision-latest", "grok-beta"),
        modelsEndpoint = "/models", keyPrefix = "xai-",
        docsUrl = "https://docs.x.ai/developers",
    ),
    ProviderConfig(
        id = "nvidia", name = "NVIDIA NIM", placeholder = "nvapi-...",
        defaultModel = "", baseUrl = "https://integrate.api.nvidia.com/v1",
        models = listOf("meta/llama-3.2-11b-vision-instruct", "meta/llama-3.3-70b-instruct", "nvidia/llama-3.1-nemotron-70b-instruct", "deepseek-ai/deepseek-r1", "openai/gpt-oss-20b", "stepfun-ai/step-5-preview"),
        modelsEndpoint = "/models", keyPrefix = "nvapi-",
        docsUrl = "https://docs.api.nvidia.com",
    ),
    ProviderConfig(
        id = "glm", name = "Zhipu GLM", placeholder = "...",
        defaultModel = "", baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        models = listOf("glm-4-plus", "glm-4-flash", "glm-4-long", "codegeex-4"),
        modelsEndpoint = "/models",
        docsUrl = "https://open.bigmodel.cn/dev/api",
    ),
    ProviderConfig(
        id = "openrouter", name = "OpenRouter", placeholder = "sk-or-...",
        defaultModel = "", baseUrl = "https://openrouter.ai/api/v1",
        models = listOf("nvidia/nemotron-3.5-lightning:free", "meta-llama/llama-3.3-70b-instruct:free", "deepseek/deepseek-r1:free", "google/gemini-2.5-flash", "anthropic/claude-3.7-sonnet", "anthropic/claude-3.5-sonnet", "openai/gpt-4o", "openai/gpt-4o-mini"),
        modelsEndpoint = "/models", keyPrefix = "sk-or-",
        docsUrl = "https://openrouter.ai/docs",
    ),
    ProviderConfig(
        id = "ollama", name = "Ollama (Local)", placeholder = "Optional API key",
        defaultModel = "", baseUrl = "http://localhost:11434/v1",
        models = listOf("qwen2.5-coder:7b", "qwen2.5-coder:14b", "qwen2.5-coder:32b", "deepseek-r1:7b", "deepseek-r1:8b", "deepseek-r1:14b", "llama3.3:70b", "llama3.1:8b", "codestral:22b"),
        modelsEndpoint = "/models",
        docsUrl = "https://ollama.com",
    ),
    ProviderConfig(
        id = "rosette", name = "Sednium Rosette", placeholder = "sk-rosette-...",
        defaultModel = "", baseUrl = "https://rosette.sednium.com/api/v1",
        models = listOf("auto-leader"),
        modelsEndpoint = "/models", keyPrefix = "sk-rosette-",
        docsUrl = "https://rosette.sednium.com",
        usesBearerAuth = true,
    ),
    ProviderConfig(
        id = "custom", name = "Custom (OpenAI-compatible)", placeholder = "...",
        defaultModel = "", baseUrl = "", models = emptyList(),
        modelsEndpoint = "/models",
    ),
)

val OORTY_PROVIDERS = LARRY_PROVIDERS

