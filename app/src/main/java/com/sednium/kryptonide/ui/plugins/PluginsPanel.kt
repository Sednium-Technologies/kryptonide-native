package com.sednium.kryptonide.ui.plugins

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.OpenableColumns
import com.sednium.kryptonide.ui.theme.LocalKryptonColors
import com.composables.icons.lucide.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsPanel(manager: PluginManager) {
    val state    by manager.state.collectAsStateWithLifecycle()
    val colors   = LocalKryptonColors.current
    val context  = androidx.compose.ui.platform.LocalContext.current
    var query    by remember { mutableStateOf("") }
    var viewingLogsForPlugin by remember { mutableStateOf<PluginInfo?>(null) }
    val filtered = remember(state.plugins, state.activeCategory, state.searchQuery) {
        state.plugins.filter { p ->
            (state.activeCategory == PluginCategory.ALL || p.category == state.activeCategory) &&
            (state.searchQuery.isBlank() ||
                p.name.contains(state.searchQuery, ignoreCase = true) ||
                p.description.contains(state.searchQuery, ignoreCase = true) ||
                p.author.contains(state.searchQuery, ignoreCase = true))
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            var fileName = "custom-extension.zip"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex) ?: fileName
                }
            }
            manager.installFromFile(fileName, uri.toString())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.Background)
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.Surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Lucide.Puzzle, null, tint = colors.Secondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Extensions", color = colors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))

            IconButton(
                onClick = { filePicker.launch("*/*") },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Lucide.FolderOpen,
                    contentDescription = "Install from VSIX / ZIP",
                    tint = colors.Primary,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            Text("${filtered.size} results", color = colors.Muted, fontSize = 11.sp)
        }

        HorizontalDivider(color = colors.Border, thickness = 0.5.dp)

        // ── Search ────────────────────────────────────────────────────────────
        OutlinedTextField(
            value         = query,
            onValueChange = { query = it; manager.search(it) },
            placeholder   = { Text("Search extensions…", color = colors.Muted, fontSize = 13.sp) },
            leadingIcon   = { Icon(Lucide.Search, null, tint = colors.Muted, modifier = Modifier.size(17.dp)) },
            trailingIcon  = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = ""; manager.search("") }) {
                        Icon(Lucide.X, "Clear", tint = colors.Muted, modifier = Modifier.size(15.dp))
                    }
                }
            },
            modifier    = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine  = true,
            shape       = RoundedCornerShape(10.dp),
            colors      = TextFieldDefaults.outlinedTextFieldColors(
                containerColor       = colors.Surface,
                focusedBorderColor   = colors.Primary,
                unfocusedBorderColor = colors.Border,
                textColor            = colors.OnBackground,
            )
        )

        // ── Category filter tabs ──────────────────────────────────────────────
        LazyRow(
            modifier            = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding      = PaddingValues(horizontal = 2.dp)
        ) {
            items(PluginCategory.values()) { cat ->
                val isActive = state.activeCategory == cat
                val bg by animateColorAsState(
                    if (isActive) colors.Primary else colors.Surface,
                    animationSpec = tween(180), label = "catBg"
                )
                val fg by animateColorAsState(
                    if (isActive) colors.OnPrimary else colors.Muted,
                    animationSpec = tween(180), label = "catFg"
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(bg)
                        .border(0.5.dp, if (isActive) colors.Primary else colors.Border, RoundedCornerShape(20.dp))
                        .clickable { manager.setCategory(cat) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(cat.label, color = fg, fontSize = 12.sp, fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }

        HorizontalDivider(color = colors.Border, thickness = 0.5.dp, modifier = Modifier.padding(top = 6.dp))

        // ── Extension list ────────────────────────────────────────────────────
        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Lucide.SearchX, null, tint = colors.Muted, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No extensions found", color = colors.Muted, fontSize = 14.sp)
                }
            }
        } else {
            LazyColumn(
                modifier       = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                items(filtered, key = { it.id }) { plugin ->
                    PluginCard(
                        plugin      = plugin,
                        colors      = colors,
                        onInstall   = { manager.installExtension(plugin.id) },
                        onUninstall = { manager.uninstallExtension(plugin.id) },
                        onViewLogs  = { viewingLogsForPlugin = plugin }
                    )
                }
            }
        }

        if (viewingLogsForPlugin != null) {
            val p = viewingLogsForPlugin!!
            AlertDialog(
                onDismissRequest = { viewingLogsForPlugin = null },
                containerColor = colors.Surface,
                title = {
                    Text("${p.name} Installation Logs", color = colors.OnBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                            .background(colors.Background, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        if (p.installLogs.isEmpty()) {
                            Text("No output logged yet.", color = colors.Muted, fontSize = 12.sp)
                        } else {
                            p.installLogs.forEach { logLine ->
                                Text(
                                    text = logLine,
                                    color = if (logLine.startsWith("✗")) colors.Error else if (logLine.startsWith("✓")) colors.Success else colors.OnBackground,
                                    fontSize = 11.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewingLogsForPlugin = null }) {
                        Text("Close", color = colors.Primary)
                    }
                }
            )
        }
    }
}

// ── Extension card ────────────────────────────────────────────────────────────
@Composable
private fun PluginCard(
    plugin:      PluginInfo,
    colors:      com.sednium.kryptonide.ui.theme.KryptonColorPalette,
    onInstall:   () -> Unit,
    onUninstall: () -> Unit,
    onViewLogs:  () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Material Icon in rounded box
            val iconVec = when (plugin.category) {
                PluginCategory.THEME -> Lucide.Palette
                PluginCategory.LANGUAGE -> Lucide.Code
                PluginCategory.FORMATTER -> Lucide.Wand
                PluginCategory.PRODUCTIVITY -> Lucide.Zap
                PluginCategory.SNIPPET -> Lucide.Braces
                else -> Lucide.Puzzle
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.SurfaceVar)
                    .border(0.5.dp, colors.Border, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(iconVec, contentDescription = null, tint = colors.Primary, modifier = Modifier.size(22.dp))
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    plugin.name,
                    color      = colors.OnBackground,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(plugin.description, color = colors.Muted, fontSize = 12.sp, maxLines = 2)
                Spacer(Modifier.height(5.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(plugin.author, color = colors.Secondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text("•", color = colors.Muted.copy(alpha = 0.4f), fontSize = 10.sp)
                    Text("v${plugin.version}", color = colors.Muted, fontSize = 11.sp)
                    if (plugin.downloads.isNotEmpty()) {
                        Text("•", color = colors.Muted.copy(alpha = 0.4f), fontSize = 10.sp)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(Lucide.Download, null, tint = colors.Muted, modifier = Modifier.size(11.dp))
                            Text(plugin.downloads, color = colors.Muted, fontSize = 11.sp)
                        }
                    }
                    if (plugin.rating > 0f) {
                        Text("•", color = colors.Muted.copy(alpha = 0.4f), fontSize = 10.sp)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                Lucide.Star,
                                contentDescription = "Rating",
                                tint = Color(0xFFFFB800),
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                "%.1f".format(plugin.rating),
                                color = colors.OnBackground.copy(alpha = 0.9f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            // Action button(s)
            Column(
                horizontalAlignment  = Alignment.End,
                verticalArrangement  = Arrangement.spacedBy(4.dp)
            ) {
                if (plugin.isInstalling) {
                    val animatedProgress by animateFloatAsState(
                        targetValue = plugin.installProgress.coerceIn(0f, 1f),
                        animationSpec = spring(stiffness = Spring.StiffnessLow),
                        label = "installProgressPill"
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.Primary.copy(alpha = 0.12f))
                            .border(0.5.dp, colors.Primary.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            CircularProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier.size(12.dp),
                                color = colors.Primary,
                                trackColor = colors.Primary.copy(alpha = 0.2f),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "${(animatedProgress * 100).toInt()}%",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.Primary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                } else if (!plugin.isInstalled) {
                    // Install
                    Button(
                        onClick        = onInstall,
                        colors         = ButtonDefaults.buttonColors(containerColor = colors.Primary),
                        shape          = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier       = Modifier.height(30.dp)
                    ) {
                        Text("Install", color = colors.OnPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    plugin.installError?.let {
                        Text(
                            it, color = colors.Error, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.widthIn(max = 140.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                    }
                } else {
                    // Installed: show "Installed" status badge + "Delete" option
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        // Installed badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.SurfaceVar)
                                .border(BorderStroke(0.5.dp, colors.Border), RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Lucide.Check, null, tint = colors.Success, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("Installed", color = colors.Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                            }
                        }

                        // Delete option button (compact 26dp matching badge height)
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.Error.copy(alpha = 0.12f))
                                .border(BorderStroke(0.5.dp, colors.Error.copy(alpha = 0.25f)), RoundedCornerShape(6.dp))
                                .clickable(onClick = onUninstall),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Lucide.Trash2,
                                contentDescription = "Delete",
                                tint = colors.Error,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }

        // Full-width real-time installation and download progress
        AnimatedVisibility(
            visible = plugin.isInstalling,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            val animatedProgress by animateFloatAsState(
                targetValue = plugin.installProgress.coerceIn(0f, 1f),
                animationSpec = spring(stiffness = Spring.StiffnessLow),
                label = "installProgressFull"
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.SurfaceVar.copy(alpha = 0.6f))
                    .border(0.5.dp, colors.Border, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(colors.Primary)
                        )
                        Text(
                            text = plugin.installStage ?: "Installing",
                            fontSize = 11.sp,
                            color = colors.OnSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${(animatedProgress * 100).toInt()}%",
                            fontSize = 11.sp,
                            color = colors.Primary,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        if (plugin.installLogs.isNotEmpty()) {
                            Text(
                                text = "Logs",
                                fontSize = 10.sp,
                                color = colors.Secondary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { onViewLogs() }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = colors.Primary,
                    trackColor = colors.Background
                )

                val latestLog = plugin.installLogs.lastOrNull() ?: plugin.installStatus ?: "Running install…"
                Text(
                    text = latestLog,
                    fontSize = 10.sp,
                    color = colors.Muted,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        HorizontalDivider(color = colors.Border.copy(alpha = 0.4f), thickness = 0.3.dp)
    }
}
