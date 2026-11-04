package com.sednium.kryptonide.ui.editor

import android.content.Context
import android.util.Log
import com.sednium.kryptonide.ui.monaco.SoraMonacoAdapter
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.widget.CodeEditor
import org.eclipse.tm4e.core.registry.IThemeSource

/**
 * Manages loading and activating VS Code TextMate themes in Sora Editor.
 * Real VS Code theme JSON files are bundled under assets/textmate/<themeId>.json.
 */
object ThemeAssetManager {

    private const val TAG = "ThemeAssetManager"
    private val loadedThemes = mutableSetOf<String>()

    val AVAILABLE_THEMES = listOf(
        "one-dark-pro",
        "dracula",
        "github-dark",
        "nord",
        "catppuccin-mocha",
        "solarized-dark",
        "tokyo-night",
        "gruvbox-dark",
        "ayu-dark",
        "night-owl",
        "monokai"
    )

    /**
     * Loads the theme asset, cleans comments, creates a fully populated native EditorColorScheme,
     * and applies it to the given CodeEditor.
     */
    fun applyTheme(editor: CodeEditor, themeId: String, context: Context): Boolean {
        return runCatching {
            val assetPath = "textmate/$themeId.json"
            val rawJson = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            val scheme = SoraMonacoAdapter.monacoThemeToEditorColorScheme(rawJson)

            editor.colorScheme = scheme
            editor.isBlockLineEnabled = true

            // Keep ThemeRegistry synchronized if TextMate registry is queried
            runCatching {
                ensureThemeLoaded(themeId, context)
                ThemeRegistry.getInstance().setTheme(themeId)
            }

            Log.d(TAG, "Successfully applied theme to Sora Editor: $themeId")
            true
        }.getOrElse { e ->
            Log.e(TAG, "Failed to apply theme $themeId: ${e.message}", e)
            false
        }
    }

    /**
     * Ensures that the given theme asset is loaded into the singleton ThemeRegistry.
     */
    @Synchronized
    fun ensureThemeLoaded(themeId: String, context: Context) {
        if (loadedThemes.contains(themeId)) return
        val assetPath = "textmate/$themeId.json"
        try {
            val rawJson = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            val cleaned = SoraMonacoAdapter.cleanJson(rawJson)
            val stream = cleaned.byteInputStream()
            stream.use { inputStream ->
                val source = IThemeSource.fromInputStream(inputStream, assetPath, null)
                val model = ThemeModel(source, themeId)
                ThemeRegistry.getInstance().loadTheme(model)
                loadedThemes.add(themeId)
                Log.d(TAG, "Loaded theme into ThemeRegistry: $themeId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not load theme asset $assetPath: ${e.message}", e)
        }
    }
}
