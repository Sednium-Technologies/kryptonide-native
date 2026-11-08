@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sednium.kryptonide.ui.editor

import androidx.activity.compose.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import com.composables.icons.lucide.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import com.sednium.kryptonide.utils.KryptonHaptics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import com.sednium.kryptonide.data.model.FileNode
import com.sednium.kryptonide.data.model.FileType
import com.sednium.kryptonide.data.model.ProjectTemplate
import com.sednium.kryptonide.ui.ai.AiViewModel
import com.sednium.kryptonide.ui.git.GitPanel
import com.sednium.kryptonide.ui.git.GitViewModel
import com.sednium.kryptonide.ui.plugins.PluginManager
import com.sednium.kryptonide.ui.plugins.PluginsPanel
import com.sednium.kryptonide.ui.settings.SettingsScreen
import com.sednium.kryptonide.ui.settings.SettingsViewModel
import com.sednium.kryptonide.ui.terminal.TerminalPanel
import com.sednium.kryptonide.ui.render.RenderEngine
import com.sednium.kryptonide.ui.render.RenderPanel
import com.sednium.kryptonide.ui.render.RenderStatus
import com.sednium.kryptonide.ui.render.RenderTemplates
import com.sednium.kryptonide.ui.render.KryptonBeamDialog
import com.sednium.kryptonide.server.KryptonBeamServer
import com.sednium.kryptonide.ui.settings.FontManager
import com.sednium.kryptonide.ui.settings.FontSelection
import com.sednium.kryptonide.ui.theme.LocalKryptonColors
import com.sednium.kryptonide.ui.theme.UrbanistFontFamily
import com.sednium.kryptonide.ui.build.BuildPanel
import com.sednium.kryptonide.ui.lsp.LspManager
import kotlinx.coroutines.*

// ─── Design tokens ────────────────────────────────────────────────────────────
val BgDark: Color @Composable get() = LocalKryptonColors.current.Background
val SurfaceDark: Color @Composable get() = LocalKryptonColors.current.Surface
val BorderDark: Color @Composable get() = LocalKryptonColors.current.Border
val TextPrimary: Color @Composable get() = LocalKryptonColors.current.OnBackground
val TextMuted: Color @Composable get() = LocalKryptonColors.current.Muted
val AccentColor: Color @Composable get() = LocalKryptonColors.current.Primary
val AccentLight: Color @Composable get() = LocalKryptonColors.current.Secondary
// Content color for anything filled with AccentColor/AccentLight — NOT a fixed
// white anymore, since Primary/Secondary now theme-flip (Ivory fill in dark
// mode, Charcoal fill in light mode) instead of being a separate accent hue.
val OnAccentColor: Color @Composable get() = LocalKryptonColors.current.OnPrimary
val OnAccentLight: Color @Composable get() = LocalKryptonColors.current.OnSecondary
val GreenRun: Color @Composable get() = LocalKryptonColors.current.Success

// Android templates — Build tab runs via GitHub Actions, see BuildPanel
private val ANDROID_TEMPLATES = setOf(
    ProjectTemplate.KOTLIN,
    ProjectTemplate.JAVA,
    ProjectTemplate.COMPOSE,
    ProjectTemplate.ANDROID_KOTLIN,
    ProjectTemplate.ANDROID_JAVA,
    ProjectTemplate.ANDROID_COMPOSE
)

// ─── EditorScreen ─────────────────────────────────────────────────────────────
@Composable
fun EditorScreen(
    editorVm:     EditorViewModel,
    renderEngine: RenderEngine    = viewModel(),
                 fontManager:  FontManager     = viewModel(),
                 aiVm:         AiViewModel     = viewModel(),
                 gitVm:        GitViewModel    = viewModel(),
                 settingsVm:   SettingsViewModel = viewModel(),
                 pluginManager: PluginManager  = viewModel(),
                 projectName:  String          = "KRYPTON MOBILE",
                 projectDir:   String          = "",
                 template:     ProjectTemplate = ProjectTemplate.BLANK,
                 onBack:       () -> Unit
) {
    val editorState  by editorVm.state.collectAsStateWithLifecycle()
    val renderState  by renderEngine.state.collectAsStateWithLifecycle()

    val fileMap      = editorState.files
    val filesList    = fileMap.values.toList()
    val activeFileId = editorState.activeFileId

    var activeTab          by rememberSaveable { mutableStateOf("editor") }
    var activeFileName     by rememberSaveable { mutableStateOf("No file selected") }
    var saveStatus         by rememberSaveable { mutableStateOf("saved") }
    var showCommandPalette by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        com.sednium.kryptonide.utils.NavigationBus.events.collect { tab ->
            activeTab = tab
        }
    }

    // Detect effective template from both the stored template AND the
    // files present or active file — so an HTML, Markdown, JSON, or JSX file in ANY project still previews.
    val effectiveTemplate: ProjectTemplate = remember(template, filesList, activeFileName) {
        val activeExt = activeFileName.substringAfterLast('.', "").lowercase()
        when {
            // Priority 1: If an instant-previewable file is actively open, target that format
            activeExt in setOf("html", "htm") -> ProjectTemplate.HTML
            activeExt in setOf("md", "markdown") -> ProjectTemplate.MARKDOWN
            activeExt == "json" -> ProjectTemplate.JSON
            activeExt in setOf("jsx", "tsx") && template in setOf(ProjectTemplate.REACT, ProjectTemplate.REACT_VITE, ProjectTemplate.NEXTJS) -> template
            activeExt in setOf("jsx", "tsx") -> ProjectTemplate.REACT

            // Priority 2: Explicit non-BLANK template always wins — respects user's choice
            template != ProjectTemplate.BLANK -> template

            // Priority 3: Auto-detect from files when template is BLANK
            filesList.any { it.name == "AndroidManifest.xml" } -> ProjectTemplate.ANDROID_KOTLIN
            filesList.any { it.name == "vite.config.js" || it.name == "vite.config.ts" } -> ProjectTemplate.REACT_VITE
            filesList.any { it.name == "next.config.js" || it.name == "next.config.mjs" || it.name == "next.config.ts" } -> ProjectTemplate.NEXTJS
            filesList.any { it.name == "index.html" } &&
                filesList.any { it.name.endsWith(".jsx") || it.name.endsWith(".tsx") } -> ProjectTemplate.REACT
            filesList.any { it.name == "index.html" } -> ProjectTemplate.VANILLA
            filesList.any { it.name.endsWith(".jsx") || it.name.endsWith(".tsx") } -> ProjectTemplate.REACT
            filesList.any { it.name.endsWith(".md") || it.name.endsWith(".markdown") } -> ProjectTemplate.MARKDOWN
            filesList.any { it.name.endsWith(".json") } && filesList.size <= 3 -> ProjectTemplate.JSON
            filesList.any { it.name == "main.py" || it.name == "app.py" } -> ProjectTemplate.PYTHON
            filesList.any { it.name == "requirements.txt" } &&
                filesList.any { it.content?.contains("fastapi") == true } -> ProjectTemplate.FASTAPI
            filesList.any { it.name == "package.json" &&
                it.content?.contains("\"express\"") == true } -> ProjectTemplate.NODE_EXPRESS
            filesList.any { it.name in setOf("index.js", "server.js", "app.js") } -> ProjectTemplate.NODE
            filesList.any { it.name == "index.php" } || filesList.any { it.name.endsWith(".php") } -> ProjectTemplate.PHP
            filesList.any { it.name == "go.mod" } || filesList.any { it.name.endsWith(".go") } -> ProjectTemplate.GO
            filesList.any { it.name == "Cargo.toml" } || filesList.any { it.name.endsWith(".rs") } -> ProjectTemplate.RUST
            filesList.any { it.name.endsWith(".cpp") || it.name.endsWith(".cc") || it.name.endsWith(".cxx") } -> ProjectTemplate.CPP
            filesList.any { it.name.endsWith(".cs") } -> ProjectTemplate.CSHARP
            filesList.any { it.name.endsWith(".c") } -> ProjectTemplate.C
            filesList.any { it.name.endsWith(".kt") } -> ProjectTemplate.KOTLIN
            filesList.any { it.name.endsWith(".java") } -> ProjectTemplate.JAVA
            else -> ProjectTemplate.BLANK
        }
    }

    // Single source of truth for what the Run button should do.
    val runAction = RenderTemplates.runActionFor(effectiveTemplate, filesList, activeFileName)
    val isAndroid = runAction == RenderTemplates.RunAction.BUILD_APK
    val hasRender = runAction == RenderTemplates.RunAction.WEB_PREVIEW
        || runAction == RenderTemplates.RunAction.SHELL_RUN
    val isDisabled = runAction == RenderTemplates.RunAction.DISABLED

    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var showTerminalSheet by remember { mutableStateOf(false) }
    var showBeamDialog by remember { mutableStateOf(false) }
    @OptIn(ExperimentalMaterial3Api::class)
    val terminalSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun dismissTerminalSheet() {
        scope.launch {
            terminalSheetState.hide()
        }.invokeOnCompletion {
            if (!terminalSheetState.isVisible) {
                showTerminalSheet = false
            }
        }
    }

    val isEditorReady = remember { mutableStateOf(false) }
    val pendingFile   = remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var lastLoadedFileId by rememberSaveable { mutableStateOf<String?>(null) }

    // ─── Real language server support (autocomplete/errors) ────────────────
    val lspProjectRoot = remember(projectDir, projectName) {
        projectDir.ifBlank { "${com.sednium.kryptonide.utils.StorageUtils.getProjectsDir().absolutePath}/$projectName" }
    }
    val lspManager = remember(lspProjectRoot) { LspManager(context, scope, lspProjectRoot) }

    fun relativePathOf(fileId: String?): String {
        val id = fileId ?: return "untitled"
        val currentMap = editorVm.state.value.files
        val node = currentMap[id] ?: return "untitled"
        val parts = mutableListOf(node.name)
        var parentId = node.parentId
        while (parentId != null) {
            val parent = currentMap[parentId] ?: break
            if (parent.parentId == null && parent.name.isBlank()) break // synthetic root sentinel
            parts.add(0, parent.name)
            parentId = parent.parentId
        }
        return parts.joinToString("/")
    }

    fun currentLspUri(): String = "file://$lspProjectRoot/${relativePathOf(editorVm.state.value.activeFileId)}"

    DisposableEffect(lspManager) {
        onDispose { lspManager.shutdown() }
    }

    // Native editing surface — see SoraEditorHost.kt.
    // NOTE: declared as `lateinit var` (not `val = remember { ... }`) because the
    // onSaveTriggered lambda below needs to call back into `soraHost` itself —
    // and a val isn't in scope inside its own initializer, even from a nested
    // lambda that only runs later. `lateinit var` puts the name in scope first;
    // the lambda only executes after assignment completes, so this is safe.
    lateinit var soraHost: SoraEditorHost
    soraHost = remember {
        SoraEditorHost(
            context = context,
            scope   = scope,
            onContentChanged = { content ->
                val currentId = editorVm.state.value.activeFileId
                if (currentId != null) editorVm.updateContent(currentId, content)
            },
            onSaveTriggered = { content ->
                val id = editorVm.state.value.activeFileId
                if (id != null) {
                    // Background auto-save: persist buffer to disk without calling soraHost.setContent(),
                    // which would reset the cursor, completion popup, and undo stack while typing.
                    editorVm.updateContent(id, content)
                    editorVm.saveFileToDisk(id)
                    saveStatus = "saved"
                    val relPath = relativePathOf(id)
                    val ext = relPath.substringAfterLast('.', "")
                    val linterConfig = com.sednium.kryptonide.ui.lint.LinterRegistry.forExtension(ext)
                    if (linterConfig != null && pluginManager.isLinterEnabled(linterConfig.id)) {
                        val fullPath = "$lspProjectRoot/$relPath"
                        scope.launch(Dispatchers.IO) {
                            val markers = com.sednium.kryptonide.ui.lint.LinterRunner.lint(context, linterConfig, fullPath)
                            if (markers.length() > 0) {
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    soraHost.setDiagnostics(markers)
                                }
                            }
                        }
                    }
                }
            },
            onReady = {
                isEditorReady.value = true
            },
            onFontSizeChanged = { newSizeSp ->
                settingsVm.setFontSize(Math.round(newSizeSp))
            },
            onCursorChanged = { line, col ->
                editorVm.setCursor(line, col)
            },
            lspManager  = lspManager,
            uriProvider = { currentLspUri() }
        )
    }

    LaunchedEffect(Unit) {
        editorVm.soraHost = soraHost
    }

    val settingsState by settingsVm.state.collectAsState()
    val pluginState by pluginManager.state.collectAsState()

    fun formatActiveFile(saveAfter: Boolean = false) {
        val id = editorVm.state.value.activeFileId ?: return
        scope.launch {
            val ext = relativePathOf(id).substringAfterLast('.', "")
            val langOrExt = ext.ifBlank { editorVm.state.value.files[id]?.language ?: "" }
            val config = com.sednium.kryptonide.ui.format.FormatterRegistry.forExtension(ext)
            val content = soraHost.getContent()
            val externalFormatted = if (config != null && pluginManager.isFormatterEnabled(config.id)) {
                com.sednium.kryptonide.ui.format.FormatterRunner.format(context, config, content, ext)
            } else null
            val formatted = externalFormatted ?: BuiltInCodeFormatter.format(content, langOrExt)
            if (formatted != null) {
                soraHost.setContent(formatted)
                editorVm.updateContent(id, formatted)
            } else {
                editorVm.updateContent(id, content)
            }
            if (saveAfter) {
                editorVm.saveFileToDisk(id)
                saveStatus = "saved"
            }
        }
    }

    LaunchedEffect(editorVm, settingsState.enablePrettier) {
        soraHost.onSaveShortcut = {
            val activeId = editorVm.state.value.activeFileId
            if (activeId != null) {
                if (settingsState.enablePrettier) {
                    formatActiveFile(saveAfter = true)
                } else {
                    editorVm.updateContent(activeId, soraHost.getContent())
                    editorVm.saveFileToDisk(activeId)
                    saveStatus = "saved"
                }
            }
        }
        soraHost.onFindShortcut = {
            soraHost.beginSearch()
        }
    }

    LaunchedEffect(lspManager, soraHost, pluginState.plugins) {
        lspManager.isLspEnabled = { serverId -> pluginManager.isLspEnabled(serverId) }
        lspManager.onDiagnostics = { _, markersJson ->
            scope.launch(Dispatchers.Main) {
                runCatching { soraHost.setDiagnostics(org.json.JSONArray(markersJson)) }
            }
        }
        lspManager.onStatusChanged = { _, _, _ ->
            // Silent status handling — no annoying toast popups on file open
        }
    }

    // Handle device back navigation
    BackHandler(enabled = true) {
        when {
            showTerminalSheet     -> dismissTerminalSheet()
            drawerState.isOpen    -> scope.launch { drawerState.close() }
            activeTab != "editor" -> activeTab  = "editor"
            else                  -> onBack()
        }
    }

    LaunchedEffect(activeFileId, fileMap[activeFileId]?.name, fileMap[activeFileId]?.isUnsaved) {
        val file = fileMap[activeFileId]
        if (file != null) {
            activeFileName = file.name
            saveStatus     = if (file.isUnsaved == true) "unsaved" else "saved"
        } else {
            activeFileName = "No file selected"
            saveStatus     = "idle"
        }
    }

    val hasActiveFileLoaded = fileMap.containsKey(activeFileId)
    LaunchedEffect(activeFileId, isEditorReady.value, hasActiveFileLoaded) {
        if (!isEditorReady.value) return@LaunchedEffect

        val currentFiles = editorVm.state.value.files
        val file = currentFiles[activeFileId]
        if (file == null) {
            if (lastLoadedFileId != null) {
                soraHost.loadFile("", "plaintext", "")
            }
            lastLoadedFileId = null
            return@LaunchedEffect
        }

        if (lastLoadedFileId != null && lastLoadedFileId != activeFileId) {
            val prevId = lastLoadedFileId
            val prevFile = currentFiles[prevId]
            if (prevId != null && prevFile?.isUnsaved == true) {
                editorVm.updateContent(prevId, soraHost.getContent())
                editorVm.saveFileToDisk(prevId)
            }
        }

        if (lastLoadedFileId != activeFileId) {
            val contentToLoad = file.content ?: ""
            val lang = file.language.ifEmpty { "plaintext" }
            soraHost.loadFile(contentToLoad, lang, file.name)
            lastLoadedFileId = activeFileId
            activeTab = "editor"
        }
    }


    // ── Settings application ─────────────────────────────────────────────────
    // Font family, font size, word wrap, and theme (both Settings' base theme
    // and Plugins' marketplace themes) are all wired below via SoraEditorHost.

    LaunchedEffect(settingsState.keymapPreset, pluginState.activeKeymapId) {
        soraHost.keymapPreset = pluginState.activeKeymapId ?: settingsState.keymapPreset
    }

    LaunchedEffect(settingsState.enableEmmet) {
        SpckCompletionEngine.emmetEnabled = settingsState.enableEmmet
    }
    val fontSelection by fontManager.selectedFont.collectAsStateWithLifecycle()

    // Apply the selected font family — real native Typeface, no CSS/base64
    // injection needed (that was a Monaco-only detour).
    LaunchedEffect(fontSelection, isEditorReady.value) {
        if (!isEditorReady.value) return@LaunchedEffect
            val sel = fontSelection ?: return@LaunchedEffect
            val typeface = withContext(Dispatchers.IO) {
                runCatching {
                    if (sel.font.isCustom) {
                        val path = sel.font.filePath ?: return@runCatching null
                        android.graphics.Typeface.createFromFile(java.io.File(path))
                    } else {
                        android.graphics.Typeface.createFromAsset(context.assets, "fonts/${sel.font.fileName}")
                    }
                }.getOrNull()
            }
            if (typeface != null) soraHost.applyTypeface(typeface)
    }

    // Apply fontSize to the editor when it changes in Settings
    LaunchedEffect(settingsState.fontSize, isEditorReady.value) {
        if (!isEditorReady.value) return@LaunchedEffect
            soraHost.setFontSize(settingsState.fontSize.toFloat())
    }

    // Apply wordWrap to the editor when it changes in Settings
    LaunchedEffect(settingsState.wordWrap, isEditorReady.value) {
        if (!isEditorReady.value) return@LaunchedEffect
            soraHost.setWordWrap(settingsState.wordWrap)
    }

    // Apply lineNumbers to the editor when it changes in Settings — this toggle
    // existed in the UI but was never actually connected to the editor.
    LaunchedEffect(settingsState.lineNumbers, isEditorReady.value) {
        if (!isEditorReady.value) return@LaunchedEffect
            soraHost.setLineNumbersEnabled(settingsState.lineNumbers)
    }

    // Apply indentationGuides to the editor when it changes in Settings — same
    // gap as lineNumbers above.
    LaunchedEffect(settingsState.indentationGuides, isEditorReady.value) {
        if (!isEditorReady.value) return@LaunchedEffect
            soraHost.setIndentationGuidesEnabled(settingsState.indentationGuides)
    }

    // Initialize AI ViewModel with editorViewModel and gitViewModel context
    LaunchedEffect(Unit) {
        aiVm.init(editorVm, gitVm)
    }

    // Apply editor theme: marketplace/textmate theme wins when active, otherwise fall back to settings theme
    LaunchedEffect(pluginState.activeThemeId, settingsState.theme, isEditorReady.value) {
        if (!isEditorReady.value) return@LaunchedEffect
        val activeId = pluginState.activeThemeId
        if (activeId != null) {
            val plugin = pluginState.plugins.firstOrNull { it.id == activeId }
            val textMateThemeId = plugin?.themeId ?: activeId.removePrefix("theme-")
            if (ThemeAssetManager.AVAILABLE_THEMES.contains(textMateThemeId)) {
                soraHost.applyTextMateTheme(textMateThemeId)
                return@LaunchedEffect
            }
            val def = com.sednium.kryptonide.ui.plugins.BuiltInThemes.ALL.firstOrNull {
                it.id == activeId || it.id == activeId.removePrefix("theme-") || "theme-${it.id}" == activeId
            }
            if (def != null) {
                soraHost.applyMarketplaceTheme(def.json)
                return@LaunchedEffect
            }
        }
        soraHost.applyBaseTheme(settingsState.theme)
    }

    val renderBadgeColor: Color? = when (renderState.status) {
        RenderStatus.RUNNING, RenderStatus.PREPARING -> AccentLight
        RenderStatus.PREVIEW_READY, RenderStatus.SUCCESS -> GreenRun
        RenderStatus.FAILED -> LocalKryptonColors.current.Error
        else -> null
    }

    fun handleNavTab(tab: String) {
        if (tab != "editor") {
            soraHost.hidePopupsAndClearFocus()
        }
        when (tab) {
            "github" -> {
                activeTab = "github"
            }
            "run" -> {
                activeTab = "run"
                renderEngine.run(effectiveTemplate, projectName, filesList, activeFileName)
            }
            else -> activeTab = tab
        }
    }

    // Dismiss floating Sora Editor completion popups & keyboard whenever leaving the editor tab
    // or opening overlay drawers/sheets/palettes, and force-close the drawer in Preview mode.
    LaunchedEffect(activeTab, drawerState.isOpen, showCommandPalette, showTerminalSheet) {
        if (activeTab != "editor" || drawerState.isOpen || showCommandPalette || showTerminalSheet) {
            soraHost.hidePopupsAndClearFocus()
        }
        if (activeTab == "run" && drawerState.isOpen) {
            drawerState.close()
        }
    }

    ModalNavigationDrawer(
        drawerState     = drawerState,
        gesturesEnabled = drawerState.isOpen,
        scrimColor      = Color.Black.copy(alpha = 0.55f),
        drawerContent   = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.background,
                drawerShape          = RectangleShape,
                modifier             = Modifier.width(300.dp).fillMaxHeight()
            ) {
                FileExplorerPanel(
                    nodes        = filesList,
                    activeFileId = activeFileId,
                    onClose      = { scope.launch { drawerState.close() } },
                    onFileSelect = { node ->
                        editorVm.openFile(node.id)
                        activeTab = "editor"
                        scope.launch { drawerState.close() }
                    },
                    onCreateNode = { parentId, name, isFolder ->
                        editorVm.createFile(
                            name, parentId,
                            if (isFolder) FileType.FOLDER else FileType.FILE
                        )
                    },
                    onDeleteNode = { node -> editorVm.deleteFile(node.id) },
                                  onRenameNode = { node, newName -> editorVm.renameFile(node.id, newName) },
                                  onMoveNode   = { node, newParentId -> editorVm.moveNode(node.id, newParentId) },
                                  onCopyNode   = { node, newParentId -> editorVm.copyNode(node.id, newParentId) },
                                  pathFor      = { node -> editorVm.pathFor(node.id) },
                                  onSendToAi = { node ->
                                      scope.launch { drawerState.close() }
                                      activeTab = "ai"
                                      val path = editorVm.pathFor(node.id).ifBlank { node.name }
                                      val raw = (node.content ?: editorVm.getRawFileContent(path) ?: "").take(12_000)
                                      val contextWithFile = "${LarrySystemPrompt.TEXT}\n\n<tagged_files>\n<file path=\"$path\">\n$raw\n</file>\n</tagged_files>"
                                      aiVm.sendMessage("Look at $path and explain what it does.", contextWithFile)
                                  }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                if (activeTab == "editor") {
                    EditorTopBar(
                        projectName      = projectName,
                        activeFileName   = activeFileName,
                        saveStatus       = saveStatus,
                        runAction        = runAction,
                        renderStatus     = renderState.status,
                        openFiles        = editorState.openFileIds.mapNotNull { fileMap[it] },
                        activeFileId     = activeFileId,
                        onOpenFileSelect = { id -> editorVm.openFile(id) },
                        onCloseFile      = { id -> editorVm.closeFile(id) },
                        onCloseAllFiles  = { editorState.openFileIds.forEach { editorVm.closeFile(it) } },
                        onSaveClick      = {
                            activeFileId?.let { id ->
                                if (settingsState.enablePrettier) {
                                    formatActiveFile(saveAfter = true)
                                } else {
                                    editorVm.updateContent(id, soraHost.getContent())
                                    editorVm.saveFileToDisk(id)
                                    saveStatus = "saved"
                                }
                            }
                        },
                        onBack = {
                            activeFileId?.let { id ->
                                if (fileMap[id]?.isUnsaved == true) {
                                    editorVm.updateContent(id, soraHost.getContent())
                                    editorVm.saveFileToDisk(id)
                                }
                            }
                            onBack()
                        },
                        onTerminal       = { showTerminalSheet = true },
                        onRun = {
                            editorVm.saveAllFiles()
                            when (runAction) {
                                RenderTemplates.RunAction.BUILD_APK    -> {
                                    activeTab = "build"
                                }
                                RenderTemplates.RunAction.WEB_PREVIEW,
                                RenderTemplates.RunAction.SHELL_RUN    -> handleNavTab("run")
                                RenderTemplates.RunAction.DISABLED     -> {
                                    showTerminalSheet = true
                                }
                            }
                        },
                        onMenuClick = {
                            scope.launch {
                                if (drawerState.isOpen) drawerState.close() else drawerState.open()
                            }
                        },
                        onCommandPalette = { showCommandPalette = true },
                        onBeam = {
                            if (!KryptonBeamServer.isRunning) {
                                val projectDir = java.io.File(com.sednium.kryptonide.utils.StorageUtils.getProjectsDir(), projectName)
                                KryptonBeamServer.start(
                                    htmlProvider = { renderEngine.state.value.previewHtml },
                                    projectDirProvider = { projectDir }
                                )
                            }
                            showBeamDialog = true
                        }
                    )
                }
            },
            bottomBar = {
                val density = LocalDensity.current
                val isKeyboardOpen = WindowInsets.ime.getBottom(density) > 0 || WindowInsets.ime.asPaddingValues().calculateBottomPadding() > 0.dp
                if (activeTab != "run" && activeTab != "build" && !isKeyboardOpen && !showTerminalSheet) {
                    EditorBottomNav(
                        activeTab      = activeTab,
                        isGitHubOpen   = activeTab == "github",
                        runAction      = runAction,
                        renderBadge    = renderBadgeColor,
                        onTabSelected  = { tab -> handleNavTab(tab) }
                    )
                }
            },
            contentWindowInsets = WindowInsets.statusBars,
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->

            Box(
                modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
            ) {
                // Editor surface & Quick-Symbol Bar arranged in a strict non-overlapping Column
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(x = if (activeTab == "editor") 0.dp else 4000.dp)
                        .imePadding()
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        AndroidView(
                            factory  = { soraHost.createView(it) },
                            update   = { editor ->
                                if (editor.isWordwrap != settingsState.wordWrap) {
                                    editor.isWordwrap = settingsState.wordWrap
                                }
                                if (editor.isLineNumberEnabled != settingsState.lineNumbers) {
                                    editor.isLineNumberEnabled = settingsState.lineNumbers
                                }
                                if (editor.isBlockLineEnabled != settingsState.indentationGuides) {
                                    editor.isBlockLineEnabled = settingsState.indentationGuides
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        if (activeFileId == null && activeTab == "editor") {
                            val kc = LocalKryptonColors.current
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(kc.Background),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Lucide.FileCode,
                                        contentDescription = null,
                                        tint = kc.Muted.copy(alpha = 0.45f),
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Text(
                                        text = "No File Open",
                                        color = kc.OnBackground,
                                        fontSize = 16.sp,
                                        fontFamily = UrbanistFontFamily,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Select or create a file from the File Explorer to start coding.",
                                        color = kc.Muted,
                                        fontSize = 13.sp,
                                        fontFamily = UrbanistFontFamily
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Button(
                                        onClick = { scope.launch { drawerState.open() } },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = kc.Primary,
                                            contentColor = kc.OnPrimary
                                        ),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Lucide.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Open File Explorer", fontSize = 13.sp, fontFamily = UrbanistFontFamily, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }

                    if (activeTab == "editor" && activeFileId != null) {
                        EditorShortcutsBar(
                            onInsert = { text -> soraHost.insertText(text) },
                            onUndo = { soraHost.undo() },
                            onRedo = { soraHost.redo() },
                            onMoveLeft = { soraHost.moveCursorLeft() },
                            onMoveRight = { soraHost.moveCursorRight() },
                            onCommandPalette = { showCommandPalette = true },
                            onEmmetExpand = if (settingsState.enableEmmet) {
                                {
                                    if (!soraHost.expandEmmetAtCursor()) {
                                        android.widget.Toast.makeText(context, "Nothing to expand", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else null,
                            hapticsEnabled = settingsState.hapticsEnabled
                        )
                    }
                }

                // Overlay panels — drawn on top of the WebView
                if (activeTab != "editor") {
                    Box(
                        modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                    ) {
                        Crossfade(targetState = activeTab, label = "TabTransition") { tab ->
                            when (tab) {
                                "run"      -> RenderPanel(
                                    vm             = renderEngine,
                                    template       = effectiveTemplate,
                                    projectName    = projectName,
                                    files          = filesList,
                                    activeFileName = activeFileName,
                                    projectDir     = lspProjectRoot,
                                    onBack         = { activeTab = "editor" }
                                )
                                "build"    -> BuildPanel(
                                    projectDir  = projectDir.ifBlank {
                                        "${com.sednium.kryptonide.utils.StorageUtils.getProjectsDir().absolutePath}/$projectName"
                                    },
                                    gitVm       = gitVm,
                                    onBack      = { activeTab = "editor" },
                                    onOpenGitHub = { activeTab = "github" }
                                )
                                "github"   -> GitPanel(
                                    gitVm = gitVm,
                                    onNavigateToAi = { prompt ->
                                        activeTab = "ai"
                                        aiVm.sendMessage(prompt, "")
                                    },
                                    onOpenFileInEditor = { relativePath ->
                                        activeTab = "editor"
                                        val fileMap = editorVm.state.value.files
                                        val matched = fileMap.values.firstOrNull {
                                            editorVm.buildPath(it, fileMap).equals(relativePath, ignoreCase = true) || it.name.equals(relativePath, ignoreCase = true)
                                        }
                                        if (matched != null) {
                                            editorVm.openFile(matched.id)
                                        }
                                    }
                                )
                                "ai"       -> LarryAIPanel(
                                    activeFileName   = activeFileName,
                                    aiVm             = aiVm,
                                    onApplyEdit      = { path, content ->
                                        val existing = editorVm.getRawFileContent(path) ?: ""
                                        val finalContent = PatchApplier.applyCodeOrPatch(existing, content)
                                        val applied = editorVm.writeFileContent(path, finalContent)
                                        if (applied) {
                                            val cleanPath = path.trim().removePrefix("./").removePrefix("/")
                                            val target = fileMap.values.firstOrNull { relativePathOf(it.id) == cleanPath || it.name == cleanPath }
                                            if (target != null) {
                                                editorVm.openFile(target.id)
                                                soraHost.setContent(finalContent)
                                            }
                                            android.widget.Toast.makeText(context, "Applied to $cleanPath", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onInsertAtCursor = { code ->
                                        soraHost.insertText(code)
                                        activeTab = "editor"
                                    },
                                    gitVm            = gitVm
                                )
                                "plugins"  -> PluginsPanel(manager = pluginManager)
                                "settings" -> SettingsScreen(
                                    viewModel     = settingsVm,
                                    fontManager   = fontManager,
                                    aiVm          = aiVm,
                                    pluginManager = pluginManager,
                                    onBack        = { activeTab = "editor" }
                                )
                            }
                        }
                    }
                }

                if (showTerminalSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showTerminalSheet = false },
                        sheetState       = terminalSheetState,
                        containerColor   = LocalKryptonColors.current.Background,
                        scrimColor       = Color.Black.copy(alpha = 0.5f),
                        dragHandle       = {
                            BottomSheetDefaults.DragHandle(
                                color = LocalKryptonColors.current.Border
                            )
                        },
                        shape            = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                        modifier         = Modifier.fillMaxSize()
                    ) {
                        TerminalPanel(
                            projectName = projectName,
                            initialDir  = lspProjectRoot,
                            onClose     = { dismissTerminalSheet() },
                            onOpenFile  = { filePath ->
                                val targetName = filePath.substringAfterLast("/")
                                val node = editorVm.state.value.files.values.firstOrNull {
                                    it.type == com.sednium.kryptonide.data.model.FileType.FILE &&
                                    (it.name == targetName || filePath.endsWith(it.name))
                                }
                                if (node != null) {
                                    editorVm.openFile(node.id)
                                    dismissTerminalSheet()
                                }
                            }
                        )
                    }
                }
            }
        }

        val currentWordAtCursor = remember(showCommandPalette) {
            if (showCommandPalette) soraHost.getWordAtCursor() else ""
        }
        val currentLineAtCursor = remember(showCommandPalette, editorState.cursorLine) {
            if (showCommandPalette) soraHost.getCurrentLine() else editorState.cursorLine
        }

        EditorCommandPalette(
            visible        = showCommandPalette,
            soraHost       = soraHost,
            pressedWord    = currentWordAtCursor,
            pressedLine    = currentLineAtCursor,
            onDismiss      = { showCommandPalette = false },
            onRun          = { handleNavTab("run") },
            onOpenTerminal = { showTerminalSheet = true },
            onOpenFiles    = { scope.launch { drawerState.open() } },
            onOpenAi       = { activeTab = "ai" },
            onAskAi        = { prompt ->
                activeTab = "ai"
                aiVm.sendMessage(prompt, LarrySystemPrompt.TEXT)
            },
            onOpenGit      = { activeTab = "github" },
            onOpenSearch   = {
                activeTab = "editor"
                soraHost.beginSearch()
            },
            onFormat       = { formatActiveFile() },
            onSave         = {
                activeFileId?.let { id ->
                    editorVm.updateContent(id, soraHost.getContent())
                    editorVm.saveFileToDisk(id)
                    saveStatus = "saved"
                }
            }
        )

        if (showBeamDialog) {
            KryptonBeamDialog(
                onDismiss = { showBeamDialog = false },
                onStopBeam = { KryptonBeamServer.stop() }
            )
        }
    }
}



// ─── Top bar ──────────────────────────────────────────────────────────────────
// ─── Editor keyboard shortcuts row ─────────────────────────────────────────────
// A row of tappable symbols that sits right above the on-screen keyboard —
// the mobile-code-editor convention for reaching characters that are buried
// behind extra taps on a stock keyboard. Ends with a command-palette trigger.
@Composable
fun EditorShortcutsBar(
    onInsert: (String) -> Unit,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onMoveLeft: () -> Unit = {},
    onMoveRight: () -> Unit = {},
    onCommandPalette: () -> Unit,
    onEmmetExpand: (() -> Unit)? = null,
    hapticsEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val kc = LocalKryptonColors.current
    val context = LocalContext.current
    val view = LocalView.current
    val performTick = {
        KryptonHaptics.tick(context, view)
    }
    val keys = listOf(
        "Tab" to "\t", "{" to "{", "}" to "}", "(" to "(", ")" to ")",
        ";" to ";", "\"" to "\"", "'" to "'", "<" to "<", ">" to ">",
        "=" to "=", "/" to "/", ":" to ":", "!" to "!", "&" to "&",
        "|" to "|", "#" to "#", "=>" to "=>", "[" to "[", "]" to "]", "_" to "_", "$" to "$"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(kc.Surface)
            .border(width = 0.5.dp, color = kc.Border)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Quick Undo / Redo / Cursor Left / Cursor Right pills
            val actionPills = listOf(
                "↶" to onUndo,
                "↷" to onRedo,
                "←" to onMoveLeft,
                "→" to onMoveRight
            )
            actionPills.forEach { (symbol, action) ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(kc.SurfaceVar)
                        .border(width = 0.5.dp, color = kc.Border.copy(alpha = 0.6f), shape = RoundedCornerShape(8.dp))
                        .clickable {
                            performTick()
                            action()
                        }
                        .defaultMinSize(minWidth = 32.dp, minHeight = 32.dp)
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        symbol,
                        color = kc.Primary,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            keys.forEach { (label, insertValue) ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(kc.SurfaceVar)
                        .border(width = 0.5.dp, color = kc.Border.copy(alpha = 0.6f), shape = RoundedCornerShape(8.dp))
                        .clickable {
                            performTick()
                            onInsert(insertValue)
                        }
                        .defaultMinSize(minWidth = 34.dp, minHeight = 32.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = kc.OnSurface,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (onEmmetExpand != null) {
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(kc.SurfaceVar)
                    .border(width = 0.5.dp, color = kc.Border.copy(alpha = 0.6f), shape = RoundedCornerShape(8.dp))
                .clickable {
                    performTick()
                    onEmmetExpand()
                }
                .defaultMinSize(minWidth = 44.dp, minHeight = 32.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Emmet",
                color = kc.Secondary,
                fontSize = 11.sp,
                fontFamily = UrbanistFontFamily,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    Spacer(Modifier.width(4.dp))
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(20.dp)
            .background(kc.Border)
    )
    Spacer(Modifier.width(4.dp))

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(kc.SurfaceVar)
            .border(width = 0.5.dp, color = kc.Border.copy(alpha = 0.6f), shape = RoundedCornerShape(8.dp))
            .clickable {
                performTick()
                onCommandPalette()
            }
            .size(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "⌘",
            color = kc.Primary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
    }
}

@Composable
fun EditorTopBar(
    projectName:      String,
    activeFileName:   String,
    saveStatus:       String,
    runAction:        RenderTemplates.RunAction,
    renderStatus:     RenderStatus,
    openFiles:        List<FileNode> = emptyList(),
    activeFileId:     String? = null,
    onOpenFileSelect: (String) -> Unit = {},
    onCloseFile:      (String) -> Unit = {},
    onCloseAllFiles:  () -> Unit = {},
    onSaveClick:      () -> Unit = {},
    onBack:           () -> Unit,
    onTerminal:       () -> Unit,
    onRun:            () -> Unit,
    onBeam:           (() -> Unit)? = null,
    onMenuClick:      () -> Unit,
    onCommandPalette: () -> Unit
) {
    val kc = LocalKryptonColors.current
    var showOpenFilesDropdown by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    val chevronRotation by animateFloatAsState(
        targetValue = if (showOpenFilesDropdown) 180f else 0f,
        label = "chevron_rotation"
    )
    val moreRotation by animateFloatAsState(
        targetValue = if (showMoreMenu) 90f else 0f,
        label = "more_rotation"
    )

    LaunchedEffect(openFiles.size) {
        if (openFiles.isEmpty()) {
            showOpenFilesDropdown = false
        }
    }

    val isUnsaved = saveStatus == "unsaved" || saveStatus.contains("unavailable")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .border(width = 1.dp, color = kc.Border)
            .statusBarsPadding()
            .heightIn(min = 52.dp)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back to Projects
        IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
            Icon(Lucide.ChevronLeft, "Back to Projects", tint = kc.Muted, modifier = Modifier.size(20.dp))
        }

        // Project Files Drawer
        IconButton(onClick = onMenuClick, modifier = Modifier.size(32.dp)) {
            Icon(Lucide.Menu, "Files Drawer", tint = kc.Muted, modifier = Modifier.size(19.dp))
        }

        Spacer(Modifier.width(2.dp))

        // Center Breadcrumb: Compact Stacked PROJECT / active_file ▾
        Box(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { if (openFiles.isNotEmpty()) showOpenFilesDropdown = !showOpenFilesDropdown }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    if (projectName.isNotBlank()) {
                        Text(
                            text = projectName.uppercase(),
                            color = kc.Secondary,
                            fontSize = 9.sp,
                            fontFamily = UrbanistFontFamily,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = activeFileName,
                        color = kc.OnBackground,
                        fontSize = 13.sp,
                        fontFamily = UrbanistFontFamily,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isUnsaved) {
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(kc.Warning)
                    )
                }
                Spacer(Modifier.width(2.dp))
                Icon(
                    Lucide.ChevronDown,
                    contentDescription = "Switch open file",
                    tint = if (openFiles.size > 1) kc.Primary else kc.Muted,
                    modifier = Modifier
                        .size(14.dp)
                        .rotate(chevronRotation)
                )
            }

            DropdownMenu(
                expanded = showOpenFilesDropdown && openFiles.isNotEmpty(),
                onDismissRequest = { showOpenFilesDropdown = false },
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .border(width = 0.5.dp, color = kc.Border, shape = RoundedCornerShape(12.dp))
                    .widthIn(min = 240.dp, max = 340.dp)
            ) {
                AnimatedVisibility(
                    visible = showOpenFilesDropdown,
                    enter = fadeIn(tween(180)) + expandVertically(tween(220), expandFrom = Alignment.Top) + scaleIn(tween(180), initialScale = 0.92f),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(120))
                ) {
                    Column {
                        Text(
                            "OPENED FILES (${openFiles.size})",
                            color = kc.Muted,
                            fontSize = 10.sp,
                            fontFamily = UrbanistFontFamily,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                        HorizontalDivider(color = kc.Border, thickness = 0.5.dp)

                        openFiles.forEach { fileNode ->
                            val isActive = fileNode.id == activeFileId
                            val (fileIcon, iconColor) = getFileIconAndColor(fileNode.name)
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = fileIcon,
                                            contentDescription = null,
                                            tint = iconColor,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        if (fileNode.isUnsaved) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(kc.Warning)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(
                                            text = fileNode.name,
                                            color = if (isActive) kc.Primary else kc.OnBackground,
                                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(kc.SurfaceVar.copy(alpha = 0.6f))
                                                .clickable {
                                                    onCloseFile(fileNode.id)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Lucide.X,
                                                contentDescription = "Close tab",
                                                tint = kc.Muted,
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    onOpenFileSelect(fileNode.id)
                                    showOpenFilesDropdown = false
                                }
                            )
                        }

                        if (openFiles.size > 1) {
                            HorizontalDivider(color = kc.Border, thickness = 0.5.dp)
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Lucide.X,
                                            contentDescription = null,
                                            tint = kc.Error,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "Close All Files",
                                            color = kc.Error,
                                            fontSize = 12.sp,
                                            fontFamily = UrbanistFontFamily,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                },
                                onClick = {
                                    onCloseAllFiles()
                                    showOpenFilesDropdown = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // Deduplicated Save Indicator / Action:
        // When unsaved, display a compact amber Save icon pill so the filename breadcrumb never truncates.
        if (isUnsaved) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(kc.Warning.copy(alpha = 0.16f))
                    .border(0.8.dp, kc.Warning.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .clickable { onSaveClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Lucide.Save,
                    contentDescription = "Save file",
                    tint = kc.Warning,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
        }

        // Context-aware Run / Build button
        val (runIcon, runLabel, runColor) = when (runAction) {
            RenderTemplates.RunAction.BUILD_APK -> Triple(
                Lucide.CloudUpload, "Actions", kc.Primary
            )
            RenderTemplates.RunAction.WEB_PREVIEW,
            RenderTemplates.RunAction.SHELL_RUN -> Triple(
                Lucide.Play,
                if (runAction == RenderTemplates.RunAction.WEB_PREVIEW) "Preview" else "Run",
                when (renderStatus) {
                    RenderStatus.RUNNING, RenderStatus.PREPARING -> kc.Secondary
                    RenderStatus.FAILED                          -> kc.Error
                    else                                          -> kc.Success
                }
            )
            RenderTemplates.RunAction.DISABLED -> Triple(
                Lucide.Terminal, "Shell", kc.SurfaceVar
            )
        }
        val runContentColor = when (runColor) {
            kc.Primary    -> OnAccentColor
            kc.Secondary  -> OnAccentLight
            kc.SurfaceVar -> kc.OnSurface
            else          -> Color.White
        }

        val view = LocalView.current
        val context = LocalContext.current
        Button(
            onClick        = {
                KryptonHaptics.tick(context, view)
                onRun()
            },
            colors         = ButtonDefaults.buttonColors(
                containerColor = runColor,
                contentColor = runContentColor
            ),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            shape          = RoundedCornerShape(50),
            elevation      = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 2.dp),
            modifier       = Modifier.height(32.dp)
        ) {
            Icon(runIcon, contentDescription = runLabel, tint = runContentColor, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(3.dp))
            Text(
                runLabel,
                color = runContentColor,
                fontSize = 11.sp,
                fontFamily = UrbanistFontFamily,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp
            )
        }

        Spacer(Modifier.width(2.dp))

        // Quick Terminal Button
        IconButton(
            onClick = {
                KryptonHaptics.tick(context, view)
                onTerminal()
            },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Lucide.Terminal,
                contentDescription = "Terminal",
                tint = kc.Muted,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(Modifier.width(1.dp))

        // Overflow More Menu (Terminal, Command Palette, etc.)
        Box {
            IconButton(
                onClick = { showMoreMenu = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Lucide.EllipsisVertical,
                    "More actions",
                    tint = kc.Muted,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(moreRotation)
                )
            }

            DropdownMenu(
                expanded = showMoreMenu,
                onDismissRequest = { showMoreMenu = false },
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .border(width = 0.5.dp, color = kc.Border, shape = RoundedCornerShape(12.dp))
                    .widthIn(min = 200.dp)
            ) {
                AnimatedVisibility(
                    visible = showMoreMenu,
                    enter = fadeIn(tween(180)) + expandVertically(tween(220), expandFrom = Alignment.Top) + scaleIn(tween(180), initialScale = 0.90f),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(120))
                ) {
                    Column {
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(Lucide.Save, null, tint = kc.Muted, modifier = Modifier.size(16.dp))
                            },
                            text = {
                                Text("Save File", color = kc.OnBackground, fontSize = 13.sp, fontFamily = UrbanistFontFamily)
                            },
                            onClick = {
                                onSaveClick()
                                showMoreMenu = false
                            }
                        )
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(Lucide.Terminal, null, tint = kc.Muted, modifier = Modifier.size(16.dp))
                            },
                            text = {
                                Text("Terminal", color = kc.OnBackground, fontSize = 13.sp, fontFamily = UrbanistFontFamily)
                            },
                            onClick = {
                                onTerminal()
                                showMoreMenu = false
                            }
                        )
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(Lucide.Search, null, tint = kc.Muted, modifier = Modifier.size(16.dp))
                            },
                            text = {
                                Text("Command Palette", color = kc.OnBackground, fontSize = 13.sp, fontFamily = UrbanistFontFamily)
                            },
                            onClick = {
                                onCommandPalette()
                                showMoreMenu = false
                            }
                        )
                        if (onBeam != null) {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(Lucide.Radio, null, tint = kc.Muted, modifier = Modifier.size(16.dp))
                                },
                                text = {
                                    Text("Beam to Screen", color = kc.OnBackground, fontSize = 13.sp, fontFamily = UrbanistFontFamily)
                                },
                                onClick = {
                                    onBeam()
                                    showMoreMenu = false
                                }
                            )
                        }
                        if (openFiles.size > 1) {
                            HorizontalDivider(color = kc.Border, thickness = 0.5.dp)
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(Lucide.X, null, tint = kc.Error, modifier = Modifier.size(16.dp))
                                },
                                text = {
                                    Text("Close All Files", color = kc.Error, fontSize = 13.sp, fontFamily = UrbanistFontFamily)
                                },
                                onClick = {
                                    onCloseAllFiles()
                                    showMoreMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Bottom nav ───────────────────────────────────────────────────────────────
@Composable
fun EditorBottomNav(
    activeTab:     String,
    isGitHubOpen:  Boolean,
    runAction:     RenderTemplates.RunAction,
    renderBadge:   Color?,
    onTabSelected: (String) -> Unit
) {
    val kc = LocalKryptonColors.current
    Row(
        modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surface)
        .border(width = 1.dp, color = kc.Border)
        .navigationBarsPadding()
        .padding(vertical = 4.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavItem(Lucide.Code,        "Edit",     activeTab == "editor",   Modifier.weight(1f)) { onTabSelected("editor") }
        NavItem(Lucide.GitBranch,   "GitHub",   isGitHubOpen,            Modifier.weight(1f)) { onTabSelected("github") }
        NavItem(Lucide.Sparkles,    "AI",       activeTab == "ai", isGlow = true, modifier = Modifier.weight(1f)) { onTabSelected("ai") }
        NavItem(Lucide.Blocks,      "Plugins",  activeTab == "plugins",  Modifier.weight(1f)) { onTabSelected("plugins") }
        NavItem(Lucide.Settings,    "Settings", activeTab == "settings", Modifier.weight(1f)) { onTabSelected("settings") }
    }
}

@Composable
fun NavItem(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    isGlow: Boolean = false,
    onClick: () -> Unit
) {
    val kc = LocalKryptonColors.current
    val tint = when {
        isActive && isGlow -> kc.Secondary
        isActive           -> kc.Primary
        else               -> kc.Muted
    }

    val displayIcon = icon

    val context = LocalContext.current
    val view = LocalView.current
    val activeBgColor = if (isGlow) kc.Secondary.copy(alpha = 0.12f) else kc.Primary.copy(alpha = 0.12f)
    val pillBackground by animateColorAsState(
        targetValue = if (isActive) activeBgColor else Color.Transparent,
        animationSpec = tween(durationMillis = 200),
        label = "nav_item_pill_bg"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                KryptonHaptics.click(context, view)
                onClick()
            }
            .padding(horizontal = 2.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(pillBackground)
                .padding(vertical = 5.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(displayIcon, contentDescription = label, tint = tint, modifier = Modifier.size(19.dp))
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                color = tint,
                fontSize = 9.sp,
                fontFamily = UrbanistFontFamily,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = 0.2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun NavItemWithBadge(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    badge: Color?,
    modifier: Modifier = Modifier,
    isGlow: Boolean = false,
    onClick: () -> Unit
) {
    val kc = LocalKryptonColors.current
    val tint = when {
        isActive && isGlow -> kc.Secondary
        isActive           -> kc.Primary
        else               -> kc.Muted
    }

    val displayIcon = icon

    val activeBgColor = if (isGlow) kc.Secondary.copy(alpha = 0.12f) else kc.Primary.copy(alpha = 0.12f)
    val pillBackground by animateColorAsState(
        targetValue = if (isActive) activeBgColor else Color.Transparent,
        animationSpec = tween(durationMillis = 200),
        label = "nav_item_badge_pill_bg"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(pillBackground)
                .padding(vertical = 5.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box {
                Icon(displayIcon, contentDescription = label, tint = tint, modifier = Modifier.size(19.dp))
                if (badge != null) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(badge)
                            .align(Alignment.TopEnd)
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                color = tint,
                fontSize = 9.sp,
                fontFamily = UrbanistFontFamily,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = 0.2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}


