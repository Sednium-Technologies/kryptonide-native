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
    val SELECTED_POCKET_MODEL_ID = stringPreferencesKey("selected_pocket_model_id")
}

class SettingsStore(private val context: Context) {

    val theme: Flow<String>      = context.dataStore.data.map { it[PrefKeys.THEME]      ?: "dark" }
    val haptics: Flow<Boolean>   = context.dataStore.data.map { it[PrefKeys.HAPTICS]    ?: true }
    val aiProvider: Flow<String> = context.dataStore.data.map { it[PrefKeys.AI_PROVIDER] ?: "pocket" }
    val aiModel: Flow<String>    = context.dataStore.data.map {
        val model = it[PrefKeys.AI_MODEL] ?: com.sednium.kryptonide.agent.pocket.PocketModels.MINICPM_MODEL_ID
        if (model == "lfm-250m-q4" || model == "needle-3-q4") com.sednium.kryptonide.agent.pocket.PocketModels.MINICPM_MODEL_ID else model
    }
    val aiBaseUrl: Flow<String>  = context.dataStore.data.map { it[PrefKeys.AI_BASE_URL] ?: "" }
    val activeFont: Flow<String> = context.dataStore.data.map { it[PrefKeys.ACTIVE_FONT] ?: "" }
    val selectedPocketModelId: Flow<String> = context.dataStore.data.map {
        val id = it[PrefKeys.SELECTED_POCKET_MODEL_ID] ?: com.sednium.kryptonide.agent.pocket.PocketModels.MINICPM_MODEL_ID
        if (id == "lfm-250m-q4" || id == "needle-3-q4") com.sednium.kryptonide.agent.pocket.PocketModels.MINICPM_MODEL_ID else id
    }

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
    suspend fun setSelectedPocketModelId(id: String) {
        setSelectedPocketModelIdSync(id)
        context.dataStore.edit { it[PrefKeys.SELECTED_POCKET_MODEL_ID] = id }
    }
    suspend fun setActiveModelId(v: String?) = context.dataStore.edit {
        if (v == null) it.remove(PrefKeys.ACTIVE_MODEL_ID) else it[PrefKeys.ACTIVE_MODEL_ID] = v
    }
    suspend fun setSavedModels(models: List<SavedModel>) = context.dataStore.edit {
        it[PrefKeys.SAVED_MODELS] = Json.encodeToString(models)
    }

    // Encrypted at rest via Android Keystore — this file holds the GitHub
    // access token, cloud-provider API keys and Gemini key.
    //
    // SECURITY: never silently fall back to a plaintext SharedPreferences file
    // for secrets. If the Keystore is unavailable we keep non-sensitive UI
    // settings usable in a separate fallback file, but secret getters return
    // empty and secret setters are no-ops. A token written in cleartext is
    // worse than making the user sign in again.
    private val securePrefs: SharedPreferences? by lazy {
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
        }.onFailure {
            android.util.Log.e("SettingsStore", "Keystore-backed preferences unavailable", it)
        }.getOrNull()
    }

    // Non-sensitive settings may use a plain fallback on devices with a broken
    // Keystore. Secret accessors below intentionally do not use this file.
    private val prefs: SharedPreferences by lazy {
        securePrefs ?: context.getSharedPreferences("krypton_settings_fallback", Context.MODE_PRIVATE)
    }

    private fun getSecret(key: String): String = securePrefs?.getString(key, "") ?: ""
    private fun setSecret(key: String, value: String) {
        securePrefs?.edit()?.putString(key, value)?.apply()
    }
    private fun clearSecret(key: String) {
        securePrefs?.edit()?.remove(key)?.apply()
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

    fun getApiKey(): String     = getSecret("api_key")
    fun setApiKey(key: String)  = setSecret("api_key", key)

    fun getGeminiKey(): String = getSecret("gemini_key")
    fun setGeminiKey(key: String) = setSecret("gemini_key", key)

    fun getGithubToken(): String       = getSecret("github_token")
    fun setGithubToken(token: String)  = setSecret("github_token", token)
    fun clearGithubToken()             = clearSecret("github_token")

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

    fun getSelectedPocketModelId(): String {
        val id = prefs.getString("selected_pocket_model_id", com.sednium.kryptonide.agent.pocket.PocketModels.MINICPM_MODEL_ID)
            ?: com.sednium.kryptonide.agent.pocket.PocketModels.MINICPM_MODEL_ID
        return if (id == "lfm-250m-q4" || id == "needle-3-q4") com.sednium.kryptonide.agent.pocket.PocketModels.MINICPM_MODEL_ID else id
    }

    fun setSelectedPocketModelIdSync(id: String) {
        prefs.edit().putString("selected_pocket_model_id", id).apply()
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
