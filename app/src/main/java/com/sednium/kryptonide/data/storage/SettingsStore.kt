package com.sednium.kryptonide.data.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.sednium.kryptonide.data.model.SavedModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "krypton_settings")

object PrefKeys {
    val THEME           = stringPreferencesKey("theme")
    val HAPTICS         = booleanPreferencesKey("haptics")
    val AI_PROVIDER     = stringPreferencesKey("ai_provider")
    val AI_MODEL        = stringPreferencesKey("ai_model")
    val AI_BASE_URL     = stringPreferencesKey("ai_base_url")
    val SAVED_MODELS    = stringPreferencesKey("saved_models")
    val ACTIVE_MODEL_ID = stringPreferencesKey("active_model_id")
    val ACTIVE_FONT     = stringPreferencesKey("active_font")
}

class SettingsStore(private val context: Context) {

    val theme: Flow<String>      = context.dataStore.data.map { it[PrefKeys.THEME]      ?: "dark" }
    val haptics: Flow<Boolean>   = context.dataStore.data.map { it[PrefKeys.HAPTICS]    ?: true }
    val aiProvider: Flow<String> = context.dataStore.data.map { it[PrefKeys.AI_PROVIDER] ?: "openai" }
    val aiModel: Flow<String>    = context.dataStore.data.map { it[PrefKeys.AI_MODEL]    ?: "gpt-4o" }
    val aiBaseUrl: Flow<String>  = context.dataStore.data.map { it[PrefKeys.AI_BASE_URL] ?: "" }
    val activeFont: Flow<String> = context.dataStore.data.map { it[PrefKeys.ACTIVE_FONT] ?: "" }

    val savedModels: Flow<List<SavedModel>> = context.dataStore.data.map { prefs ->
        val raw = prefs[PrefKeys.SAVED_MODELS] ?: return@map emptyList()
        runCatching { Json.decodeFromString<List<SavedModel>>(raw) }.getOrElse { emptyList() }
    }

    val activeModelId: Flow<String?> = context.dataStore.data.map { it[PrefKeys.ACTIVE_MODEL_ID] }

    suspend fun setTheme(v: String)          = context.dataStore.edit { it[PrefKeys.THEME] = v }
    suspend fun setHaptics(v: Boolean) {
        setHapticsSync(v)
        context.dataStore.edit { it[PrefKeys.HAPTICS] = v }
    }
    suspend fun setAiProvider(v: String)     = context.dataStore.edit { it[PrefKeys.AI_PROVIDER] = v }
    suspend fun setAiModel(v: String)        = context.dataStore.edit { it[PrefKeys.AI_MODEL] = v }
    suspend fun setAiBaseUrl(v: String)      = context.dataStore.edit { it[PrefKeys.AI_BASE_URL] = v }
    suspend fun setActiveFont(v: String)     = context.dataStore.edit { it[PrefKeys.ACTIVE_FONT] = v }
    suspend fun setActiveModelId(v: String?) = context.dataStore.edit {
        if (v == null) it.remove(PrefKeys.ACTIVE_MODEL_ID) else it[PrefKeys.ACTIVE_MODEL_ID] = v
    }
    suspend fun setSavedModels(models: List<SavedModel>) = context.dataStore.edit {
        it[PrefKeys.SAVED_MODELS] = Json.encodeToString(models)
    }

    // Encrypted at rest via Android Keystore — this file holds the GitHub
    // access token (previously plain SharedPreferences despite the
    // security-crypto dependency already being declared and unused for this
    // purpose). Falls back to plain prefs only if key generation itself
    // fails (e.g. some very old/locked-down OEM keystores) rather than
    // crashing sign-in entirely — a degraded-but-working state is better
    // than a hard crash for something this load-bearing.
    private val prefs: SharedPreferences by lazy {
        runCatching {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                "krypton_secure",
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            context.getSharedPreferences("krypton_secure", Context.MODE_PRIVATE)
        }
    }

    fun getEditorTheme(): com.sednium.kryptonide.ui.editor.EditorTheme {
        return try {
            com.sednium.kryptonide.ui.editor.EditorTheme.valueOf(prefs.getString("editor_theme", "LIGHT") ?: "LIGHT")
        } catch (_: Exception) {
            com.sednium.kryptonide.ui.editor.EditorTheme.LIGHT
        }
    }
    fun setEditorTheme(theme: com.sednium.kryptonide.ui.editor.EditorTheme) {
        prefs.edit().putString("editor_theme", theme.name).apply()
    }
    fun getFontSize(): Int = prefs.getInt("font_size", 14)
    fun setFontSize(size: Int) = prefs.edit().putInt("font_size", size).apply()
    fun getFontFamily(): String {
        return prefs.getString("font_family", "Urbanist") ?: "Urbanist"
    }
    fun setFontFamily(font: String) {
        prefs.edit().putString("font_family", font).apply()
    }
    fun getWordWrap(): Boolean = prefs.getBoolean("word_wrap", false)
    fun setWordWrap(wrap: Boolean) = prefs.edit().putBoolean("word_wrap", wrap).apply()
    fun getKeymapPreset(): String = prefs.getString("keymap_preset", "vscode") ?: "vscode"
    fun setKeymapPreset(preset: String) = prefs.edit().putString("keymap_preset", preset).apply()
    fun getLineNumbers(): Boolean = prefs.getBoolean("line_numbers", true)
    fun setLineNumbers(lines: Boolean) = prefs.edit().putBoolean("line_numbers", lines).apply()
    
    fun getIndentationGuides(): Boolean = prefs.getBoolean("indentation_guides", false)
    fun setIndentationGuides(guides: Boolean) = prefs.edit().putBoolean("indentation_guides", guides).apply()
    
    fun getSystemPrompt(): String = prefs.getString("system_prompt", "") ?: ""
    fun setSystemPrompt(prompt: String) = prefs.edit().putString("system_prompt", prompt).apply()
    
    fun getModelChoice(): String = prefs.getString("model_choice", "") ?: ""
    fun setModelChoice(model: String) = prefs.edit().putString("model_choice", model).apply()

    fun getApiKey(): String     = prefs.getString("api_key", "") ?: ""
    fun setApiKey(key: String)  = prefs.edit().putString("api_key", key).apply()

    fun getGeminiKey(): String = prefs.getString("gemini_key", "") ?: ""
    fun setGeminiKey(key: String) = prefs.edit().putString("gemini_key", key).apply()

    fun getGithubToken(): String       = prefs.getString("github_token", "") ?: ""
    fun setGithubToken(token: String)  = prefs.edit().putString("github_token", token).apply()
    fun clearGithubToken()             = prefs.edit().remove("github_token").apply()

    // Tracks the release from the last successful cloud build per repo, so
    // a *new* successful build can offer to delete the previous one. Stored
    // as "owner/repo" -> "releaseId|tag" so multiple repos each keep their
    // own history independently.
    fun getLastCloudBuildRelease(owner: String, repo: String): Pair<Long, String>? {
        val raw = prefs.getString("cloud_build_release_$owner/$repo", null) ?: return null
        val parts = raw.split("|", limit = 2)
        if (parts.size != 2) return null
        val id = parts[0].toLongOrNull() ?: return null
        return id to parts[1]
    }
    fun setLastCloudBuildRelease(owner: String, repo: String, releaseId: Long, tag: String) =
        prefs.edit().putString("cloud_build_release_$owner/$repo", "$releaseId|$tag").apply()
    fun clearLastCloudBuildRelease(owner: String, repo: String) =
        prefs.edit().remove("cloud_build_release_$owner/$repo").apply()

    fun getThemeMode(): Int {
        // 0 = System, 1 = Light, 2 = Dark
        return prefs.getInt("theme_mode_krypton", 0)
    }
    fun setThemeMode(mode: Int) = prefs.edit().putInt("theme_mode_krypton", mode).apply()

    fun getUseDynamicColor(): Boolean = prefs.getBoolean("use_dynamic_color", false)
    fun setUseDynamicColor(use: Boolean) = prefs.edit().putBoolean("use_dynamic_color", use).apply()

    fun getHapticsSync(): Boolean = prefs.getBoolean("haptics_enabled", true)
    fun setHapticsSync(enabled: Boolean) {
        prefs.edit().putBoolean("haptics_enabled", enabled).apply()
    }

    fun getEnablePrettier(): Boolean = prefs.getBoolean("enable_prettier", true)
    fun setEnablePrettier(enabled: Boolean) {
        prefs.edit().putBoolean("enable_prettier", enabled).apply()
    }

    fun getEnableEmmet(): Boolean = prefs.getBoolean("enable_emmet", true)
    fun setEnableEmmet(enabled: Boolean) {
        prefs.edit().putBoolean("enable_emmet", enabled).apply()
    }
}
