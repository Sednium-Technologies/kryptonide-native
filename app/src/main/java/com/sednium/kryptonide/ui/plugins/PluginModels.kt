package com.sednium.kryptonide.ui.plugins

data class PluginInfo(
    val id:             String,
    val name:           String,
    val description:    String,
    val version:        String,
    val author:         String,
    val category:       PluginCategory,
    val downloads:      String  = "",    // e.g. "18.2M"
    val rating:         Float   = 0f,
    val themeId:        String? = null,  // for theme plugins
    val lspServerId:    String? = null,  // for language plugins — real id in LspServerRegistry
    val formatterId:    String? = null,  // for formatter plugins — real id in FormatterRegistry
    val linterId:       String? = null,  // for linter plugins — real id in LinterRegistry
    val keymapPresetId: String? = null,  // for keymap plugins
    val isInstalled:    Boolean = false,
    val isEnabled:      Boolean = false,
    val isActive:       Boolean = false, // for themes/keymaps: currently applied
    val isInstalling:   Boolean = false,
    val installStatus:  String? = null,
    val installProgress: Float  = 0f,
    val installStage:   String? = null,
    val installLogs:    List<String> = emptyList(),
    val installError:   String? = null,
    val isCustom:       Boolean = false,
    val customFilePath: String? = null,
)

enum class PluginCategory(val label: String) {
    ALL("All"),
    THEME("Themes"),
    LANGUAGE("Language"),
    FORMATTER("Formatter"),
    LINTER("Linters"),
    KEYMAP("Keymaps"),
    PRODUCTIVITY("Productivity"),
    SNIPPET("Snippets"),
}
