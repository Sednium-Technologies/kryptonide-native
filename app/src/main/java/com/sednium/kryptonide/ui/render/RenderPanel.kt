package com.sednium.kryptonide.ui.render

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import com.composables.icons.lucide.*
import androidx.compose.material3.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.sednium.kryptonide.data.model.FileNode
import com.sednium.kryptonide.data.model.ProjectTemplate
import com.sednium.kryptonide.ui.theme.LocalKryptonColors
import com.composables.icons.lucide.*

// ── Log level colors ──────────────────────────────────────────────────────────
private fun logColor(level: RenderLogLevel, colors: com.sednium.kryptonide.ui.theme.KryptonColorPalette): Color = when (level) {
    RenderLogLevel.INFO    -> colors.OnBackground
    RenderLogLevel.WARN    -> colors.Warning
    RenderLogLevel.ERROR   -> colors.Error
    RenderLogLevel.SUCCESS -> colors.Success
    RenderLogLevel.CMD     -> colors.Secondary
    RenderLogLevel.SYSTEM  -> colors.Secondary
}

data class ConsoleLog(
    val level:   String,
    val message: String,
    val source:  String = "",
    val line:    Int    = 0
)

private fun consoleLogColor(level: String, colors: com.sednium.kryptonide.ui.theme.KryptonColorPalette): Color = when (level.uppercase()) {
    "WARNING" -> colors.Warning
    "ERROR"   -> colors.Error
    "DEBUG"   -> colors.Secondary
    else      -> colors.OnBackground
}

// ── MIME type helper ────────────────────────────────────────────────────────
private fun getMimeType(path: String): String {
    val ext = path.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "html", "htm" -> "text/html"
        "css"         -> "text/css"
        "js", "mjs"   -> "application/javascript"
        "jsx"         -> "text/javascript"
        "ts"          -> "text/javascript"
        "tsx"         -> "text/javascript"
        "json"        -> "application/json"
        "png"         -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif"         -> "image/gif"
        "svg"         -> "image/svg+xml"
        "webp"        -> "image/webp"
        "ico"         -> "image/x-icon"
        "woff"        -> "font/woff"
        "woff2"       -> "font/woff2"
        "ttf"         -> "font/ttf"
        "otf"         -> "font/otf"
        "mp3"         -> "audio/mpeg"
        "mp4"         -> "video/mp4"
        "txt"         -> "text/plain"
        "xml"         -> "application/xml"
        "wasm"        -> "application/wasm"
        else          -> "application/octet-stream"
    }
}

// ── RenderPanel — SPCK-style full-screen preview ──────────────────────────────
@Composable
fun RenderPanel(
    vm:             RenderEngine,
    template:       ProjectTemplate,
    projectName:    String,
    files:          List<FileNode>,
    activeFileName: String? = null,
    projectDir:     String  = "",
    onBack:         () -> Unit
) {
    val state         by vm.state.collectAsState()
    val colors         = LocalKryptonColors.current
    val hasWebPreview  = RenderTemplates.isWebPreview(template, files, activeFileName)
    val consoleLogs    = remember { mutableStateListOf<ConsoleLog>() }

    // Navigation & viewport state
    val webViewRef        = remember { mutableStateOf<WebView?>(null) }
    var canGoBack         by remember { mutableStateOf(false) }
    var isMobileFrame     by remember { mutableStateOf(false) }
    var isDesktopMode     by remember { mutableStateOf(false) }

    // Console drawer state
    var showConsoleDrawer by remember { mutableStateOf(false) }
    var showBeamDialog    by remember { mutableStateOf(false) }
    var consoleTab        by remember { mutableStateOf("console") } // "console" | "log"
    var logFilter         by remember { mutableStateOf("ALL") }     // "ALL" | "ERROR" | "WARNING"

    val errorCount = consoleLogs.count { it.level.uppercase() == "ERROR" }
    val warnCount  = consoleLogs.count { it.level.uppercase() == "WARNING" }

    LaunchedEffect(template, projectName, files, activeFileName) {
        vm.run(template, projectName, files, activeFileName)
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── LAYER 1: Full-screen WebView or Responsive Phone Frame ────────────
        if (hasWebPreview) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 54.dp)
                    .background(if (isMobileFrame) colors.Background else Color.White),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = if (isMobileFrame && !isDesktopMode) {
                        Modifier
                            .widthIn(max = 390.dp)
                            .fillMaxHeight()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .border(1.5.dp, colors.Border, RoundedCornerShape(20.dp))
                            .shadow(6.dp, RoundedCornerShape(20.dp))
                            .background(Color.White)
                    } else {
                        Modifier.fillMaxSize()
                    }
                ) {
                    WebPreviewPane(
                        html              = state.previewHtml,
                        status            = state.status,
                        files             = files,
                        projectDir        = projectDir,
                        webViewRef        = webViewRef,
                        isDesktopMode     = isDesktopMode,
                        onCanGoBackChange = { canGoBack = it },
                        onConsoleLog      = { level, msg, src, ln ->
                            consoleLogs.add(ConsoleLog(level, msg, src, ln))
                        }
                    )
                }
            }
        } else {
            // Shell output fills the whole screen
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 54.dp)
                    .background(colors.Background)
            ) {
                ConsoleLogPane(
                    log          = state.log,
                    status       = state.status,
                    errorSummary = state.errorSummary
                )
            }
        }

        // ── LAYER 2: Unified top bar ────────────────────────────────────────────
        val statusColor = when (state.status) {
            RenderStatus.PREVIEW_READY, RenderStatus.SUCCESS  -> colors.Success
            RenderStatus.FAILED                               -> colors.Error
            RenderStatus.RUNNING, RenderStatus.PREPARING      -> colors.Secondary
            else                                              -> colors.Muted
        }
        val isRunning = state.status == RenderStatus.RUNNING || state.status == RenderStatus.PREPARING
        val inf = rememberInfiniteTransition(label = "pulse")
        val pulseAlpha by inf.animateFloat(
            initialValue  = 1f,
            targetValue   = 0.35f,
            animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label         = "pulseAlpha"
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .align(Alignment.TopCenter)
                .background(MaterialTheme.colorScheme.surface)
                .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant))
                .padding(horizontal = 8.dp)
                .zIndex(10f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back button: navigate web history if possible, otherwise exit preview
            IconButton(
                onClick = {
                    if (canGoBack && webViewRef.value?.canGoBack() == true) {
                        webViewRef.value?.goBack()
                    } else {
                        onBack()
                    }
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Lucide.ArrowLeft,
                    contentDescription = if (canGoBack) "Back in preview" else "Back to editor",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(Modifier.width(4.dp))

            // Pulsing live status indicator
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = if (isRunning) pulseAlpha else 1f))
            )

            Spacer(Modifier.width(6.dp))

            // Address bar / target file indicator
            val displayTarget = activeFileName?.takeIf {
                it.endsWith(".html", ignoreCase = true) || it.endsWith(".htm", ignoreCase = true)
            } ?: "index.html"

            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Lucide.Lock, null, tint = colors.Success, modifier = Modifier.size(10.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "krypton://$projectName/$displayTarget",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(4.dp))

            // Device Viewport Toggle (Phone Frame vs Desktop Site)
            if (hasWebPreview) {
                IconButton(
                    onClick = {
                        isDesktopMode = !isDesktopMode
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = if (isDesktopMode) Lucide.Laptop else Lucide.Smartphone,
                        contentDescription = if (isDesktopMode) "Desktop View (Active)" else "Mobile View (Active)",
                        tint = if (isDesktopMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            // Console drawer toggle with error/warning badge
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showConsoleDrawer = !showConsoleDrawer }
                    .padding(horizontal = 7.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Lucide.Terminal,
                    "Console",
                    tint = if (showConsoleDrawer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp)
                )
                if (errorCount > 0) {
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(colors.Error),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (errorCount > 9) "9+" else errorCount.toString(),
                            color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold
                        )
                    }
                } else if (warnCount > 0) {
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(colors.Warning),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (warnCount > 9) "9+" else warnCount.toString(),
                            color = Color.Black, fontSize = 8.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Open externally (Live port or exported HTML)
            val context = LocalContext.current
            IconButton(
                onClick = {
                    runCatching {
                        if (!state.previewUrl.isNullOrBlank()) {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(state.previewUrl))
                            context.startActivity(intent)
                        } else if (hasWebPreview) {
                            val dir = if (projectDir.isNotBlank()) File(projectDir)
                            else File(
                                com.sednium.kryptonide.utils.StorageUtils.getProjectsDir(),
                                projectName
                            )
                            dir.mkdirs()
                            val html = state.previewHtml ?: return@runCatching
                            val previewFile = File(dir, ".krypton_preview.html")
                            previewFile.writeText(html)
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", previewFile
                            )
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "text/html")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(intent)
                        }
                    }.onFailure {
                        android.widget.Toast.makeText(context, "Couldn't open externally: ${it.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    Lucide.ExternalLink,
                    "Open externally",
                    tint = if (!state.previewUrl.isNullOrBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Beam to LAN Wi-Fi
            IconButton(
                onClick = {
                    if (!com.sednium.kryptonide.server.KryptonBeamServer.isRunning) {
                        val projectDir = File(com.sednium.kryptonide.utils.StorageUtils.getProjectsDir(), projectName)
                        com.sednium.kryptonide.server.KryptonBeamServer.start(
                            htmlProvider = { state.previewHtml },
                            projectDirProvider = { projectDir }
                        )
                    }
                    showBeamDialog = true
                },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    Lucide.Radio,
                    "Beam to Screen",
                    tint = if (com.sednium.kryptonide.server.KryptonBeamServer.isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Refresh preview
            IconButton(
                onClick = {
                    consoleLogs.clear()
                    vm.restart(template, projectName, files, activeFileName)
                },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(Lucide.RefreshCw, "Refresh", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
            }

            // Close preview
            IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                Icon(Lucide.X, "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }

        // ── LAYER 3: Console drawer (slides up from bottom) ───────────────────
        // Background scrim: tap outside to close the drawer
        if (showConsoleDrawer) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(18f)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable { showConsoleDrawer = false }
            )
        }

        AnimatedVisibility(
            visible = showConsoleDrawer,
            enter   = slideInVertically(initialOffsetY = { it }, animationSpec = tween(280, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
            exit    = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(220, easing = FastOutLinearInEasing)) + fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.BottomCenter).zIndex(20f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.55f)
                    .shadow(12.dp, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(colors.Background)
            ) {
                // Top drag handle bar (drag down to close; no accidental click-dismiss)
                var dragOffset by remember { mutableStateOf(0f) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .background(colors.Surface)
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                if (delta > 0) dragOffset += delta
                            },
                            onDragStopped = { velocity ->
                                if (velocity > 80f || dragOffset > 25f) {
                                    showConsoleDrawer = false
                                }
                                dragOffset = 0f
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(44.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(2.5.dp))
                            .background(colors.Border)
                    )
                }

                // Drawer action bar: tabs on left, filters & buttons on right
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.Surface)
                        .padding(horizontal = 8.dp, vertical = 0.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Tabs
                    if (hasWebPreview) {
                        ConsoleDrawerTab("Console", Lucide.Bug, consoleTab == "console",
                            badge = if (errorCount > 0) errorCount.toString() else null
                        ) { consoleTab = "console" }
                    }
                    ConsoleDrawerTab("Engine Log", Lucide.List, consoleTab == "log") { consoleTab = "log" }

                    Spacer(Modifier.weight(1f))

                    // Filter chips if in browser console tab
                    if (consoleTab == "console") {
                        val filterOptions = listOf("ALL", "ERROR", "WARN")
                        filterOptions.forEach { opt ->
                            val isSelected = (opt == "ALL" && logFilter == "ALL") ||
                                (opt == "ERROR" && logFilter == "ERROR") ||
                                (opt == "WARN" && logFilter == "WARNING")
                            Text(
                                text = opt,
                                fontSize = 9.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) colors.Primary else colors.Muted,
                                modifier = Modifier
                                    .padding(horizontal = 2.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) colors.Border else Color.Transparent)
                                    .clickable {
                                        logFilter = when (opt) {
                                            "ERROR" -> "ERROR"
                                            "WARN"  -> "WARNING"
                                            else    -> "ALL"
                                        }
                                    }
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }

                    // Clear button
                    IconButton(onClick = { consoleLogs.clear(); vm.clearLog() }, modifier = Modifier.size(28.dp)) {
                        Icon(Lucide.Trash2, "Clear", tint = colors.Muted, modifier = Modifier.size(15.dp))
                    }
                    // Close button
                    IconButton(onClick = { showConsoleDrawer = false }, modifier = Modifier.size(28.dp)) {
                        Icon(Lucide.ChevronDown, "Close", tint = colors.Muted, modifier = Modifier.size(18.dp))
                    }
                }

                HorizontalDivider(color = colors.Border, thickness = 0.5.dp)

                // Drawer content
                Box(modifier = Modifier.fillMaxSize()) {
                    when (consoleTab) {
                        "console" -> {
                            val filteredLogs = remember(consoleLogs.size, logFilter) {
                                when (logFilter) {
                                    "ERROR"   -> consoleLogs.filter { it.level.uppercase() == "ERROR" }
                                    "WARNING" -> consoleLogs.filter { it.level.uppercase() == "WARNING" }
                                    else      -> consoleLogs
                                }
                            }
                            BrowserConsolePane(logs = filteredLogs)
                        }
                        "log" -> ConsoleLogPane(
                            log          = state.log,
                            status       = state.status,
                            errorSummary = state.errorSummary
                        )
                    }
                }
            }
        }
    }

    if (showBeamDialog) {
        KryptonBeamDialog(
            onDismiss = { showBeamDialog = false },
            onStopBeam = { com.sednium.kryptonide.server.KryptonBeamServer.stop() }
        )
    }
}

// ── Console drawer tab ────────────────────────────────────────────────────────
@Composable
private fun ConsoleDrawerTab(
    label:   String,
    icon:    ImageVector,
    isActive: Boolean,
    badge:   String? = null,
    onClick: () -> Unit
) {
    val colors = LocalKryptonColors.current
    val tint by animateColorAsState(
        if (isActive) colors.Secondary else colors.Muted,
        tween(160), label = "tint"
    )
    Column(
        modifier = Modifier
            .width(IntrinsicSize.Min)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(top = 9.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(13.dp))
            Text(
                text = label,
                color = tint,
                fontSize = 11.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                softWrap = false
            )
            if (badge != null) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.Error)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(badge, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (isActive) colors.Secondary else Color.Transparent)
        )
    }
}

private const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

private fun applyWebViewDesktopMode(wv: WebView, isDesktop: Boolean) {
    val defaultUa = WebSettings.getDefaultUserAgent(wv.context)
    wv.settings.userAgentString = if (isDesktop) DESKTOP_UA else defaultUa
    wv.settings.useWideViewPort = true
    wv.settings.loadWithOverviewMode = true

    val js = if (isDesktop) {
        """
        (function() {
            function applyDesktop() {
                try {
                    var meta = document.querySelector('meta[name="viewport"]');
                    if (!meta) {
                        meta = document.createElement('meta');
                        meta.name = 'viewport';
                        if (document.head) {
                            document.head.appendChild(meta);
                        } else if (document.documentElement) {
                            document.documentElement.appendChild(meta);
                        }
                    }
                    if (meta) {
                        meta.setAttribute('content', 'width=1180, initial-scale=0.35, maximum-scale=3.0, user-scalable=yes');
                    }
                    if (document.documentElement && document.documentElement.style) {
                        document.documentElement.style.minWidth = '1180px';
                        document.documentElement.style.width = '1180px';
                    }
                    if (document.body && document.body.style) {
                        document.body.style.minWidth = '1180px';
                        document.body.style.width = '1180px';
                    }
                } catch(e) {}
            }
            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', applyDesktop);
            } else {
                applyDesktop();
            }
        })();
        """.trimIndent()
    } else {
        """
        (function() {
            function applyMobile() {
                try {
                    var meta = document.querySelector('meta[name="viewport"]');
                    if (meta) {
                        meta.setAttribute('content', 'width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes');
                    }
                    if (document.documentElement && document.documentElement.style) {
                        document.documentElement.style.minWidth = '';
                        document.documentElement.style.width = '';
                    }
                    if (document.body && document.body.style) {
                        document.body.style.minWidth = '';
                        document.body.style.width = '';
                    }
                } catch(e) {}
            }
            if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', applyMobile);
            } else {
                applyMobile();
            }
        })();
        """.trimIndent()
    }
    wv.evaluateJavascript(js, null)
}

// ── Full-screen WebView ───────────────────────────────────────────────────────
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPreviewPane(
    html:              String?,
    status:            RenderStatus,
    files:             List<FileNode>,
    projectDir:        String,
    webViewRef:        MutableState<WebView?>,
    isDesktopMode:     Boolean = false,
    onCanGoBackChange: (Boolean) -> Unit,
    onConsoleLog:      (level: String, msg: String, src: String, line: Int) -> Unit
) {
    var lastValidHtml by remember { mutableStateOf(html) }

    LaunchedEffect(isDesktopMode) {
        webViewRef.value?.let { wv ->
            applyWebViewDesktopMode(wv, isDesktopMode)
        }
    }

    LaunchedEffect(html, status) {
        if (!html.isNullOrBlank() && (status == RenderStatus.PREVIEW_READY || status == RenderStatus.RUNNING || status == RenderStatus.IDLE)) {
            lastValidHtml = html
            webViewRef.value?.let { wv ->
                wv.loadDataWithBaseURL("https://krypton.preview/", html, "text/html", "UTF-8", null)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            com.sednium.kryptonide.ui.preview.PreviewConsoleBridge.unregisterWebView(webViewRef.value)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val currentHtml = lastValidHtml
        if (currentHtml != null) {
            AndroidView(
                factory = { context ->
                    WebView(context).also { wv ->
                        webViewRef.value = wv
                        com.sednium.kryptonide.ui.preview.PreviewConsoleBridge.registerWebView(wv)
                        wv.settings.apply {
                            javaScriptEnabled                     = true
                            domStorageEnabled                     = true
                            // The preview is untrusted user content. It must not
                            // be able to read app-private files, content:// URIs,
                            // or mixed HTTP resources while the page itself is
                            // served from an HTTPS origin.
                            allowFileAccess                       = false
                            allowContentAccess                    = false
                            allowFileAccessFromFileURLs           = false
                            allowUniversalAccessFromFileURLs      = false
                            useWideViewPort                       = true
                            loadWithOverviewMode                  = true
                            setSupportZoom(true)
                            builtInZoomControls                   = true
                            displayZoomControls                   = false
                            textZoom                              = 100
                            javaScriptCanOpenWindowsAutomatically = false
                            mediaPlaybackRequiresUserGesture      = true
                            setSafeBrowsingEnabled(true)
                            mixedContentMode                      = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            if (isDesktopMode) {
                                userAgentString = DESKTOP_UA
                            }
                        }
                        wv.setBackgroundColor(android.graphics.Color.WHITE)
                        wv.webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                                val lvl = msg.messageLevel().name
                                val m = msg.message()
                                val src = msg.sourceId() ?: ""
                                val ln = msg.lineNumber()
                                com.sednium.kryptonide.ui.preview.PreviewConsoleBridge.record(lvl, m, src, ln)
                                onConsoleLog(lvl, m, src, ln)
                                return false
                            }
                        }
                        wv.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean {
                                onCanGoBackChange(view?.canGoBack() == true)
                                return false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                onCanGoBackChange(view?.canGoBack() == true)
                                view?.let { applyWebViewDesktopMode(it, isDesktopMode) }
                            }

                            override fun shouldInterceptRequest(view: WebView?, req: WebResourceRequest?): WebResourceResponse? {
                                val url = req?.url ?: return null
                                val host = url.host ?: ""
                                if (host.equals("krypton.preview", ignoreCase = true) || url.scheme.equals("file", ignoreCase = true)) {
                                    val rawPath = url.path?.removePrefix("/") ?: return null
                                    val path = rawPath.substringBefore('?').substringBefore('#')
                                    if (path.isEmpty() || path == "index.html" || path == ".krypton_preview.html") {
                                        val h = currentHtml ?: lastValidHtml
                                        if (h != null) {
                                            val stream = ByteArrayInputStream(h.toByteArray(Charsets.UTF_8))
                                            val response = WebResourceResponse("text/html", "UTF-8", stream)
                                            response.responseHeaders = mapOf(
                                                "Access-Control-Allow-Origin" to "*",
                                                "Cache-Control" to "no-cache"
                                            )
                                            return response
                                        }
                                        return null
                                    }

                                    val mimeType = getMimeType(path)
                                    val isBinary = (mimeType.startsWith("image/") && mimeType != "image/svg+xml") ||
                                        mimeType.startsWith("font/") ||
                                        mimeType.startsWith("audio/") ||
                                        mimeType.startsWith("video/") ||
                                        mimeType == "image/x-icon" ||
                                        mimeType == "application/wasm" ||
                                        mimeType == "application/octet-stream"

                                    // 1. Resolve binary assets or exact disk files first to preserve raw bytes
                                    if (projectDir.isNotBlank()) {
                                        val baseProjDir = runCatching { File(projectDir).canonicalFile }.getOrNull()
                                        val diskFile = File(projectDir, path)
                                        val isSafe = baseProjDir != null && runCatching { diskFile.canonicalFile.startsWith(baseProjDir) }.getOrDefault(false)
                                        if (isSafe && diskFile.exists() && diskFile.isFile) {
                                            return try {
                                                val stream = FileInputStream(diskFile)
                                                val encoding = if (isBinary) null else "UTF-8"
                                                val response = WebResourceResponse(mimeType, encoding, stream)
                                                response.responseHeaders = mapOf(
                                                    "Access-Control-Allow-Origin" to "*",
                                                    "Cache-Control" to "no-cache"
                                                )
                                                response
                                            } catch (_: Exception) { null }
                                        }
                                    }

                                    // 2. Candidate paths for extensionless imports (e.g. './Card' -> 'Card.jsx')
                                    val candidatePaths = listOf(
                                        path,
                                        "$path.jsx",
                                        "$path.tsx",
                                        "$path.js",
                                        "$path.ts",
                                        "$path/index.jsx",
                                        "$path/index.tsx",
                                        "$path/index.js",
                                        "$path/index.ts"
                                    )

                                    val fileMatch = files.firstOrNull { node ->
                                        val nodeName = node.name
                                        candidatePaths.any { cand ->
                                            nodeName.equals(cand, ignoreCase = true) ||
                                                cand.endsWith("/$nodeName", ignoreCase = true) ||
                                                nodeName.endsWith("/$cand", ignoreCase = true)
                                        }
                                    }
                                    if (fileMatch != null && fileMatch.content != null) {
                                        val resolvedMime = getMimeType(fileMatch.name)
                                        val stream = ByteArrayInputStream(fileMatch.content.toByteArray(Charsets.UTF_8))
                                        val response = WebResourceResponse(resolvedMime, "UTF-8", stream)
                                        response.responseHeaders = mapOf(
                                            "Access-Control-Allow-Origin" to "*",
                                            "Cache-Control" to "no-cache"
                                        )
                                        return response
                                    }

                                    // 3. Fallback to candidate paths on disk
                                    if (projectDir.isNotBlank()) {
                                        val baseProjDir = runCatching { File(projectDir).canonicalFile }.getOrNull()
                                        for (cand in candidatePaths) {
                                            val diskFile = File(projectDir, cand)
                                            val isSafe = baseProjDir != null && runCatching { diskFile.canonicalFile.startsWith(baseProjDir) }.getOrDefault(false)
                                            if (isSafe && diskFile.exists() && diskFile.isFile) {
                                                return try {
                                                    val stream = FileInputStream(diskFile)
                                                    val resolvedMime = getMimeType(diskFile.name)
                                                    val resIsBinary = (resolvedMime.startsWith("image/") && resolvedMime != "image/svg+xml") ||
                                                        resolvedMime.startsWith("font/") ||
                                                        resolvedMime.startsWith("audio/") ||
                                                        resolvedMime.startsWith("video/")
                                                    val response = WebResourceResponse(resolvedMime, if (resIsBinary) null else "UTF-8", stream)
                                                    response.responseHeaders = mapOf(
                                                        "Access-Control-Allow-Origin" to "*",
                                                        "Cache-Control" to "no-cache"
                                                    )
                                                    response
                                                } catch (_: Exception) { null }
                                            }
                                        }
                                    }
                                }
                                return super.shouldInterceptRequest(view, req)
                            }
                        }
                        wv.loadDataWithBaseURL("https://krypton.preview/", currentHtml, "text/html", "UTF-8", null)
                    }
                },
                update   = { wv ->
                    // Viewport & desktop UA are dynamically applied in LaunchedEffect(isDesktopMode)
                },
                modifier = Modifier.fillMaxSize()
            )

            if (status == RenderStatus.PREPARING || status == RenderStatus.RUNNING) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .align(Alignment.TopCenter),
                    color = LocalKryptonColors.current.Secondary,
                    trackColor = Color.Transparent
                )
            }
        } else {
            // No HTML yet
            Box(modifier = Modifier.fillMaxSize().background(LocalKryptonColors.current.Background), contentAlignment = Alignment.Center) {
                when (status) {
                    RenderStatus.PREPARING, RenderStatus.RUNNING -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = LocalKryptonColors.current.Secondary, modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                            Spacer(Modifier.height(14.dp))
                            Text("Building preview…", color = LocalKryptonColors.current.Muted, fontSize = 13.sp)
                        }
                    }
                    RenderStatus.FAILED -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Lucide.CircleAlert, null, tint = LocalKryptonColors.current.Error, modifier = Modifier.size(44.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("Preview failed", color = LocalKryptonColors.current.Error, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text("Tap ⊞ to see the error log.", color = LocalKryptonColors.current.Muted, fontSize = 12.sp)
                        }
                    }
                    else -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Lucide.CirclePlay, null, tint = LocalKryptonColors.current.Muted, modifier = Modifier.size(52.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("Press Run to preview", color = LocalKryptonColors.current.Muted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

// ── Browser console pane ──────────────────────────────────────────────────────
@Composable
fun BrowserConsolePane(logs: List<ConsoleLog>) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1) }

    if (logs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Lucide.CircleCheck, null, tint = LocalKryptonColors.current.Success, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(8.dp))
                Text("No console errors", color = LocalKryptonColors.current.Muted, fontSize = 13.sp)
            }
        }
    } else {
        val colors = LocalKryptonColors.current
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().background(colors.Background), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(logs) { log ->
                val color = consoleLogColor(log.level, colors)
                val bg    = when (log.level.uppercase()) {
                    "ERROR"   -> colors.Error.copy(alpha = 0.07f)
                    "WARNING" -> colors.Warning.copy(alpha = 0.06f)
                    else      -> Color.Transparent
                }
                Row(
                    modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    val levelIcon = when (log.level.uppercase()) {
                        "ERROR"   -> Lucide.CircleAlert
                        "WARNING" -> Lucide.TriangleAlert
                        else      -> Lucide.Info
                    }
                    Icon(levelIcon, null, tint = color, modifier = Modifier.size(13.dp).padding(top = 2.dp))
                    Spacer(Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(log.message, color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
                        if (log.source.isNotEmpty()) {
                            Text("${log.source}:${log.line}", color = colors.Muted.copy(alpha = 0.6f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                HorizontalDivider(color = colors.Border.copy(alpha = 0.4f), thickness = 0.3.dp)
            }
        }
    }
}

// ── Engine log pane ───────────────────────────────────────────────────────────
@Composable
fun ConsoleLogPane(log: List<RenderLogLine>, status: RenderStatus, errorSummary: String?) {
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1) }

    Column(modifier = Modifier.fillMaxSize()) {
        val errColors = LocalKryptonColors.current
        AnimatedVisibility(visible = !errorSummary.isNullOrEmpty() && status == RenderStatus.FAILED) {
            Row(
                modifier = Modifier.fillMaxWidth().background(errColors.Error.copy(alpha = 0.12f))
                    .border(0.5.dp, errColors.Error.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Lucide.CircleAlert, null, tint = errColors.Error, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text(errorSummary ?: "", color = errColors.Error, fontSize = 11.sp, modifier = Modifier.weight(1f))
            }
        }

        if (log.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (status) {
                    RenderStatus.IDLE -> Text("Press ▶ Run to start.", color = errColors.Muted, fontSize = 13.sp)
                    RenderStatus.PREPARING, RenderStatus.RUNNING -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = errColors.Secondary, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                            Spacer(Modifier.height(10.dp))
                            Text("Starting…", color = errColors.Muted, fontSize = 13.sp)
                        }
                    }
                    else -> Text("No output.", color = errColors.Muted, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().background(errColors.Background), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                items(log) { line ->
                    val clr = logColor(line.level, errColors)
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.5.dp), verticalAlignment = Alignment.Top) {
                        val lbl = when (line.level) {
                            RenderLogLevel.INFO    -> "INFO"
                            RenderLogLevel.WARN    -> "WARN"
                            RenderLogLevel.ERROR   -> "ERR "
                            RenderLogLevel.CMD     -> "CMD "
                            RenderLogLevel.SYSTEM  -> "SYS "
                            RenderLogLevel.SUCCESS -> "OK  "
                        }
                        Box(modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(clr.copy(alpha = 0.12f)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                            Text(lbl, color = clr, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(line.text, color = clr, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp, modifier = Modifier.weight(1f))
                    }
                }
                if (status == RenderStatus.RUNNING || status == RenderStatus.PREPARING) {
                    item {
                        val a = rememberInfiniteTransition(label = "c")
                        val al by a.animateFloat(1f, 0f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "ca")
                        Text("▌", color = errColors.Secondary.copy(alpha = al), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
