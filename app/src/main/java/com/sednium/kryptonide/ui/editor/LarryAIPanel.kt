@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.sednium.kryptonide.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.composables.icons.lucide.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sednium.kryptonide.data.model.ChatMessage
import com.sednium.kryptonide.data.model.ChatSession
import com.sednium.kryptonide.data.model.MessageRole
import com.sednium.kryptonide.data.model.SavedModel
import com.sednium.kryptonide.ui.ai.AiViewModel
import com.sednium.kryptonide.ui.larry.LARRY_PROVIDERS
import com.sednium.kryptonide.agent.oorty.*
import com.sednium.kryptonide.agent.pocket.PocketServerManager
import com.sednium.kryptonide.agent.pocket.PocketServerStatus
import com.sednium.kryptonide.ui.ai.ImportHuggingFaceDialog
import com.sednium.kryptonide.ui.settings.AddModelDialog
import com.sednium.kryptonide.ui.theme.KryptonColorPalette
import com.sednium.kryptonide.ui.theme.LocalKryptonColors
import kotlinx.coroutines.launch

@Composable
fun LarryAIPanel(
    activeFileName: String,
    aiVm: AiViewModel,
    onApplyEdit: (path: String, content: String) -> Unit = { _, _ -> },
    onInsertAtCursor: (code: String) -> Unit = {},
    gitVm: com.sednium.kryptonide.ui.git.GitViewModel? = null
) {
    LaunchedEffect(gitVm) {
        if (gitVm != null) aiVm.setGitViewModel(gitVm)
    }

    val colors = LocalKryptonColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pocketManager = remember { PocketServerManager.getInstance(context) }
    val inputText by aiVm.draftPromptText.collectAsStateWithLifecycle()
    val aiState by aiVm.state.collectAsStateWithLifecycle()
    var showSessions by remember { mutableStateOf(false) }
    var showModelSwitcher by remember { mutableStateOf(false) }
    var showAddModelDialog by remember { mutableStateOf(false) }
    var showHfImportDialog by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf<SavedModel?>(null) }
    var renamingSession by remember { mutableStateOf<ChatSession?>(null) }
    var sessionSearchQuery by remember { mutableStateOf("") }
    var sessionToDelete by remember { mutableStateOf<ChatSession?>(null) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var showAttachPicker by remember { mutableStateOf(false) }
    var aiMode by remember { mutableStateOf("chat") } // "chat" | "write" | "agent"
    val attachedFiles by aiVm.draftAttachedFiles.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    fun formatRelativeTime(millis: Long): String {
        val now = System.currentTimeMillis()
        val diff = (now - millis).coerceAtLeast(0)
        val minutes = diff / (60 * 1000)
        val hours = diff / (60 * 60 * 1000)
        val days = diff / (24 * 60 * 60 * 1000)
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days == 1L -> "Yesterday"
            days < 7 -> "${days}d ago"
            else -> {
                val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
                sdf.format(java.util.Date(millis))
            }
        }
    }

    val hasValidFile = activeFileName.isNotBlank() && activeFileName != "No file selected"

    val hasValidKey = remember(aiState.activeModelId, aiState.savedModels, aiState.provider) {
        aiVm.hasValidApiKey()
    }

    val activeModelName = remember(aiState.activeModelId, aiState.savedModels, aiState.provider, aiState.model, hasValidKey) {
        val saved = aiState.savedModels.firstOrNull { it.id == aiState.activeModelId }
        val providerDef = LARRY_PROVIDERS.firstOrNull { it.id == (saved?.provider ?: aiState.provider) }
        val isPocket = (saved?.provider ?: aiState.provider) == "pocket"
        val baseLabel = if (isPocket) {
            "⚡ Pocket Server · ${saved?.model ?: aiState.model}"
        } else {
            saved?.name ?: "${providerDef?.name ?: aiState.provider} · ${aiState.model}"
        }
        if (hasValidKey || isPocket) baseLabel else "$baseLabel (No API Key)"
    }

    val projectFiles = remember(activeFileName, showAttachPicker, inputText) {
        aiVm.getProjectFilePaths()
    }

    // Detect @mention query at the end of inputText
    val mentionQuery: String? = remember(inputText) {
        val atIdx = inputText.lastIndexOf('@')
        if (atIdx >= 0 && (atIdx == 0 || inputText[atIdx - 1].isWhitespace())) {
            val tail = inputText.substring(atIdx + 1)
            if (!tail.contains(' ') && !tail.contains('\n')) tail else null
        } else null
    }

    val mentionSuggestions = remember(mentionQuery, projectFiles) {
        if (mentionQuery == null) emptyList()
        else projectFiles.filter { it.contains(mentionQuery, ignoreCase = true) }.take(6)
    }

    fun buildPrompt(targetMode: String = aiMode): String {
        val modeGuidance = when (targetMode) {
            "write" -> "\nMode: DIRECT_CODE_WRITER. Generate complete, syntactically correct code. Prefer outputting <edit file=\"...\"> blocks with exact file contents ready to apply."
            "agent" -> "\nMode: DEEP_REASONING_AGENT. Always plan thoroughly. Start with <thinking> step-by-step reasoning </thinking> and <plan> numbered plan </plan> before implementing."
            else -> ""
        }
        val projectTree = projectFiles.joinToString("\n").ifBlank { "(empty project)" }
        val linkedRepo = aiVm.getLinkedRepo() ?: gitVm?.state?.value?.linkedRepo
        val authStatus = aiVm.getGithubAuthStatus()

        return com.sednium.kryptonide.agent.oorty.SystemPrompt.buildImmutablePrompt(
            projectFileTree = projectTree,
            linkedGithubRepo = linkedRepo,
            githubAuthStatus = authStatus
        ) + modeGuidance
    }

    val contextPrompt = remember(activeFileName, aiMode, attachedFiles, aiState.messages.size) {
        buildPrompt()
    }

    // Filter out empty intermediate assistant messages that only carry toolCallsJson
    val visibleMessages = remember(aiState.messages) {
        aiState.messages.filter { msg ->
            !(msg.role == MessageRole.ASSISTANT && (msg.content.isBlank() || msg.content == "null" || msg.content == "nullnull") && !msg.toolCallsJson.isNullOrBlank())
        }
    }

    val totalItemsCount = (if (visibleMessages.isEmpty() && aiState.streamingText.isEmpty()) 1 else 0) +
        visibleMessages.size +
        (if (aiState.streamingText.isNotEmpty() || aiState.isLoading) 1 else 0)

    var userScrolledAway by remember { mutableStateOf(false) }

    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visible = layoutInfo.visibleItemsInfo
            if (visible.isEmpty() || layoutInfo.totalItemsCount == 0) true
            else {
                val lastVisible = visible.last()
                val isLastIndex = lastVisible.index >= layoutInfo.totalItemsCount - 1
                val isBottomShowing = (lastVisible.offset + lastVisible.size) <= (layoutInfo.viewportEndOffset + 80)
                isLastIndex && isBottomShowing
            }
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            userScrolledAway = !isAtBottom
        }
    }

    // Always scroll directly to bottom on initial composition and panel open
    LaunchedEffect(Unit) {
        userScrolledAway = false
        val lastIdx = listState.layoutInfo.totalItemsCount - 1
        if (lastIdx >= 0) {
            runCatching { listState.scrollToItem(lastIdx, 0) }
        }
    }

    // Auto-scroll on new message added
    LaunchedEffect(visibleMessages.size) {
        userScrolledAway = false
        val lastIdx = listState.layoutInfo.totalItemsCount - 1
        if (lastIdx >= 0) {
            runCatching { listState.scrollToItem(lastIdx, 0) }
        }
    }

    // For streaming text updates, pin to bottom unless user explicitly dragged away
    val streamingChunkIndex = remember(aiState.streamingText.length) {
        aiState.streamingText.length / 25
    }
    LaunchedEffect(streamingChunkIndex, aiState.isLoading) {
        if (!userScrolledAway && !listState.isScrollInProgress) {
            val lastIdx = listState.layoutInfo.totalItemsCount - 1
            if (lastIdx >= 0) {
                runCatching { listState.scrollToItem(lastIdx, 0) }
            }
        }
    }

    fun exportConversationMarkdown() {
        if (visibleMessages.isEmpty()) {
            Toast.makeText(context, "No messages to export", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder()
        sb.appendLine("# AI Conversation — $activeModelName")
        sb.appendLine()
        visibleMessages.forEach { m ->
            val roleHeader = when (m.role) {
                MessageRole.USER -> "### User"
                MessageRole.TOOL -> "### Tool (${m.toolName ?: "tool"})"
                else -> "### AI Assistant"
            }
            sb.appendLine(roleHeader)
            sb.appendLine(m.content)
            sb.appendLine()
        }
        copyToClipboard(context, sb.toString().trim(), "Full chat copied as Markdown")
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.Background)) {
        // ── AI Header ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.Surface)
                .border(0.5.dp, colors.Border)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(colors.Primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Lucide.Sparkles, contentDescription = null, tint = colors.Primary, modifier = Modifier.size(20.dp))
                }
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { showModelSwitcher = true }
                        .padding(vertical = 2.dp, horizontal = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Kryptonide AI", color = colors.OnBackground, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        if (aiState.isLoading) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(colors.Primary.copy(alpha = 0.2f))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text("STREAMING", color = colors.Primary, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        if (!hasValidKey) {
                            Icon(
                                Lucide.TriangleAlert,
                                contentDescription = null,
                                tint = colors.Error,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        val isPocket = aiState.provider == "pocket"
                        val displayText = when {
                            isPocket && aiState.modelRamStatus == com.sednium.kryptonide.ui.ai.ModelRamStatus.LOADING -> "⚡ Loading into RAM..."
                            isPocket && aiState.modelRamStatus == com.sednium.kryptonide.ui.ai.ModelRamStatus.READY -> "● Ready in RAM • $activeModelName"
                            isPocket && aiState.modelRamStatus == com.sednium.kryptonide.ui.ai.ModelRamStatus.ERROR -> "⚠️ RAM Load Failed"
                            else -> activeModelName
                        }
                        val statusColor = when {
                            !hasValidKey -> colors.Error
                            isPocket && aiState.modelRamStatus == com.sednium.kryptonide.ui.ai.ModelRamStatus.READY -> colors.Primary
                            isPocket && aiState.modelRamStatus == com.sednium.kryptonide.ui.ai.ModelRamStatus.LOADING -> colors.Warning
                            else -> colors.Muted
                        }
                        Text(
                            displayText,
                            color = statusColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            Lucide.ChevronDown,
                            contentDescription = "Switch Model",
                            tint = statusColor,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (visibleMessages.isNotEmpty()) {
                    IconButton(
                        onClick = { exportConversationMarkdown() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Lucide.Share2,
                            contentDescription = "Export Chat as Markdown",
                            tint = colors.Muted,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    IconButton(
                        onClick = { aiVm.clearCurrentSession() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Lucide.Trash2,
                            contentDescription = "Clear Chat",
                            tint = colors.Muted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Sessions list toggle
                IconButton(
                    onClick = { showSessions = !showSessions },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Lucide.History,
                        contentDescription = "Sessions",
                        tint = if (showSessions) colors.Primary else colors.Muted,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // New Chat button
                IconButton(
                    onClick = {
                        aiVm.createSession()
                        showSessions = false
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Lucide.MessageSquarePlus,
                        contentDescription = "New Chat",
                        tint = colors.Primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // ── Active Context Strip ─────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.Surface)
                .border(0.5.dp, colors.Border.copy(alpha = 0.5f))
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left side: Active file chip (+ attachment count, clickable to attach)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.SurfaceVar.copy(alpha = 0.6f))
                    .clickable {
                        if (projectFiles.isNotEmpty()) showAttachPicker = true
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(Lucide.FileText, null, tint = colors.Primary, modifier = Modifier.size(12.dp))
                Text(
                    if (hasValidFile) activeFileName else "No file open",
                    color = colors.OnSurface,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (attachedFiles.isNotEmpty()) {
                    Text(
                        "+${attachedFiles.size}",
                        color = colors.Primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Right side: Status indicator
            if (aiState.isLoading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = colors.Primary)
                    Text("Generating…", color = colors.Primary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (hasValidKey) colors.Primary else colors.Muted)
                    )
                    Text(
                        if (hasValidKey) "Ready" else "No API Key",
                        color = colors.Muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // ── Recent Chats Drawer ──────────────────────────────────────────────────
        AnimatedVisibility(
            visible = showSessions,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            // Deduplicate empty "New Chat" sessions in display so only at most one "New Chat" is shown
            val displaySessions = remember(aiState.sessions, aiState.activeSessionId) {
                var seenEmptyNewChat = false
                aiState.sessions.filter { s ->
                    if (s.title == "New Chat" && s.id != aiState.activeSessionId) {
                        if (seenEmptyNewChat) false else {
                            seenEmptyNewChat = true
                            true
                        }
                    } else true
                }
            }
            val filteredSessions = remember(displaySessions, sessionSearchQuery) {
                if (sessionSearchQuery.isBlank()) displaySessions
                else displaySessions.filter { it.title.contains(sessionSearchQuery, ignoreCase = true) }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .background(colors.Surface)
                    .border(
                        BorderStroke(0.5.dp, colors.Border.copy(alpha = 0.8f)),
                        RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                    )
                    .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
            ) {
                // Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.Primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Lucide.MessagesSquare,
                                contentDescription = null,
                                tint = colors.Primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            "Recent Chats",
                            color = colors.OnBackground,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(colors.SurfaceVar)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "${displaySessions.size}",
                                color = colors.Muted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // + New Chat Pill Button
                        Button(
                            onClick = {
                                aiVm.createSession()
                                showSessions = false
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp),
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.Primary,
                                contentColor = colors.OnPrimary
                            )
                        ) {
                            Icon(Lucide.Plus, null, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("New Chat", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }

                        IconButton(
                            onClick = { showSessions = false },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Lucide.X, "Close", tint = colors.Muted, modifier = Modifier.size(15.dp))
                        }
                    }
                }

                // Search Bar
                if (displaySessions.size > 2 || sessionSearchQuery.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.Background)
                                .border(0.5.dp, colors.Border, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Lucide.Search, null, tint = colors.Muted, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(8.dp))
                            BasicTextField(
                                value = sessionSearchQuery,
                                onValueChange = { sessionSearchQuery = it },
                                singleLine = true,
                                textStyle = TextStyle(color = colors.OnBackground, fontSize = 12.sp),
                                modifier = Modifier.weight(1f),
                                decorationBox = { innerTextField ->
                                    if (sessionSearchQuery.isEmpty()) {
                                        Text("Search conversations...", color = colors.Muted, fontSize = 12.sp)
                                    }
                                    innerTextField()
                                }
                            )
                            if (sessionSearchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { sessionSearchQuery = "" },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Lucide.X, "Clear", tint = colors.Muted, modifier = Modifier.size(13.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                HorizontalDivider(color = colors.Border.copy(alpha = 0.5f), thickness = 0.5.dp)

                // List of Sessions
                if (filteredSessions.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                if (sessionSearchQuery.isNotBlank()) Lucide.SearchX else Lucide.MessageCircle,
                                null,
                                tint = colors.Muted,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (sessionSearchQuery.isNotBlank()) "No chats found for \"$sessionSearchQuery\""
                                else "No chat history yet",
                                color = colors.OnBackground,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                if (sessionSearchQuery.isNotBlank()) "Try a different search term"
                                else "Conversations will be automatically saved here",
                                color = colors.Muted,
                                fontSize = 11.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredSessions, key = { it.id }) { session ->
                            val isSelected = session.id == aiState.activeSessionId
                            val stats = aiState.sessionStats[session.id]
                            val msgCount = if (isSelected && aiState.messages.isNotEmpty()) {
                                aiState.messages.size
                            } else {
                                stats?.messageCount ?: 0
                            }
                            val lastSnippet = if (isSelected && aiState.messages.isNotEmpty()) {
                                aiState.messages.lastOrNull { it.role != MessageRole.TOOL }?.content?.trim()?.take(80)?.replace("\n", " ") ?: ""
                            } else {
                                stats?.lastSnippet ?: ""
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) colors.Primary.copy(alpha = 0.08f) else colors.Background)
                                    .border(
                                        if (isSelected) 1.dp else 0.5.dp,
                                        if (isSelected) colors.Primary.copy(alpha = 0.6f) else colors.Border.copy(alpha = 0.6f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        aiVm.setActiveSession(session.id)
                                        showSessions = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Active indicator pill
                                Box(
                                    modifier = Modifier
                                        .size(width = 3.dp, height = 32.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(if (isSelected) colors.Primary else Color.Transparent)
                                )

                                Spacer(Modifier.width(10.dp))

                                // Chat Icon
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) colors.Primary.copy(alpha = 0.18f) else colors.SurfaceVar),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        if (isSelected) Lucide.MessageCircle else Lucide.MessageCircle,
                                        null,
                                        tint = if (isSelected) colors.Primary else colors.Muted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                Spacer(Modifier.width(12.dp))

                                // Session details
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            session.title,
                                            color = if (isSelected) colors.OnBackground else colors.OnBackground.copy(alpha = 0.9f),
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(colors.Primary.copy(alpha = 0.15f))
                                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                                            ) {
                                                Text(
                                                    "ACTIVE",
                                                    color = colors.Primary,
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }

                                    if (lastSnippet.isNotBlank()) {
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            lastSnippet,
                                            color = colors.Muted,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(Modifier.height(4.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            Lucide.Clock,
                                            null,
                                            tint = colors.Muted.copy(alpha = 0.7f),
                                            modifier = Modifier.size(11.dp)
                                        )
                                        Text(
                                            formatRelativeTime(session.updatedAt),
                                            color = colors.Muted,
                                            fontSize = 10.sp
                                        )
                                        Text("•", color = colors.Muted.copy(alpha = 0.5f), fontSize = 10.sp)
                                        Text(
                                            if (msgCount == 1) "1 message" else "$msgCount messages",
                                            color = colors.Muted,
                                            fontSize = 10.sp
                                        )
                                    }
                                }

                                Spacer(Modifier.width(6.dp))

                                // Quick actions
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { renamingSession = session },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            Lucide.Pencil,
                                            "Rename",
                                            tint = colors.Muted,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { sessionToDelete = session },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            Lucide.Trash2,
                                            "Delete",
                                            tint = colors.Muted.copy(alpha = 0.8f),
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom bar in drawer (Clear All option)
                if (displaySessions.size > 1) {
                    HorizontalDivider(color = colors.Border.copy(alpha = 0.4f), thickness = 0.5.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${displaySessions.size} saved conversations",
                            color = colors.Muted,
                            fontSize = 11.sp
                        )
                        TextButton(
                            onClick = { showClearAllConfirm = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text(
                                "Clear All",
                                color = colors.Error.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        // ── Chat History Box (with Jump-to-Bottom Button) ────────────────────────
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Show full onboarding + quick starter grid only when chat is empty;
                // otherwise show a compact context banner if no key is configured.
                if (visibleMessages.isEmpty() && aiState.streamingText.isEmpty()) {
                    item(key = "welcome_empty_state") {
                        EmptyStateHero(
                            activeFileName = if (hasValidFile) activeFileName else null,
                            aiMode = aiMode,
                            hasValidKey = hasValidKey,
                            hasSavedModels = aiState.savedModels.isNotEmpty(),
                            colors = colors,
                            onAddModel = { showAddModelDialog = true },
                            onSwitchModel = { showModelSwitcher = true },
                            onQuickPrompt = { promptText, preferredMode ->
                                userScrolledAway = false
                                val modeToUse = preferredMode ?: aiMode
                                if (preferredMode != null) aiMode = preferredMode
                                aiVm.sendMessage(promptText, buildPrompt(modeToUse))
                            }
                        )
                    }
                } else if (!hasValidKey) {
                    item(key = "compact_key_banner") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.SurfaceVar.copy(alpha = 0.7f))
                                .border(0.8.dp, colors.Border, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Lucide.Key, null, tint = colors.Primary, modifier = Modifier.size(15.dp))
                                Text(
                                    "No API key configured for $activeModelName",
                                    color = colors.OnBackground,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(
                                onClick = { showAddModelDialog = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Text("Add Key", color = colors.Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                items(visibleMessages, key = { it.id }) { msg ->
                    LarryMessageItem(
                        msg = msg,
                        activeFileName = if (hasValidFile) activeFileName else null,
                        colors = colors,
                        onApplyEdit = onApplyEdit,
                        onInsertAtCursor = onInsertAtCursor,
                        onRetry = { aiVm.retryFromError(msg.id, contextPrompt) },
                        onRegenerate = { aiVm.regenerateMessage(msg.id, contextPrompt) },
                        onDelete = { aiVm.deleteSingleMessage(msg.id) },
                        onEditPrompt = { oldText ->
                            val attachmentPrefix = "[📎 "
                            if (oldText.startsWith(attachmentPrefix)) {
                                val endIdx = oldText.indexOf("]\n")
                                if (endIdx != -1) {
                                    val files = oldText.substring(attachmentPrefix.length, endIdx).split(",").map { it.trim() }
                                    aiVm.setDraftAttachedFiles(files.toSet())
                                    aiVm.setDraftPromptText(oldText.substring(endIdx + 2))
                                } else {
                                    aiVm.setDraftPromptText(oldText)
                                }
                            } else {
                                aiVm.setDraftPromptText(oldText)
                            }
                            aiVm.deleteSingleMessage(msg.id)
                        },
                        onConfigureKey = { showAddModelDialog = true },
                        onSwitchModel = { showModelSwitcher = true },
                        onRevertTurn = { aiVm.revertTurn(msg.id) },
                        hasCheckpoint = aiVm.hasCheckpointForTurn(msg.id),
                        isReverted = aiVm.isTurnReverted(msg.id),
                        modifiedFiles = aiVm.getModifiedFilesForTurn(msg.id)
                    )
                }

                if (aiState.streamingText.isNotEmpty()) {
                    item(key = "streaming_message") {
                        LarryMessageView(
                            text = aiState.streamingText,
                            isUser = false,
                            activeFileName = if (hasValidFile) activeFileName else null,
                            colors = colors,
                            onApplyEdit = onApplyEdit,
                            onInsertAtCursor = onInsertAtCursor,
                            isStreaming = true
                        )
                    }
                } else if (aiState.isLoading) {
                    item(key = "thinking_skeleton") {
                        ThinkingPulseCard(colors = colors)
                    }
                }

                // Dedicated bottom anchor to pin viewport to the generation bottom line
                item(key = "chat_bottom_anchor") {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            // Floating Scroll-to-Bottom Pill
            androidx.compose.animation.AnimatedVisibility(
                visible = userScrolledAway || !isAtBottom,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 10.dp)
            ) {
                Surface(
                    onClick = {
                        userScrolledAway = false
                        scope.launch {
                            val lastIdx = listState.layoutInfo.totalItemsCount - 1
                            if (lastIdx >= 0) {
                                listState.animateScrollToItem(lastIdx, 0)
                            }
                        }
                    },
                    shape = CircleShape,
                    color = colors.Surface,
                    border = BorderStroke(0.8.dp, colors.Border),
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Lucide.ChevronDown,
                            contentDescription = "Scroll to bottom",
                            tint = colors.Primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // ── Input Container ──────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.Surface)
                .border(0.5.dp, colors.Border)
                .imePadding()
                .padding(top = 6.dp, bottom = 8.dp)
        ) {
            // @mention autocomplete pop-up bar
            if (mentionSuggestions.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(mentionSuggestions, key = { it }) { filePath ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.SurfaceVar)
                                .border(0.5.dp, colors.Primary.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .clickable {
                                    val atIdx = inputText.lastIndexOf('@')
                                    if (atIdx >= 0) {
                                        aiVm.setDraftPromptText(inputText.substring(0, atIdx) + "@$filePath ")
                                    }
                                    aiVm.setDraftAttachedFiles(attachedFiles + filePath)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Lucide.FileText, null, tint = colors.Primary, modifier = Modifier.size(12.dp))
                            Text(filePath, color = colors.OnBackground, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // Suggestion Chips (shown only on fresh empty state to preserve vertical screen height on 720p displays)
            if (visibleMessages.isEmpty() && inputText.isEmpty()) {
                val targetLabel = if (hasValidFile) activeFileName else "this project"
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        SuggestionChip("Explain code", Lucide.FileText, colors) {
                            aiVm.sendMessage("Explain the code in $targetLabel", contextPrompt)
                        }
                    }
                    item {
                        SuggestionChip("Find bugs", Lucide.Bug, colors) {
                            aiVm.sendMessage("Review $targetLabel for potential bugs, edge cases, and performance improvements.", contextPrompt)
                        }
                    }
                    item {
                        SuggestionChip("Refactor", Lucide.Wand, colors) {
                            aiVm.sendMessage("Refactor $targetLabel to improve clarity, error handling, and idiomatic structure.", contextPrompt)
                        }
                    }
                    item {
                        SuggestionChip("Add comments", Lucide.FileText, colors) {
                            aiVm.sendMessage("Add clear documentation comments to $targetLabel.", contextPrompt)
                        }
                    }
                    item {
                        SuggestionChip("Write tests", Lucide.ListChecks, colors) {
                            aiVm.sendMessage("Write unit tests covering the core logic and edge cases in $targetLabel.", contextPrompt)
                        }
                    }
                    item {
                        SuggestionChip("Optimize", Lucide.Zap, colors) {
                            aiVm.sendMessage("Optimize $targetLabel for speed, memory efficiency, and clean architecture.", contextPrompt)
                        }
                    }
                }
            }

            // Attached files pills
            if (attachedFiles.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(attachedFiles.toList(), key = { it }) { file ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.Primary.copy(alpha = 0.12f))
                                .border(0.5.dp, colors.Primary.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Lucide.Paperclip, null, tint = colors.Primary, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                file,
                                color = colors.Primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Lucide.X,
                                "Remove",
                                tint = colors.Primary,
                                modifier = Modifier.size(13.dp).clickable { aiVm.setDraftAttachedFiles(attachedFiles - file) }
                            )
                        }
                    }
                }
            }

            // Compact modern composer row: [Attach] [@] [BasicTextField] [Clear?] [Send/Stop]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .heightIn(min = 42.dp, max = 120.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(colors.Background)
                    .border(1.dp, colors.Border, RoundedCornerShape(22.dp))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Attach file button with badge count
                Box(contentAlignment = Alignment.TopEnd) {
                    IconButton(
                        onClick = {
                            if (projectFiles.isNotEmpty()) {
                                showAttachPicker = true
                            } else if (hasValidFile) {
                                aiVm.setDraftAttachedFiles(
                                    if (activeFileName in attachedFiles) {
                                        attachedFiles - activeFileName
                                    } else {
                                        attachedFiles + activeFileName
                                    }
                                )
                            } else {
                                Toast.makeText(context, "No files in project to attach", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Lucide.Paperclip,
                            contentDescription = "Attach File",
                            tint = if (attachedFiles.isNotEmpty()) colors.Primary else colors.Muted,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    if (attachedFiles.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(colors.Primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${attachedFiles.size}",
                                color = colors.OnPrimary,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                val canSend = inputText.isNotBlank() && !aiState.isLoading
                val handleSend: () -> Unit = {
                    if (canSend) {
                        userScrolledAway = false
                        val currentAttachments = attachedFiles.toList()
                        val prompt = buildPrompt()
                        val dynamicContext = buildString {
                            if (currentAttachments.isNotEmpty()) {
                                appendLine("<tagged_files>")
                                currentAttachments.forEach { path ->
                                    val content = aiVm.getRawFileContent(path) ?: ""
                                    val numbered = content.take(15_000).lines().mapIndexed { idx, line -> "${idx + 1}: $line" }.joinToString("\n")
                                    appendLine("<file path=\"$path\">\n$numbered\n</file>")
                                }
                                appendLine("</tagged_files>")
                            }
                        }
                        val prefix = if (currentAttachments.isNotEmpty()) "[📎 ${currentAttachments.joinToString(", ")}]\n" else ""
                        val userContent = if (dynamicContext.isNotBlank()) {
                            "$dynamicContext\n$prefix${inputText.trim()}"
                        } else {
                            "$prefix${inputText.trim()}"
                        }
                        aiVm.sendMessage(userContent, prompt)
                        aiVm.setDraftPromptText("")
                        aiVm.setDraftAttachedFiles(emptySet())
                    }
                }

                // Compact Multiline BasicTextField
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (inputText.isEmpty()) {
                        Text(
                            when (aiMode) {
                                "write" -> "Describe code to write or edit in ${if (hasValidFile) activeFileName else "project"}…"
                                "agent" -> "Describe a multi-step task (type @ to tag files)…"
                                else -> "Ask Kryptonide AI or type @ to tag files…"
                            },
                            color = colors.Muted,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = inputText,
                        onValueChange = { aiVm.setDraftPromptText(it) },
                        textStyle = TextStyle(
                            color = colors.OnBackground,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        ),
                        cursorBrush = SolidColor(colors.Primary),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Send,
                            capitalization = KeyboardCapitalization.Sentences
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = { handleSend() }
                        ),
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (inputText.isNotEmpty() && !aiState.isLoading) {
                    IconButton(
                        onClick = { aiVm.setDraftPromptText("") },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            Lucide.X,
                            contentDescription = "Clear input",
                            tint = colors.Muted,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                }

                // Send or Stop Button
                if (aiState.isLoading) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(colors.Error)
                            .clickable(
                                role = Role.Button,
                                onClick = { aiVm.abortChat() }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Lucide.Square, contentDescription = "Stop", tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (canSend) colors.Primary else colors.SurfaceVar)
                            .clickable(
                                enabled = canSend,
                                role = Role.Button,
                                onClick = { handleSend() }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Lucide.ArrowUp,
                            contentDescription = "Send",
                            tint = if (canSend) colors.OnPrimary else colors.Muted,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }
    }

    // ── Rename Session Dialog ────────────────────────────────────────────────
    renamingSession?.let { targetSession ->
        var titleInput by remember(targetSession) { mutableStateOf(targetSession.title) }
        AlertDialog(
            onDismissRequest = { renamingSession = null },
            containerColor = colors.Surface,
            shape = RoundedCornerShape(18.dp),
            title = {
                Text("Rename Chat Session", color = colors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            },
            text = {
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    singleLine = true,
                    placeholder = { Text("Session title", color = colors.Muted, fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        aiVm.renameSession(targetSession.id, titleInput)
                        renamingSession = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.Primary, contentColor = colors.OnPrimary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Save", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingSession = null }) {
                    Text("Cancel", color = colors.Muted)
                }
            }
        )
    }

    // ── Delete Session Confirmation Dialog ────────────────────────────────────
    sessionToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            containerColor = colors.Surface,
            shape = RoundedCornerShape(18.dp),
            title = {
                Text("Delete Conversation?", color = colors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Are you sure you want to delete \"${target.title}\"? This action cannot be undone.",
                    color = colors.Muted,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        aiVm.deleteSession(target.id)
                        sessionToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.Error,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Delete", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text("Cancel", color = colors.Muted)
                }
            }
        )
    }

    // ── Clear All Sessions Confirmation Dialog ────────────────────────────────
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            containerColor = colors.Surface,
            shape = RoundedCornerShape(18.dp),
            title = {
                Text("Clear All Sessions?", color = colors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "This will permanently delete all saved chat sessions for this project and start a new empty session.",
                    color = colors.Muted,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        aiVm.clearAllSessions()
                        showClearAllConfirm = false
                        showSessions = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.Error,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Clear All", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) {
                    Text("Cancel", color = colors.Muted)
                }
            }
        )
    }

    // ── Model Switcher Dialog ────────────────────────────────────────────────
    if (showModelSwitcher) {
        AlertDialog(
            onDismissRequest = { showModelSwitcher = false },
            containerColor = colors.Surface,
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("AI Models & Keys", color = colors.OnBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("Select active model or add an API key", color = colors.Muted, fontSize = 11.sp)
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val pocketTelemetry by pocketManager.telemetry.collectAsState()
                    val isPocketRunning = pocketTelemetry.status == PocketServerStatus.RUNNING
                    val activeSaved = aiState.savedModels.firstOrNull { it.id == aiState.activeModelId }
                    val isPocketActive = (activeSaved?.provider ?: aiState.provider) == "pocket"
                    val localModelName = pocketTelemetry.activeModel?.name ?: "Needle 3 (needle-3-q4.cact)"

                    // 1. On-Device AI Option
                    Text(
                        "ON-DEVICE INFERENCE",
                        color = colors.Primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    val primaryPocketSaved = aiState.savedModels.firstOrNull { it.provider == "pocket" }
                    val activeModelDisplay = pocketTelemetry.activeModel?.name ?: primaryPocketSaved?.model ?: aiState.model.ifBlank { "minicpm5-1b-thinking-q4.gguf" }
                    val pocketSubtitle = if (isPocketRunning) {
                        "Active: $activeModelDisplay · Hardware Pinned"
                    } else {
                        "$activeModelDisplay · On-device GGUF"
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isPocketActive) colors.Primary.copy(alpha = 0.12f) else colors.Background)
                            .border(
                                0.5.dp,
                                if (isPocketActive) colors.Primary else colors.Border,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                aiVm.setProvider("pocket")
                                if (primaryPocketSaved != null) {
                                    aiVm.setActiveSavedModel(primaryPocketSaved.id)
                                } else {
                                    val newPocket = SavedModel(
                                        id = "pocket-local-daemon",
                                        name = "Pocket Server (Local)",
                                        provider = "pocket",
                                        model = activeModelDisplay,
                                        apiKey = "",
                                        baseUrl = "http://127.0.0.1:8765/v1"
                                    )
                                    aiVm.addSavedModel(newPocket)
                                    aiVm.setActiveSavedModel(newPocket.id)
                                }
                                aiVm.preloadPocketModel(activeModelDisplay)
                                scope.launch {
                                    val models = pocketManager.listDiscoveredModels()
                                    val match = models.firstOrNull {
                                        it.name.contains(activeModelDisplay.substringBeforeLast('.'), ignoreCase = true) ||
                                        it.matchedCatalogSpec?.id?.equals(activeModelDisplay, ignoreCase = true) == true
                                    } ?: models.firstOrNull()
                                    if (match != null && (!isPocketRunning || pocketTelemetry.activeModel?.name != match.name)) {
                                        pocketManager.startServer(match)
                                    }
                                }
                                showModelSwitcher = false
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isPocketActive) Lucide.CircleCheck else Lucide.Circle,
                            contentDescription = null,
                            tint = if (isPocketActive) colors.Primary else colors.Muted,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "Pocket Server (Local)",
                                    color = colors.OnBackground,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isPocketRunning) colors.Success.copy(alpha = 0.15f) else colors.SurfaceVar)
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        if (isPocketRunning) "RUNNING" else "LOCAL",
                                        color = if (isPocketRunning) colors.Success else colors.Muted,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(
                                pocketSubtitle,
                                color = colors.Muted,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "CLOUD PROVIDERS",
                        color = colors.Muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    val nonPocketModels = aiState.savedModels.filter {
                        it.provider != "pocket" && !it.name.contains("Pocket Server", ignoreCase = true)
                    }
                    if (nonPocketModels.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.Background)
                                .border(0.5.dp, colors.Border, RoundedCornerShape(10.dp))
                                .padding(14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No cloud API keys saved yet. Add a key below (Gemini, Claude, OpenAI, Groq, OpenRouter).",
                                color = colors.Muted,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    } else {
                        nonPocketModels.forEach { saved ->
                            val isSelected = saved.id == aiState.activeModelId
                            val providerDef = LARRY_PROVIDERS.firstOrNull { it.id == saved.provider }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) colors.Primary.copy(alpha = 0.12f) else colors.Background)
                                    .border(
                                        0.5.dp,
                                        if (isSelected) colors.Primary else colors.Border,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        aiVm.setActiveSavedModel(saved.id)
                                        if (saved.provider == "pocket") {
                                            aiVm.preloadPocketModel(saved.model)
                                        }
                                        showModelSwitcher = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (isSelected) Lucide.CircleCheck else Lucide.Circle,
                                    contentDescription = null,
                                    tint = if (isSelected) colors.Primary else colors.Muted,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(saved.name, color = colors.OnBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${providerDef?.name ?: saved.provider} · ${saved.model}",
                                        color = colors.Muted,
                                        fontSize = 11.sp
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        editingModel = saved
                                        showModelSwitcher = false
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Lucide.Pencil, "Edit", tint = colors.Muted, modifier = Modifier.size(15.dp))
                                }
                                IconButton(
                                    onClick = { aiVm.removeSavedModel(saved.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Lucide.Trash2, "Delete", tint = colors.Muted, modifier = Modifier.size(15.dp))
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = {
                            showModelSwitcher = false
                            showAddModelDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.Primary,
                            contentColor = colors.OnPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Lucide.Plus, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add Model / API Key", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            showModelSwitcher = false
                            showHfImportDialog = true
                        },
                        border = BorderStroke(1.dp, colors.Border),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = colors.OnBackground
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Lucide.Download, null, modifier = Modifier.size(16.dp), tint = colors.Primary)
                        Spacer(Modifier.width(6.dp))
                        Text("Import from HuggingFace / URL", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelSwitcher = false }) {
                    Text("Close", color = colors.Muted)
                }
            }
        )
    }

    if (showHfImportDialog) {
        ImportHuggingFaceDialog(
            colors = colors,
            onDismiss = { showHfImportDialog = false },
            onModelImported = { spec ->
                aiVm.setProvider("pocket")
                val primaryPocketSaved = aiState.savedModels.firstOrNull { it.provider == "pocket" }
                if (primaryPocketSaved != null) {
                    aiVm.setActiveSavedModel(primaryPocketSaved.id)
                } else {
                    val newPocket = SavedModel(
                        id = "pocket-local-daemon",
                        name = "Pocket Server (Local)",
                        provider = "pocket",
                        model = spec.name,
                        apiKey = "",
                        baseUrl = "http://127.0.0.1:8765/v1"
                    )
                    aiVm.addSavedModel(newPocket)
                    aiVm.setActiveSavedModel(newPocket.id)
                }
                aiVm.preloadPocketModel(spec.id)
                pocketManager.startServerForSpec(spec)
                showHfImportDialog = false
            }
        )
    }

    if (showAddModelDialog) {
        AddModelDialog(
            onDismiss = { showAddModelDialog = false },
            onSave = { saved ->
                aiVm.addSavedModel(saved)
                showAddModelDialog = false
            }
        )
    }

    editingModel?.let { modelToEdit ->
        AddModelDialog(
            initialModel = modelToEdit,
            onDismiss = { editingModel = null },
            onSave = { updated ->
                aiVm.updateSavedModel(updated)
                editingModel = null
            }
        )
    }

    // ── Project File Attachment Picker Dialog ────────────────────────────────
    if (showAttachPicker) {
        AlertDialog(
            onDismissRequest = { showAttachPicker = false },
            containerColor = colors.Surface,
            shape = RoundedCornerShape(20.dp),
            title = {
                Column {
                    Text("Attach Project Files", color = colors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Selected files are included in full inside the AI prompt context", color = colors.Muted, fontSize = 11.sp)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    projectFiles.forEach { filePath ->
                        val isAttached = filePath in attachedFiles
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isAttached) colors.Primary.copy(alpha = 0.12f) else colors.Background)
                                .border(0.5.dp, if (isAttached) colors.Primary else colors.Border, RoundedCornerShape(8.dp))
                                .clickable {
                                    aiVm.setDraftAttachedFiles(if (isAttached) attachedFiles - filePath else attachedFiles + filePath)
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isAttached) Lucide.SquareCheck else Lucide.Square,
                                contentDescription = null,
                                tint = if (isAttached) colors.Primary else colors.Muted,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                filePath,
                                color = colors.OnBackground,
                                fontSize = 13.sp,
                                fontWeight = if (isAttached) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                            if (filePath == activeFileName) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(colors.SurfaceVar)
                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                ) {
                                    Text("ACTIVE", color = colors.Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAttachPicker = false }) {
                    Text("Done", color = colors.Primary, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                if (attachedFiles.isNotEmpty()) {
                    TextButton(onClick = { aiVm.setDraftAttachedFiles(emptySet()) }) {
                        Text("Clear All", color = colors.Muted)
                    }
                }
            }
        )
    }
}

@Composable
private fun EmptyStateHero(
    activeFileName: String?,
    aiMode: String,
    hasValidKey: Boolean,
    hasSavedModels: Boolean,
    colors: KryptonColorPalette,
    onAddModel: () -> Unit,
    onSwitchModel: () -> Unit,
    onQuickPrompt: (prompt: String, mode: String?) -> Unit
) {
    val targetLabel = activeFileName ?: "this project"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Welcome Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.Surface)
                .border(0.5.dp, colors.Border, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(colors.Primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Lucide.Sparkles, contentDescription = null, tint = colors.Primary, modifier = Modifier.size(16.dp))
                }
                Column {
                    Text(
                        "Kryptonide AI Agent Harness",
                        color = colors.OnBackground,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        when (aiMode) {
                            "write" -> "Direct Code Writer · Ready to generate & apply file edits"
                            "agent" -> "Autonomous Agent · Multi-step planning & tool execution"
                            else -> "Interactive Chat · Context-aware workspace assistant"
                        },
                        color = colors.Primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Text(
                when (aiMode) {
                    "write" -> "Ask me to build components, add features, or rewrite $targetLabel with one-tap Apply blocks."
                    "agent" -> "Give me a feature spec or bug report — I will inspect your project files, build a plan, and apply changes."
                    else -> "Ask questions about $targetLabel, debug errors, or type @ in the prompt box to attach any project file."
                },
                color = colors.Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }

        // Setup Card if no API key
        if (!hasValidKey) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.SurfaceVar.copy(alpha = 0.65f))
                    .border(1.dp, colors.Border, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Lucide.Key, null, tint = colors.Primary, modifier = Modifier.size(16.dp))
                    Text(
                        "Connect an AI Model",
                        color = colors.OnBackground,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    "Add your API key for Google Gemini, OpenAI, Anthropic Claude, DeepSeek, Groq, OpenRouter, or connect local Ollama to start coding with AI.",
                    color = colors.Muted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onAddModel,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.Primary,
                            contentColor = colors.OnPrimary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Lucide.Plus, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Model & Key", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (hasSavedModels) {
                        OutlinedButton(
                            onClick = onSwitchModel,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(32.dp),
                            border = BorderStroke(0.5.dp, colors.Border)
                        ) {
                            Text("Switch Model", color = colors.OnBackground, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Quick-Action Starter Grid (2x2)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickActionCard(
                    title = "Explain File",
                    subtitle = "Walk through $targetLabel",
                    icon = Lucide.FileText,
                    colors = colors,
                    modifier = Modifier.weight(1f),
                    onClick = { onQuickPrompt("Explain the architecture and key logic in $targetLabel.", "chat") }
                )
                QuickActionCard(
                    title = "Find & Fix Bugs",
                    subtitle = "Detect issues & patch",
                    icon = Lucide.Bug,
                    colors = colors,
                    modifier = Modifier.weight(1f),
                    onClick = { onQuickPrompt("Inspect $targetLabel for bugs or edge cases and provide a fix.", "write") }
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickActionCard(
                    title = "Upgrade UI / Code",
                    subtitle = "Modernize & refactor",
                    icon = Lucide.Wand,
                    colors = colors,
                    modifier = Modifier.weight(1f),
                    onClick = { onQuickPrompt("Refactor and improve the code structure and UI polish in $targetLabel.", "write") }
                )
                QuickActionCard(
                    title = "Agent Feature Plan",
                    subtitle = "Multi-file reasoning",
                    icon = Lucide.Brain,
                    colors = colors,
                    modifier = Modifier.weight(1f),
                    onClick = { onQuickPrompt("Analyze the project structure and propose high-impact improvements.", "agent") }
                )
            }
        }
    }
}

@Composable
private fun QuickActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    colors: KryptonColorPalette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.Surface)
            .border(0.5.dp, colors.Border, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = colors.Primary, modifier = Modifier.size(14.dp))
            Text(
                title,
                color = colors.OnBackground,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            subtitle,
            color = colors.Muted,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ThinkingPulseCard(colors: KryptonColorPalette) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).background(colors.Primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Lucide.Sparkles, null, tint = colors.Primary, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp))
                .background(colors.Surface)
                .border(0.5.dp, colors.Border, RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                strokeWidth = 1.8.dp,
                color = colors.Primary
            )
            Text(
                "Analyzing workspace & generating response…",
                color = colors.Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun LarryMessageItem(
    msg: ChatMessage,
    activeFileName: String?,
    colors: KryptonColorPalette,
    onApplyEdit: (String, String) -> Unit,
    onInsertAtCursor: (String) -> Unit,
    onRetry: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onEditPrompt: (String) -> Unit,
    onConfigureKey: () -> Unit,
    onSwitchModel: () -> Unit,
    onRevertTurn: (() -> Unit)? = null,
    hasCheckpoint: Boolean = false,
    isReverted: Boolean = false,
    modifiedFiles: List<String> = emptyList()
) {
    val context = LocalContext.current
    when (msg.role) {
        MessageRole.USER -> {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                ChatBubble(text = msg.content, isUser = true, colors = colors)
                Spacer(Modifier.height(3.dp))
                Row(
                    modifier = Modifier.padding(end = 36.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MessageActionChip(
                        label = "Edit",
                        icon = Lucide.Pencil,
                        colors = colors,
                        onClick = { onEditPrompt(msg.content) }
                    )
                    MessageActionChip(
                        label = "Re-run",
                        icon = Lucide.RefreshCw,
                        colors = colors,
                        onClick = onRegenerate
                    )
                    MessageActionChip(
                        label = "Copy",
                        icon = Lucide.Copy,
                        colors = colors,
                        onClick = { copyToClipboard(context, msg.content) }
                    )
                    Icon(
                        Lucide.Trash2,
                        contentDescription = "Delete message",
                        tint = colors.Muted,
                        modifier = Modifier
                            .size(13.dp)
                            .clickable { onDelete() }
                    )
                }
            }
        }
        MessageRole.TOOL -> {
            ToolExecutionCard(
                toolName = msg.toolName ?: "tool",
                output = msg.content,
                colors = colors
            )
        }
        MessageRole.ASSISTANT, MessageRole.SYSTEM -> {
            if (msg.content.startsWith("Error:")) {
                ErrorMessageCard(
                    errorText = msg.content.removePrefix("Error:").trim(),
                    colors = colors,
                    onRetry = onRetry,
                    onConfigureKey = onConfigureKey,
                    onSwitchModel = onSwitchModel,
                    onDelete = onDelete
                )
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LarryMessageView(
                        text = msg.content,
                        isUser = false,
                        activeFileName = activeFileName,
                        colors = colors,
                        onApplyEdit = onApplyEdit,
                        onInsertAtCursor = onInsertAtCursor,
                        isStreaming = false
                    )
                    if ((hasCheckpoint || isReverted) && modifiedFiles.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .padding(start = 36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isReverted) colors.SurfaceVar else colors.Warning.copy(alpha = 0.12f))
                                .border(0.5.dp, if (isReverted) colors.Border else colors.Warning.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                if (isReverted) Lucide.Check else Lucide.Undo2,
                                contentDescription = null,
                                tint = if (isReverted) colors.Success else colors.Warning,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                if (isReverted) "Changes reverted (${modifiedFiles.size} files)" else "Modified ${modifiedFiles.size} files (${modifiedFiles.take(2).joinToString(", ")})",
                                color = colors.OnSurface,
                                fontSize = 11.sp
                            )
                            if (!isReverted && onRevertTurn != null) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "↺ Revert",
                                    color = colors.Warning,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { onRevertTurn() }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.padding(start = 36.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MessageActionChip(
                            label = "Copy",
                            icon = Lucide.Copy,
                            colors = colors,
                            onClick = { copyToClipboard(context, msg.content) }
                        )
                        MessageActionChip(
                            label = "Regenerate",
                            icon = Lucide.RefreshCw,
                            colors = colors,
                            onClick = onRegenerate
                        )
                        MessageActionChip(
                            label = "Delete",
                            icon = Lucide.Trash2,
                            colors = colors,
                            onClick = onDelete
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageActionChip(
    label: String,
    icon: ImageVector,
    colors: KryptonColorPalette,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(icon, contentDescription = label, tint = colors.Muted, modifier = Modifier.size(11.dp))
        Text(label, color = colors.Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ToolExecutionCard(
    toolName: String,
    output: String,
    colors: KryptonColorPalette
) {
    var expanded by remember { mutableStateOf(false) }
    val isError = output.startsWith("Error:")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.SurfaceVar.copy(alpha = 0.55f))
            .border(0.5.dp, if (isError) colors.Error.copy(alpha = 0.5f) else colors.Border, RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    if (isError) Lucide.CircleAlert else Lucide.Terminal,
                    contentDescription = null,
                    tint = if (isError) colors.Error else colors.Primary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    "Tool: $toolName",
                    color = colors.OnBackground,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(
                if (expanded) "Hide output ▲" else "View output ▼",
                color = colors.Muted,
                fontSize = 10.sp
            )
        }
        if (expanded && output.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.Background)
                    .padding(8.dp)
            ) {
                val preview = output.lineSequence().take(16).joinToString("\n") +
                    if (output.lineSequence().count() > 16) "\n… (${output.lineSequence().count()} lines total)" else ""
                Text(
                    preview,
                    color = if (isError) colors.Error else colors.Muted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun ErrorMessageCard(
    errorText: String,
    colors: KryptonColorPalette,
    onRetry: () -> Unit,
    onConfigureKey: () -> Unit,
    onSwitchModel: () -> Unit,
    onDelete: () -> Unit
) {
    val isKeyError = errorText.contains("API key", ignoreCase = true) ||
        errorText.contains("401", ignoreCase = true) ||
        errorText.contains("Unauthorized", ignoreCase = true)

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).background(colors.Error.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Lucide.CircleAlert, null, tint = colors.Error, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp))
                .background(colors.Surface)
                .border(0.8.dp, colors.Error.copy(alpha = 0.45f), RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Request Failed",
                    color = colors.Error,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    Lucide.X,
                    contentDescription = "Dismiss error",
                    tint = colors.Muted,
                    modifier = Modifier.size(14.dp).clickable { onDelete() }
                )
            }
            Text(
                errorText,
                color = colors.OnBackground,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isKeyError) {
                    Button(
                        onClick = onConfigureKey,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.Primary,
                            contentColor = colors.OnPrimary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Lucide.Key, null, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Key", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                OutlinedButton(
                    onClick = onSwitchModel,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(28.dp),
                    border = BorderStroke(0.5.dp, colors.Border)
                ) {
                    Text("Switch Model", color = colors.OnBackground, fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = onRetry,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(28.dp),
                    border = BorderStroke(0.5.dp, colors.Border)
                ) {
                    Icon(Lucide.RefreshCw, null, tint = colors.OnBackground, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Retry", color = colors.OnBackground, fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * Renders one message with block-based UI.
 */
@Composable
fun LarryMessageView(
    text: String,
    isUser: Boolean,
    activeFileName: String? = null,
    colors: KryptonColorPalette,
    onApplyEdit: (String, String) -> Unit,
    onInsertAtCursor: (String) -> Unit = {},
    isStreaming: Boolean = false
) {
    if (isUser) {
        ChatBubble(text = text, isUser = true, colors = colors)
        return
    }

    val blocks = remember(text) { LarryTagParser.parse(text) }
    if (blocks.isEmpty()) return

    val editBlocks = remember(blocks) { blocks.filterIsInstance<LarryBlock.Edit>().filter { it.file.isNotBlank() } }
    val context = LocalContext.current

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).background(colors.Primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Lucide.Sparkles, null, tint = colors.Primary, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.fillMaxWidth(0.92f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (editBlocks.size > 1 && !isStreaming) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.Primary.copy(alpha = 0.12f))
                        .border(0.5.dp, colors.Primary.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${editBlocks.size} file edits ready",
                        color = colors.Primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Button(
                        onClick = {
                            editBlocks.forEach { onApplyEdit(it.file, it.content) }
                            Toast.makeText(context, "Applied all ${editBlocks.size} files", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.Primary, contentColor = colors.OnPrimary),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text("Apply All (${editBlocks.size})", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            blocks.forEach { block ->
                when (block) {
                    is LarryBlock.Ans -> AnsBlockView(block.text, activeFileName, colors, onApplyEdit, onInsertAtCursor, isStreaming)
                    is LarryBlock.Thinking -> ThinkingBlockView(block.text, colors)
                    is LarryBlock.Plan -> PlanBlockView(block.text, colors)
                    is LarryBlock.Status -> StatusBlockView(block.text, colors)
                    is LarryBlock.Edit -> EditBlockView(block.file, block.content, colors, onApplyEdit, onInsertAtCursor, isStreaming)
                }
            }
        }
    }
}

private sealed class MarkdownSegment {
    data class Prose(val text: String) : MarkdownSegment()
    data class CodeFence(val lang: String, val code: String) : MarkdownSegment()
}

private fun parseMarkdownSegments(raw: String): List<MarkdownSegment> {
    val fencePattern = Regex("```([a-zA-Z0-9_-]*)\\n([\\s\\S]*?)```")
    val segments = mutableListOf<MarkdownSegment>()
    var lastIndex = 0
    for (match in fencePattern.findAll(raw)) {
        if (match.range.first > lastIndex) {
            val prose = raw.substring(lastIndex, match.range.first).trim()
            if (prose.isNotEmpty()) segments += MarkdownSegment.Prose(prose)
        }
        val lang = match.groupValues[1].trim()
        val code = match.groupValues[2].trimEnd()
        segments += MarkdownSegment.CodeFence(lang, code)
        lastIndex = match.range.last + 1
    }
    if (lastIndex < raw.length) {
        val tail = raw.substring(lastIndex).trim()
        val unclosedPattern = Regex("^```([a-zA-Z0-9_-]*)\\n([\\s\\S]*)$")
        val unclosedMatch = unclosedPattern.find(tail)
        if (unclosedMatch != null) {
            val lang = unclosedMatch.groupValues[1].trim()
            val code = unclosedMatch.groupValues[2].trimEnd()
            segments += MarkdownSegment.CodeFence(lang, code)
        } else if (tail.isNotEmpty()) {
            segments += MarkdownSegment.Prose(tail)
        }
    }
    return if (segments.isEmpty()) listOf(MarkdownSegment.Prose(raw)) else segments
}

private fun detectFileNameInCode(code: String, lang: String, fallbackActiveFile: String?): String {
    val firstLines = code.lines().take(4)
    val fileHintRegex = Regex("""(?:(?://|#|/\*|<!--)\s*(?:file:\s*|filename:\s*)?([a-zA-Z0-9_./-]+\.[a-zA-Z0-9]+))""")
    for (line in firstLines) {
        val match = fileHintRegex.find(line.trim())
        if (match != null) {
            val candidate = match.groupValues[1].trim()
            if (candidate.contains('.')) return candidate
        }
    }

    if (!fallbackActiveFile.isNullOrBlank() && fallbackActiveFile != "No file selected") {
        return fallbackActiveFile
    }

    return when (lang.lowercase(java.util.Locale.ROOT).trim()) {
        "html", "htm" -> "index.html"
        "css" -> "style.css"
        "js", "javascript" -> "main.js"
        "jsx" -> "App.jsx"
        "ts", "typescript" -> "main.ts"
        "tsx" -> "App.tsx"
        "kt", "kotlin" -> "Main.kt"
        "py", "python" -> "main.py"
        "json" -> "data.json"
        else -> "snippet.${lang.ifBlank { "txt" }}"
    }
}

@Composable
private fun AnsBlockView(
    text: String,
    activeFileName: String?,
    colors: KryptonColorPalette,
    onApplyEdit: (String, String) -> Unit,
    onInsertAtCursor: (String) -> Unit,
    isStreaming: Boolean = false
) {
    val segments = remember(text) { parseMarkdownSegments(text) }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp))
            .background(colors.Surface)
            .border(0.5.dp, colors.Border, RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp))
            .padding(12.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                segments.forEach { seg ->
                    when (seg) {
                        is MarkdownSegment.Prose -> {
                            FormattedProseText(seg.text, colors)
                        }
                        is MarkdownSegment.CodeFence -> {
                            CodeFenceCard(
                                lang = seg.lang,
                                code = seg.code,
                                activeFileName = activeFileName,
                                colors = colors,
                                onApplyEdit = onApplyEdit,
                                onInsertAtCursor = onInsertAtCursor,
                                isStreaming = isStreaming
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormattedProseText(text: String, colors: KryptonColorPalette) {
    val lines = remember(text) { text.lines() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { line ->
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("### ") -> {
                    Text(
                        formatInlineTokens(trimmed.removePrefix("### "), colors),
                        color = colors.OnBackground,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                trimmed.startsWith("## ") -> {
                    Text(
                        formatInlineTokens(trimmed.removePrefix("## "), colors),
                        color = colors.OnBackground,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                trimmed.startsWith("# ") -> {
                    Text(
                        formatInlineTokens(trimmed.removePrefix("# "), colors),
                        color = colors.OnBackground,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    Row(modifier = Modifier.padding(start = 4.dp), verticalAlignment = Alignment.Top) {
                        Text("• ", color = colors.Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(
                            formatInlineTokens(trimmed.substring(2), colors),
                            color = colors.OnBackground,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    }
                }
                trimmed.isNotBlank() -> {
                    Text(
                        formatInlineTokens(line, colors),
                        color = colors.OnBackground,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                }
            }
        }
    }
}

private fun formatInlineTokens(text: String, colors: KryptonColorPalette) = buildAnnotatedString {
    val tokenRegex = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`)")
    var cursor = 0
    for (m in tokenRegex.findAll(text)) {
        if (m.range.first > cursor) {
            append(text.substring(cursor, m.range.first))
        }
        val token = m.value
        when {
            token.startsWith("**") && token.endsWith("**") && token.length > 4 -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.OnBackground)) {
                    append(token.substring(2, token.length - 2))
                }
            }
            token.startsWith("`") && token.endsWith("`") && token.length > 2 -> {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = colors.SurfaceVar,
                        color = colors.Primary,
                        fontSize = 12.sp
                    )
                ) {
                    append(" ${token.substring(1, token.length - 1)} ")
                }
            }
            else -> append(token)
        }
        cursor = m.range.last + 1
    }
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}

@Composable
private fun CodeFenceCard(
    lang: String,
    code: String,
    activeFileName: String?,
    colors: KryptonColorPalette,
    onApplyEdit: (String, String) -> Unit,
    onInsertAtCursor: (String) -> Unit,
    isStreaming: Boolean = false
) {
    val context = LocalContext.current
    var applied by remember(code) { mutableStateOf(false) }
    val targetFile = remember(code, lang, activeFileName) {
        detectFileNameInCode(code, lang, activeFileName)
    }
    val isPatch = remember(code) { PatchApplier.isSurgicalPatch(code) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.Background)
            .border(0.5.dp, colors.Border, RoundedCornerShape(8.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.SurfaceVar.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top Row: Language badge + target file spanning full width
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Lucide.FileCode, null, tint = colors.Primary, modifier = Modifier.size(15.dp))
                Text(
                    lang.ifBlank { "code" }.uppercase(),
                    color = colors.Muted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    if (isPatch) "→ $targetFile • Patch" else "→ $targetFile",
                    color = colors.Primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            // Bottom Row: Action buttons Copy, Insert, Apply or Streaming indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isStreaming) {
                    Row(
                        modifier = Modifier.height(32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = colors.Primary
                        )
                        Text(
                            "Generating code...",
                            color = colors.Muted,
                            fontSize = 11.sp,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                copyToClipboard(context, code)
                                Toast.makeText(context, "Copied code", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(0.8.dp, colors.Border),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.OnBackground),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Lucide.Copy, null, modifier = Modifier.size(13.dp), tint = colors.Muted)
                            Spacer(Modifier.width(5.dp))
                            Text("Copy", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }

                        OutlinedButton(
                            onClick = {
                                onInsertAtCursor(code)
                                Toast.makeText(context, "Inserted at cursor in $targetFile", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(0.8.dp, colors.Border),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.OnBackground),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Insert", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }

                        Button(
                            onClick = {
                                onApplyEdit(targetFile, code)
                                applied = true
                                Toast.makeText(context, if (isPatch) "Applied surgical patch to $targetFile" else "Applied to $targetFile", Toast.LENGTH_SHORT).show()
                            },
                            enabled = !applied,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (applied) colors.SurfaceVar else colors.Primary,
                                contentColor = if (applied) colors.Muted else colors.OnPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                if (applied) "Applied ✓" else "Apply",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            Text(
                code,
                color = colors.OnBackground,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun ThinkingBlockView(text: String, colors: KryptonColorPalette) {
    var expanded by remember { mutableStateOf(false) }
    val wordCount = remember(text) { text.split(Regex("\\s+")).count { it.isNotBlank() } }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.Background)
            .border(0.5.dp, colors.Border, RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Lucide.Brain, null, tint = colors.Primary, modifier = Modifier.size(13.dp))
                Text(
                    if (expanded) "Thinking ($wordCount words)" else "Thinking ($wordCount words) • tap to view",
                    color = colors.Muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Icon(
                if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = colors.Muted,
                modifier = Modifier.size(13.dp)
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = colors.Border.copy(alpha = 0.5f), thickness = 0.5.dp)
            Spacer(Modifier.height(6.dp))
            Text(text, color = colors.Muted, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun PlanBlockView(text: String, colors: KryptonColorPalette) {
    var expanded by remember { mutableStateOf(true) }
    val stepCount = remember(text) {
        Regex("(?m)^\\s*(\\d+\\.|[-*])").findAll(text).count().let { if (it > 0) "$it steps" else "" }
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.Primary.copy(alpha = 0.08f))
            .border(0.5.dp, colors.Primary.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(10.dp)
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Lucide.ListChecks, null, tint = colors.Primary, modifier = Modifier.size(14.dp))
                Text(
                    if (stepCount.isNotEmpty()) "Implementation Plan ($stepCount)" else "Implementation Plan",
                    color = colors.Primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (expanded) "collapse" else "view",
                    color = colors.Primary.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
                Icon(
                    if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                    contentDescription = null,
                    tint = colors.Primary,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            FormattedProseText(text, colors)
        }
    }
}

@Composable
private fun StatusBlockView(text: String, colors: KryptonColorPalette) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.Surface)
            .border(0.5.dp, colors.Border, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(Lucide.Zap, null, tint = colors.Secondary, modifier = Modifier.size(12.dp))
        Text(text, color = colors.Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EditBlockView(
    file: String,
    content: String,
    colors: KryptonColorPalette,
    onApplyEdit: (String, String) -> Unit,
    onInsertAtCursor: (String) -> Unit,
    isStreaming: Boolean = false
) {
    val context = LocalContext.current
    var applied by rememberSaveable(file, content) { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val totalLines = remember(content) { content.lines().size }
    val isPatch = remember(content) { PatchApplier.isSurgicalPatch(content) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.Background)
            .border(0.8.dp, colors.Border, RoundedCornerShape(10.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.SurfaceVar.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top Row: Full width filename + line count + expand chevron
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(colors.Primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Lucide.Pencil, null, tint = colors.Primary, modifier = Modifier.size(13.dp))
                    }
                    Text(
                        file.ifBlank { "(unknown file)" },
                        color = colors.OnBackground,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (isPatch) "• Surgical Patch (Apply)" else "• $totalLines lines (${if (expanded) "collapse" else "preview"})",
                        color = if (isPatch) colors.Primary else colors.Muted,
                        fontSize = 11.sp,
                        fontWeight = if (isPatch) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = colors.Muted,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Bottom Row: Action buttons Copy, Insert, Apply or Streaming indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isStreaming) {
                    Row(
                        modifier = Modifier.height(32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = colors.Primary
                        )
                        Text(
                            "Generating code...",
                            color = colors.Muted,
                            fontSize = 11.sp,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                copyToClipboard(context, content)
                                Toast.makeText(context, "Copied file content", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(0.8.dp, colors.Border),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.OnBackground),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Lucide.Copy, null, modifier = Modifier.size(13.dp), tint = colors.Muted)
                            Spacer(Modifier.width(5.dp))
                            Text("Copy", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }

                        OutlinedButton(
                            onClick = {
                                onInsertAtCursor(content)
                                Toast.makeText(context, "Inserted at cursor", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(0.8.dp, colors.Border),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.OnBackground),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Insert", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }

                        Button(
                            onClick = {
                                onApplyEdit(file, content)
                                applied = true
                                Toast.makeText(context, if (isPatch) "Applied surgical patch to $file" else "Applied changes to $file", Toast.LENGTH_SHORT).show()
                            },
                            enabled = file.isNotBlank() && !applied,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (applied) colors.SurfaceVar else colors.Primary,
                                contentColor = if (applied) colors.Muted else colors.OnPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                if (applied) "Applied ✓" else "Apply",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        if (expanded) {
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = colors.Border.copy(alpha = 0.5f), thickness = 0.5.dp)
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                Text(
                    content,
                    color = colors.Muted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Collapse code preview ▲",
                color = colors.Primary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { expanded = false }
            )
        }
    }
}

@Composable
fun ChatBubble(
    text: String,
    isUser: Boolean,
    colors: KryptonColorPalette
) {
    val attachmentPrefix = "[📎 "
    val hasAttachments = isUser && text.startsWith(attachmentPrefix)
    val (attachmentsLine, messageBody) = if (hasAttachments) {
        val endIdx = text.indexOf("]\n")
        if (endIdx != -1) {
            val filesStr = text.substring(attachmentPrefix.length, endIdx)
            filesStr to text.substring(endIdx + 2)
        } else {
            null to text
        }
    } else {
        null to text
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape).background(colors.Primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Lucide.Sparkles, null, tint = colors.Primary, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(8.dp))
        }
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 14.dp,
                        topEnd = 14.dp,
                        bottomStart = if (isUser) 14.dp else 0.dp,
                        bottomEnd = if (isUser) 0.dp else 14.dp
                    )
                )
                .background(if (isUser) colors.Primary else colors.Surface)
                .border(0.5.dp, if (isUser) colors.Primary else colors.Border)
                .padding(12.dp)
                .widthIn(max = 280.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (attachmentsLine != null) {
                    val fileNames = attachmentsLine.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        fileNames.forEach { fname ->
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.OnPrimary.copy(alpha = 0.22f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Lucide.Paperclip,
                                    null,
                                    tint = colors.OnPrimary,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    fname,
                                    color = colors.OnPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
                Text(
                    messageBody,
                    color = if (isUser) colors.OnPrimary else colors.OnBackground,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }
        if (isUser) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape).background(colors.SurfaceVar),
                contentAlignment = Alignment.Center
            ) {
                Icon(Lucide.User, null, tint = colors.OnBackground, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
fun SuggestionChip(
    text: String,
    icon: ImageVector,
    colors: KryptonColorPalette,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(0.5.dp, colors.Border, RoundedCornerShape(50))
            .background(colors.Background)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(icon, null, tint = colors.Muted, modifier = Modifier.size(11.dp))
        Text(text, color = colors.Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

private fun copyToClipboard(context: Context, text: String, toastMessage: String = "Copied to clipboard") {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Kryptonide AI", text))
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
}
