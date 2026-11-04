package com.sednium.kryptonide.ota

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.composables.icons.lucide.*
import com.sednium.kryptonide.ui.theme.LocalKryptonColors
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Clean, modern OTA Update dialog showing release notes, APK size,
 * live download progress bar, and 1-tap installation.
 */
@Composable
fun OtaUpdateDialog(
    release: OtaReleaseInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val colors = LocalKryptonColors.current
    val scope = rememberCoroutineScope()

    var downloadState by remember { mutableStateOf<OtaDownloadState>(OtaDownloadState.Idle) }
    val sizeMb = remember(release.apkSize) {
        if (release.apkSize > 0) String.format("%.1f MB", release.apkSize / (1024.0 * 1024.0)) else "Direct APK"
    }

    Dialog(onDismissRequest = {
        if (downloadState !is OtaDownloadState.Downloading) onDismiss()
    }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.Surface)
                .border(1.dp, colors.Border, RoundedCornerShape(20.dp))
                .padding(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colors.Primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Lucide.Sparkles, null, tint = colors.Primary, modifier = Modifier.size(18.dp))
                    }
                    Column {
                        Text(
                            "Update Available",
                            color = colors.OnSurface,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "v${OtaUpdateManager.getCurrentAppVersion()} → ${release.tagName}",
                            color = colors.Primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (downloadState !is OtaDownloadState.Downloading) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Lucide.X, "Close", tint = colors.Muted, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Release Notes Box
            Text(
                "WHAT'S FIXED & NEW",
                color = colors.Muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )

            Spacer(Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.Background)
                    .border(0.5.dp, colors.Border, RoundedCornerShape(12.dp))
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = release.body.ifBlank { "Performance improvements, bug fixes, and general reliability enhancements." },
                    color = colors.OnBackground,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }

            Spacer(Modifier.height(14.dp))

            // Download Status / Progress
            when (val state = downloadState) {
                is OtaDownloadState.Idle -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Package Size: $sizeMb",
                            color = colors.Muted,
                            fontSize = 12.sp
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    OtaUpdateManager.downloadApk(context, release).collectLatest {
                                        downloadState = it
                                        if (it is OtaDownloadState.ReadyToInstall) {
                                            OtaUpdateManager.installApk(context, it.apkFile, release)
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.Primary,
                                contentColor = colors.OnPrimary
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                            modifier = Modifier.height(38.dp)
                        ) {
                            Icon(Lucide.Download, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Update & Install", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                is OtaDownloadState.Downloading -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Downloading ${state.percent}%",
                                color = colors.OnSurface,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                String.format("%.2f MB/s", state.speedMbPerSec),
                                color = colors.Primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        LinearProgressIndicator(
                            progress = { state.percent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = colors.Primary,
                            trackColor = colors.SurfaceVar
                        )
                    }
                }
                is OtaDownloadState.ReadyToInstall -> {
                    Button(
                        onClick = {
                            OtaUpdateManager.installApk(context, state.apkFile, release)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.Success,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        Icon(Lucide.Check, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Ready to Install • Tap to Launch", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                is OtaDownloadState.Error -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Download error: ${state.message}",
                            color = colors.Error,
                            fontSize = 12.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f).height(36.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Cancel", fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        OtaUpdateManager.downloadApk(context, release).collectLatest {
                                            downloadState = it
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f).height(36.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.Primary)
                            ) {
                                Text("Retry", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dialog shown after the app launches following a version upgrade.
 * Highlights the release's fixes and updates.
 */
@Composable
fun WelcomeBackDialog(
    versionName: String,
    releaseNotes: String,
    onDismiss: () -> Unit
) {
    val colors = LocalKryptonColors.current

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(colors.Surface)
                .border(1.dp, colors.Border, RoundedCornerShape(22.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(colors.Primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Lucide.Sparkles, null, tint = colors.Primary, modifier = Modifier.size(26.dp))
            }

            Spacer(Modifier.height(14.dp))

            Text(
                "Welcome to Kryptonide v$versionName!",
                color = colors.OnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                "Here is what was fixed and upgraded in this release:",
                color = colors.Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.Background)
                    .border(0.5.dp, colors.Border, RoundedCornerShape(12.dp))
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = releaseNotes.ifBlank { "Various stability and performance improvements across editor and on-device AI." },
                    color = colors.OnBackground,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }

            Spacer(Modifier.height(18.dp))

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.Primary,
                    contentColor = colors.OnPrimary
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Text("Awesome, Let's Code! 🚀", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * BottomSheet or Dialog for raising a new GitHub Issue.
 * Supports token-based direct POST or 1-tap browser pre-filled submission.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaiseIssueSheet(
    githubToken: String?,
    onDismiss: () -> Unit,
    onIssueCreated: (IssueItem) -> Unit
) {
    val context = LocalContext.current
    val colors = LocalKryptonColors.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var category by remember { mutableStateOf("🐛 Bug Report") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var attachDiagnostics by remember { mutableStateOf(true) }
    var isSubmitting by remember { mutableStateOf(false) }

    val categories = listOf("🐛 Bug Report", "✨ Feature Request", "⚡ AI / Model Issue")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.Surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 30.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Lucide.CircleDot, null, tint = colors.Primary, modifier = Modifier.size(18.dp))
                    Text(
                        "Raise GitHub Issue",
                        color = colors.OnSurface,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Lucide.X, "Close", tint = colors.Muted, modifier = Modifier.size(16.dp))
                }
            }

            // Category Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { cat ->
                    val isSelected = category == cat
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) colors.Primary.copy(alpha = 0.15f) else colors.SurfaceVar)
                            .border(1.dp, if (isSelected) colors.Primary else colors.Border, RoundedCornerShape(8.dp))
                            .clickable { category = cat }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            cat,
                            color = if (isSelected) colors.Primary else colors.OnSurface,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Title
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Issue Title", color = colors.Muted) },
                placeholder = { Text("Short description of what happened...", color = colors.Muted) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = colors.OnSurface,
                    focusedBorderColor = colors.Primary,
                    unfocusedBorderColor = colors.Border
                )
            )

            // Description
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description & Steps to Reproduce", color = colors.Muted) },
                placeholder = { Text("Describe the expected vs actual behavior...", color = colors.Muted) },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = colors.OnSurface,
                    focusedBorderColor = colors.Primary,
                    unfocusedBorderColor = colors.Border
                )
            )

            // Attach Diagnostics Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { attachDiagnostics = !attachDiagnostics }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = attachDiagnostics,
                    onCheckedChange = { attachDiagnostics = it },
                    colors = CheckboxDefaults.colors(checkedColor = colors.Primary)
                )
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        "Attach Device Diagnostics",
                        color = colors.OnSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Includes Kryptonide v4.0, Android OS, Device Model, and RAM",
                        color = colors.Muted,
                        fontSize = 11.sp
                    )
                }
            }

            // Submit Button
            val canSubmit = title.isNotBlank() && description.isNotBlank() && !isSubmitting
            val hasToken = !githubToken.isNullOrBlank()

            Button(
                onClick = {
                    val label = when (category) {
                        "🐛 Bug Report" -> "bug"
                        "✨ Feature Request" -> "enhancement"
                        else -> "ai"
                    }
                    val fullBody = buildString {
                        append(description.trim())
                        if (attachDiagnostics) {
                            append("\n")
                            append(GitHubIssuesManager.getDeviceDiagnostics(context))
                        }
                    }

                    if (hasToken) {
                        isSubmitting = true
                        scope.launch {
                            val result = GitHubIssuesManager.createIssue(
                                title = title.trim(),
                                body = fullBody,
                                labels = listOf(label),
                                token = githubToken!!
                            )
                            isSubmitting = false
                            result.onSuccess { item ->
                                Toast.makeText(context, "Issue #${item.number} submitted successfully!", Toast.LENGTH_SHORT).show()
                                onIssueCreated(item)
                                onDismiss()
                            }.onFailure { err ->
                                Toast.makeText(context, "Failed: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        // Launch GitHub web issue form in browser
                        val browserUrl = GitHubIssuesManager.buildBrowserNewIssueUrl(
                            title = title.trim(),
                            body = fullBody,
                            labels = listOf(label)
                        )
                        uriHandler.openUri(browserUrl)
                        onDismiss()
                    }
                },
                enabled = canSubmit,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.Primary,
                    contentColor = colors.OnPrimary
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = colors.OnPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Submitting...", fontSize = 13.sp)
                } else {
                    Icon(
                        if (hasToken) Lucide.CircleDot else Lucide.ExternalLink,
                        null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (hasToken) "Submit Issue directly to GitHub" else "Open in GitHub to Submit",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
