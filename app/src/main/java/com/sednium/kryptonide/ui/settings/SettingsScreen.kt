package com.sednium.kryptonide.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sednium.kryptonide.ui.editor.* // Theme enums and colors
import com.sednium.kryptonide.ui.theme.LocalKryptonColors
import com.sednium.kryptonide.data.model.SavedModel
import com.sednium.kryptonide.ui.larry.LARRY_PROVIDERS
import com.sednium.kryptonide.ui.larry.ProviderConfig
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.provider.OpenableColumns
import android.widget.Toast
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import com.sednium.kryptonide.ui.plugins.PluginCategory
import com.sednium.kryptonide.ui.plugins.PluginInfo
import com.sednium.kryptonide.ui.plugins.PluginManager
import com.sednium.kryptonide.ui.plugins.PluginsUiState
import com.sednium.kryptonide.ui.projects.DriveSyncHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.composables.icons.lucide.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    fontManager: FontManager = androidx.lifecycle.viewmodel.compose.viewModel(),
    aiVm: com.sednium.kryptonide.ui.ai.AiViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    pluginManager: PluginManager? = null,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedFont by fontManager.selectedFont.collectAsStateWithLifecycle()
    var showFontPicker by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val pluginState by (pluginManager?.state ?: remember { MutableStateFlow(PluginsUiState()) }).collectAsState()
    val installedThemePlugins = remember(pluginState.plugins) {
        pluginState.plugins.filter { it.category == PluginCategory.THEME && it.isInstalled }
    }
    val installedPlugins = remember(pluginState.plugins) {
        pluginState.plugins.filter { it.isInstalled }
    }
    val activeThemeId = pluginState.activeThemeId
    var showThemeDialog by remember { mutableStateOf(false) }

    var showLspModal by remember { mutableStateOf(false) }
    var showFormattersModal by remember { mutableStateOf(false) }
    var showLintersModal by remember { mutableStateOf(false) }
    var showSnippetsModal by remember { mutableStateOf(false) }
    var showKeymapsModal by remember { mutableStateOf(false) }
    var showProductivityModal by remember { mutableStateOf(false) }

    val activeLspCount = remember(pluginState.plugins) {
        pluginState.plugins.count { it.category == PluginCategory.LANGUAGE && it.isInstalled && it.isEnabled }
    }
    val activeFormattersCount = remember(pluginState.plugins) {
        pluginState.plugins.count { it.category == PluginCategory.FORMATTER && it.isInstalled && it.isEnabled }
    }
    val activeLintersCount = remember(pluginState.plugins) {
        pluginState.plugins.count { it.category == PluginCategory.LINTER && it.isInstalled && it.isEnabled }
    }
    val activeSnippetsCount = remember(pluginState.plugins) {
        pluginState.plugins.count { it.category == PluginCategory.SNIPPET && it.isInstalled && it.isEnabled }
    }
    val activeProductivityCount = remember(pluginState.plugins) {
        pluginState.plugins.count { it.category == PluginCategory.PRODUCTIVITY && it.isInstalled && it.isEnabled }
    }
    val effectiveKeymapId = pluginState.activeKeymapId ?: state.keymapPreset
    val activeKeymapDisplayName = remember(effectiveKeymapId, pluginState.plugins) {
        val p = pluginState.plugins.firstOrNull { it.category == PluginCategory.KEYMAP && it.keymapPresetId == effectiveKeymapId }
        when {
            effectiveKeymapId.equals("vscode", ignoreCase = true) -> "VS Code (Standard)"
            p != null -> p.name
            else -> effectiveKeymapId.replaceFirstChar { it.uppercase() }
        }
    }

    val activeThemeDisplayName = remember(activeThemeId, state.theme, installedThemePlugins) {
        if (activeThemeId != null) {
            val p = installedThemePlugins.firstOrNull { it.id == activeThemeId }
            p?.name ?: activeThemeId.removePrefix("theme-").replace("-", " ")
        } else {
            state.theme.displayName
        }
    }
    
    var showPocketServerModal by remember { mutableStateOf(false) }
    var showAiOnboardingModal by remember { mutableStateOf(false) }
    var otaReleaseToPrompt by remember { mutableStateOf<com.sednium.kryptonide.ota.OtaReleaseInfo?>(null) }

    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                fontManager.importCustomFont(uri, context)
            }
        }
    }

    if (showFontPicker) {
        FontPickerScreen(
            fontManager = fontManager,
            onImportClick = { importLauncher.launch("*/*") },
            onBack = { showFontPicker = false }
        )
    } else {
        Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        // Compact Sleek Header matching other panels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(28.dp)) {
                Icon(Lucide.ArrowLeft, "Back to Editor", tint = TextPrimary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Icon(Lucide.Settings, contentDescription = null, tint = AccentLight, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Settings", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider(color = BorderDark, thickness = 0.5.dp)

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            // -- EDITOR SECTION --
            item { SectionHeader("Editor") }

            item {
                var expanded by remember { mutableStateOf(false) }
                val themeModes = listOf("System Default", "Light", "Dark")
                val currentModeStr = themeModes.getOrNull(state.themeMode) ?: "System Default"
                
                SettingRow(
                    title = "Theme Mode",
                    subtitle = currentModeStr,
                    onClick = { expanded = true }
                ) {
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier
                            .background(SurfaceDark, RoundedCornerShape(12.dp))
                            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
                    ) {
                        themeModes.forEachIndexed { index, mode ->
                            val isSelected = state.themeMode == index
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                                    ) {
                                        Text(
                                            mode,
                                            color = if (isSelected) AccentLight else TextPrimary,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            fontSize = 14.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (isSelected) {
                                            Spacer(Modifier.width(10.dp))
                                            Icon(
                                                Lucide.Check,
                                                contentDescription = "Selected",
                                                tint = AccentLight,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = { viewModel.setThemeMode(index); expanded = false }
                            )
                        }
                    }
                }
            }
            
            item {
                SettingSwitchRow(
                    title    = "Dynamic Colors",
                    subtitle = "Use Material You dynamic themes (Android 12+)",
                    checked  = state.useDynamicColor,
                    onCheckedChange = { viewModel.setUseDynamicColor(it) }
                )
            }
            
            item {
                SettingRow(
                    title = "Editor Font",
                    subtitle = "${selectedFont?.font?.name ?: "JetBrains Mono"} • Customize typeface for the code editor",
                    onClick = { showFontPicker = true }
                )
            }

            item {
                SettingRow(
                    title = "Editor Theme",
                    subtitle = activeThemeDisplayName,
                    onClick = { showThemeDialog = true }
                )
            }

            item {
                SettingSliderRow(
                    title = "Font Size",
                    subtitle = "${state.fontSize}sp",
                    value = state.fontSize.toFloat(),
                    onValueChange = { viewModel.setFontSize(it.toInt()) },
                    range = 10f..32f,
                    steps = 21 // (32 - 10 - 1)
                )
            }

            item {
                SettingSwitchRow(
                    title = "Word Wrap",
                    subtitle = "Wrap long lines to fit the screen width",
                    checked = state.wordWrap,
                    onCheckedChange = { viewModel.setWordWrap(it) }
                )
            }

            item {
                SettingSwitchRow(
                    title = "Line Numbers",
                    subtitle = "Show line numbers in the gutter",
                    checked = state.lineNumbers,
                    onCheckedChange = { viewModel.setLineNumbers(it) }
                )
            }
            
            item {
                SettingSwitchRow(
                    title = "Indentation Guides",
                    subtitle = "Show vertical lines to highlight code blocks",
                    checked = state.indentationGuides,
                    onCheckedChange = { viewModel.setIndentationGuides(it) }
                )
            }

            item {
                SettingSwitchRow(
                    title = "Haptic Feedback",
                    subtitle = "Vibrate on tapping editor shortcuts, controls, and keys",
                    checked = state.hapticsEnabled,
                    onCheckedChange = { viewModel.setHapticsEnabled(it) }
                )
            }
            
            // -- AI SECTION --
            item { Spacer(Modifier.height(16.dp)) }
            item { SectionHeader("AI Configuration") }
            
            item {
                val aiState by aiVm.state.collectAsStateWithLifecycle()
                var showAddModel by remember { mutableStateOf(false) }
                var editingModel by remember { mutableStateOf<SavedModel?>(null) }

                Column {
                    Text(
                        "AI Models & Keys",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                    )
                    aiState.savedModels.forEach { saved ->
                        val provider = LARRY_PROVIDERS.firstOrNull { it.id == saved.provider }
                        val isActive = aiState.activeModelId == saved.id
                        val isPocket = saved.provider == "pocket"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                                .clickable { aiVm.setActiveSavedModel(saved.id) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isActive) Lucide.CircleCheck else Lucide.Circle,
                                contentDescription = null,
                                tint = if (isActive) AccentColor else TextMuted,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(saved.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    if (isPocket) "Pocket Server (Local) · ${saved.model}"
                                    else "${provider?.name ?: saved.provider} · ${saved.model}",
                                    color = if (isPocket) AccentLight else TextMuted, fontSize = 12.sp
                                )
                            }
                            IconButton(onClick = { editingModel = saved }) {
                                Icon(Lucide.Pencil, "Edit", tint = TextMuted, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { aiVm.removeSavedModel(saved.id) }) {
                                Icon(Lucide.Trash2, "Remove", tint = TextMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    val hasPocketSaved = aiState.savedModels.any { it.provider == "pocket" }
                    if (!hasPocketSaved) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 6.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(SurfaceDark)
                                .border(0.5.dp, BorderDark, RoundedCornerShape(10.dp))
                                .clickable {
                                    val newPocket = SavedModel(
                                        id = "pocket-local-daemon",
                                        name = "Pocket Server (Local)",
                                        provider = "pocket",
                                        model = com.sednium.kryptonide.agent.pocket.PocketModels.MINICPM_MODEL_ID,
                                        apiKey = "",
                                        baseUrl = "http://127.0.0.1:8080/v1"
                                    )
                                    aiVm.addSavedModel(newPocket)
                                    aiVm.setActiveSavedModel(newPocket.id)
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Lucide.Zap, null, tint = AccentLight, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Add Pocket Server (On-Device)", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text("1-tap enable local inference with MiniCPM 1B (no key)", color = TextMuted, fontSize = 11.sp)
                            }
                            Icon(Lucide.Plus, null, tint = AccentLight, modifier = Modifier.size(16.dp))
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceDark)
                            .border(0.5.dp, BorderDark, RoundedCornerShape(10.dp))
                            .clickable {
                                com.sednium.kryptonide.utils.KryptonHaptics.click(context, null)
                                showAiOnboardingModal = true
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Lucide.Sparkles, null, tint = AccentLight, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Re-run AI Onboarding Setup", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Download & Activate MiniCPM 1B (~650MB)", color = TextMuted, fontSize = 11.sp)
                        }
                        Icon(Lucide.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(16.dp))
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                            .clickable { showAddModel = true },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Lucide.CirclePlus, null, tint = AccentColor, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Add Model", color = AccentColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }

                    if (aiState.savedModels.isEmpty()) {
                        Text(
                            "No models configured yet — add one to use AI Assistant.",
                            color = TextMuted, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                        )
                    }
                }

                if (showAddModel) {
                    AddModelDialog(
                        onDismiss = { showAddModel = false },
                        onSave = { saved -> aiVm.addSavedModel(saved); showAddModel = false }
                    )
                }

                editingModel?.let { modelToEdit ->
                    AddModelDialog(
                        initialModel = modelToEdit,
                        onDismiss = { editingModel = null },
                        onSave = { updated -> aiVm.updateSavedModel(updated); editingModel = null }
                    )
                }
            }
            
            item {
                var currentTier by remember { mutableStateOf(aiVm.oortyEngine.executionTier) }
                com.sednium.kryptonide.agent.ui.OortySettingsSection(
                    currentTier = currentTier,
                    onTierChanged = {
                        currentTier = it
                        aiVm.oortyEngine.executionTier = it
                    },
                    onOpenPocketServer = {
                        showPocketServerModal = true
                    }
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
            item { SectionHeader("Skills") }
            item {
                val context = androidx.compose.ui.platform.LocalContext.current
                val skillsRepo = remember { com.sednium.kryptonide.data.repository.SkillsRepository(context) }
                val scope = rememberCoroutineScope()
                var skills by remember { mutableStateOf<List<com.sednium.kryptonide.data.repository.Skill>>(emptyList()) }
                var showAddSkill by remember { mutableStateOf(false) }
                var editingSkill by remember { mutableStateOf<com.sednium.kryptonide.data.repository.Skill?>(null) }

                val skillFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        scope.launch {
                            try {
                                var fileName = "skill.md"
                                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                    if (cursor.moveToFirst() && nameIndex >= 0) {
                                        fileName = cursor.getString(nameIndex) ?: fileName
                                    }
                                }
                                val rawContent = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                                if (rawContent.isNotBlank()) {
                                    val importedName = skillsRepo.importSkill(fileName, rawContent)
                                    skills = skillsRepo.listSkills()
                                    Toast.makeText(context, "Imported skill: $importedName", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Failed to import skill: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }

                LaunchedEffect(Unit) { skills = skillsRepo.listSkills() }

                Text(
                    "Markdown files with extra instructions/context for AI — added to every request, similar to Claude's own skills.",
                    color = TextMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )

                skills.forEach { skill ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editingSkill = skill }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Lucide.FileText, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(skill.name, color = TextPrimary, fontSize = 14.sp)
                            Text("${skill.sizeBytes} B • Tap to view/edit", color = TextMuted, fontSize = 11.sp)
                        }
                        IconButton(onClick = { editingSkill = skill }) {
                            Icon(Lucide.Pencil, "Edit", tint = TextMuted, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = {
                            scope.launch { skillsRepo.deleteSkill(skill.name); skills = skillsRepo.listSkills() }
                        }) {
                            Icon(Lucide.Trash2, "Remove", tint = TextMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.clickable { showAddSkill = true },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Lucide.CirclePlus, null, tint = AccentColor, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add Skill", color = AccentColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }

                    Row(
                        modifier = Modifier.clickable { skillFilePicker.launch(arrayOf("text/*", "*/*")) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Lucide.FileUp, null, tint = AccentLight, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Import .md File", color = AccentLight, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }

                if (showAddSkill || editingSkill != null) {
                    val initial = editingSkill
                    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
                    var content by remember(initial) { mutableStateOf(initial?.content ?: "") }
                    AlertDialog(
                        onDismissRequest = { showAddSkill = false; editingSkill = null },
                        containerColor = SurfaceDark,
                        shape = RoundedCornerShape(20.dp),
                        title = { Text(if (initial != null) "Edit Skill" else "Add Skill", color = TextPrimary) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = name, onValueChange = { name = it },
                                    label = { Text("Name", color = TextMuted) }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = TextFieldDefaults.outlinedTextFieldColors(
                                        textColor = TextPrimary, focusedBorderColor = AccentColor, unfocusedBorderColor = BorderDark
                                    )
                                )
                                OutlinedTextField(
                                    value = content, onValueChange = { content = it },
                                    label = { Text("Markdown content", color = TextMuted) },
                                    modifier = Modifier.fillMaxWidth().height(200.dp),
                                    colors = TextFieldDefaults.outlinedTextFieldColors(
                                        textColor = TextPrimary, focusedBorderColor = AccentColor, unfocusedBorderColor = BorderDark
                                    )
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = name.isNotBlank() && content.isNotBlank(),
                                onClick = {
                                    scope.launch {
                                        if (initial != null && initial.name != name) {
                                            skillsRepo.deleteSkill(initial.name)
                                        }
                                        skillsRepo.addSkill(name, content)
                                        skills = skillsRepo.listSkills()
                                        showAddSkill = false
                                        editingSkill = null
                                    }
                                }
                            ) { Text("Save", color = AccentLight) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAddSkill = false; editingSkill = null }) { Text("Cancel", color = TextMuted) }
                        }
                    )
                }
            }

            // -- MCP CONNECTORS --
            item { Spacer(Modifier.height(16.dp)) }
            item { SectionHeader("MCP Connectors (Model Context Protocol)") }
            item {
                val context = androidx.compose.ui.platform.LocalContext.current
                val mcpRepo = remember { com.sednium.kryptonide.data.mcp.McpRepository(context) }
                val scope = rememberCoroutineScope()
                var mcpServers by remember { mutableStateOf<List<com.sednium.kryptonide.data.mcp.McpServerConfig>>(emptyList()) }
                var showAddMcp by remember { mutableStateOf(false) }
                var editingMcp by remember { mutableStateOf<com.sednium.kryptonide.data.mcp.McpServerConfig?>(null) }

                LaunchedEffect(Unit) { mcpServers = mcpRepo.listServers() }

                Text(
                    "Connect external MCP servers (e.g. GitHub, Filesystem, Fetch) so AI can communicate with external tools and services.",
                    color = TextMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )

                mcpServers.forEach { server ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editingMcp = server }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (server.isEnabled) Color(0xFF3FB950) else TextMuted)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(server.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(server.endpointUrl, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Switch(
                            checked = server.isEnabled,
                            onCheckedChange = { isChecked ->
                                scope.launch {
                                    mcpRepo.toggleServer(server.id, isChecked)
                                    mcpServers = mcpRepo.listServers()
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = OnAccentColor,
                                checkedTrackColor = AccentColor,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = SurfaceDark
                            )
                        )
                        Spacer(Modifier.width(6.dp))
                        IconButton(onClick = {
                            scope.launch {
                                mcpRepo.deleteServer(server.id)
                                mcpServers = mcpRepo.listServers()
                            }
                        }) {
                            Icon(Lucide.Trash2, "Delete", tint = TextMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .clickable { showAddMcp = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Lucide.CirclePlus, null, tint = AccentColor, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Add MCP Server", color = AccentColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }

                if (showAddMcp || editingMcp != null) {
                    val initialServer = editingMcp
                    var mcpName by remember(initialServer) { mutableStateOf(initialServer?.name ?: "") }
                    var mcpUrl by remember(initialServer) { mutableStateOf(initialServer?.endpointUrl ?: "") }
                    var mcpApiKey by remember(initialServer) { mutableStateOf(initialServer?.apiKey ?: "") }
                    AlertDialog(
                        onDismissRequest = { showAddMcp = false; editingMcp = null },
                        containerColor = SurfaceDark,
                        shape = RoundedCornerShape(20.dp),
                        title = { Text(if (initialServer != null) "Edit MCP Server" else "Add MCP Server", color = TextPrimary) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = mcpName, onValueChange = { mcpName = it },
                                    label = { Text("Server Name", color = TextMuted) }, singleLine = true,
                                    placeholder = { Text("GitHub MCP", color = TextMuted) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = TextFieldDefaults.outlinedTextFieldColors(
                                        textColor = TextPrimary, focusedBorderColor = AccentColor, unfocusedBorderColor = BorderDark
                                    )
                                )
                                OutlinedTextField(
                                    value = mcpUrl, onValueChange = { mcpUrl = it },
                                    label = { Text("Endpoint URL (SSE / HTTP)", color = TextMuted) }, singleLine = true,
                                    placeholder = { Text("http://localhost:8000/sse", color = TextMuted) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = TextFieldDefaults.outlinedTextFieldColors(
                                        textColor = TextPrimary, focusedBorderColor = AccentColor, unfocusedBorderColor = BorderDark
                                    )
                                )
                                OutlinedTextField(
                                    value = mcpApiKey, onValueChange = { mcpApiKey = it },
                                    label = { Text("Auth Token / Key (Optional)", color = TextMuted) }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = TextFieldDefaults.outlinedTextFieldColors(
                                        textColor = TextPrimary, focusedBorderColor = AccentColor, unfocusedBorderColor = BorderDark
                                    )
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = mcpName.isNotBlank() && mcpUrl.isNotBlank(),
                                onClick = {
                                    scope.launch {
                                        if (initialServer != null) {
                                            mcpRepo.updateServer(initialServer.id, mcpName, mcpUrl, mcpApiKey)
                                        } else {
                                            mcpRepo.addServer(mcpName, mcpUrl, mcpApiKey)
                                        }
                                        mcpServers = mcpRepo.listServers()
                                        showAddMcp = false
                                        editingMcp = null
                                        Toast.makeText(context, "Saved MCP Server: $mcpName", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) { Text("Save", color = AccentLight) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAddMcp = false; editingMcp = null }) { Text("Cancel", color = TextMuted) }
                        }
                    )
                }
            }

            // -- PLUGINS --
            item { Spacer(Modifier.height(16.dp)) }
            item { SectionHeader("Plugins & Extensions") }
            item {
                SettingSwitchRow(
                    title = "Prettier Auto-Format",
                    subtitle = "Format code on save",
                    checked = state.enablePrettier,
                    onCheckedChange = { viewModel.setEnablePrettier(it) }
                )
            }
            item {
                SettingSwitchRow(
                    title = "Emmet Abbreviations",
                    subtitle = "Expand HTML/CSS snippets",
                    checked = state.enableEmmet,
                    onCheckedChange = { viewModel.setEnableEmmet(it) }
                )
            }

            item {
                SettingRow(
                    title = "Language Servers (LSP)",
                    subtitle = if (activeLspCount > 0) "$activeLspCount active language servers" else "Activate or deactivate language servers",
                    onClick = { showLspModal = true }
                )
            }
            item {
                SettingRow(
                    title = "Code Formatters",
                    subtitle = if (activeFormattersCount > 0) "$activeFormattersCount active formatters" else "Activate or deactivate code formatters",
                    onClick = { showFormattersModal = true }
                )
            }
            item {
                SettingRow(
                    title = "Code Linters",
                    subtitle = if (activeLintersCount > 0) "$activeLintersCount active linters" else "Activate or deactivate static code linters",
                    onClick = { showLintersModal = true }
                )
            }
            item {
                SettingRow(
                    title = "Language Snippets",
                    subtitle = if (activeSnippetsCount > 0) "$activeSnippetsCount active snippet packs" else "Activate or deactivate code snippet expansions",
                    onClick = { showSnippetsModal = true }
                )
            }
            item {
                SettingRow(
                    title = "Keymaps & Shortcuts",
                    subtitle = "Active preset: $activeKeymapDisplayName",
                    onClick = { showKeymapsModal = true }
                )
            }
            item {
                SettingRow(
                    title = "Productivity Extensions",
                    subtitle = if (activeProductivityCount > 0) "$activeProductivityCount active extensions" else "Activate or deactivate productivity plugins",
                    onClick = { showProductivityModal = true }
                )
            }

            // -- CLOUD BACKUP & RESTORE --
            item { Spacer(Modifier.height(16.dp)) }
            item { SectionHeader("Cloud Backup & Restore") }
            item {
                CloudBackupSection(context = context)
            }

            // -- ABOUT & UPDATES --
            item { Spacer(Modifier.height(16.dp)) }
            item { SectionHeader("About & Updates") }
            item {
                AboutAndUpdatesSection(
                    context = context,
                    onShowUpdate = { otaRelease ->
                        otaReleaseToPrompt = otaRelease
                    }
                )
            }
        }
        }
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(
                    "Select Editor Theme",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (activeThemeId != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFF85149).copy(alpha = 0.12f))
                                .clickable {
                                    pluginManager?.deactivateTheme()
                                    showThemeDialog = false
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Lucide.X, null, tint = Color(0xFFF85149), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Deactivate Custom Theme",
                                color = Color(0xFFF85149),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "Revert to ${state.theme.displayName}",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    Text(
                        "BUILT-IN THEMES",
                        color = AccentLight,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    EditorTheme.values().forEach { t ->
                        val isSelected = activeThemeId == null && state.theme == t
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) AccentLight.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable {
                                    viewModel.setTheme(t)
                                    pluginManager?.deactivateTheme()
                                    showThemeDialog = false
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                t.displayName,
                                color = if (isSelected) AccentLight else TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                Icon(Lucide.Check, "Selected", tint = AccentLight, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        "INSTALLED THEME PLUGINS (${installedThemePlugins.size})",
                        color = AccentLight,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    if (installedThemePlugins.isEmpty()) {
                        Text(
                            "No theme plugins installed yet. You can install custom VS Code themes from the Plugins tab.",
                            color = TextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                        )
                    } else {
                        installedThemePlugins.forEach { plugin ->
                            val isSelected = activeThemeId == plugin.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) AccentLight.copy(alpha = 0.15f) else Color.Transparent)
                                    .clickable {
                                        pluginManager?.activateTheme(plugin)
                                        showThemeDialog = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        plugin.name,
                                        color = if (isSelected) AccentLight else TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                    Text(
                                        "by ${plugin.author} • v${plugin.version}",
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                                if (isSelected) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(LocalKryptonColors.current.Success.copy(alpha = 0.2f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text("ACTIVE", color = LocalKryptonColors.current.Success, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        OutlinedButton(
                                            onClick = {
                                                pluginManager?.deactivateTheme()
                                                showThemeDialog = false
                                            },
                                            shape = RoundedCornerShape(4.dp),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                            modifier = Modifier.height(24.dp),
                                            border = androidx.compose.foundation.BorderStroke(0.5.dp, BorderDark)
                                        ) {
                                            Text("Deactivate", color = TextMuted, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("Close", color = AccentLight)
                }
            }
        )
    }

    if (showLspModal) {
        CategoryPluginModal(
            title = "Language Servers (LSP)",
            subtitle = "Enable or disable installed language intelligence and analyzers",
            icon = Lucide.Code,
            category = PluginCategory.LANGUAGE,
            plugins = pluginState.plugins,
            onToggleEnabled = { pluginId, enabled ->
                pluginManager?.setPluginEnabled(pluginId, enabled)
            },
            onDismiss = { showLspModal = false }
        )
    }

    if (showFormattersModal) {
        CategoryPluginModal(
            title = "Code Formatters",
            subtitle = "Enable or disable installed formatters for code cleanup",
            icon = Lucide.Wand,
            category = PluginCategory.FORMATTER,
            plugins = pluginState.plugins,
            onToggleEnabled = { pluginId, enabled ->
                pluginManager?.setPluginEnabled(pluginId, enabled)
            },
            onDismiss = { showFormattersModal = false }
        )
    }

    if (showLintersModal) {
        CategoryPluginModal(
            title = "Code Linters",
            subtitle = "Enable or disable static analysis tools and diagnostics",
            icon = Lucide.CircleCheck,
            category = PluginCategory.LINTER,
            plugins = pluginState.plugins,
            onToggleEnabled = { pluginId, enabled ->
                pluginManager?.setPluginEnabled(pluginId, enabled)
            },
            onDismiss = { showLintersModal = false }
        )
    }

    if (showSnippetsModal) {
        CategoryPluginModal(
            title = "Language Snippets",
            subtitle = "Enable or disable code snippet expansions and templates",
            icon = Lucide.Braces,
            category = PluginCategory.SNIPPET,
            plugins = pluginState.plugins,
            onToggleEnabled = { pluginId, enabled ->
                pluginManager?.setPluginEnabled(pluginId, enabled)
            },
            onDismiss = { showSnippetsModal = false }
        )
    }

    if (showKeymapsModal) {
        KeymapModal(
            plugins = pluginState.plugins,
            activeKeymapId = effectiveKeymapId,
            onActivateKeymap = { keymapPresetId ->
                pluginManager?.activateKeymap(keymapPresetId)
                viewModel.setKeymapPreset(keymapPresetId)
            },
            onDismiss = { showKeymapsModal = false }
        )
    }

    if (showProductivityModal) {
        CategoryPluginModal(
            title = "Productivity Extensions",
            subtitle = "Enable or disable developer productivity tools",
            icon = Lucide.Zap,
            category = PluginCategory.PRODUCTIVITY,
            plugins = pluginState.plugins,
            onToggleEnabled = { pluginId, enabled ->
                pluginManager?.setPluginEnabled(pluginId, enabled)
            },
            onDismiss = { showProductivityModal = false }
        )
    }

    if (showPocketServerModal) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val pocketManager = remember { com.sednium.kryptonide.agent.pocket.PocketServerManager.getInstance(context) }
        com.sednium.kryptonide.agent.ui.PocketServerScreen(
            pocketManager = pocketManager,
            onBack = { showPocketServerModal = false }
        )
    }

    if (showAiOnboardingModal) {
        val kc = LocalKryptonColors.current
        com.sednium.kryptonide.ui.onboarding.AiOnboardingDialog(
            colors = kc,
            onDismiss = { showAiOnboardingModal = false }
        )
    }

    if (otaReleaseToPrompt != null) {
        com.sednium.kryptonide.ota.OtaUpdateDialog(
            release = otaReleaseToPrompt!!,
            onDismiss = { otaReleaseToPrompt = null }
        )
    }
}

@Composable
private fun AboutAndUpdatesSection(
    context: Context,
    onShowUpdate: (com.sednium.kryptonide.ota.OtaReleaseInfo) -> Unit
) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var isChecking by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderDark, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Kryptonide Native", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AccentLight.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "v${com.sednium.kryptonide.ota.OtaUpdateManager.getCurrentAppVersion()}",
                            color = AccentLight,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "Sednium Technologies • Official Release",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }

        HorizontalDivider(color = BorderDark, thickness = 0.5.dp)

        // Check for updates button
        Button(
            onClick = {
                if (isChecking) return@Button
                isChecking = true
                scope.launch {
                    val result = com.sednium.kryptonide.ota.OtaUpdateManager.checkUpdateStatus()
                    isChecking = false
                    when (result) {
                        is com.sednium.kryptonide.ota.OtaCheckResult.UpdateAvailable -> {
                            onShowUpdate(result.release)
                        }
                        is com.sednium.kryptonide.ota.OtaCheckResult.UpToDate -> {
                            Toast.makeText(context, "Kryptonide is up to date (v${com.sednium.kryptonide.ota.OtaUpdateManager.getCurrentAppVersion()})", Toast.LENGTH_SHORT).show()
                        }
                        is com.sednium.kryptonide.ota.OtaCheckResult.Error -> {
                            Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(42.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
            shape = RoundedCornerShape(10.dp)
        ) {
            if (isChecking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = OnAccentColor,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text("Checking GitHub Releases...", color = OnAccentColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            } else {
                Icon(Lucide.RefreshCw, null, tint = OnAccentColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Check for Updates", color = OnAccentColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Links
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    uriHandler.openUri(com.sednium.kryptonide.ota.OtaUpdateManager.GITHUB_REPO_URL)
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Lucide.Github, null, tint = TextMuted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("GitHub Repository", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("Sednium-Technologies/kryptonide-native", color = AccentLight, fontSize = 11.sp)
            }
            Icon(Lucide.ExternalLink, null, tint = TextMuted, modifier = Modifier.size(14.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    uriHandler.openUri("${com.sednium.kryptonide.ota.OtaUpdateManager.GITHUB_REPO_URL}/issues")
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Lucide.CircleDot, null, tint = TextMuted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Issues & Bug Reports", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("View open tickets or submit feedback", color = TextMuted, fontSize = 11.sp)
            }
            Icon(Lucide.ExternalLink, null, tint = TextMuted, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun CloudBackupSection(context: Context) {
    val scope = rememberCoroutineScope()
    var googleAccount by remember { mutableStateOf(GoogleSignIn.getLastSignedInAccount(context)) }
    var isBackingUp by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var backupStatusText by remember { mutableStateOf(DriveSyncHelper.getLastBackupInfo(context)) }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (data != null) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            runCatching {
                googleAccount = task.getResult(ApiException::class.java)
                backupStatusText = DriveSyncHelper.getLastBackupInfo(context)
                Toast.makeText(context, "Connected to Google Drive: ${googleAccount?.email}", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                val code = (e as? ApiException)?.statusCode
                val msg = when (code) {
                    10    -> "Google Sign-In: Developer Error 10 (Verify SHA-1 fingerprint in Google Cloud Console)"
                    12501 -> "Google Sign-In was cancelled."
                    12500 -> "Google Sign-In failed. Check Google Play Services."
                    7     -> "Network error: Please check your connection."
                    4     -> "No Google account configured on device. Use Local Backup below or add an account in Android Settings."
                    else  -> "Sign-in failed (code ${code ?: -1}): ${e.message ?: "Failed"}"
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "Google Sign-In was cancelled or unavailable on this device. You can use Local Backup below.", Toast.LENGTH_SHORT).show()
        }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isBackingUp = true
                try {
                    val stream = context.contentResolver.openOutputStream(uri)
                    if (stream != null) {
                        val res = stream.use {
                            DriveSyncHelper.createWorkspaceBackupZip(context, it)
                        }
                        backupStatusText = DriveSyncHelper.getLastBackupInfo(context)
                        Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Could not open target file for writing", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Local export failed: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    isBackingUp = false
                }
            }
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isRestoring = true
                try {
                    val stream = context.contentResolver.openInputStream(uri)
                    if (stream != null) {
                        val res = stream.use {
                            DriveSyncHelper.restoreWorkspaceFromStream(context, it)
                        }
                        backupStatusText = DriveSyncHelper.getLastBackupInfo(context)
                        Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Could not open backup archive for reading", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Local restore failed: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    isRestoring = false
                }
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AccentLight.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Lucide.CloudUpload,
                        contentDescription = "Cloud",
                        tint = AccentLight,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Google Drive Sync",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (googleAccount != null) googleAccount?.email ?: "Connected" else "Not connected",
                        color = if (googleAccount != null) LocalKryptonColors.current.Success else TextMuted,
                        fontSize = 12.sp
                    )
                }
                if (googleAccount != null) {
                    TextButton(
                        onClick = {
                            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
                            GoogleSignIn.getClient(context, gso).signOut().addOnCompleteListener {
                                googleAccount = null
                                backupStatusText = DriveSyncHelper.getLastBackupInfo(context)
                            }
                        }
                    ) {
                        Text("Sign Out", color = LocalKryptonColors.current.Error, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = BorderDark, thickness = 0.5.dp)
            Spacer(Modifier.height(12.dp))

            // Last Backup info row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Lucide.History, null, tint = TextMuted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Last Backup:", color = TextMuted, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    backupStatusText,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(16.dp))

            if (googleAccount == null) {
                Button(
                    onClick = {
                        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestEmail()
                            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
                            .build()
                        val client = GoogleSignIn.getClient(context, gso)
                        signInLauncher.launch(client.signInIntent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentColor,
                        contentColor = OnAccentColor
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Lucide.Cloud, null, tint = OnAccentColor, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Connect Google Drive", color = OnAccentColor, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Backup Now button
                    Button(
                        onClick = {
                            if (!isBackingUp && !isRestoring) {
                                scope.launch {
                                    isBackingUp = true
                                    try {
                                        val res = DriveSyncHelper.backupWorkspace(context, googleAccount!!)
                                        backupStatusText = DriveSyncHelper.getLastBackupInfo(context)
                                        Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
                                    } finally {
                                        isBackingUp = false
                                    }
                                }
                            }
                        },
                        enabled = !isBackingUp && !isRestoring,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentColor,
                            contentColor = OnAccentColor
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isBackingUp) {
                            CircularProgressIndicator(color = OnAccentColor, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("Backing up...", color = OnAccentColor, fontSize = 12.sp)
                        } else {
                            Icon(Lucide.CloudUpload, null, tint = OnAccentColor, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Backup Now", color = OnAccentColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // Restore from Drive button
                    OutlinedButton(
                        onClick = {
                            if (!isBackingUp && !isRestoring) {
                                showRestoreConfirm = true
                            }
                        },
                        enabled = !isBackingUp && !isRestoring,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
                    ) {
                        if (isRestoring) {
                            CircularProgressIndicator(color = AccentLight, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("Restoring...", color = TextPrimary, fontSize = 12.sp)
                        } else {
                            Icon(Lucide.CloudDownload, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Restore", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = BorderDark, thickness = 0.5.dp)
            Spacer(Modifier.height(14.dp))

            // Local / Offline Workspace Backup
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Lucide.HardDrive, null, tint = AccentLight, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Local Backup (.zip)", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        exportBackupLauncher.launch("krypton_backup_$timestamp.zip")
                    },
                    enabled = !isBackingUp && !isRestoring,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
                ) {
                    Icon(Lucide.Download, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export .zip", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }

                OutlinedButton(
                    onClick = {
                        importBackupLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                    },
                    enabled = !isBackingUp && !isRestoring,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
                ) {
                    Icon(Lucide.Upload, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Restore .zip", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Restore Workspace from Drive?", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "This will download the latest backup archive from Google Drive and unpack it into your Kryptonide workspace. Existing files with matching names will be updated.",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestoreConfirm = false
                        scope.launch {
                            isRestoring = true
                            try {
                                val res = DriveSyncHelper.restoreWorkspace(context, googleAccount!!)
                                backupStatusText = DriveSyncHelper.getLastBackupInfo(context)
                                Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                isRestoring = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentColor,
                        contentColor = OnAccentColor
                    )
                ) {
                    Text("Restore Now", color = OnAccentColor, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = AccentLight,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(
                    subtitle, 
                    color = TextMuted, 
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailingContent != null) {
            Box { trailingContent() }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                onCheckedChange(!checked)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextMuted, fontSize = 13.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                onCheckedChange(it)
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = OnAccentColor,
                checkedTrackColor = AccentColor,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = SurfaceDark
            )
        )
    }
}

@Composable
private fun SettingSliderRow(
    title: String,
    subtitle: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    steps: Int
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = AccentLight, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = { newVal ->
                if (newVal.toInt() != value.toInt()) {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                }
                onValueChange(newVal)
            },
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = AccentColor,
                activeTrackColor = AccentColor,
                inactiveTrackColor = SurfaceDark
            )
        )
    }
}

// ─── Add / Edit Model dialog ────────────────────────────────────────────────
// Provider select, model select/search/manual-type, API key, and a real
// "Test Connection" that hits the provider's own /models endpoint (or, for
// providers with a different auth scheme, the equivalent lightweight check)
// and maps common HTTP failure modes to a readable message.
@Composable
fun AddModelDialog(
    initialModel: SavedModel? = null,
    onDismiss: () -> Unit,
    onSave: (SavedModel) -> Unit,
) {
    val initialProvider = remember(initialModel) {
        LARRY_PROVIDERS.firstOrNull { it.id == initialModel?.provider }
            ?: (LARRY_PROVIDERS.firstOrNull { it.id == "gemini" } ?: LARRY_PROVIDERS.first())
    }
    var provider by remember(initialModel) { mutableStateOf(initialProvider) }
    var providerMenuOpen by remember { mutableStateOf(false) }
    var displayName by remember(initialModel) { mutableStateOf(initialModel?.name ?: initialProvider.name) }
    var modelText by remember(initialModel) { mutableStateOf(initialModel?.model ?: "") }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var apiKey by remember(initialModel) { mutableStateOf(initialModel?.apiKey ?: "") }
    var showKey by remember { mutableStateOf(false) }
    var baseUrl by remember(initialModel) { mutableStateOf(initialModel?.baseUrl?.ifBlank { initialProvider.baseUrl } ?: initialProvider.baseUrl) }

    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchStatusMessage by remember { mutableStateOf<String?>(null) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    var testState by remember { mutableStateOf<TestState>(TestState.Idle) }
    val scope = rememberCoroutineScope()

    fun fetchModels() {
        val key = apiKey.trim()
        val canFetch = key.isNotBlank() || provider.id == "ollama" || provider.id == "custom" || provider.id == "pocket"
        if (!canFetch) return
        isFetchingModels = true
        fetchStatusMessage = null
        scope.launch {
            val result = fetchProviderModels(provider, key, baseUrl)
            isFetchingModels = false
            result.onSuccess { models ->
                fetchedModels = models
                if (models.isNotEmpty()) {
                    fetchStatusMessage = if (provider.id == "pocket") {
                        "${models.size} local model(s) discovered"
                    } else {
                        "${models.size} models loaded from ${provider.name}"
                    }
                } else {
                    fetchStatusMessage = "No models returned by ${provider.name}"
                }
            }.onFailure { err ->
                fetchStatusMessage = "Could not fetch models: ${err.message}"
            }
        }
    }

    LaunchedEffect(Unit) {
        if (provider.id == "pocket") {
            fetchModels()
        }
    }

    LaunchedEffect(apiKey, provider.id, baseUrl) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.length >= 8 || provider.id == "ollama" || provider.id == "pocket") {
            kotlinx.coroutines.delay(600)
            fetchModels()
        }
    }

    fun selectProvider(p: ProviderConfig) {
        provider = p
        displayName = if (p.id == "pocket") "Pocket Server (Local)" else p.name
        modelText = ""
        baseUrl = p.baseUrl
        fetchedModels = emptyList()
        fetchStatusMessage = null
        testState = TestState.Idle
        if (p.id == "pocket") {
            fetchModels()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(20.dp),
        title = { Text(if (initialModel != null) "Edit Model" else "Add Model", color = TextPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Provider picker
                Box {
                    OutlinedTextField(
                        value = provider.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Provider", color = TextMuted) },
                        trailingIcon = { Icon(Lucide.ChevronDown, null, tint = TextMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            textColor = TextPrimary, focusedBorderColor = AccentColor, unfocusedBorderColor = BorderDark
                        )
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { providerMenuOpen = true }
                    )
                    DropdownMenu(
                        expanded = providerMenuOpen,
                        onDismissRequest = { providerMenuOpen = false },
                        modifier = Modifier
                            .background(SurfaceDark, RoundedCornerShape(12.dp))
                            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
                    ) {
                        LARRY_PROVIDERS.forEach { p ->
                            val isSelected = p.id == provider.id
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                                    ) {
                                        Text(
                                            p.name,
                                            color = if (isSelected) AccentLight else TextPrimary,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            fontSize = 14.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (isSelected) {
                                            Spacer(Modifier.width(10.dp))
                                            Icon(
                                                Lucide.Check,
                                                contentDescription = "Selected",
                                                tint = AccentLight,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = { selectProvider(p); providerMenuOpen = false }
                            )
                        }
                    }
                }

                // 2. API Key (Enter key first so models can be selected/fetched)
                if (provider.id == "pocket") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceDark)
                            .border(0.5.dp, BorderDark, RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(Lucide.Cpu, contentDescription = null, tint = AccentLight, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Local On-Device Daemon", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Inference runs on localhost. No API key or internet required.", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it; testState = TestState.Idle },
                            label = { Text(provider.placeholder.ifBlank { "API key" }, color = TextMuted) },
                            singleLine = true,
                            visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None
                                                   else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = {
                                        clipboardManager.getText()?.text?.let { text ->
                                            if (text.isNotBlank()) {
                                                apiKey = text.trim()
                                                testState = TestState.Idle
                                            }
                                        }
                                    }) {
                                        Icon(Lucide.ClipboardPaste, "Paste API Key", tint = TextMuted, modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { showKey = !showKey }) {
                                        Icon(if (showKey) Lucide.EyeOff else Lucide.Eye, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                textColor = TextPrimary, focusedBorderColor = AccentColor, unfocusedBorderColor = BorderDark
                            )
                        )
                        if (apiKey.isBlank() && provider.id != "ollama" && provider.id != "custom" && provider.id != "pocket") {
                            Text(
                                "Put your API key to search and fetch models from ${provider.name}",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                if (provider.id == "custom" || provider.id == "ollama") {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it; testState = TestState.Idle },
                        label = { Text("Base URL", color = TextMuted) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            textColor = TextPrimary, focusedBorderColor = AccentColor, unfocusedBorderColor = BorderDark
                        )
                    )
                }

                // 3. Model Search & Selection
                val availableModels = if (fetchedModels.isNotEmpty()) fetchedModels else provider.models
                val filteredModels = if (modelText.isBlank()) {
                    availableModels
                } else {
                    availableModels.filter { it.contains(modelText.trim(), ignoreCase = true) }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box {
                        OutlinedTextField(
                            value = modelText,
                            onValueChange = {
                                modelText = it
                                modelMenuOpen = it.isNotBlank()
                            },
                            label = { Text("Model (search or enter)", color = TextMuted) },
                            placeholder = {
                                Text(
                                    if (isFetchingModels) "Fetching models from provider..."
                                    else if (availableModels.isNotEmpty()) "Search ${availableModels.size} models or type..."
                                    else "Enter model ID...",
                                    color = TextMuted.copy(alpha = 0.6f),
                                    fontSize = 13.sp
                                )
                            },
                            singleLine = true,
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (modelText.isNotBlank()) {
                                        IconButton(onClick = {
                                            modelText = ""
                                            modelMenuOpen = false
                                        }) {
                                            Icon(
                                                Lucide.X,
                                                contentDescription = "Clear model",
                                                tint = TextMuted,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    if (isFetchingModels) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = AccentColor
                                        )
                                        Spacer(Modifier.width(4.dp))
                                    } else if (apiKey.isNotBlank() || provider.id == "ollama" || provider.id == "pocket") {
                                        IconButton(onClick = { fetchModels() }) {
                                            Icon(
                                                Lucide.RefreshCw,
                                                contentDescription = "Refresh models",
                                                tint = TextMuted,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    IconButton(onClick = { modelMenuOpen = !modelMenuOpen }) {
                                        Icon(Lucide.ChevronDown, "Select model", tint = TextMuted)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                textColor = TextPrimary, focusedBorderColor = AccentColor, unfocusedBorderColor = BorderDark
                            )
                        )

                        if (modelMenuOpen && filteredModels.isNotEmpty()) {
                            DropdownMenu(
                                expanded = true,
                                onDismissRequest = { modelMenuOpen = false },
                                modifier = Modifier
                                    .background(SurfaceDark, RoundedCornerShape(12.dp))
                                    .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
                                    .heightIn(max = 280.dp)
                            ) {
                                if (fetchedModels.isNotEmpty()) {
                                    Text(
                                        if (provider.id == "pocket") "Installed Models (${filteredModels.size})" else "Live Models (${filteredModels.size})",
                                        color = AccentLight,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                    HorizontalDivider(color = BorderDark, thickness = 0.5.dp)
                                }
                                filteredModels.take(80).forEach { m ->
                                    val isSelected = m.equals(modelText.trim(), ignoreCase = true)
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                                            ) {
                                                Text(
                                                    m,
                                                    color = if (isSelected) AccentLight else TextPrimary,
                                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                    fontSize = 13.sp,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                if (isSelected) {
                                                    Spacer(Modifier.width(10.dp))
                                                    Icon(
                                                        Lucide.Check,
                                                        contentDescription = "Selected",
                                                        tint = AccentLight,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            modelText = m
                                            modelMenuOpen = false
                                            if (provider.id == "pocket" || displayName.isBlank() || displayName == provider.name || displayName.startsWith("Pocket Server")) {
                                                val cleanName = m.substringBeforeLast('.')
                                                displayName = if (provider.id == "pocket") "Pocket Server ($cleanName)" else "$cleanName (${provider.name})"
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (isFetchingModels) {
                        Text("Fetching models from ${provider.name}...", color = AccentLight, fontSize = 11.sp)
                    } else if (fetchStatusMessage != null) {
                        Text(
                            fetchStatusMessage!!,
                            color = if (fetchedModels.isNotEmpty()) LocalKryptonColors.current.Success else LocalKryptonColors.current.Warning,
                            fontSize = 11.sp
                        )
                    }
                }

                // 4. Display name
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display name", color = TextMuted) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        textColor = TextPrimary, focusedBorderColor = AccentColor, unfocusedBorderColor = BorderDark
                    )
                )

                // 5. Test Connection
                val canTest = (apiKey.isNotBlank() || provider.id == "ollama" || provider.id == "custom" || provider.id == "pocket") && testState !is TestState.Testing
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = canTest,
                        onClick = {
                            testState = TestState.Testing
                            scope.launch {
                                val res = testProviderConnection(provider, apiKey, baseUrl)
                                testState = res
                                if (res is TestState.Success && fetchedModels.isEmpty() && res.models.isNotEmpty()) {
                                    fetchedModels = res.models
                                }
                            }
                        }
                    ) {
                        Text("Test Connection", color = if (canTest) AccentLight else TextMuted)
                    }
                    Spacer(Modifier.width(4.dp))
                    when (val t = testState) {
                        is TestState.Testing -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentColor)
                        is TestState.Success -> Icon(Lucide.CircleCheck, null, tint = LocalKryptonColors.current.Success, modifier = Modifier.size(18.dp))
                        is TestState.Failure -> Icon(Lucide.CircleX, null, tint = LocalKryptonColors.current.Error, modifier = Modifier.size(18.dp))
                        TestState.Idle -> {}
                    }
                }
                (testState as? TestState.Failure)?.let {
                    Text(it.message, color = LocalKryptonColors.current.Error, fontSize = 12.sp)
                }
                (testState as? TestState.Success)?.let {
                    Text(it.message, color = LocalKryptonColors.current.Success, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = displayName.isNotBlank() && modelText.isNotBlank(),
                onClick = {
                    onSave(
                        SavedModel(
                            id = initialModel?.id ?: java.util.UUID.randomUUID().toString(),
                            name = displayName.trim(),
                            provider = provider.id,
                            model = modelText.trim(),
                            apiKey = apiKey.trim(),
                            baseUrl = baseUrl.trim(),
                        )
                    )
                }
            ) { Text("Save", color = AccentLight) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) }
        }
    )
}

private sealed class TestState {
    object Idle : TestState()
    object Testing : TestState()
    data class Success(val message: String, val models: List<String> = emptyList()) : TestState()
    data class Failure(val message: String) : TestState()
}

/**
 * Real validation, not just "did the field get filled in" — sends a live,
 * lightweight request (GET /models, or the closest equivalent) and maps
 * common failure modes to a message someone can actually act on.
 */
private suspend fun testProviderConnection(provider: ProviderConfig, apiKey: String, baseUrlOverride: String): TestState =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (provider.id == "pocket") {
            val models = fetchProviderModels(provider, apiKey, baseUrlOverride).getOrDefault(emptyList())
            val localCount = runCatching {
                com.sednium.kryptonide.agent.pocket.StorageModelScanner().scanModels().size
            }.getOrDefault(0)
            val msg = if (localCount > 0) {
                "Pocket Server ready — $localCount local model file(s) discovered on storage."
            } else {
                "Pocket Server ready — ${models.size} local catalog models available."
            }
            return@withContext TestState.Success(msg, models)
        }
        val result = fetchProviderModels(provider, apiKey, baseUrlOverride)
        result.fold(
            onSuccess = { models ->
                if (models.isNotEmpty()) {
                    TestState.Success("Connected — ${models.size} models verified.", models)
                } else {
                    TestState.Success("Connected — endpoint & key verified.", models)
                }
            },
            onFailure = { error ->
                val msg = error.message ?: "Connection failed"
                TestState.Failure(msg)
            }
        )
    }

/**
 * Fetches dynamic models list from provider using the given API key.
 */
private suspend fun fetchProviderModels(provider: ProviderConfig, apiKey: String, baseUrlOverride: String): Result<List<String>> =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (provider.id == "pocket") {
            val localDiscovered = runCatching {
                val scanner = com.sednium.kryptonide.agent.pocket.StorageModelScanner()
                scanner.scanModels().map { it.name }
            }.getOrDefault(emptyList())
            val models = if (localDiscovered.isNotEmpty()) {
                localDiscovered.distinct()
            } else {
                com.sednium.kryptonide.agent.pocket.PocketModels.PREDEFINED_CATALOG.map { it.id }
            }
            return@withContext Result.success(models.filter { it.isNotBlank() })
        }
        try {
            val baseUrl = baseUrlOverride.ifBlank { provider.baseUrl }.trimEnd('/')
            if (baseUrl.isBlank()) return@withContext Result.failure(Exception("No base URL configured for this provider."))

            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val endpoint = provider.modelsEndpoint ?: "/models"
            val url = if (provider.id == "gemini") {
                "$baseUrl$endpoint?key=$apiKey"
            } else {
                "$baseUrl$endpoint"
            }

            val reqBuilder = okhttp3.Request.Builder().url(url).get()
            when {
                provider.id == "anthropic" -> reqBuilder
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                provider.id == "gemini" -> { /* key in query param above */ }
                provider.usesBearerAuth && apiKey.isNotBlank() -> reqBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            val response = client.newCall(reqBuilder.build()).execute()
            val code = response.code
            when {
                response.isSuccessful -> {
                    val bodyStr = response.body?.string() ?: return@withContext Result.success(emptyList())
                    val modelsList = mutableListOf<String>()
                    val json = org.json.JSONObject(bodyStr)
                    if (json.has("data")) {
                        val arr = json.getJSONArray("data")
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            val id = item.optString("id")
                            if (id.isNotBlank()) modelsList.add(id)
                        }
                    } else if (json.has("models")) {
                        val arr = json.getJSONArray("models")
                        for (i in 0 until arr.length()) {
                            val item = arr.getJSONObject(i)
                            val name = item.optString("name").ifBlank { item.optString("id") }
                            val clean = name.removePrefix("models/")
                            val methods = item.optJSONArray("supportedGenerationMethods")
                            if (methods != null) {
                                var canGenerate = false
                                for (j in 0 until methods.length()) {
                                    if (methods.getString(j).contains("generateContent", ignoreCase = true)) {
                                        canGenerate = true
                                        break
                                    }
                                }
                                if (canGenerate && clean.isNotBlank()) modelsList.add(clean)
                            } else if (clean.isNotBlank()) {
                                modelsList.add(clean)
                            }
                        }
                    }
                    Result.success(modelsList.distinct())
                }
                code == 400 || code == 401 || code == 403 -> Result.failure(Exception("Invalid or unauthorized API key (HTTP $code)."))
                code == 404 -> Result.failure(Exception("Endpoint not found (HTTP 404) — check the base URL."))
                code == 429 -> Result.failure(Exception("Rate limited (HTTP 429) — try again shortly."))
                code in 500..599 -> Result.failure(Exception("Provider server error (HTTP $code) — try again later."))
                else -> Result.failure(Exception("HTTP $code"))
            }
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(Exception("Timed out — check your connection or the base URL."))
        } catch (e: java.net.UnknownHostException) {
            Result.failure(Exception("Couldn't reach the base URL — check your internet connection."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

@Composable
private fun CategoryPluginModal(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    category: PluginCategory,
    plugins: List<PluginInfo>,
    onToggleEnabled: (pluginId: String, enabled: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val installed = remember(plugins, category) {
        plugins.filter { it.category == category && it.isInstalled }
    }
    val available = remember(plugins, category) {
        plugins.filter { it.category == category && !it.isInstalled }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AccentLight.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = AccentLight, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, color = TextMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "INSTALLED (${installed.size})",
                    color = AccentLight,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (installed.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceDark.copy(alpha = 0.6f))
                            .border(0.5.dp, BorderDark, RoundedCornerShape(10.dp))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Lucide.Puzzle, null, tint = TextMuted, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "No extensions installed",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Download and install from the Plugins tab to activate them here.",
                                color = TextMuted,
                                fontSize = 11.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    installed.forEach { plugin ->
                        val verText = if (plugin.version.startsWith("v") || plugin.version == "latest") plugin.version else "v${plugin.version}"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (plugin.isEnabled) AccentColor.copy(alpha = 0.08f) else SurfaceDark)
                                .border(
                                    width = 0.5.dp,
                                    color = if (plugin.isEnabled) AccentLight.copy(alpha = 0.35f) else BorderDark,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        plugin.name,
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                if (plugin.isEnabled) Color(0xFF3FB950).copy(alpha = 0.2f)
                                                else TextMuted.copy(alpha = 0.15f)
                                            )
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            if (plugin.isEnabled) "ACTIVE" else "OFF",
                                            color = if (plugin.isEnabled) Color(0xFF3FB950) else TextMuted,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    plugin.description,
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "by ${plugin.author} • $verText",
                                    color = TextMuted.copy(alpha = 0.7f),
                                    fontSize = 10.sp
                                )
                            }

                            Spacer(Modifier.width(10.dp))

                            Switch(
                                checked = plugin.isEnabled,
                                onCheckedChange = { onToggleEnabled(plugin.id, it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = OnAccentColor,
                                    checkedTrackColor = AccentColor,
                                    uncheckedThumbColor = TextMuted,
                                    uncheckedTrackColor = SurfaceDark
                                )
                            )
                        }
                    }
                }

                if (available.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "AVAILABLE IN PLUGIN STORE (${available.size})",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Text(
                        "Download in the Plugins tab to install and activate:",
                        color = TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    available.forEach { plugin ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceDark.copy(alpha = 0.4f))
                                .border(0.5.dp, BorderDark.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    plugin.name,
                                    color = TextPrimary.copy(alpha = 0.85f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "by ${plugin.author} • ${plugin.downloads} downloads",
                                    color = TextMuted,
                                    fontSize = 10.sp
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(BorderDark)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("In Store", color = TextMuted, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = AccentLight)
            }
        }
    )
}

@Composable
private fun KeymapModal(
    plugins: List<PluginInfo>,
    activeKeymapId: String?,
    onActivateKeymap: (keymapPresetId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val defaultPreset = "vscode"
    val installedKeymaps = remember(plugins) {
        plugins.filter {
            it.category == PluginCategory.KEYMAP &&
            it.isInstalled &&
            (it.keymapPresetId ?: it.id) != defaultPreset
        }
    }
    val availableKeymaps = remember(plugins) {
        plugins.filter { it.category == PluginCategory.KEYMAP && !it.isInstalled }
    }

    val isDefaultActive = activeKeymapId == null || activeKeymapId.equals(defaultPreset, ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AccentLight.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Lucide.SlidersHorizontal, contentDescription = null, tint = AccentLight, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Keymaps & Shortcuts", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Select your preferred editor keybinding preset", color = TextMuted, fontSize = 11.sp)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "PRESETS",
                    color = AccentLight,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // VS Code Default
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isDefaultActive) AccentColor.copy(alpha = 0.12f) else SurfaceDark)
                        .border(
                            width = 0.5.dp,
                            color = if (isDefaultActive) AccentLight.copy(alpha = 0.4f) else BorderDark,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            onActivateKeymap(defaultPreset)
                            onDismiss()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("VS Code (Standard)", color = if (isDefaultActive) AccentLight else TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Default Kryptonide keybindings and shortcuts (Ctrl+S, Ctrl+F, Ctrl+Z/Y)", color = TextMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (isDefaultActive) {
                        Icon(Lucide.Check, "Selected", tint = AccentLight, modifier = Modifier.size(18.dp))
                    }
                }

                // Installed keymap plugins
                installedKeymaps.forEach { plugin ->
                    val presetId = plugin.keymapPresetId ?: plugin.id
                    val isSelected = activeKeymapId.equals(presetId, ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) AccentColor.copy(alpha = 0.12f) else SurfaceDark)
                            .border(
                                width = 0.5.dp,
                                color = if (isSelected) AccentLight.copy(alpha = 0.4f) else BorderDark,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                onActivateKeymap(presetId)
                                onDismiss()
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(plugin.name, color = if (isSelected) AccentLight else TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(plugin.description, color = TextMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("by ${plugin.author}", color = TextMuted.copy(alpha = 0.7f), fontSize = 10.sp)
                        }
                        if (isSelected) {
                            Icon(Lucide.Check, "Selected", tint = AccentLight, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                if (availableKeymaps.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "AVAILABLE IN PLUGIN STORE (${availableKeymaps.size})",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Text(
                        "Install from the Plugins tab to activate:",
                        color = TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    availableKeymaps.forEach { plugin ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceDark.copy(alpha = 0.4f))
                                .border(0.5.dp, BorderDark.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(plugin.name, color = TextPrimary.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text("by ${plugin.author} • ${plugin.downloads} downloads", color = TextMuted, fontSize = 10.sp)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(BorderDark)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("In Store", color = TextMuted, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = AccentLight)
            }
        }
    )
}
