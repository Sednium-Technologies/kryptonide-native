package com.sednium.kryptonide.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import com.composables.icons.lucide.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sednium.kryptonide.ui.theme.LocalKryptonColors
import com.composables.icons.lucide.*

// ─── Main Terminal Panel ──────────────────────────────────────────────────────

@Composable
fun TerminalPanel(
    projectName: String,
    initialDir:  String = "",
    onClose:     (() -> Unit)? = null,
    onOpenFile:  ((String) -> Unit)? = null,
    vm:          TerminalViewModel = viewModel()
) {
    val state        by vm.state.collectAsState()
    val listState    = rememberLazyListState()
    var inputValue   by remember { mutableStateOf(TextFieldValue("")) }
    val focusReq     = remember { FocusRequester() }
    val history      = remember { mutableStateListOf<String>() }
    var historyIdx   by remember { mutableStateOf(-1) }
    val context      = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val colors       = LocalKryptonColors.current

    var activeCategory by remember { mutableStateOf("ALL") }

    val executeCurrentInput = {
        val cmd = inputValue.text.trim()
        if (cmd.isNotEmpty()) {
            if (cmd == "clear") {
                vm.clear()
            } else {
                history.add(0, cmd)
                historyIdx = -1
                vm.sendCommand(cmd, onOpenFile)
            }
            inputValue = TextFieldValue("")
        }
    }

    LaunchedEffect(projectName, initialDir) {
        vm.init(projectName, initialDir)
    }

    // Auto-scroll to bottom on new output without jerky animation cancellation
    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) {
            val targetIdx = state.lines.size - 1
            val isNearBottom = (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= targetIdx - 3
            if (isNearBottom || state.lines.size <= 2) {
                listState.scrollToItem(targetIdx)
            }
        }
    }

    // Dynamic quick commands by category
    val quickCommands = remember(activeCategory, state.isTermuxAvailable) {
        when (activeCategory) {
            "GIT" -> listOf("git status", "git log -n 5", "git branch", "git diff", "git add .", "git commit -m \"update\"")
            "FILES" -> listOf("ls -la", "pwd", "tree", "cat ", "du -h", "zip -r backup.zip .", "unzip ", "find . -name \"*.*\"")
            "SYSTEM" -> listOf("neofetch", "free -h", "ps", "top", "uname -a", "df -h", "uptime", "env")
            "DEV" -> listOf("diff ", "sed ", "awk ", "sort ", "base64 ", "md5sum ", "sha256sum ", "xxd ", "code ")
            "NETWORK" -> listOf("ping google.com", "host github.com", "wget ", "curl https://api.github.com")
            "PACKAGES" -> listOf("npm install ", "npm run dev", "npm list", "pip install ", "pip list", "node ", "python ")
            else -> listOf("ls -la", "pwd", "npm list", "git status", "neofetch", "free -h", "tree", "help", "clear")
        }
    }

    // Autocomplete matching commands
    val autoSuggestions = remember(inputValue.text) {
        val query = inputValue.text.trim()
        if (query.isNotEmpty() && !query.contains(" ")) {
            KryptonShellEngine.BUILTIN_COMMANDS.filter { it.startsWith(query.lowercase()) && it != query }
        } else emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.Background)
    ) {
        // ── Toolbar ───────────────────────────────────────────────────────────
        TerminalToolbar(
            state          = state,
            onClose        = onClose,
            onSelectMode   = { vm.setConnectionMode(it) },
            onOpenTermux   = { TermuxBridge.openTermuxAt(context, state.currentDir) },
            onCopyAll      = {
                val clip = ClipData.newPlainText("Terminal Log", vm.getAllOutputText())
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(clip)
                Toast.makeText(context, "Terminal output copied", Toast.LENGTH_SHORT).show()
            },
            onInterrupt    = { vm.sendInterrupt() },
            onClear        = { vm.clear() },
            onReconnect    = { vm.reconnect() }
        )

        // ── Quick Command Category Bar ────────────────────────────────────────
        QuickCategoryBar(
            activeCategory   = activeCategory,
            onSelectCategory = { activeCategory = it }
        )

        // ── Quick Command Chips with Smooth AnimatedContent ───────────────────
        QuickCommandChips(
            commands  = quickCommands,
            onCommand = { cmd ->
                if (cmd == "clear") {
                    vm.clear()
                } else if (cmd.endsWith(" ")) {
                    inputValue = TextFieldValue(cmd, selection = androidx.compose.ui.text.TextRange(cmd.length))
                    focusReq.requestFocus()
                    keyboardController?.show()
                } else {
                    vm.sendCommand(cmd, onOpenFile)
                }
            }
        )

        // ── Output Scroll Area ────────────────────────────────────────────────
        Box(modifier = Modifier.weight(1f)) {
            if (state.lines.isEmpty() && !state.isConnected) {
                TerminalEmptyState(isTermuxAvailable = state.isTermuxAvailable)
            } else {
                LazyColumn(
                    state          = listState,
                    modifier       = Modifier
                        .fillMaxSize()
                        .clickable {
                            focusReq.requestFocus()
                            keyboardController?.show()
                        },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    items(state.lines, key = { it.id }) { line ->
                        TerminalLineItem(
                            line   = line,
                            isDark = colors.isDark,
                            onCopy = {
                                val plain = AnsiParser.stripAnsi(line.text)
                                val clip = ClipData.newPlainText("Terminal Line", plain)
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(clip)
                                Toast.makeText(context, "Line copied", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }

                    // Authentic Termux-style inline active prompt & input field
                    if (state.isConnected) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clickable {
                                        focusReq.requestFocus()
                                        keyboardController?.show()
                                    }
                            ) {
                                Text(
                                    "$ ", 
                                    color = colors.Primary, 
                                    fontSize = 13.sp, 
                                    fontFamily = FontFamily.Monospace, 
                                    fontWeight = FontWeight.Bold
                                )
                                BasicTextField(
                                    value         = inputValue,
                                    onValueChange = { inputValue = it },
                                    modifier      = Modifier
                                        .weight(1f)
                                        .focusRequester(focusReq)
                                        .onKeyEvent { event ->
                                            if (event.key == Key.Enter && event.type == KeyEventType.KeyUp) {
                                                executeCurrentInput()
                                                true
                                            } else false
                                        },
                                    enabled       = state.isConnected && !state.isExecuting,
                                    textStyle     = TextStyle(
                                        color      = colors.OnBackground,
                                        fontSize   = 13.sp,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    cursorBrush   = SolidColor(colors.Primary),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                    keyboardActions = KeyboardActions(onSend = { executeCurrentInput() }),
                                    singleLine    = true
                                )
                                if (state.isExecuting) {
                                    Spacer(Modifier.width(6.dp))
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.5.dp,
                                        color = colors.Primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Autocomplete Bar (if suggestions match) with Smooth Spring Animation ──
        AnimatedVisibility(
            visible = autoSuggestions.isNotEmpty(),
            enter   = fadeIn(tween(150)) + expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)),
            exit    = fadeOut(tween(100)) + shrinkVertically(animationSpec = tween(100))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.SurfaceVar)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                autoSuggestions.forEach { suggestion ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.Surface)
                            .border(BorderStroke(0.5.dp, colors.Border), RoundedCornerShape(4.dp))
                            .clickable {
                                inputValue = TextFieldValue(
                                    "$suggestion ",
                                    selection = androidx.compose.ui.text.TextRange(suggestion.length + 1)
                                )
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(suggestion, color = colors.OnSurface, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // ── Termux-style Accessory Keys Bar at Bottom ─────────────────────────
        Surface(
            color = colors.Surface,
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
        ) {
            Column {
                HorizontalDivider(color = colors.Border, thickness = 0.5.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        TerminalAccessoryBar(
                            onInterrupt   = { vm.sendInterrupt() },
                            onTab         = { vm.sendTab() },
                            onHistoryUp   = {
                                if (history.isNotEmpty()) {
                                    historyIdx = (historyIdx + 1).coerceAtMost(history.size - 1)
                                    val text = history[historyIdx]
                                    inputValue = TextFieldValue(text, selection = androidx.compose.ui.text.TextRange(text.length))
                                }
                            },
                            onHistoryDown = {
                                historyIdx = (historyIdx - 1).coerceAtLeast(-1)
                                val text = if (historyIdx == -1) "" else history[historyIdx]
                                inputValue = TextFieldValue(text, selection = androidx.compose.ui.text.TextRange(text.length))
                            },
                            onClear       = { vm.clear() },
                            onInsertText  = { char ->
                                val current = inputValue.text
                                val sel = inputValue.selection
                                val newText = StringBuilder(current).insert(sel.start, char).toString()
                                inputValue = TextFieldValue(
                                    text = newText,
                                    selection = androidx.compose.ui.text.TextRange(sel.start + char.length)
                                )
                            }
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    // Execute / Return button on accessory bar
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (inputValue.text.isNotEmpty()) colors.Primary else colors.SurfaceVar)
                            .clickable(enabled = inputValue.text.isNotEmpty() && !state.isExecuting) {
                                executeCurrentInput()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Lucide.CornerDownLeft,
                            contentDescription = "Execute",
                            tint = if (inputValue.text.isNotEmpty()) colors.OnPrimary else colors.Muted,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}

// ─── Toolbar ──────────────────────────────────────────────────────────────────

@Composable
private fun TerminalToolbar(
    state:        TerminalState,
    onClose:      (() -> Unit)?,
    onSelectMode: (TerminalConnectionMode) -> Unit,
    onOpenTermux: () -> Unit,
    onCopyAll:    () -> Unit,
    onInterrupt:  () -> Unit,
    onClear:      () -> Unit,
    onReconnect:  () -> Unit
) {
    val colors = LocalKryptonColors.current
    var showModeMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.Surface)
            .border(BorderStroke(0.5.dp, colors.Border))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onClose != null) {
            IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                Icon(
                    Lucide.ArrowLeft,
                    contentDescription = "Back to Editor",
                    tint = colors.OnSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
        }

        // Mode Selector Dropdown
        Box {
            val (modeColor, modeBg) = when (state.connectionMode) {
                TerminalConnectionMode.KRYPTON -> colors.Success to colors.Success.copy(alpha = 0.12f)
                TerminalConnectionMode.TERMUX  -> colors.Primary to colors.Primary.copy(alpha = 0.12f)
                TerminalConnectionMode.SYSTEM  -> colors.Warning to colors.Warning.copy(alpha = 0.12f)
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(modeBg)
                    .border(BorderStroke(0.5.dp, modeColor.copy(alpha = 0.3f)), RoundedCornerShape(6.dp))
                    .clickable { showModeMenu = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(modeColor)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = when (state.connectionMode) {
                        TerminalConnectionMode.KRYPTON -> "Kryptonide Shell"
                        TerminalConnectionMode.TERMUX  -> "Termux Bridge"
                        TerminalConnectionMode.SYSTEM  -> "System sh"
                    },
                    color = modeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    Lucide.ChevronDown, null,
                    tint = colors.Muted,
                    modifier = Modifier.size(16.dp)
                )
            }

            DropdownMenu(
                expanded = showModeMenu,
                onDismissRequest = { showModeMenu = false },
                modifier = Modifier
                    .background(colors.Surface)
                    .border(BorderStroke(0.5.dp, colors.Border))
            ) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Kryptonide Shell (Recommended)", color = colors.OnSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Built-in Git, POSIX file tools, curl, tree & fallback", color = colors.Muted, fontSize = 10.sp)
                        }
                    },
                    onClick = { onSelectMode(TerminalConnectionMode.KRYPTON); showModeMenu = false }
                )
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Termux Bridge", color = colors.OnSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Dispatches commands directly to Termux app", color = colors.Muted, fontSize = 10.sp)
                        }
                    },
                    onClick = { onSelectMode(TerminalConnectionMode.TERMUX); showModeMenu = false }
                )
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("System /bin/sh", color = colors.OnSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Direct Android OS system shell", color = colors.Muted, fontSize = 10.sp)
                        }
                    },
                    onClick = { onSelectMode(TerminalConnectionMode.SYSTEM); showModeMenu = false }
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // Working directory pill
        val rawDir = state.currentDir.substringAfterLast("Kryptonide", "").ifEmpty {
            state.currentDir.substringAfterLast("KryptonIDE", "").ifEmpty { state.currentDir.substringAfterLast("/") }
        }
        val dirDisplay = rawDir.removePrefix("/")
        Text(
            text = if (dirDisplay.isEmpty()) "~" else "~/$dirDisplay",
            color = colors.Muted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )

        Spacer(Modifier.weight(1f))

        // Open in Termux button
        if (state.isTermuxAvailable) {
            IconButton(onClick = onOpenTermux, modifier = Modifier.size(28.dp)) {
                Icon(Lucide.ExternalLink, "Open in Termux", tint = colors.Primary, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(2.dp))
        }

        // Copy All Output button
        IconButton(onClick = onCopyAll, modifier = Modifier.size(28.dp)) {
            Icon(Lucide.Copy, "Copy Output", tint = colors.Muted, modifier = Modifier.size(15.dp))
        }

        // Interrupt Ctrl+C button
        IconButton(onClick = onInterrupt, modifier = Modifier.size(28.dp)) {
            Text("^C", color = colors.Warning, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }

        // Clear button
        IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
            Icon(Lucide.Trash2, "Clear", tint = colors.Muted, modifier = Modifier.size(16.dp))
        }

        // Reconnect button
        IconButton(onClick = onReconnect, modifier = Modifier.size(28.dp)) {
            Icon(Lucide.RefreshCw, "Reconnect", tint = colors.Muted, modifier = Modifier.size(16.dp))
        }

        // Close / Dismiss Sheet button (if provided)
        if (onClose != null) {
            Spacer(Modifier.width(2.dp))
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Lucide.X, "Close Terminal", tint = colors.OnSurface, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ─── Quick Category Bar ───────────────────────────────────────────────────────

@Composable
private fun QuickCategoryBar(
    activeCategory:   String,
    onSelectCategory: (String) -> Unit
) {
    val colors = LocalKryptonColors.current
    val categories = listOf("ALL", "FILES", "GIT", "PACKAGES", "SYSTEM", "DEV", "NETWORK")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.Surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        categories.forEach { cat ->
            val isSelected = cat == activeCategory
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) colors.Primary else Color.Transparent,
                animationSpec = tween(180),
                label = "catBgAnim"
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) colors.OnPrimary else colors.Muted,
                animationSpec = tween(180),
                label = "catTextAnim"
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(bgColor)
                    .clickable { onSelectCategory(cat) }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = cat,
                    color = textColor,
                    fontSize = 10.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

// ─── Quick Command Chips ──────────────────────────────────────────────────────

@Composable
private fun QuickCommandChips(commands: List<String>, onCommand: (String) -> Unit) {
    val colors = LocalKryptonColors.current

    AnimatedContent(
        targetState = commands,
        transitionSpec = {
            (fadeIn(tween(180)) + slideInHorizontally(tween(180)) { it / 6 })
                .togetherWith(fadeOut(tween(120)) + slideOutHorizontally(tween(120)) { -it / 6 })
        },
        label = "quickCommandsAnim"
    ) { currentCommands ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.Background)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            currentCommands.forEach { cmd ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.SurfaceVar)
                        .border(BorderStroke(0.5.dp, colors.Border.copy(alpha = 0.7f)), RoundedCornerShape(4.dp))
                        .clickable { onCommand(cmd) }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(cmd, color = colors.OnSurface, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

// ─── Output Line Item with ANSI Parsing ───────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalLineItem(line: TerminalLine, isDark: Boolean, onCopy: () -> Unit) {
    val colors = LocalKryptonColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onCopy
            )
            .padding(vertical = 0.5.dp),
        verticalAlignment = Alignment.Top
    ) {
        when (line.type) {
            TerminalLineType.INPUT -> {
                Text("$ ", color = colors.Primary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(
                    text = line.text.removePrefix("$ "),
                    color = colors.OnBackground,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }
            TerminalLineType.SYSTEM -> {
                Text("# ", color = colors.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                val annotated = remember(line.text, isDark) { AnsiParser.parse(line.text, colors.Muted, isDark) }
                Text(
                    text = annotated,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp,
                    modifier = Modifier.weight(1f)
                )
            }
            TerminalLineType.ERROR -> {
                Text("! ", color = colors.Error, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                val annotated = remember(line.text, isDark) { AnsiParser.parse(line.text, colors.Error, isDark) }
                Text(
                    text = annotated,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp,
                    modifier = Modifier.weight(1f)
                )
            }
            TerminalLineType.OUTPUT -> {
                val annotated = remember(line.text, isDark) { AnsiParser.parse(line.text, colors.OnBackground, isDark) }
                Text(
                    text = annotated,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ─── Authentic Blinking Cursor ────────────────────────────────────────────────

@Composable
private fun TerminalCursor(color: Color) {
    val anim = rememberInfiniteTransition(label = "cursor")
    val isVisible by anim.animateFloat(
        initialValue = 1f,
        targetValue  = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1000
                1f at 0
                1f at 500
                0f at 501
                0f at 1000
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "cursorBlink"
    )
    if (isVisible > 0.5f) {
        Text("█", color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    } else {
        Text(" ", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

// ─── Empty State ──────────────────────────────────────────────────────────────

@Composable
private fun TerminalEmptyState(isTermuxAvailable: Boolean) {
    val colors = LocalKryptonColors.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(Lucide.Terminal, null, tint = colors.Primary.copy(alpha = 0.7f), modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text("Kryptonide Terminal", color = colors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Full POSIX commands, native package manager (npm/pip), and built-in Git.",
                color = colors.Muted, fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text("✓ Pure standalone engine ready — no external dependencies.", color = colors.Success, fontSize = 11.sp)
        }
    }
}

// ─── Mobile Accessory Key Bar ─────────────────────────────────────────────────

@Composable
private fun TerminalAccessoryBar(
    onInterrupt:  () -> Unit,
    onTab:        () -> Unit,
    onHistoryUp:  () -> Unit,
    onHistoryDown:() -> Unit,
    onClear:      () -> Unit,
    onInsertText: (String) -> Unit
) {
    val colors = LocalKryptonColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.Surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        AccessoryKey("ESC", textColor = colors.Error) { onClear() }
        AccessoryKey("TAB", textColor = colors.Primary) { onTab() }
        AccessoryKey("CTRL+C", textColor = colors.Warning) { onInterrupt() }
        AccessoryKey("↑") { onHistoryUp() }
        AccessoryKey("↓") { onHistoryDown() }
        AccessoryKey("|") { onInsertText("|") }
        AccessoryKey("&") { onInsertText("&") }
        AccessoryKey(";") { onInsertText(";") }
        AccessoryKey(">") { onInsertText(">") }
        AccessoryKey("<") { onInsertText("<") }
        AccessoryKey("$") { onInsertText("$") }
        AccessoryKey("/") { onInsertText("/") }
        AccessoryKey("-") { onInsertText("-") }
        AccessoryKey("~") { onInsertText("~") }
        AccessoryKey("\"") { onInsertText("\"") }
        AccessoryKey("'") { onInsertText("'") }
        AccessoryKey("CLEAR") { onClear() }
    }
}

@Composable
private fun AccessoryKey(
    text:      String,
    textColor: Color? = null,
    onClick:   () -> Unit
) {
    val colors = LocalKryptonColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.SurfaceVar)
            .border(BorderStroke(0.5.dp, colors.Border.copy(alpha = 0.6f)), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor ?: colors.OnSurface,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )
    }
}


