package com.sednium.kryptonide.ui.git

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.OpenInBrowser
import com.composables.icons.lucide.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sednium.kryptonide.ui.theme.LocalKryptonColors
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GithubActionsBuildSheet(
    owner: String,
    repo: String,
    branch: String = "main",
    onDismiss: () -> Unit,
    buildVm: GithubActionsBuildEngine = viewModel()
) {
    val state by buildVm.state.collectAsStateWithLifecycle()
    val colors = LocalKryptonColors.current
    val context = LocalContext.current
    var buildType by remember { mutableStateOf("debug") }

    state.pendingCleanup?.let { cleanup ->
        AlertDialog(
            onDismissRequest = { buildVm.dismissCleanup() },
            title = { Text("Delete previous build?", color = colors.OnBackground, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This build succeeded. The previous release (\"${cleanup.tag}\") is still on GitHub — delete it to keep the repo's Releases tab tidy, or keep it around.",
                    color = colors.Muted, fontSize = 13.sp, lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { buildVm.confirmCleanup() }) { Text("Delete", color = colors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { buildVm.dismissCleanup() }) { Text("Keep it", color = colors.Muted) }
            }
        )
    }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.CloudUpload, null, tint = colors.OnBackground, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Build via GitHub Actions", color = colors.OnBackground, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("$owner/$repo · $branch", color = colors.Muted, fontSize = 11.sp)
            }
        }

        if (state.phase == CloudBuildPhase.IDLE) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("debug", "release").forEach { type ->
                    FilterChip(
                        selected = buildType == type,
                        onClick = { buildType = type },
                        label = { Text(type.replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
            if (buildType == "release") {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Release APKs need a signingConfig in build.gradle to install — an unsigned one will build fine but won't install as-is.",
                    color = colors.Warning, fontSize = 10.sp, lineHeight = 14.sp
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        if (state.phase != CloudBuildPhase.IDLE) {
            PhaseRow(state.phase, colors)
            Spacer(Modifier.height(10.dp))
        }

        if (state.jobs.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.SurfaceVar, RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                state.jobs.forEach { job ->
                    job.steps.forEach { step ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            StepDot(step.status, step.conclusion, colors)
                            Spacer(Modifier.width(8.dp))
                            Text(step.name, color = colors.OnBackground, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .background(Color(0xFF0A0A0F), RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            items(state.log) { line ->
                Text(line, color = Color(0xFFCCCCCC), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }

        state.error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = colors.Error, fontSize = 12.sp)
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            state.runUrl?.let { url ->
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }, modifier = Modifier.weight(1f)) {
                    Icon(Lucide.ExternalLink, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("View on GitHub")
                }
            }

            when (state.phase) {
                CloudBuildPhase.SUCCESS -> {
                    Button(
                        onClick = { state.apkPath?.let { installApkFile(context, it) } },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.Success)
                    ) {
                        Icon(Lucide.Smartphone, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Install APK")
                    }
                }
                CloudBuildPhase.FAILED, CloudBuildPhase.CANCELLED -> {
                    Button(
                        onClick = { buildVm.start(owner, repo, branch, buildType) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Retry") }
                }
                CloudBuildPhase.IDLE -> {
                    Button(
                        onClick = { buildVm.start(owner, repo, branch, buildType) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.Primary)
                    ) { Text("Start ${buildType.replaceFirstChar { it.uppercase() }} Build") }
                }
                else -> {
                    OutlinedButton(
                        onClick = { buildVm.cancel() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.Error)
                    ) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun PhaseRow(phase: CloudBuildPhase, colors: com.sednium.kryptonide.ui.theme.KryptonColorPalette) {
    val label = when (phase) {
        CloudBuildPhase.IDLE               -> "Starting…"
        CloudBuildPhase.ENSURING_WORKFLOW   -> "Setting up workflow…"
        CloudBuildPhase.TRIGGERING          -> "Triggering build…"
        CloudBuildPhase.WAITING_FOR_RUN     -> "Waiting for run to start…"
        CloudBuildPhase.QUEUED              -> "Queued on GitHub…"
        CloudBuildPhase.IN_PROGRESS         -> "Building…"
        CloudBuildPhase.DOWNLOADING         -> "Downloading APK…"
        CloudBuildPhase.SUCCESS             -> "Build successful"
        CloudBuildPhase.FAILED              -> "Build failed"
        CloudBuildPhase.CANCELLED           -> "Cancelled"
    }
    val color = when (phase) {
        CloudBuildPhase.SUCCESS -> colors.Success
        CloudBuildPhase.FAILED  -> colors.Error
        else -> colors.OnBackground
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (phase !in setOf(CloudBuildPhase.SUCCESS, CloudBuildPhase.FAILED, CloudBuildPhase.CANCELLED)) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.OnBackground)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StepDot(status: String, conclusion: String?, colors: com.sednium.kryptonide.ui.theme.KryptonColorPalette) {
    val color = when {
        conclusion == "success"      -> colors.Success
        conclusion == "failure"      -> colors.Error
        status == "in_progress"      -> colors.OnBackground
        else                          -> colors.Muted
    }
    Box(modifier = Modifier.size(7.dp).background(color, CircleShape))
}

private fun installApkFile(context: android.content.Context, apkPath: String) {
    val apkFile = File(apkPath)
    if (!apkFile.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}
