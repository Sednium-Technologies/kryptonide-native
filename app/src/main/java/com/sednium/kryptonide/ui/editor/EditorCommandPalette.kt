package com.sednium.kryptonide.ui.editor

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

import com.sednium.kryptonide.ui.theme.LocalKryptonColors

// ── Colours ─────────────────────────
private val Bg: Color @Composable get() = LocalKryptonColors.current.Background
private val Surface: Color @Composable get() = LocalKryptonColors.current.Surface
private val Surface2: Color @Composable get() = LocalKryptonColors.current.SurfaceVar
private val Border: Color @Composable get() = LocalKryptonColors.current.Border
private val TextPri: Color @Composable get() = LocalKryptonColors.current.OnBackground
private val TextMut: Color @Composable get() = LocalKryptonColors.current.Muted
private val Accent: Color @Composable get() = LocalKryptonColors.current.Primary
private val AccentLt: Color @Composable get() = LocalKryptonColors.current.Secondary
private val Green: Color @Composable get() = LocalKryptonColors.current.Success
private val Yellow: Color @Composable get() = LocalKryptonColors.current.Warning
private val Red: Color @Composable get() = LocalKryptonColors.current.Error

// ══════════════════════════════════════════════════════════════
//  DATA MODEL
// ══════════════════════════════════════════════════════════════

enum class PaletteCategory(val label: String) {
    EDIT("Edit"),
    SELECTION("Selection"),
    VIEW("View"),
    GO("Go To"),
    AI("AI"),
    FILE("File"),
    GIT("Git"),
    RUN("Run"),
}

data class PaletteCommand(
    val id:          String,
    val title:       String,
    val subtitle:    String    = "",
    val category:    PaletteCategory,
    val icon:        ImageVector,
    val shortcut:    String    = "",
    val action:      () -> Unit,
)

// ══════════════════════════════════════════════════════════════
//  COMMAND PALETTE COMPOSABLE
// ══════════════════════════════════════════════════════════════

@Composable
fun EditorCommandPalette(
    visible:        Boolean,
    soraHost:       SoraEditorHost,
    pressedWord:    String    = "",
    pressedLine:    Int       = 1,
    onDismiss:      () -> Unit,
    onRun:          () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenFiles:    () -> Unit = {},
    onOpenAi:       () -> Unit = {},
    onAskAi:        (String) -> Unit = {},
    onOpenGit:      () -> Unit = {},
    onOpenSearch:   () -> Unit = {},
    onFormat:       () -> Unit = {},
    onSave:         () -> Unit = {},
) {
    if (!visible) return

    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val allCommands = remember(pressedWord, pressedLine) {
        buildCommands(
            soraHost       = soraHost,
            word           = pressedWord,
            line           = pressedLine,
            onRun          = onRun,
            onOpenTerminal = onOpenTerminal,
            onOpenFiles    = onOpenFiles,
            onOpenAi       = onOpenAi,
            onAskAi        = onAskAi,
            onOpenGit      = onOpenGit,
            onOpenSearch   = onOpenSearch,
            onDismiss      = onDismiss,
            onFormat       = onFormat,
            onSave         = onSave,
        )
    }

    val filtered = remember(query, allCommands) {
        if (query.isBlank()) allCommands
        else {
            val q = query.trim().lowercase()
            allCommands.filter { cmd ->
                cmd.title.lowercase().contains(q)    ||
                cmd.subtitle.lowercase().contains(q) ||
                cmd.category.label.lowercase().contains(q)
            }
        }
    }

    val grouped = remember(filtered) { filtered.groupBy { it.category } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows  = false,
        )
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(onClick = onDismiss)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, start = 12.dp, end = 12.dp, bottom = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Surface)
                    .border(1.dp, Border, RoundedCornerShape(16.dp))
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Surface2)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Lucide.Search, null, tint = TextMut, modifier = Modifier.size(18.dp))
                    BasicTextField(
                        value         = query,
                        onValueChange = { query = it },
                        modifier      = Modifier.weight(1f).focusRequester(focusRequester),
                        singleLine    = true,
                        textStyle     = TextStyle(color = TextPri, fontSize = 15.sp),
                        cursorBrush   = SolidColor(AccentLt),
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text("> Command or search…", color = TextMut, fontSize = 15.sp)
                            }
                            inner()
                        }
                    )
                    if (query.isNotEmpty()) {
                        Icon(
                            Lucide.X, "Clear", tint = TextMut,
                            modifier = Modifier.size(16.dp).clickable { query = "" }
                        )
                    }
                }

                HorizontalDivider(color = Border, thickness = 1.dp)

                if (pressedWord.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Accent.copy(alpha = 0.08f))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Lucide.Code, null, tint = AccentLt, modifier = Modifier.size(14.dp))
                        Text(
                            "\"$pressedWord\"  ·  Line $pressedLine",
                            color = AccentLt, fontSize = 12.sp, fontWeight = FontWeight.Medium
                        )
                    }
                    HorizontalDivider(color = Border, thickness = 1.dp)
                }

                LazyColumn(
                    modifier       = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 480.dp),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    grouped.forEach { (category, commands) ->
                        item(key = "header_${category.name}") {
                            Text(
                                category.label.uppercase(),
                                color = TextMut, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(commands, key = { it.id }) { cmd ->
                            CommandRow(
                                command  = cmd,
                                query    = query,
                                onClick  = {
                                    onDismiss()
                                    cmd.action()
                                }
                            )
                        }
                    }

                    if (filtered.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No commands found for \"$query\"", color = TextMut, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(visible) {
        if (visible) {
            kotlinx.coroutines.delay(80)
            focusRequester.requestFocus()
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
private fun CommandRow(
    command: PaletteCommand,
    query:   String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(command.category.iconBg()),
            contentAlignment = Alignment.Center
        ) {
            Icon(command.icon, null, tint = command.category.iconTint(), modifier = Modifier.size(16.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                command.title,
                color      = TextPri,
                fontSize   = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
            if (command.subtitle.isNotEmpty()) {
                Text(
                    command.subtitle,
                    color    = TextMut,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (command.shortcut.isNotEmpty()) {
            Text(
                command.shortcut,
                color    = TextMut,
                fontSize = 10.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Border)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun PaletteCategory.iconBg(): Color {
    val c = LocalKryptonColors.current
    return when (this) {
        PaletteCategory.EDIT      -> c.Secondary.copy(alpha = 0.12f)
        PaletteCategory.SELECTION -> c.Primary.copy(alpha = 0.12f)
        PaletteCategory.VIEW      -> c.Secondary.copy(alpha = 0.10f)
        PaletteCategory.GO        -> c.Success.copy(alpha = 0.12f)
        PaletteCategory.AI        -> c.Primary.copy(alpha = 0.14f)
        PaletteCategory.FILE      -> c.Warning.copy(alpha = 0.12f)
        PaletteCategory.GIT       -> c.Error.copy(alpha = 0.12f)
        PaletteCategory.RUN       -> c.Success.copy(alpha = 0.12f)
    }
}

@Composable
private fun PaletteCategory.iconTint(): Color {
    val c = LocalKryptonColors.current
    return when (this) {
        PaletteCategory.EDIT      -> c.Secondary
        PaletteCategory.SELECTION -> c.Primary
        PaletteCategory.VIEW      -> c.Secondary
        PaletteCategory.GO        -> c.Success
        PaletteCategory.AI        -> c.Primary
        PaletteCategory.FILE      -> c.Warning
        PaletteCategory.GIT       -> c.Error
        PaletteCategory.RUN       -> c.Success
    }
}

private fun buildCommands(
    soraHost:       SoraEditorHost,
    word:           String,
    line:           Int,
    onRun:          () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenFiles:    () -> Unit,
    onOpenAi:       () -> Unit,
    onAskAi:        (String) -> Unit,
    onOpenGit:      () -> Unit,
    onOpenSearch:   () -> Unit,
    @Suppress("UNUSED_PARAMETER") onDismiss: () -> Unit,
    onFormat:       () -> Unit,
    onSave:         () -> Unit,
): List<PaletteCommand> = buildList {

    // ── EDIT ──────────────────────────────────────────────────
    add(PaletteCommand(id = "undo", title = "Undo", category = PaletteCategory.EDIT, icon = Lucide.Undo2, shortcut = "Ctrl+Z", action = { soraHost.undo() }))
    add(PaletteCommand(id = "redo", title = "Redo", category = PaletteCategory.EDIT, icon = Lucide.Redo2, shortcut = "Ctrl+Y", action = { soraHost.redo() }))
    add(PaletteCommand(id = "format", title = "Format Document", subtitle = "Auto-indent and format the file", category = PaletteCategory.EDIT, icon = Lucide.Wand, shortcut = "Shift+Alt+F", action = { onFormat() }))
    add(PaletteCommand(id = "toggle_comment", title = "Toggle Line Comment", subtitle = "Comment or uncomment current line", category = PaletteCategory.EDIT, icon = Lucide.Code, shortcut = "Ctrl+/", action = { soraHost.toggleLineComment() }))
    add(PaletteCommand(id = "duplicate_line", title = "Duplicate Line", subtitle = "Copy current line below", category = PaletteCategory.EDIT, icon = Lucide.Copy, shortcut = "Shift+Alt+Down", action = { soraHost.duplicateLine() }))
    add(PaletteCommand(id = "delete_line", title = "Delete Line", subtitle = "Remove current line", category = PaletteCategory.EDIT, icon = Lucide.Trash2, shortcut = "Ctrl+Shift+K", action = { soraHost.deleteLine() }))
    add(PaletteCommand(id = "expand_emmet", title = "Expand Emmet Abbreviation", subtitle = "Expand HTML/CSS abbreviation at cursor", category = PaletteCategory.EDIT, icon = Lucide.Zap, shortcut = "Tab", action = { soraHost.expandEmmetAtCursor() }))
    add(PaletteCommand(id = "indent", title = "Indent Line", category = PaletteCategory.EDIT, icon = Lucide.IndentIncrease, action = { soraHost.insertText("  ") }))

    // ── SELECTION ─────────────────────────────────────────────
    add(PaletteCommand(id = "select_all", title = "Select All", category = PaletteCategory.SELECTION, icon = Lucide.MousePointer, shortcut = "Ctrl+A", action = { soraHost.selectAllText() }))

    // ── VIEW ──────────────────────────────────────────────────
    add(PaletteCommand(id = "toggle_word_wrap", title = "Toggle Word Wrap", category = PaletteCategory.VIEW, icon = Lucide.WrapText, action = { soraHost.toggleWordWrap() }))
    add(PaletteCommand(id = "increase_font", title = "Increase Font Size", category = PaletteCategory.VIEW, icon = Lucide.ZoomIn, action = { soraHost.increaseFontSize() }))
    add(PaletteCommand(id = "decrease_font", title = "Decrease Font Size", category = PaletteCategory.VIEW, icon = Lucide.ZoomOut, action = { soraHost.decreaseFontSize() }))
    add(PaletteCommand(id = "scroll_top", title = "Scroll to Top", category = PaletteCategory.VIEW, icon = Lucide.ArrowUp, action = { soraHost.scrollToTop() }))

    // ── GO TO ─────────────────────────────────────────────────
    add(PaletteCommand(id = "find", title = "Find & Replace in File", subtitle = "Search or replace text in the current file", category = PaletteCategory.GO, icon = Lucide.Search, shortcut = "Ctrl+F", action = { onOpenSearch() }))
    add(PaletteCommand(id = "goto_line", title = "Go to Line $line", subtitle = "Current line", category = PaletteCategory.GO, icon = Lucide.ArrowRight, action = { soraHost.gotoLine(line) }))

    // ── AI ───────────────────────────────────────────────────
    add(PaletteCommand(id = "ai_open", title = "Open Kryptonide AI", subtitle = "Chat with Kryptonide AI about your code", category = PaletteCategory.AI, icon = Lucide.Sparkles, action = { onOpenAi() }))
    if (word.isNotEmpty()) {
        add(PaletteCommand(id = "ai_explain", title = "Ask Kryptonide AI to explain \"$word\"", subtitle = "Ask Kryptonide AI to explain this symbol", category = PaletteCategory.AI, icon = Lucide.CircleHelp, action = { onAskAi("Explain `$word` near line $line in this file.") }))
        add(PaletteCommand(id = "ai_fix", title = "Ask Kryptonide AI to fix Issue at Line $line", subtitle = "Ask Kryptonide AI to fix the problem here", category = PaletteCategory.AI, icon = Lucide.Wrench, action = { onAskAi("Fix the issue around `$word` at line $line.") }))
    }
    add(PaletteCommand(id = "ai_generate", title = "Generate Code with Kryptonide AI", subtitle = "Describe what to generate", category = PaletteCategory.AI, icon = Lucide.CirclePlus, action = { onOpenAi() }))
    add(PaletteCommand(id = "ai_review", title = "Review This File with Kryptonide AI", subtitle = "Get Kryptonide AI's analysis of the whole file", category = PaletteCategory.AI, icon = Lucide.ClipboardCheck, action = { onAskAi("Review the current file for bugs, edge cases, and improvements.") }))

    // ── FILE ──────────────────────────────────────────────────
    add(PaletteCommand(id = "save", title = "Save File", category = PaletteCategory.FILE, icon = Lucide.Save, shortcut = "Ctrl+S", action = { onSave() }))
    add(PaletteCommand(id = "open_files", title = "Open File Explorer", category = PaletteCategory.FILE, icon = Lucide.FolderOpen, action = { onOpenFiles() }))

    // ── RUN ───────────────────────────────────────────────────
    add(PaletteCommand(id = "run", title = "Run File", subtitle = "Build and execute current file", category = PaletteCategory.RUN, icon = Lucide.Play, action = { onRun() }))
    add(PaletteCommand(id = "open_terminal", title = "Open Terminal", subtitle = "Launch interactive shell sheet", category = PaletteCategory.RUN, icon = Lucide.Terminal, action = { onOpenTerminal() }))

    // ── GIT ───────────────────────────────────────────────────
    add(PaletteCommand(id = "git", title = "Open Git Panel", category = PaletteCategory.GIT, icon = Lucide.GitBranch, action = { onOpenGit() }))
    add(PaletteCommand(id = "git_commit", title = "Git: Commit", category = PaletteCategory.GIT, icon = Lucide.Check, action = { onOpenGit() }))
    add(PaletteCommand(id = "git_push", title = "Git: Push", category = PaletteCategory.GIT, icon = Lucide.Upload, action = { onOpenGit() }))
    add(PaletteCommand(id = "git_pull", title = "Git: Pull", category = PaletteCategory.GIT, icon = Lucide.Download, action = { onOpenGit() }))
}
