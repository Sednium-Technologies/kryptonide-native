package com.sednium.kryptonide.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.composables.icons.lucide.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sednium.kryptonide.data.model.ChatMessage
import com.sednium.kryptonide.data.model.MessageRole
import com.sednium.kryptonide.ui.editor.EditorViewModel
import com.sednium.kryptonide.ui.theme.LocalKryptonColors

private const val SYSTEM_PROMPT = "You are Oorty Lite, Krypton IDE's AI assistant. You have access to tools to read and edit files in the user's project. Always prefer surgical edits (edit_lines, patch_file) over full rewrites. When using patch_file, use exact code that exists in the file. When asked to make changes, read the file first if you haven't already."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiPanel(aiVm: AiViewModel, editorVm: EditorViewModel) {
    val state by aiVm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val inputText by aiVm.draftPromptText.collectAsStateWithLifecycle()
    var showSessions by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        aiVm.init(editorVm)
        val lastIdx = listState.layoutInfo.totalItemsCount - 1
        if (lastIdx >= 0) {
            runCatching { listState.scrollToItem(lastIdx, 0) }
        }
    }

    LaunchedEffect(state.messages.size, state.streamingText.length / 20) {
        val lastIdx = listState.layoutInfo.totalItemsCount - 1
        if (lastIdx >= 0) {
            runCatching { listState.scrollToItem(lastIdx, 0) }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("AI CHAT", color = LocalKryptonColors.current.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { showSessions = !showSessions }, modifier = Modifier.size(28.dp)) {
                Icon(Lucide.History, contentDescription = "Sessions", tint = LocalKryptonColors.current.Muted, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = { aiVm.createSession() }, modifier = Modifier.size(28.dp)) {
                Icon(Lucide.Plus, contentDescription = "New chat", tint = LocalKryptonColors.current.Muted, modifier = Modifier.size(16.dp))
            }
        }

        HorizontalDivider(color = LocalKryptonColors.current.Border, thickness = 0.5.dp)

        if (showSessions && state.sessions.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).background(LocalKryptonColors.current.Background)) {
                state.sessions.forEach { session ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (session.id == state.activeSessionId) LocalKryptonColors.current.SurfaceVar else LocalKryptonColors.current.Background)
                            .clickable { aiVm.setActiveSession(session.id); showSessions = false }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(session.title, color = LocalKryptonColors.current.OnSurface, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        IconButton(onClick = { aiVm.deleteSession(session.id) }, modifier = Modifier.size(24.dp)) {
                            Icon(Lucide.X, contentDescription = "Delete", tint = LocalKryptonColors.current.Muted, modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
            HorizontalDivider(color = LocalKryptonColors.current.Border, thickness = 0.5.dp)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (state.messages.isEmpty() && !state.isLoading) {
                item {
                    Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Lucide.Sparkles, contentDescription = null, tint = LocalKryptonColors.current.Primary, modifier = Modifier.size(32.dp))
                            Text("Ask AI anything about your project", color = LocalKryptonColors.current.Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
            items(state.messages, key = { it.id }) { msg -> MessageBubble(msg) }
            if (state.streamingText.isNotBlank()) {
                item { StreamingBubble(state.streamingText) }
            }
            if (state.isLoading && state.streamingText.isBlank()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = LocalKryptonColors.current.Primary)
                        Text("Thinking…", color = LocalKryptonColors.current.Muted, fontSize = 12.sp)
                    }
                }
            }
            item(key = "chat_bottom_anchor") {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        HorizontalDivider(color = LocalKryptonColors.current.Border, thickness = 0.5.dp)

        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { aiVm.setDraftPromptText(it) },
                placeholder = { Text("Message AI…", color = LocalKryptonColors.current.Muted, fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                maxLines = 5,
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedBorderColor = LocalKryptonColors.current.Primary,
                    unfocusedBorderColor = LocalKryptonColors.current.Border,
                    textColor = LocalKryptonColors.current.OnBackground,
                    cursorColor = LocalKryptonColors.current.Primary,
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
            )
            Spacer(Modifier.width(6.dp))
            if (state.isLoading) {
                IconButton(
                    onClick = { aiVm.abortChat() },
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(LocalKryptonColors.current.Error),
                ) {
                    Icon(Lucide.Square, contentDescription = "Stop", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp))
                }
            } else {
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            val text = inputText.trim()
                            aiVm.setDraftPromptText("")
                            aiVm.sendMessage(text, SYSTEM_PROMPT)
                        }
                    },
                    enabled = inputText.isNotBlank(),
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (inputText.isNotBlank()) LocalKryptonColors.current.Primary else LocalKryptonColors.current.SurfaceVar),
                ) {
                    Icon(
                        Lucide.ArrowUp, contentDescription = "Send",
                        tint = if (inputText.isNotBlank()) LocalKryptonColors.current.OnPrimary else LocalKryptonColors.current.Muted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == MessageRole.USER
    val isTool = msg.role == MessageRole.TOOL
    val bgColor = when { isUser -> LocalKryptonColors.current.Primary; isTool -> LocalKryptonColors.current.SurfaceVar; else -> LocalKryptonColors.current.Surface }
    val textColor = if (isUser) LocalKryptonColors.current.OnPrimary else LocalKryptonColors.current.OnSurface

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        if (!isUser && !isTool) {
            Icon(Lucide.Sparkles, contentDescription = null, tint = LocalKryptonColors.current.Primary, modifier = Modifier.size(16.dp).padding(top = 2.dp))
            Spacer(Modifier.width(4.dp))
        }
        SelectionContainer {
            Box(
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .clip(RoundedCornerShape(topStart = if (isUser) 12.dp else 2.dp, topEnd = if (isUser) 2.dp else 12.dp, bottomStart = 12.dp, bottomEnd = 12.dp))
                    .background(bgColor)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                if (isTool) {
                    Column {
                        Text("Tool: ${msg.toolName ?: ""}", color = LocalKryptonColors.current.Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        Text(msg.content.take(200), color = LocalKryptonColors.current.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                } else {
                    Text(msg.content, color = textColor, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Icon(Lucide.Sparkles, contentDescription = null, tint = LocalKryptonColors.current.Primary, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier.widthIn(max = 220.dp)
                .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 12.dp))
                .background(LocalKryptonColors.current.Surface)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text(text, color = LocalKryptonColors.current.OnSurface, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}
