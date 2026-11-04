package com.sednium.kryptonide.ui.ai

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.sednium.kryptonide.agent.pocket.*
import com.sednium.kryptonide.ui.theme.KryptonColorPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportHuggingFaceDialog(
    colors: KryptonColorPalette,
    onDismiss: () -> Unit,
    onModelImported: (LocalModelSpec) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var urlInput by remember { mutableStateOf("") }
    var isInspecting by remember { mutableStateOf(false) }
    var inspectedModels by remember { mutableStateOf<List<HuggingFaceModelInfo>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val downloadStates by ModelDownloadManager.downloadStates.collectAsState()
    val freeRamMb = remember { HuggingFaceModelInspector.getFreeRamBytes(context) / (1024 * 1024) }
    val totalRamMb = remember { HuggingFaceModelInspector.getTotalRamBytes(context) / (1024 * 1024) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.Surface,
        shape = RoundedCornerShape(20.dp),
        title = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Import HuggingFace GGUF",
                        color = colors.OnBackground,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Lucide.X, null, tint = colors.Muted, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Device RAM:", color = colors.Muted, fontSize = 11.sp)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.SurfaceVar)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "${freeRamMb}MB free / ${totalRamMb}MB total",
                            color = colors.Primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = {
                        urlInput = it
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "Paste direct .gguf link or repo (e.g. Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF)",
                            fontSize = 12.sp,
                            color = colors.Muted
                        )
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.OnBackground,
                        unfocusedTextColor = colors.OnBackground,
                        focusedBorderColor = colors.Primary,
                        unfocusedBorderColor = colors.Border,
                        focusedContainerColor = colors.Background,
                        unfocusedContainerColor = colors.Background
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                Button(
                    onClick = {
                        if (urlInput.isBlank()) return@Button
                        isInspecting = true
                        errorMessage = null
                        scope.launch {
                            val res = HuggingFaceModelInspector.inspectUrl(context, urlInput)
                            isInspecting = false
                            res.onSuccess { models ->
                                inspectedModels = models
                                if (models.isEmpty()) {
                                    errorMessage = "No .gguf models found at this URL."
                                }
                            }.onFailure { err ->
                                errorMessage = err.message ?: "Failed to inspect HuggingFace URL"
                            }
                        }
                    },
                    enabled = urlInput.isNotBlank() && !isInspecting,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.Primary),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isInspecting) {
                        CircularProgressIndicator(color = colors.OnPrimary, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Inspecting HuggingFace...", fontSize = 13.sp)
                    } else {
                        Icon(Lucide.Search, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Inspect Model / Scan Repo", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (errorMessage != null) {
                    Text(
                        errorMessage!!,
                        color = colors.Error,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                if (inspectedModels.isNotEmpty()) {
                    Text(
                        "DISCOVERED GGUF FILES (${inspectedModels.size})",
                        color = colors.Muted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )

                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(inspectedModels) { model ->
                            val cleanId = model.filename.substringBeforeLast('.')
                            val downloadProgress = downloadStates[cleanId]
                            val isDownloaded = remember(cleanId) {
                                val targetFile = java.io.File(ModelDownloadManager.getModelsDirectory(context), model.filename)
                                targetFile.exists() && targetFile.length() > 0
                            }

                            val sizeMb = model.sizeBytes / (1024 * 1024)
                            val ramMb = model.estimatedRamBytes / (1024 * 1024)

                            val badgeColor = when (model.oomRisk) {
                                OomRiskLevel.LOW -> Color(0xFF10B981) // Green
                                OomRiskLevel.MODERATE -> Color(0xFFF59E0B) // Amber
                                OomRiskLevel.HIGH -> Color(0xFFEF4444) // Red
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.Background)
                                    .border(0.5.dp, colors.Border, RoundedCornerShape(10.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        model.filename,
                                        color = colors.OnBackground,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(colors.SurfaceVar)
                                            .padding(horizontal = 5.dp, vertical = 2.dp)
                                    ) {
                                        Text(model.quantization, color = colors.Primary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "Size: ${sizeMb}MB • RAM: ~${ramMb}MB",
                                        color = colors.Muted,
                                        fontSize = 11.sp
                                    )

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(badgeColor.copy(alpha = 0.15f))
                                            .border(0.5.dp, badgeColor, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            when (model.oomRisk) {
                                                OomRiskLevel.LOW -> "🟢 Safe"
                                                OomRiskLevel.MODERATE -> "🟡 Moderate"
                                                OomRiskLevel.HIGH -> "🔴 High OOM Risk"
                                            },
                                            color = badgeColor,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                if (downloadProgress != null && downloadProgress.status == DownloadStatus.DOWNLOADING) {
                                    val percent = (downloadProgress.progress * 100).toInt()
                                    val dlMb = downloadProgress.downloadedBytes / (1024 * 1024)
                                    val totalMb = downloadProgress.totalBytes / (1024 * 1024)
                                    LinearProgressIndicator(
                                        progress = { downloadProgress.progress },
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                        color = colors.Primary,
                                        trackColor = colors.SurfaceVar
                                    )
                                    Text(
                                        "Downloading: $percent% ($dlMb / $totalMb MB)",
                                        color = colors.Muted,
                                        fontSize = 10.sp
                                    )
                                } else {
                                    Button(
                                        onClick = {
                                            val spec = LocalModelSpec(
                                                id = cleanId,
                                                name = cleanId,
                                                description = "Imported HuggingFace model (${model.quantization})",
                                                format = if (model.filename.endsWith(".cact", ignoreCase = true)) ModelFormat.CACT else ModelFormat.GGUF,
                                                parameterCountBillion = 1.5f,
                                                quantization = model.quantization,
                                                sizeBytesApprox = model.sizeBytes,
                                                minRamBytesRequired = model.estimatedRamBytes,
                                                recommendedThreads = 4,
                                                downloadUrl = model.downloadUrl
                                            )
                                            if (isDownloaded) {
                                                onModelImported(spec)
                                                onDismiss()
                                            } else {
                                                Toast.makeText(context, "Starting download for ${model.filename}", Toast.LENGTH_SHORT).show()
                                                ModelDownloadManager.startDownload(context, spec) {
                                                    onModelImported(spec)
                                                    onDismiss()
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isDownloaded) colors.Success else colors.Primary
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().height(34.dp)
                                    ) {
                                        Icon(if (isDownloaded) Lucide.Check else Lucide.Download, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (isDownloaded) "Installed (Select Model)" else "Download & Install", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = colors.Muted)
            }
        }
    )
}
