package com.sednium.kryptonide.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sednium.kryptonide.data.storage.SettingsStore
import com.sednium.kryptonide.ui.editor.EditorTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val theme: EditorTheme = EditorTheme.LIGHT,
    val fontSize: Int = 14,
    val fontFamily: String = "Urbanist",
    val wordWrap: Boolean = false,
    val lineNumbers: Boolean = true,
    val indentationGuides: Boolean = false,
    val keymapPreset: String = "vscode",
    val geminiApiKey: String = "",
    val githubToken: String = "",
    val customSystemPrompt: String = "",
    val modelChoice: String = "",
    // Plugin settings placeholders
    val enablePrettier: Boolean = true,
    val enableEmmet: Boolean = true,
    val themeMode: Int = 0, // 0 = System, 1 = Light, 2 = Dark
    val useDynamicColor: Boolean = false,
    val hapticsEnabled: Boolean = true,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()
    
    init {
        // Load settings from store
        _state.value = SettingsUiState(
            theme = store.getEditorTheme(),
            fontSize = store.getFontSize(),
            fontFamily = store.getFontFamily(),
            wordWrap = store.getWordWrap(),
            lineNumbers = store.getLineNumbers(),
            indentationGuides = store.getIndentationGuides(),
            keymapPreset = store.getKeymapPreset(),
            geminiApiKey = store.getGeminiKey(),
            githubToken = store.getGithubToken(),
            customSystemPrompt = store.getSystemPrompt(),
            modelChoice = store.getModelChoice(),
            enablePrettier = store.getEnablePrettier(),
            enableEmmet = store.getEnableEmmet(),
            themeMode = store.getThemeMode(),
            useDynamicColor = store.getUseDynamicColor(),
            hapticsEnabled = store.getHapticsSync(),
        )
    }

    // --- Editor Settings ---
    fun setThemeMode(mode: Int) {
        store.setThemeMode(mode)
        val currentEditorTheme = _state.value.theme
        val syncedEditorTheme = when {
            mode == 1 && (currentEditorTheme == EditorTheme.DARK || currentEditorTheme == EditorTheme.KRYPTON_DARK) -> {
                store.setEditorTheme(EditorTheme.LIGHT)
                EditorTheme.LIGHT
            }
            mode == 2 && currentEditorTheme == EditorTheme.LIGHT -> {
                store.setEditorTheme(EditorTheme.KRYPTON_DARK)
                EditorTheme.KRYPTON_DARK
            }
            else -> currentEditorTheme
        }
        _state.update { it.copy(themeMode = mode, theme = syncedEditorTheme) }
    }

    fun setUseDynamicColor(dynamic: Boolean) {
        store.setUseDynamicColor(dynamic)
        _state.update { it.copy(useDynamicColor = dynamic) }
    }

    fun setTheme(theme: EditorTheme) {
        store.setEditorTheme(theme)
        _state.update { it.copy(theme = theme) }
    }
    
    fun setFontSize(size: Int) {
        val s = size.coerceIn(8, 48)
        store.setFontSize(s)
        _state.update { it.copy(fontSize = s) }
    }
    
    fun setFontFamily(font: String) {
        store.setFontFamily(font)
        _state.update { it.copy(fontFamily = font) }
    }
    
    fun setWordWrap(enabled: Boolean) {
        store.setWordWrap(enabled)
        _state.update { it.copy(wordWrap = enabled) }
    }
    
    fun setLineNumbers(enabled: Boolean) {
        store.setLineNumbers(enabled)
        _state.update { it.copy(lineNumbers = enabled) }
    }
    
    fun setIndentationGuides(enabled: Boolean) {
        store.setIndentationGuides(enabled)
        _state.update { it.copy(indentationGuides = enabled) }
    }
    
    fun setHapticsEnabled(enabled: Boolean) {
        store.setHapticsSync(enabled)
        viewModelScope.launch { store.setHaptics(enabled) }
        _state.update { it.copy(hapticsEnabled = enabled) }
    }
    
    // --- API Settings ---
    fun setGeminiKey(key: String) {
        store.setGeminiKey(key.trim())
        _state.update { it.copy(geminiApiKey = key.trim()) }
    }
    
    fun setGithubToken(token: String) {
        store.setGithubToken(token.trim())
        _state.update { it.copy(githubToken = token.trim()) }
    }
    
    // --- AI Settings ---
    fun setSystemPrompt(prompt: String) {
        store.setSystemPrompt(prompt.trim())
        _state.update { it.copy(customSystemPrompt = prompt.trim()) }
    }
    
    fun setModelChoice(model: String) {
        store.setModelChoice(model)
        _state.update { it.copy(modelChoice = model) }
    }
    
    // --- Plugin Settings ---
    fun setEnablePrettier(enabled: Boolean) {
        store.setEnablePrettier(enabled)
        _state.update { it.copy(enablePrettier = enabled) }
    }
    fun setEnableEmmet(enabled: Boolean) {
        store.setEnableEmmet(enabled)
        _state.update { it.copy(enableEmmet = enabled) }
    }

    fun setKeymapPreset(preset: String) {
        store.setKeymapPreset(preset)
        _state.update { it.copy(keymapPreset = preset) }
    }
}
