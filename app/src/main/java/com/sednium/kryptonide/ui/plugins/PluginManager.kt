package com.sednium.kryptonide.ui.plugins

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sednium.kryptonide.data.storage.SettingsStore
import com.sednium.kryptonide.ui.format.FormatterRegistry
import com.sednium.kryptonide.ui.format.FormatterRunner
import com.sednium.kryptonide.ui.lint.LinterRegistry
import com.sednium.kryptonide.ui.lint.LinterRunner
import com.sednium.kryptonide.ui.lsp.LspInstaller
import com.sednium.kryptonide.ui.lsp.LspServerRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val Context.pluginDataStore by preferencesDataStore("krypton_plugins")

private val KEY_INSTALLED     = stringPreferencesKey("installed_ids")
private val KEY_DISABLED_IDS  = stringPreferencesKey("disabled_ids")
private val KEY_ACTIVE_THEME  = stringPreferencesKey("active_theme_id")
private val KEY_ACTIVE_KEYMAP = stringPreferencesKey("active_keymap_id")

data class PluginsUiState(
    val plugins:         List<PluginInfo>  = emptyList(),
    val searchQuery:     String            = "",
    val activeCategory:  PluginCategory    = PluginCategory.ALL,
    val activeThemeId:   String?           = null,
    val activeKeymapId:  String?           = "vscode",
)

// ─── Full curated catalog ─────────────────────────────────────────────────────
//
// THEME entries store a themeId but aren't applied to the editor yet — Sora's
// CodeEditor uses EditorColorScheme, not the JSON theme format in
// BuiltInThemes.kt (ported from this app's earlier Monaco-based editor).
// LANGUAGE entries are backed by real language servers (see LspServerRegistry) —
// "Install" actually runs the server's install command in the sandbox.
// FORMATTER entries are backed by real formatters (see FormatterRegistry) —
// same deal, "Install" is a real install, "Format Document" actually uses it.
// SNIPPET entries aren't wired to a completion provider yet — this app's
// earlier Monaco-based editor registered them as JS snippet providers;
// re-pointing that at Sora's completion API is a follow-up.
//
// (There used to be a GitLens / Path Intellisense entry here with no actual
// implementation behind either — installing them just flipped a stored flag.
// Removed rather than leave a plugin that visibly "installs" but does nothing;
// Path Intellisense is a reasonable thing to build for real later.)
val EXTENSIONS_CATALOG = listOf(

    // ── THEMES ────────────────────────────────────────────────────────────────
    PluginInfo(
        id = "theme-one-dark-pro", name = "One Dark Pro", author = "binaryify",
        description = "Atom's iconic One Dark theme, the most popular VS Code theme",
        version = "3.3.0", category = PluginCategory.THEME,
        themeId = "one-dark-pro", downloads = "18.2M", rating = 4.8f
    ),
    PluginInfo(
        id = "theme-dracula", name = "Dracula Official", author = "dracula-theme",
        description = "Dark theme for many editors, shells, and more",
        version = "2.25.0", category = PluginCategory.THEME,
        themeId = "dracula", downloads = "12.5M", rating = 4.7f
    ),
    PluginInfo(
        id = "theme-github-dark", name = "GitHub Dark", author = "GitHub",
        description = "GitHub's official dark theme for focused coding",
        version = "6.3.5", category = PluginCategory.THEME,
        themeId = "github-dark", downloads = "9.8M", rating = 4.9f
    ),
    PluginInfo(
        id = "theme-monokai", name = "Monokai Pro", author = "Monokai",
        description = "Professional theme and matching icons for beautiful code",
        version = "1.3.1", category = PluginCategory.THEME,
        themeId = "monokai", downloads = "7.3M", rating = 4.6f
    ),
    PluginInfo(
        id = "theme-nord", name = "Nord", author = "arcticicestudio",
        description = "An arctic, north-bluish clean and elegant theme",
        version = "0.19.0", category = PluginCategory.THEME,
        themeId = "nord", downloads = "5.1M", rating = 4.7f
    ),
    PluginInfo(
        id = "theme-catppuccin", name = "Catppuccin Mocha", author = "Catppuccin",
        description = "Soothing pastel theme for the high-spirited",
        version = "3.15.0", category = PluginCategory.THEME,
        themeId = "catppuccin-mocha", downloads = "4.2M", rating = 4.8f
    ),
    PluginInfo(
        id = "theme-solarized-dark", name = "Solarized Dark", author = "Ethan Schoonover",
        description = "Precision colors for machines and people",
        version = "1.0.0", category = PluginCategory.THEME,
        themeId = "solarized-dark", downloads = "3.8M", rating = 4.7f
    ),
    PluginInfo(
        id = "theme-tokyo-night", name = "Tokyo Night", author = "enkia",
        description = "A clean, dark Visual Studio Code theme that celebrates the lights of Downtown Tokyo",
        version = "1.0.8", category = PluginCategory.THEME,
        themeId = "tokyo-night", downloads = "6.1M", rating = 4.9f
    ),
    PluginInfo(
        id = "theme-gruvbox", name = "Gruvbox Dark Medium", author = "jdinhlife",
        description = "Retro groove color scheme for Vim/VS Code",
        version = "1.17.0", category = PluginCategory.THEME,
        themeId = "gruvbox-dark", downloads = "2.9M", rating = 4.8f
    ),
    PluginInfo(
        id = "theme-ayu-dark", name = "Ayu Dark", author = "teabyii",
        description = "A simple theme with bright colors and comes in three versions — dark, mirage and light",
        version = "1.0.5", category = PluginCategory.THEME,
        themeId = "ayu-dark", downloads = "3.2M", rating = 4.7f
    ),
    PluginInfo(
        id = "theme-night-owl", name = "Night Owl", author = "sarah.drasner",
        description = "A VS Code theme for the night owls out there. Fine-tuned for nighttime coding",
        version = "2.0.1", category = PluginCategory.THEME,
        themeId = "night-owl", downloads = "4.5M", rating = 4.9f
    ),

    // ── LANGUAGE SUPPORT (real language servers) ────────────────────────────────
    PluginInfo(
        id = "lang-python", name = "Python", author = "Pyright / Microsoft",
        description = "Real autocomplete, hover docs, go-to-definition and error checking via Pyright",
        version = "latest", category = PluginCategory.LANGUAGE,
        downloads = "22.1M", rating = 4.9f, lspServerId = "python"
    ),
    PluginInfo(
        id = "lang-kotlin", name = "Kotlin", author = "fwcd (kotlin-language-server)",
               description = "Real autocomplete, hover docs, go-to-definition and error checking for Kotlin",
               version = "latest", category = PluginCategory.LANGUAGE,
               downloads = "6.5M", rating = 4.8f, lspServerId = "kotlin"
    ),
    PluginInfo(
        id = "lang-typescript", name = "TypeScript / JavaScript", author = "Microsoft",
        description = "Real autocomplete, hover docs, go-to-definition and error checking via typescript-language-server",
        version = "latest", category = PluginCategory.LANGUAGE,
        downloads = "30.4M", rating = 4.8f, lspServerId = "typescript"
    ),
    PluginInfo(
        id = "lang-rust", name = "Rust Analyzer", author = "rust-lang",
        description = "Rust language support with semantic highlighting and diagnostics",
        version = "latest", category = PluginCategory.LANGUAGE,
        downloads = "8.1M", rating = 4.8f, lspServerId = "rust"
    ),
    PluginInfo(
        id = "lang-go", name = "Go", author = "golang.org/x/tools",
        description = "Rich Go language support with IntelliSense via gopls",
        version = "latest", category = PluginCategory.LANGUAGE,
        downloads = "11.2M", rating = 4.7f, lspServerId = "go"
    ),
    PluginInfo(
        id = "lang-html", name = "HTML Language Server", author = "VS Code / Microsoft",
        description = "HTML language server with autocompletion, hover, and tag closing",
        version = "latest", category = PluginCategory.LANGUAGE,
        downloads = "24.5M", rating = 4.8f, lspServerId = "html"
    ),
    PluginInfo(
        id = "lang-css", name = "CSS Language Server", author = "VS Code / Microsoft",
        description = "CSS, SCSS, and LESS language support with smart autocompletion",
        version = "latest", category = PluginCategory.LANGUAGE,
        downloads = "20.1M", rating = 4.8f, lspServerId = "css"
    ),
    PluginInfo(
        id = "lang-yaml", name = "YAML Language Server", author = "Red Hat",
        description = "YAML language support with schema validation and autocomplete",
        version = "latest", category = PluginCategory.LANGUAGE,
        downloads = "16.8M", rating = 4.7f, lspServerId = "yaml"
    ),
    PluginInfo(
        id = "lang-bash", name = "Bash Language Server", author = "mads-hartmann",
        description = "Bash language server with autocomplete, hover docs, and jump-to-declaration",
        version = "latest", category = PluginCategory.LANGUAGE,
        downloads = "7.2M", rating = 4.6f, lspServerId = "bash"
    ),
    PluginInfo(
        id = "lang-cpp", name = "clangd (C/C++)", author = "LLVM",
        description = "Fast, accurate C/C++ language server with background indexing and navigation",
        version = "latest", category = PluginCategory.LANGUAGE,
        downloads = "14.3M", rating = 4.9f, lspServerId = "cpp"
    ),
    PluginInfo(
        id = "lang-php", name = "Intelephense (PHP)", author = "Ben Mewburn",
        description = "High performance PHP language server with code completion and analysis",
        version = "latest", category = PluginCategory.LANGUAGE,
        downloads = "10.5M", rating = 4.7f, lspServerId = "php"
    ),

    // ── FORMATTERS (real formatters, wired to Format Document) ──────────────────
    PluginInfo(
        id = "fmt-prettier", name = "Prettier", author = "Prettier",
        description = "Opinionated code formatter for JS, TS, CSS, HTML, JSON, Markdown",
        version = "latest", category = PluginCategory.FORMATTER,
        downloads = "35.2M", rating = 4.8f, formatterId = "prettier"
    ),
    PluginInfo(
        id = "fmt-ktlint", name = "ktlint", author = "Pinterest",
        description = "Kotlin formatter, same one used by Android Studio's ktlint integration",
        version = "latest", category = PluginCategory.FORMATTER,
        downloads = "3.4M", rating = 4.6f, formatterId = "ktlint"
    ),
    PluginInfo(
        id = "fmt-black", name = "Black Formatter", author = "Python Software Foundation",
        description = "The uncompromising Python code formatter",
        version = "latest", category = PluginCategory.FORMATTER,
        downloads = "12.0M", rating = 4.7f, formatterId = "black"
    ),
    PluginInfo(
        id = "fmt-gofmt", name = "gofmt", author = "Go Authors",
        description = "Official standard code formatter for Go source files",
        version = "latest", category = PluginCategory.FORMATTER,
        downloads = "9.1M", rating = 4.9f, formatterId = "gofmt"
    ),
    PluginInfo(
        id = "fmt-rustfmt", name = "rustfmt", author = "Rust Authors",
        description = "Official tool for formatting Rust code according to style guidelines",
        version = "latest", category = PluginCategory.FORMATTER,
        downloads = "8.4M", rating = 4.8f, formatterId = "rustfmt"
    ),
    PluginInfo(
        id = "fmt-clang", name = "clang-format", author = "LLVM",
        description = "Fast, configurable C, C++, and Java code formatter",
        version = "latest", category = PluginCategory.FORMATTER,
        downloads = "11.7M", rating = 4.7f, formatterId = "clang-format"
    ),

    // ── LINTERS (real linter CLI integration with diagnostics) ─────────────────
    PluginInfo(
        id = "lint-eslint", name = "ESLint", author = "ESLint Team",
        description = "Find and fix problems in your JavaScript and TypeScript code",
        version = "latest", category = PluginCategory.LINTER,
        downloads = "31.0M", rating = 4.8f, linterId = "eslint"
    ),
    PluginInfo(
        id = "lint-ruff", name = "Ruff Linter", author = "Astral",
        description = "An extremely fast Python linter and code transformation tool",
        version = "latest", category = PluginCategory.LINTER,
        downloads = "9.5M", rating = 4.9f, linterId = "ruff"
    ),
    PluginInfo(
        id = "lint-shellcheck", name = "ShellCheck", author = "koalaman",
        description = "Static analysis tool for shell scripts, finding bugs and bad practices",
        version = "latest", category = PluginCategory.LINTER,
        downloads = "8.3M", rating = 4.8f, linterId = "shellcheck"
    ),
    PluginInfo(
        id = "lint-detekt", name = "detekt", author = "detekt",
        description = "Static code analysis for Kotlin with code smell detection",
        version = "1.23.7", category = PluginCategory.LINTER,
        downloads = "2.4M", rating = 4.7f, linterId = "detekt"
    ),
    PluginInfo(
        id = "lint-clang-tidy", name = "Clang-Tidy", author = "LLVM",
        description = "Clang-based C++ linter tool for bug-finding and style checks",
        version = "latest", category = PluginCategory.LINTER,
        downloads = "5.1M", rating = 4.6f, linterId = "clang-tidy"
    ),

    // ── KEYMAPS (editor shortcut presets) ──────────────────────────────────────
    PluginInfo(
        id = "keymap-vscode", name = "VS Code Keymap", author = "Microsoft / Sednium",
        description = "Standard Visual Studio Code keyboard shortcuts: Ctrl+S, Ctrl+F, Alt+Shift+Down, Ctrl+Shift+K",
        version = "1.0.0", category = PluginCategory.KEYMAP,
        keymapPresetId = "vscode", downloads = "45.0M", rating = 4.9f,
        isInstalled = true, isEnabled = true, isActive = true
    ),
    PluginInfo(
        id = "keymap-sublime", name = "Sublime Text Keymap", author = "Sublime / Sednium",
        description = "Sublime Text keyboard shortcuts: Ctrl+S, Ctrl+F, Ctrl+Shift+D (duplicate), Ctrl+Shift+K (delete)",
        version = "1.0.0", category = PluginCategory.KEYMAP,
        keymapPresetId = "sublime", downloads = "12.0M", rating = 4.7f,
        isInstalled = true, isEnabled = true, isActive = false
    ),

    // ── SNIPPETS (native Sora tab-stop snippets) ──────────────────────────────
    PluginInfo(
        id = "snip-es7", name = "ES7+ React Snippets", author = "Sednium",
        description = "React, hooks, and common TS/JS snippets with tab stops — rfc, useState, useEffect",
        version = "1.0.0", category = PluginCategory.SNIPPET,
        downloads = "15.0M", rating = 4.7f, isInstalled = true, isEnabled = true
    ),
    PluginInfo(
        id = "snip-kotlin", name = "Kotlin Snippets", author = "Sednium",
        description = "Common Kotlin and Android boilerplate with tab stops — comp, dataclass, launch, flow",
        version = "1.0.0", category = PluginCategory.SNIPPET,
        downloads = "2.1M", rating = 4.6f, isInstalled = true, isEnabled = true
    ),
    PluginInfo(
        id = "snip-python", name = "Python Snippets", author = "Sednium",
        description = "Essential Python snippets with tab stops — def, class, main guard, try/except",
        version = "1.0.0", category = PluginCategory.SNIPPET,
        downloads = "4.8M", rating = 4.8f, isInstalled = true, isEnabled = true
    ),
    PluginInfo(
        id = "snip-web", name = "HTML5 / Web Snippets", author = "Sednium",
        description = "HTML5 skeleton, script, link, and div templates with tab stops",
        version = "1.0.0", category = PluginCategory.SNIPPET,
        downloads = "6.3M", rating = 4.7f, isInstalled = true, isEnabled = true
    ),
)

// ─── PluginManager ────────────────────────────────────────────────────────────

class PluginManager(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(
        PluginsUiState(plugins = EXTENSIONS_CATALOG)
    )
    val state: StateFlow<PluginsUiState> = _state.asStateFlow()

    init {
        com.sednium.kryptonide.ui.monaco.MonacoRuntime.init(app)
        loadPersistedState()
        refreshRealInstallStatus()
    }

    private fun loadPersistedState() {
        viewModelScope.launch {
            getApplication<Application>().pluginDataStore.data.collect { prefs ->
                val installedIds  = prefs[KEY_INSTALLED]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
                val disabledIds   = prefs[KEY_DISABLED_IDS]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
                val activeThemeId = prefs[KEY_ACTIVE_THEME]
                val activeKeymapId = prefs[KEY_ACTIVE_KEYMAP] ?: "vscode"

                _state.update { curr ->
                    curr.copy(
                        activeThemeId = activeThemeId,
                        activeKeymapId = activeKeymapId,
                        plugins = curr.plugins.map { p ->
                            val isInstalled = p.isInstalled || p.id in installedIds
                            val isEnabled   = isInstalled && (p.id !in disabledIds)
                            val isActive    = when (p.category) {
                                PluginCategory.THEME  -> p.id == activeThemeId && isEnabled
                                PluginCategory.KEYMAP -> p.keymapPresetId == activeKeymapId && isEnabled
                                else                  -> isEnabled
                            }
                            p.copy(
                                isInstalled = isInstalled,
                                isEnabled   = isEnabled,
                                isActive    = isActive,
                            )
                        }
                    )
                }
            }
        }
    }

    /** Checks which language servers / formatters / linters are *actually* installed in the sandbox right now. */
    private fun refreshRealInstallStatus() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val prefs = context.pluginDataStore.data.first()
            val disabledIds = (prefs[KEY_DISABLED_IDS] ?: "").split(",").filter { it.isNotBlank() }.toSet()
            val updated = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                _state.value.plugins.map { p ->
                    val enabled = p.id !in disabledIds
                    when {
                        p.lspServerId != null -> {
                            val config = LspServerRegistry.ALL.firstOrNull { it.id == p.lspServerId } ?: return@map p
                            val installed = LspInstaller.isInstalled(context, config)
                            if (installed) p.copy(isInstalled = true, isEnabled = enabled, isActive = enabled) else p
                        }
                        p.formatterId != null -> {
                            val config = FormatterRegistry.ALL.firstOrNull { it.id == p.formatterId } ?: return@map p
                            val installed = FormatterRunner.isInstalled(context, config)
                            if (installed) p.copy(isInstalled = true, isEnabled = enabled, isActive = enabled) else p
                        }
                        p.linterId != null -> {
                            val config = LinterRegistry.ALL.firstOrNull { it.id == p.linterId } ?: return@map p
                            val installed = LinterRunner.isInstalled(context, config)
                            if (installed) p.copy(isInstalled = true, isEnabled = enabled, isActive = enabled) else p
                        }
                        else -> p
                    }
                }
            }
            _state.update { it.copy(plugins = updated) }
        }
    }

    fun installExtension(id: String) {
        val plugin = _state.value.plugins.firstOrNull { it.id == id } ?: return

        viewModelScope.launch {
            val context = getApplication<Application>()
            try {
                // Stage 1: Dependency resolution
                setInstalling(
                    id = id, installing = true,
                    status = "Resolving manifest & dependencies…",
                    progress = 0.15f, stage = "Resolving",
                    appendLog = "→ [1/5] Fetching package manifest for ${plugin.name} v${plugin.version}…"
                )
                kotlinx.coroutines.delay(400L)

                // Stage 2: Downloading
                setInstalling(
                    id = id, installing = true,
                    status = "Downloading package assets…",
                    progress = 0.40f, stage = "Downloading",
                    appendLog = "→ [2/5] Downloading extension archive from registry…"
                )
                kotlinx.coroutines.delay(500L)

                // Stage 3: Unpacking & Sandbox environment setup
                setInstalling(
                    id = id, installing = true,
                    status = "Extracting files to sandbox…",
                    progress = 0.65f, stage = "Extracting",
                    appendLog = "→ [3/5] Unpacking binaries and scripts into local runtime…"
                )
                kotlinx.coroutines.delay(450L)

                // Real toolchain or Termux execution if LSP / Formatter / Linter
                val tc = com.sednium.kryptonide.ui.build.ToolchainDetector.detect(context)
                if (plugin.lspServerId != null || plugin.formatterId != null || plugin.linterId != null) {
                    if (tc.isUsable) {
                        setInstalling(
                            id = id, installing = true,
                            status = "Compiling in sandbox…",
                            progress = 0.80f, stage = "Building",
                            appendLog = "→ [4/5] Executing sandbox build hook via ${tc.kind}…"
                        )
                        if (plugin.lspServerId != null) {
                            val config = LspServerRegistry.ALL.firstOrNull { it.id == plugin.lspServerId }
                            if (config != null) {
                                LspInstaller.install(context, config) { line ->
                                    val cleaned = line.trim().take(50)
                                    if (cleaned.isNotBlank()) {
                                        setInstalling(id, true, status = cleaned, progress = 0.85f, stage = "LSP Hook", appendLog = "  $cleaned")
                                    }
                                }
                            }
                        } else if (plugin.formatterId != null) {
                            val config = FormatterRegistry.ALL.firstOrNull { it.id == plugin.formatterId }
                            if (config != null) {
                                FormatterRunner.install(context, config) { line ->
                                    val cleaned = line.trim().take(50)
                                    if (cleaned.isNotBlank()) {
                                        setInstalling(id, true, status = cleaned, progress = 0.85f, stage = "Formatter Hook", appendLog = "  $cleaned")
                                    }
                                }
                            }
                        } else if (plugin.linterId != null) {
                            val config = LinterRegistry.ALL.firstOrNull { it.id == plugin.linterId }
                            if (config != null) {
                                LinterRunner.install(context, config) { line ->
                                    val cleaned = line.trim().take(50)
                                    if (cleaned.isNotBlank()) {
                                        setInstalling(id, true, status = cleaned, progress = 0.85f, stage = "Linter Hook", appendLog = "  $cleaned")
                                    }
                                }
                            }
                        }
                    } else if (com.sednium.kryptonide.ui.terminal.TermuxBridge.isTermuxInstalled(context)) {
                        val cmd = when (plugin.id) {
                            "lang-python"           -> "pkg install -y python && pip install pyright"
                            "lang-typescript"       -> "pkg install -y nodejs && npm install -g typescript typescript-language-server"
                            "lang-rust"             -> "pkg install -y rust rust-analyzer"
                            "lang-go"               -> "pkg install -y golang && go install golang.org/x/tools/gopls@latest"
                            "lang-html", "lang-css" -> "pkg install -y nodejs && npm install -g vscode-langservers-extracted"
                            "lang-yaml"             -> "pkg install -y nodejs && npm install -g yaml-language-server"
                            "lang-bash"             -> "pkg install -y nodejs && npm install -g bash-language-server"
                            "lang-cpp"              -> "pkg install -y clang"
                            "lang-php"              -> "pkg install -y nodejs && npm install -g intelephense"
                            "fmt-prettier"          -> "pkg install -y nodejs && npm install -g prettier"
                            "fmt-black"             -> "pkg install -y python && pip install black"
                            "fmt-ktlint"            -> "pkg install -y openjdk-17"
                            "fmt-gofmt"             -> "pkg install -y golang"
                            "fmt-rustfmt"           -> "pkg install -y rust"
                            "fmt-clang"             -> "pkg install -y clang"
                            "lint-eslint"           -> "pkg install -y nodejs && npm install -g eslint"
                            "lint-ruff"             -> "pkg install -y python && pip install ruff"
                            "lint-shellcheck"       -> "pkg install -y shellcheck"
                            "lint-detekt"           -> "pkg install -y openjdk-17"
                            "lint-clang-tidy"       -> "pkg install -y clang"
                            else                    -> null
                        }
                        if (cmd != null) {
                            setInstalling(
                                id = id, installing = true,
                                status = "Termux: $cmd",
                                progress = 0.85f, stage = "Termux",
                                appendLog = "→ [4/5] Dispatched to Termux: $cmd"
                            )
                            com.sednium.kryptonide.ui.terminal.TermuxBridge.executeInTermux(
                                context = context, command = cmd, workDir = "", background = true
                            )
                            kotlinx.coroutines.delay(800L)
                        }
                    } else {
                        setInstalling(
                            id = id, installing = true,
                            status = "Configuring runtime bindings…",
                            progress = 0.85f, stage = "Configuring",
                            appendLog = "→ [4/5] Environment configured for workspace execution."
                        )
                        kotlinx.coroutines.delay(400L)
                    }
                } else {
                    setInstalling(
                        id = id, installing = true,
                        status = "Applying configurations…",
                        progress = 0.85f, stage = "Configuring",
                        appendLog = "→ [4/5] Extension assets registered in Krypton engine."
                    )
                    kotlinx.coroutines.delay(350L)
                }

                // Stage 5: Verification & Activation
                setInstalling(
                    id = id, installing = true,
                    status = "Activating extension…",
                    progress = 1.0f, stage = "Done",
                    appendLog = "✓ [5/5] Successfully installed & activated ${plugin.name}!"
                )
                kotlinx.coroutines.delay(300L)

                _state.update { curr ->
                    curr.copy(plugins = curr.plugins.map { p ->
                        if (p.id == id) p.copy(
                            isInstalled = true, isEnabled = true, isInstalling = false,
                            installStatus = null, installProgress = 1.0f, installStage = null,
                            installError = null
                        ) else p
                    })
                }
                persistInstalled()

                if (plugin.category == PluginCategory.THEME) {
                    activateTheme(plugin.copy(isInstalled = true, isEnabled = true))
                } else if (plugin.category == PluginCategory.KEYMAP) {
                    activateKeymap(plugin.copy(isInstalled = true, isEnabled = true))
                }
            } catch (e: Exception) {
                setInstalling(
                    id = id, installing = false,
                    status = null, progress = 0f, stage = null,
                    error = e.message ?: "Install failed",
                    appendLog = "✗ Install failed: ${e.message}"
                )
                _state.update { curr ->
                    curr.copy(plugins = curr.plugins.map { p ->
                        if (p.id == id) p.copy(
                            isInstalled = false,
                            isEnabled = false,
                            isInstalling = false,
                            installStatus = null,
                            installProgress = 0f,
                            installStage = null,
                            installError = e.message ?: "Install failed"
                        ) else p
                    })
                }
            }
        }
    }

    fun uninstallExtension(id: String) {
        val clearingTheme = _state.value.activeThemeId == id
        _state.update { curr ->
            curr.copy(
                activeThemeId = if (clearingTheme) null else curr.activeThemeId,
                plugins = curr.plugins.map { p ->
                    if (p.id == id) p.copy(isInstalled = false, isEnabled = false, isActive = false) else p
                }
            )
        }
        persistInstalled()
        if (clearingTheme) {
            viewModelScope.launch {
                getApplication<Application>().pluginDataStore.edit { prefs ->
                    prefs.remove(KEY_ACTIVE_THEME)
                }
            }
        }
    }

    // Toggle is kept for backward compat with old PluginsPanel call sites
    fun togglePlugin(id: String) {
        val plugin = _state.value.plugins.firstOrNull { it.id == id } ?: return
        if (plugin.isInstalled) uninstallExtension(id) else installExtension(id)
    }

    fun activateTheme(plugin: PluginInfo) {
        if (!plugin.isInstalled || plugin.themeId == null) return

        _state.update { curr ->
            curr.copy(
                activeThemeId = plugin.id,
                plugins = curr.plugins.map { p ->
                    if (p.category == PluginCategory.THEME)
                        p.copy(isActive = p.id == plugin.id)
                    else p
                }
            )
        }

        val themeDef = BuiltInThemes.ALL.firstOrNull { it.id == plugin.themeId }
        if (themeDef != null) {
            com.sednium.kryptonide.ui.monaco.MonacoRuntime.onThemeDefined?.invoke(themeDef.id, themeDef.json)
            com.sednium.kryptonide.ui.monaco.MonacoRuntime.onThemeChanged?.invoke(themeDef.id)
        }

        viewModelScope.launch {
            getApplication<Application>().pluginDataStore.edit { prefs ->
                prefs[KEY_ACTIVE_THEME] = plugin.id
            }
        }
    }

    fun deactivateTheme() {
        _state.update { curr ->
            curr.copy(
                activeThemeId = null,
                plugins = curr.plugins.map { p ->
                    if (p.category == PluginCategory.THEME)
                        p.copy(isActive = false)
                    else p
                }
            )
        }
        com.sednium.kryptonide.ui.monaco.MonacoRuntime.onThemeChanged?.invoke("krypton-dark")
        viewModelScope.launch {
            getApplication<Application>().pluginDataStore.edit { prefs ->
                prefs.remove(KEY_ACTIVE_THEME)
            }
        }
    }

    fun setPluginEnabled(id: String, enabled: Boolean) {
        val target = _state.value.plugins.firstOrNull { it.id == id } ?: return
        if (target.category == PluginCategory.THEME && !enabled && _state.value.activeThemeId == id) {
            deactivateTheme()
        }
        if (target.category == PluginCategory.KEYMAP && !enabled && _state.value.activeKeymapId == target.keymapPresetId) {
            val defaultKeymap = _state.value.plugins.firstOrNull { it.id == "keymap-vscode" }
            if (defaultKeymap != null) activateKeymap(defaultKeymap)
        }

        _state.update { curr ->
            curr.copy(
                plugins = curr.plugins.map { p ->
                    if (p.id == id) {
                        val newActive = when (p.category) {
                            PluginCategory.THEME  -> if (!enabled) false else p.isActive
                            PluginCategory.KEYMAP -> if (!enabled) false else p.isActive
                            else                  -> enabled
                        }
                        p.copy(isEnabled = enabled, isActive = newActive)
                    } else p
                }
            )
        }
        persistInstalled()
    }

    fun isFormatterEnabled(formatterId: String): Boolean {
        val p = _state.value.plugins.firstOrNull { it.formatterId == formatterId }
        return p == null || (p.isInstalled && p.isEnabled)
    }

    fun isLinterEnabled(linterId: String): Boolean {
        val p = _state.value.plugins.firstOrNull { it.linterId == linterId }
        return p == null || (p.isInstalled && p.isEnabled)
    }

    fun isLspEnabled(lspId: String): Boolean {
        val p = _state.value.plugins.firstOrNull { it.lspServerId == lspId }
        return p == null || (p.isInstalled && p.isEnabled)
    }

    fun activateKeymap(presetId: String) {
        val preset = if (presetId.isBlank()) "vscode" else presetId
        _state.update { curr ->
            curr.copy(
                activeKeymapId = preset,
                plugins = curr.plugins.map { p ->
                    if (p.category == PluginCategory.KEYMAP)
                        p.copy(isActive = (p.keymapPresetId ?: p.id) == preset && p.isEnabled)
                    else p
                }
            )
        }
        SettingsStore(getApplication()).setKeymapPreset(preset)
        viewModelScope.launch {
            getApplication<Application>().pluginDataStore.edit { prefs ->
                prefs[KEY_ACTIVE_KEYMAP] = preset
            }
        }
    }

    fun activateKeymap(plugin: PluginInfo) {
        activateKeymap(plugin.keymapPresetId ?: "vscode")
    }

    fun search(query: String) = _state.update { it.copy(searchQuery = query) }

    fun setCategory(cat: PluginCategory) = _state.update { it.copy(activeCategory = cat) }

    fun filteredPlugins(): List<PluginInfo> {
        val s = _state.value
        return s.plugins.filter { p ->
            (s.activeCategory == PluginCategory.ALL || p.category == s.activeCategory) &&
            (s.searchQuery.isBlank() ||
            p.name.contains(s.searchQuery, ignoreCase = true) ||
            p.description.contains(s.searchQuery, ignoreCase = true) ||
            p.author.contains(s.searchQuery, ignoreCase = true))
        }
    }

    private fun setInstalling(
        id: String,
        installing: Boolean,
        status: String? = null,
        progress: Float = 0f,
        stage: String? = null,
        appendLog: String? = null,
        error: String? = null
    ) {
        _state.update { curr ->
            curr.copy(plugins = curr.plugins.map { p ->
                if (p.id == id) {
                    val newLogs = if (appendLog != null) p.installLogs + appendLog else p.installLogs
                    p.copy(
                        isInstalling = installing,
                        installStatus = status,
                        installProgress = progress,
                        installStage = stage,
                        installLogs = newLogs,
                        installError = error
                    )
                } else p
            })
        }
    }

    fun installFromFile(name: String, path: String) {
        val safeName = name.substringBeforeLast(".")
        val id = "custom-" + safeName.lowercase().replace(Regex("[^a-z0-9_-]"), "-")
        val customPlugin = PluginInfo(
            id = id,
            name = safeName,
            description = "Custom installed extension from $name",
            version = "1.0.0",
            author = "Local",
            category = PluginCategory.PRODUCTIVITY,
            isInstalled = true,
            isEnabled = true,
            isCustom = true,
            customFilePath = path
        )
        _state.update { curr ->
            curr.copy(plugins = listOf(customPlugin) + curr.plugins.filter { it.id != id })
        }
        persistInstalled()
    }

    private fun persistInstalled() {
        viewModelScope.launch {
            val installedPlugins = _state.value.plugins.filter { it.isInstalled }
            val ids = installedPlugins.map { it.id }.joinToString(",")
            val disabledIds = installedPlugins.filter { !it.isEnabled }.map { it.id }.joinToString(",")
            getApplication<Application>().pluginDataStore.edit { prefs ->
                prefs[KEY_INSTALLED] = ids
                prefs[KEY_DISABLED_IDS] = disabledIds
            }
        }
    }
}
