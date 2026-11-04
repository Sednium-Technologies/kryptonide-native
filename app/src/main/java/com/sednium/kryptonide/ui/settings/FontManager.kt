package com.sednium.kryptonide.ui.settings

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

private val Context.fontDataStore by preferencesDataStore(name = "krypton_fonts")
private val SELECTED_FONT_KEY = stringPreferencesKey("selected_font_name")

data class FontItem(
    val name: String,
    val fileName: String,
    val isCustom: Boolean,
    val filePath: String?
)

data class FontSelection(
    val font: FontItem,
    val base64Data: String?
)

class FontManager(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val fontsDir = File(context.filesDir, "fonts").apply { mkdirs() }

    private val bundledFonts = listOf(
        FontItem("JetBrains Mono", "jetbrainsmono_regular.ttf", false, null),
        FontItem("Fira Code", "firacode_regular.ttf", false, null),
        FontItem("Cascadia Code", "cascadiacode_regular.ttf", false, null),
        FontItem("Source Code Pro", "sourcecodepro_regular.ttf", false, null),
        FontItem("Urbanist", "urbanist_regular.ttf", false, null)
    )

    private val _fonts = MutableStateFlow<List<FontItem>>(bundledFonts)
    val fonts: StateFlow<List<FontItem>> = _fonts.asStateFlow()

    private val _selectedFont = MutableStateFlow<FontSelection?>(null)
    val selectedFont: StateFlow<FontSelection?> = _selectedFont.asStateFlow()

    init {
        loadCustomFonts()
        viewModelScope.launch {
            context.fontDataStore.data
                .map { preferences ->
                    preferences[SELECTED_FONT_KEY] ?: "JetBrains Mono"
                }
                .collect { selectedFontName ->
                    val font = _fonts.value.find { it.name == selectedFontName } 
                        ?: bundledFonts.first()
                    loadAndEncodeFont(font)
                }
        }
    }

    private fun loadCustomFonts() {
        val customFonts = fontsDir.listFiles()?.filter { it.extension.lowercase() in setOf("ttf", "otf") }?.map { file ->
            val name = file.nameWithoutExtension.replace("_", " ").replace("-", " ").split(" ")
                .filter { it.isNotBlank() }
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            FontItem(name, file.name, true, file.absolutePath)
        } ?: emptyList()
        
        _fonts.value = bundledFonts + customFonts
    }

    fun selectFont(font: FontItem) {
        viewModelScope.launch {
            context.fontDataStore.edit { preferences ->
                preferences[SELECTED_FONT_KEY] = font.name
            }
        }
    }

    private suspend fun loadAndEncodeFont(font: FontItem) {
        withContext(Dispatchers.IO) {
            try {
                if (!font.isCustom) {
                    val validAsset = runCatching {
                        android.graphics.Typeface.createFromAsset(context.assets, "fonts/${font.fileName}")
                    }.getOrNull() != null
                    if (!validAsset) {
                        val fallback = bundledFonts.firstOrNull { it.name != font.name }
                            ?: bundledFonts.first()
                        _selectedFont.value = FontSelection(fallback, base64Data = null)
                        return@withContext
                    }
                    _selectedFont.value = FontSelection(font, base64Data = null)
                    return@withContext
                }

                // Custom font — encode to base64 for data-URI injection
                val bytes = if (font.filePath != null) {
                    val file = File(font.filePath)
                    if (file.exists() && file.length() > 0) file.readBytes() else ByteArray(0)
                } else {
                    ByteArray(0)
                }

                if (bytes.isEmpty()) {
                    _selectedFont.value = FontSelection(font, base64Data = null)
                    return@withContext
                }

                val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                _selectedFont.value = FontSelection(font, base64Data)
            } catch (e: Exception) {
                e.printStackTrace()
                _selectedFont.value = FontSelection(font, base64Data = null)
            }
        }
    }

    
    private fun getBundledFontBytes(fileName: String): ByteArray {
        return try {
            context.assets.open("fonts/$fileName").use { it.readBytes() }
        } catch (e: Exception) {
            e.printStackTrace()
            ByteArray(0)
        }
    }

    suspend fun importCustomFont(uri: Uri, context: Context): FontItem? = withContext(Dispatchers.IO) {
        try {
            val fileName = getFileName(uri, context) ?: "custom_font_${System.currentTimeMillis()}.ttf"
            val destFile = File(fontsDir, fileName)
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            val nameName = destFile.nameWithoutExtension.replace("_", " ").split(" ")
                .joinToString(" ") { it.capitalize() }
            val newFont = FontItem(nameName, destFile.name, true, destFile.absolutePath)
            
            loadCustomFonts()
            selectFont(newFont)
            return@withContext newFont
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    fun deleteCustomFont(font: FontItem) {
        if (!font.isCustom || font.filePath == null) return
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(font.filePath)
            if (file.exists()) {
                file.delete()
                loadCustomFonts()
                
                // If deleted font was selected, fallback to default
                if (_selectedFont.value?.font?.name == font.name) {
                    selectFont(bundledFonts.first())
                }
            }
        }
    }
    
    private fun getFileName(uri: Uri, context: Context): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = cursor.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path?.substringAfterLast('/')
        }
        return result
    }
}
