package com.sednium.kryptonide.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.BorderStroke
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Zap
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Shield
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.TriangleAlert
import com.sednium.kryptonide.agent.hardware.HardwareTopology
import com.sednium.kryptonide.agent.pocket.*
import com.sednium.kryptonide.ui.theme.LocalKryptonColors
import kotlinx.coroutines.launch

@Composable
fun PocketServerScreen(
    pocketManager: PocketServerManager,
    onBack: () -> Unit
) {
    val colors = LocalKryptonColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val telemetry by pocketManager.telemetry.collectAsState()
    val downloadStates by ModelDownloadManager.downloadStates.collectAsState()

    var discoveredModels by remember { mutableStateOf<List<DiscoveredModel>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var selectedWarningModel by remember { mutableStateOf<LocalModelSpec?>(null) }
    var hwAccelerationEnabled by remember { mutableStateOf(true) }
    var filterOnlyCompatibleRam by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            isScanning = true
            pocketManager.refreshHardwareStats()
            discoveredModels = pocketManager.listDiscoveredModels()
            isScanning = false
        }
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.Surface)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(Lucide.ArrowLeft, contentDescription = "Back", tint = colors.OnBackground)
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Pocket Server",
                        color = colors.OnBackground,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Local on-device inference daemon",
                        color = colors.Muted,
                        fontSize = 11.sp
                    )
                }
                IconButton(onClick = { refresh() }, modifier = Modifier.size(36.dp)) {
                    Icon(Lucide.RefreshCw, contentDescription = "Refresh", tint = colors.Muted)
                }
            }
        },
        containerColor = colors.Background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // ── Daemon Status & Telemetry Header ────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.Surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when (telemetry.status) {
                                                PocketServerStatus.RUNNING -> Color(0xFF22C55E)
                                                PocketServerStatus.STARTING -> Color(0xFFF59E0B)
                                                PocketServerStatus.ERROR -> Color(0xFFEF4444)
                                                PocketServerStatus.STOPPED -> colors.Muted
                                            }
                                        )
                                )
                                Text(
                                    text = if (telemetry.status == PocketServerStatus.RUNNING && telemetry.activeModel != null) {
                                        "Status: RUNNING (${telemetry.activeModel?.name?.take(18)})"
                                    } else {
                                        "Status: ${telemetry.status.name}"
                                    },
                                    color = colors.OnBackground,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            if (telemetry.status == PocketServerStatus.RUNNING) {
                                Button(
                                    onClick = { pocketManager.stopServer() },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.Error),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    modifier = Modifier.defaultMinSize(minWidth = 78.dp)
                                ) {
                                    Icon(Lucide.Square, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Stop", color = Color.White, fontSize = 12.sp, maxLines = 1, softWrap = false)
                                }
                            } else if (discoveredModels.isNotEmpty()) {
                                Button(
                                    onClick = { pocketManager.startServer(discoveredModels.first()) },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.Primary),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    modifier = Modifier.defaultMinSize(minWidth = 78.dp)
                                ) {
                                    Icon(Lucide.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Start", color = Color.White, fontSize = 12.sp, maxLines = 1, softWrap = false)
                                }
                            }
                        }

                        if (!telemetry.errorMessage.isNullOrBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.Error.copy(alpha = 0.12f))
                                    .border(0.5.dp, colors.Error.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Lucide.TriangleAlert, contentDescription = null, tint = colors.Error, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(telemetry.errorMessage ?: "", color = colors.Error, fontSize = 11.sp, lineHeight = 15.sp)
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = colors.Border, thickness = 0.5.dp)
                        Spacer(Modifier.height(14.dp))

                        // Telemetry Grid
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TelemetryPill(
                                label = "Big-Cores",
                                value = "${telemetry.bigCoreCount} Cores",
                                sub = if (telemetry.isPinned) "Pinned" else "Dynamic",
                                icon = Lucide.Cpu,
                                colors = colors
                            )
                            TelemetryPill(
                                label = "Free RAM",
                                value = "${telemetry.availableRamBytes / (1024 * 1024)} MB",
                                sub = "Total ${telemetry.totalRamBytes / (1024 * 1024)} MB",
                                icon = Lucide.HardDrive,
                                colors = colors
                            )
                            TelemetryPill(
                                label = "GPU Node",
                                value = telemetry.gpuVendor.take(8),
                                sub = "Vulkan / GL",
                                icon = Lucide.Zap,
                                colors = colors
                            )
                        }
                    }
                }
            }

            // ── Hardware Acceleration Switch ───────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.Surface)
                        .border(0.8.dp, colors.Border, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Hardware Core Pinning (JNI)", color = colors.OnBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Uses sched_setaffinity via liboorty_engine.so to bind inference threads strictly to Prime cores.", color = colors.Muted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = hwAccelerationEnabled,
                        onCheckedChange = { hwAccelerationEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.Background,
                            checkedTrackColor = colors.Primary,
                            checkedBorderColor = Color.Transparent,
                            uncheckedThumbColor = colors.Muted,
                            uncheckedTrackColor = colors.SurfaceVar,
                            uncheckedBorderColor = colors.Border
                        )
                    )
                }
            }

            // ── Discovered & Catalog Models ────────────────────
            item {
                Text(
                    text = "Discovered Local Models (${discoveredModels.size})",
                    color = colors.OnBackground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (discoveredModels.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.Surface)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Lucide.Server, contentDescription = null, tint = colors.Muted, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("No local models found in storage", color = colors.OnBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Download a model below (Needle 3 is only 36MB) or place .gguf/.cact files in /storage/emulated/0/Kryptonide/models/", color = colors.Muted, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            } else {
                items(discoveredModels, key = { it.file.absolutePath }) { model ->
                    val isRunning = telemetry.activeModel?.file?.absolutePath == model.file.absolutePath && telemetry.status == PocketServerStatus.RUNNING

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.Surface)
                            .border(0.8.dp, if (isRunning) colors.Primary else colors.Border, RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = model.name,
                                    color = colors.OnBackground,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (model.isSystemOneCandidate) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(colors.Primary.copy(alpha = 0.15f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("System 1", color = colors.Primary, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "${model.detectedQuantization} · ${model.sizeBytes / (1024 * 1024)} MB",
                                color = colors.Muted,
                                fontSize = 11.sp
                            )
                        }

                        Button(
                            onClick = {
                                if (isRunning) {
                                    pocketManager.stopServer()
                                } else {
                                    pocketManager.startServer(model)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) colors.Error else colors.Primary),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(if (isRunning) "Stop" else "Launch", fontSize = 12.sp)
                        }
                    }
                }
            }

            // ── Predefined Catalog Section ─────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recommended Model Catalog",
                        color = colors.OnBackground,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (filterOnlyCompatibleRam) colors.Primary.copy(alpha = 0.15f) else colors.Surface)
                            .border(0.5.dp, if (filterOnlyCompatibleRam) colors.Primary else colors.Border, RoundedCornerShape(8.dp))
                            .clickable { filterOnlyCompatibleRam = !filterOnlyCompatibleRam }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Lucide.Shield,
                            contentDescription = null,
                            tint = if (filterOnlyCompatibleRam) colors.Primary else colors.Muted,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "RAM Safe",
                            color = if (filterOnlyCompatibleRam) colors.Primary else colors.Muted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            val catalog = if (filterOnlyCompatibleRam && telemetry.availableRamBytes > 0) {
                PocketModels.PREDEFINED_CATALOG.filter { it.minRamBytesRequired <= telemetry.availableRamBytes }
            } else {
                PocketModels.PREDEFINED_CATALOG
            }

            items(catalog, key = { it.id }) { spec ->
                val isInstalled = ModelDownloadManager.isModelInstalled(context, spec) ||
                        discoveredModels.any { it.matchedCatalogSpec?.id == spec.id || it.name.lowercase().contains(spec.id) }
                val downloadState = downloadStates[spec.id]
                val isDownloading = downloadState?.status == DownloadStatus.DOWNLOADING

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.Surface)
                        .border(0.8.dp, colors.Border, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = spec.name,
                            color = colors.OnBackground,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (spec.id == PocketModels.DEFAULT_MODEL_ID) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(colors.Primary)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        "Unified Agent",
                                        color = colors.Background,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(colors.SurfaceVar)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    spec.quantization,
                                    color = colors.Muted,
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = spec.description,
                            color = colors.Muted,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        val isRamInsufficient = telemetry.availableRamBytes > 0 && telemetry.availableRamBytes < spec.minRamBytesRequired
                        Text(
                            if (isRamInsufficient) {
                                "⚠️ Needs ${spec.minRamBytesRequired / (1024 * 1024)}MB RAM (${telemetry.availableRamBytes / (1024 * 1024)}MB Free) · OOM Risk"
                            } else {
                                "Min RAM: ${spec.minRamBytesRequired / (1024 * 1024)}MB · Size: ~${spec.sizeBytesApprox / (1024 * 1024)}MB"
                            },
                            color = if (isRamInsufficient) colors.Error else colors.Primary,
                            fontSize = 10.sp
                        )

                        if (downloadState?.status == DownloadStatus.FAILED) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "⚠️ ${downloadState.errorMessage ?: "Download failed"}",
                                color = colors.Error,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    if (isDownloading) {
                        Column(horizontalAlignment = Alignment.End) {
                            val percent = ((downloadState?.progress ?: 0f) * 100).toInt()
                            val curMb = (downloadState?.downloadedBytes ?: 0L) / (1024 * 1024)
                            val totMb = (downloadState?.totalBytes ?: spec.sizeBytesApprox) / (1024 * 1024)
                            Text(
                                if (totMb > 0) "$percent% ($curMb/$totMb MB)" else "$percent%",
                                color = colors.Primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LinearProgressIndicator(
                                    progress = { downloadState?.progress ?: 0f },
                                    modifier = Modifier
                                        .width(90.dp)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = colors.Primary,
                                    trackColor = colors.SurfaceVar
                                )
                                Icon(
                                    Lucide.X,
                                    contentDescription = "Cancel",
                                    tint = colors.Muted,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { ModelDownloadManager.cancelDownload(spec.id) }
                                )
                            }
                        }
                    } else if (isInstalled) {
                        val isThisRunning = telemetry.status == PocketServerStatus.RUNNING &&
                                (telemetry.activeModel?.matchedCatalogSpec?.id == spec.id ||
                                 telemetry.activeModel?.name?.contains(spec.id, ignoreCase = true) == true ||
                                 (spec.id.contains("needle") && telemetry.activeModel?.name?.contains("needle", ignoreCase = true) == true))

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.Success.copy(alpha = 0.12f))
                                    .border(0.5.dp, colors.Success.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 6.dp, vertical = 5.dp)
                            ) {
                                Icon(Lucide.CircleCheck, contentDescription = null, tint = colors.Success, modifier = Modifier.size(12.dp))
                                Text("Ready", color = colors.Success, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }

                            Button(
                                onClick = {
                                    if (isThisRunning) {
                                        pocketManager.stopServer()
                                    } else {
                                        pocketManager.startServerForSpec(spec)
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isThisRunning) colors.Error else colors.Primary
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(if (isThisRunning) "Stop" else "Launch", fontSize = 12.sp)
                            }
                        }
                    } else {
                        val isFailed = downloadState?.status == DownloadStatus.FAILED
                        Button(
                            onClick = {
                                val isRamInsufficient = telemetry.availableRamBytes > 0 && telemetry.availableRamBytes < spec.minRamBytesRequired
                                if (isRamInsufficient) {
                                    selectedWarningModel = spec
                                } else {
                                    ModelDownloadManager.startDownload(context, spec) {
                                        refresh()
                                    }
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isFailed) colors.Error.copy(alpha = 0.15f) else colors.Primary.copy(alpha = 0.12f),
                                contentColor = if (isFailed) colors.Error else colors.Primary
                            ),
                            border = BorderStroke(0.5.dp, if (isFailed) colors.Error.copy(alpha = 0.5f) else colors.Primary.copy(alpha = 0.4f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(if (isFailed) Lucide.RefreshCw else Lucide.Download, contentDescription = null, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (isFailed) "Retry" else "${spec.sizeBytesApprox / (1024 * 1024)}MB",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    selectedWarningModel?.let { spec ->
        HardwareWarningDialog(
            modelSpec = spec,
            availableRamBytes = telemetry.availableRamBytes,
            isThermalThrottling = false,
            batteryTempCelsius = 36.0f,
            onDismiss = { selectedWarningModel = null },
            onProceedAnyway = {
                val target = selectedWarningModel
                selectedWarningModel = null
                if (target != null) {
                    ModelDownloadManager.startDownload(context, target) {
                        refresh()
                    }
                }
            }
        )
    }
}

@Composable
private fun TelemetryPill(
    label: String,
    value: String,
    sub: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    colors: com.sednium.kryptonide.ui.theme.KryptonColorPalette
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.SurfaceVar.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = colors.Primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.height(4.dp))
        Text(value, color = colors.OnBackground, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = colors.Muted, fontSize = 10.sp)
    }
}
