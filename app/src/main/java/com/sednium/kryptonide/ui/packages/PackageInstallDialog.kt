package com.sednium.kryptonide.ui.packages

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sednium.kryptonide.data.model.ProjectTemplate
import com.sednium.kryptonide.ui.theme.KryptonColorPalette
import com.sednium.kryptonide.ui.theme.LocalKryptonColors
import kotlinx.coroutines.launch
import com.composables.icons.lucide.*

enum class StageState {
    PENDING,
    RUNNING,
    COMPLETED,
    SKIPPED,
    FAILED
}

@Composable
fun PackageInstallDialog(
    template: ProjectTemplate,
    onDismiss: () -> Unit,
    onComplete: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kc = LocalKryptonColors.current

    var progress by remember { mutableFloatStateOf(0f) }
    var currentStageText by remember { mutableStateOf("Initializing package manager...") }
    var isInstalling by remember { mutableStateOf(true) }

    var stage1State by remember { mutableStateOf(StageState.PENDING) }
    var stage2State by remember { mutableStateOf(StageState.PENDING) }
    var stage3State by remember { mutableStateOf(StageState.PENDING) }

    val logs = remember { mutableStateListOf<String>() }
    val scrollState = rememberScrollState()

    // Auto-scroll to bottom of terminal console
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    // Launch installation workflow
    LaunchedEffect(template) {
        scope.launch {
            isInstalling = true
            stage1State = StageState.RUNNING

            val success = PackageInstaller.installPackages(
                context = context,
                template = template,
                onProgress = { fraction, stage ->
                    progress = fraction
                    currentStageText = stage

                    when {
                        fraction < 0.25f -> {
                            stage1State = StageState.RUNNING
                        }
                        fraction in 0.25f..0.65f -> {
                            stage1State = StageState.COMPLETED
                            stage2State = if (stage.contains("Skipped", ignoreCase = true)) {
                                StageState.SKIPPED
                            } else {
                                StageState.RUNNING
                            }
                        }
                        fraction in 0.65f..0.98f -> {
                            stage1State = StageState.COMPLETED
                            if (stage2State == StageState.RUNNING) stage2State = StageState.COMPLETED
                            stage3State = if (stage.contains("Skipped", ignoreCase = true)) {
                                StageState.SKIPPED
                            } else {
                                StageState.RUNNING
                            }
                        }
                        fraction >= 1.0f -> {
                            stage1State = StageState.COMPLETED
                            if (stage2State == StageState.RUNNING) stage2State = StageState.COMPLETED
                            if (stage3State == StageState.RUNNING) stage3State = StageState.COMPLETED
                        }
                    }
                },
                onLog = { line ->
                    logs.add(line)
                }
            )

            progress = 1.0f
            isInstalling = false
            if (stage1State == StageState.RUNNING) stage1State = StageState.COMPLETED
            if (stage2State == StageState.RUNNING) stage2State = StageState.COMPLETED
            if (stage3State == StageState.RUNNING) stage3State = StageState.COMPLETED
        }
    }

    Dialog(
        onDismissRequest = {
            if (!isInstalling) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(kc.Surface)
                    .border(1.dp, kc.Border, RoundedCornerShape(24.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(kc.Primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Lucide.Package,
                            contentDescription = null,
                            tint = kc.Primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Installing Shared Packages",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = kc.OnSurface
                        )
                        Text(
                            text = "Configuring dependencies for ${template.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = kc.Muted
                        )
                    }

                    if (!isInstalling) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Lucide.X,
                                contentDescription = "Close",
                                tint = kc.Muted
                            )
                        }
                    }
                }

                // Path note
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(kc.SurfaceVar)
                        .border(1.dp, kc.Border.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Lucide.Folder,
                        contentDescription = null,
                        tint = kc.Primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = PackageInstaller.getPackagesDir().absolutePath,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = kc.OnSurface.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Multi-stage progress indicators:
                // 1. Setup Directories
                // 2. NPM Modules (--no-bin-links)
                // 3. Python Packages (--target)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(kc.Background.copy(alpha = 0.5f))
                        .border(1.dp, kc.Border.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StageItem(
                        stageNumber = "1",
                        title = "Setup Directories",
                        state = stage1State,
                        kc = kc
                    )
                    StageItem(
                        stageNumber = "2",
                        title = "NPM Modules (--no-bin-links)",
                        state = stage2State,
                        kc = kc
                    )
                    StageItem(
                        stageNumber = "3",
                        title = "Python Packages (--target)",
                        state = stage3State,
                        kc = kc
                    )
                }

                // Animated LinearProgressIndicator
                val animatedProgress by animateFloatAsState(
                    targetValue = progress,
                    animationSpec = tween(durationMillis = 300),
                    label = "package_progress"
                )
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentStageText,
                            fontSize = 11.sp,
                            color = kc.Muted,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${(animatedProgress.coerceIn(0f, 1f) * 100).toInt()}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = kc.Primary
                        )
                    }
                    LinearProgressIndicator(
                        progress = { animatedProgress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = kc.Primary,
                        trackColor = kc.Border
                    )
                }

                // Scrollable terminal output console with monospace font
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0F172A))
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                        .verticalScroll(scrollState)
                ) {
                    if (logs.isEmpty()) {
                        Text(
                            text = "Waiting for installation to start...",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    } else {
                        logs.forEach { logLine ->
                            val textColor = when {
                                logLine.startsWith("✓") -> Color(0xFF10B981) // Green
                                logLine.startsWith("!") || logLine.contains("error", ignoreCase = true) -> Color(0xFFEF4444) // Red
                                logLine.startsWith("▶") || logLine.startsWith("$") -> Color(0xFF38BDF8) // Cyan / Blue
                                logLine.startsWith("↷") -> Color(0xFF94A3B8) // Muted Gray
                                logLine.startsWith("Tip:") -> Color(0xFFF59E0B) // Amber
                                else -> Color(0xFFE2E8F0) // Terminal text
                            }
                            Text(
                                text = logLine,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = textColor
                            )
                        }
                    }
                }

                // Bottom button: Done when finished, or Close
                if (isInstalling) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = kc.Muted),
                        border = androidx.compose.foundation.BorderStroke(1.dp, kc.Border)
                    ) {
                        Text("Close", fontWeight = FontWeight.Medium)
                    }
                } else {
                    Button(
                        onClick = {
                            onComplete?.invoke()
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = kc.Primary,
                            contentColor = kc.OnPrimary
                        )
                    ) {
                        Text("Done", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun StageItem(
    stageNumber: String,
    title: String,
    state: StageState,
    kc: KryptonColorPalette
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (state) {
            StageState.COMPLETED -> {
                Icon(
                    imageVector = Lucide.CircleCheck,
                    contentDescription = "Completed",
                    tint = kc.Success,
                    modifier = Modifier.size(16.dp)
                )
            }
            StageState.RUNNING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = kc.Primary
                )
            }
            StageState.SKIPPED -> {
                Icon(
                    imageVector = Lucide.CircleMinus,
                    contentDescription = "Skipped",
                    tint = kc.Muted,
                    modifier = Modifier.size(16.dp)
                )
            }
            StageState.FAILED -> {
                Icon(
                    imageVector = Lucide.CircleAlert,
                    contentDescription = "Failed",
                    tint = kc.Error,
                    modifier = Modifier.size(16.dp)
                )
            }
            StageState.PENDING -> {
                Icon(
                    imageVector = Lucide.Circle,
                    contentDescription = "Pending",
                    tint = kc.Muted.copy(alpha = 0.4f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = "$stageNumber. $title",
            fontSize = 12.sp,
            color = if (state == StageState.RUNNING || state == StageState.COMPLETED) kc.OnSurface else kc.Muted,
            fontWeight = if (state == StageState.RUNNING) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )

        val statusLabel = when (state) {
            StageState.COMPLETED -> "Done"
            StageState.RUNNING -> "Installing..."
            StageState.SKIPPED -> "Skipped"
            StageState.FAILED -> "Failed"
            StageState.PENDING -> "Pending"
        }
        val statusColor = when (state) {
            StageState.COMPLETED -> kc.Success
            StageState.RUNNING -> kc.Primary
            StageState.SKIPPED -> kc.Muted
            StageState.FAILED -> kc.Error
            StageState.PENDING -> kc.Muted.copy(alpha = 0.6f)
        }

        Text(
            text = statusLabel,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = statusColor
        )
    }
}
