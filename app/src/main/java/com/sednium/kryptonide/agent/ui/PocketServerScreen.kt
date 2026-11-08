package com.sednium.kryptonide.agent.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.sednium.kryptonide.agent.pocket.*
import com.sednium.kryptonide.ui.theme.KryptonColorPalette
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

    val catalogByCategory = remember(filterOnlyCompatibleRam, telemetry.availableRamBytes) {
        val base = if (filterOnlyCompatibleRam && telemetry.availableRamBytes > 0) {
            PocketModels.PREDEFINED_CATALOG.filter { it.minRamBytesRequired <= telemetry.availableRamBytes }
        } else {
            PocketModels.PREDEFINED_CATALOG
        }
        base.groupBy { it.category }
    }

    // Custom HuggingFace Model Downloader State
    var hfUrlInput by remember { mutableStateOf("") }
    var isHfInspecting by remember { mutableStateOf(false) }
    var inspectedHfModels by remember { mutableStateOf<List<HuggingFaceModelInfo>>(emptyList()) }
    var hfErrorMessage by remember { mutableStateOf<String?>(null) }

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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.Surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Lucide.ArrowLeft,
                            contentDescription = "Back to Settings",
                            tint = colors.OnBackground,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    Icon(
                        Lucide.Server,
                        contentDescription = null,
                        tint = colors.Primary,
                        modifier = Modifier.size(18.dp)
                    )

                    Spacer(Modifier.width(8.dp))

                    Text(
                        text = "Pocket Server",
                        color = colors.OnBackground,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(Modifier.width(6.dp))

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.SurfaceVar)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "Local Daemon",
                            color = colors.Muted,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    IconButton(
                        onClick = { refresh() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Lucide.RefreshCw,
                            contentDescription = "Refresh",
                            tint = colors.Muted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                HorizontalDivider(color = colors.Border, thickness = 0.5.dp)
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
                    colors = CardDefaults.cardColors(containerColor = colors.Surface),
                    border = BorderStroke(0.8.dp, colors.Border)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text(
                                    text = "Local Inference Daemon",
                                    color = colors.Muted,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(2.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
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
                            }

                            if (telemetry.status == PocketServerStatus.RUNNING) {
                                Button(
                                    onClick = { pocketManager.stopServer() },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.Error),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(Lucide.Square, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Stop", color = Color.White, fontSize = 12.sp, maxLines = 1, softWrap = false)
                                }
                            } else if (discoveredModels.isNotEmpty()) {
                                Button(
                                    onClick = { pocketManager.startServer(discoveredModels.first()) },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.Primary, contentColor = colors.OnPrimary),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(Lucide.Play, contentDescription = null, tint = colors.OnPrimary, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Start", color = colors.OnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
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

                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = colors.Border.copy(alpha = 0.5f), thickness = 0.5.dp)
                        Spacer(Modifier.height(12.dp))

                        // Telemetry Grid (Equal width & uniform 68dp height)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
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
                                value = telemetry.gpuVendor.take(8).ifBlank { "ARM Mali" },
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

            // ── Predefined Catalog Section Header ──────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recommended Model Catalog",
                        color = colors.OnBackground,
                        fontSize = 15.sp,
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

            // Render Catalog by ModelCategory Sections
            ModelCategory.values().forEach { category ->
                val modelsInSection = catalogByCategory[category] ?: emptyList()
                if (modelsInSection.isNotEmpty()) {
                    item(key = "section_${category.name}") {
                        Column(modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = category.title,
                                    color = colors.OnBackground,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(colors.SurfaceVar)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        "${modelsInSection.size} models",
                                        color = colors.Muted,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(
                                text = category.subtitle,
                                color = colors.Muted,
                                fontSize = 10.5.sp
                            )
                        }
                    }

                    items(modelsInSection, key = { it.id }) { spec ->
                        CatalogModelCard(
                            spec = spec,
                            colors = colors,
                            context = context,
                            telemetry = telemetry,
                            discoveredModels = discoveredModels,
                            downloadState = downloadStates[spec.id],
                            onWarningRequested = { selectedWarningModel = it },
                            onLaunchRequested = { pocketManager.startServerForSpec(it) },
                            onStopRequested = { pocketManager.stopServer() },
                            onRefresh = { refresh() }
                        )
                    }
                }
            }
            // ── Custom Hugging Face Model Downloader & Link Parser ──
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.Surface),
                    border = BorderStroke(0.8.dp, colors.Border)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.Primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Lucide.Globe, contentDescription = null, tint = colors.Primary, modifier = Modifier.size(18.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Custom Hugging Face Downloader",
                                    color = colors.OnBackground,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Parse HF repo or direct .gguf link, inspect size & OOM risk, and download",
                                    color = colors.Muted,
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = hfUrlInput,
                            onValueChange = {
                                hfUrlInput = it
                                hfErrorMessage = null
                            },
                            placeholder = {
                                Text(
                                    "Repo ID (e.g. Qwen/Qwen2.5-Coder-1.5B-GGUF) or direct .gguf link",
                                    color = colors.Muted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = colors.OnBackground),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.Primary,
                                unfocusedBorderColor = colors.Border,
                                focusedContainerColor = colors.Background,
                                unfocusedContainerColor = colors.Background
                            ),
                            trailingIcon = {
                                if (hfUrlInput.isNotBlank()) {
                                    IconButton(onClick = {
                                        hfUrlInput = ""
                                        inspectedHfModels = emptyList()
                                        hfErrorMessage = null
                                    }) {
                                        Icon(Lucide.X, contentDescription = "Clear", tint = colors.Muted, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        )

                        Spacer(Modifier.height(10.dp))

                        Button(
                            onClick = {
                                if (hfUrlInput.isBlank()) return@Button
                                scope.launch {
                                    isHfInspecting = true
                                    hfErrorMessage = null
                                    val result = HuggingFaceModelInspector.inspectUrl(context, hfUrlInput)
                                    result.onSuccess { models ->
                                        inspectedHfModels = models
                                        if (models.isEmpty()) {
                                            hfErrorMessage = "No .gguf model files detected at specified link."
                                        }
                                    }.onFailure { err ->
                                        hfErrorMessage = err.message ?: "Failed to inspect Hugging Face link."
                                    }
                                    isHfInspecting = false
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.Primary,
                                contentColor = colors.OnPrimary
                            ),
                            enabled = !isHfInspecting && hfUrlInput.isNotBlank()
                        ) {
                            if (isHfInspecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = colors.OnPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Inspecting Model & Checking OOM...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Icon(Lucide.Search, contentDescription = null, tint = colors.OnPrimary, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Inspect Link & Analyze OOM", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (!hfErrorMessage.isNullOrBlank()) {
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
                                Icon(Lucide.TriangleAlert, contentDescription = null, tint = colors.Error, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(hfErrorMessage ?: "", color = colors.Error, fontSize = 11.sp, lineHeight = 15.sp)
                            }
                        }

                        // Parsed models list
                        if (inspectedHfModels.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = colors.Border.copy(alpha = 0.5f), thickness = 0.5.dp)
                            Spacer(Modifier.height(10.dp))

                            Text(
                                text = "Parsed HuggingFace Models (${inspectedHfModels.size})",
                                color = colors.OnBackground,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(Modifier.height(8.dp))

                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                inspectedHfModels.forEach { modelInfo ->
                                    val hfSpec = remember(modelInfo.filename) {
                                        LocalModelSpec(
                                            id = modelInfo.filename.removeSuffix(".gguf").lowercase().replace(Regex("[^a-z0-9_.-]"), "-"),
                                            name = modelInfo.filename.removeSuffix(".gguf"),
                                            description = "Custom HuggingFace Model (${modelInfo.quantization})",
                                            format = ModelFormat.GGUF,
                                            parameterCountBillion = 0f,
                                            quantization = modelInfo.quantization,
                                            sizeBytesApprox = modelInfo.sizeBytes,
                                            minRamBytesRequired = modelInfo.estimatedRamBytes,
                                            recommendedThreads = 4,
                                            downloadUrl = modelInfo.downloadUrl,
                                            isSystemOne = false,
                                            category = ModelCategory.CODE_INTELLIGENCE
                                        )
                                    }

                                    val isInstalled = ModelDownloadManager.isModelInstalled(context, hfSpec) ||
                                            discoveredModels.any { it.name.contains(hfSpec.id, ignoreCase = true) || it.name.equals(modelInfo.filename, ignoreCase = true) }
                                    val dlState = downloadStates[hfSpec.id]
                                    val isDownloading = dlState?.status == DownloadStatus.DOWNLOADING
                                    val isPaused = dlState?.status == DownloadStatus.PAUSED

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(colors.Background)
                                            .border(0.5.dp, colors.Border, RoundedCornerShape(10.dp))
                                            .padding(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = modelInfo.filename,
                                                color = colors.OnBackground,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(colors.SurfaceVar)
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    modelInfo.quantization,
                                                    color = colors.Muted,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        Spacer(Modifier.height(4.dp))

                                        // OOM Risk Pill & Metrics Row
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            val oomColor = when (modelInfo.oomRisk) {
                                                OomRiskLevel.LOW -> Color(0xFF22C55E)
                                                OomRiskLevel.MODERATE -> Color(0xFFF59E0B)
                                                OomRiskLevel.HIGH -> colors.Error
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(oomColor.copy(alpha = 0.15f))
                                                    .border(0.5.dp, oomColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = when (modelInfo.oomRisk) {
                                                        OomRiskLevel.LOW -> "LOW OOM RISK"
                                                        OomRiskLevel.MODERATE -> "MODERATE OOM RISK"
                                                        OomRiskLevel.HIGH -> "HIGH OOM RISK"
                                                    },
                                                    color = oomColor,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }

                                            Text(
                                                text = "Size: ${formatModelSize(modelInfo.sizeBytes)} · RAM: ~${formatModelSize(modelInfo.estimatedRamBytes)}",
                                                color = colors.Muted,
                                                fontSize = 10.sp
                                            )
                                        }

                                        Spacer(Modifier.height(8.dp))

                                        // Download / Pause / Resume / Stop Controls Row
                                        if (isDownloading) {
                                            val percent = ((dlState?.progress ?: 0f) * 100).toInt()
                                            val curMb = (dlState?.downloadedBytes ?: 0L) / (1024 * 1024)
                                            val totMb = (dlState?.totalBytes ?: hfSpec.sizeBytesApprox) / (1024 * 1024)
                                            val speedStr = if ((dlState?.speedMbPerSec ?: 0.0) > 0) String.format(java.util.Locale.US, "%.1f MB/s", dlState?.speedMbPerSec) else ""

                                            Column(modifier = Modifier.fillMaxWidth()) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        "$percent% ($curMb/$totMb MB) ${if (speedStr.isNotBlank()) "· $speedStr" else ""}",
                                                        color = colors.Primary,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        Button(
                                                            onClick = { ModelDownloadManager.pauseDownload(hfSpec.id) },
                                                            colors = ButtonDefaults.buttonColors(containerColor = colors.SurfaceVar, contentColor = colors.OnBackground),
                                                            shape = RoundedCornerShape(6.dp),
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                            modifier = Modifier.height(28.dp)
                                                        ) {
                                                            Icon(Lucide.Pause, contentDescription = "Pause", tint = colors.OnBackground, modifier = Modifier.size(10.dp))
                                                            Spacer(Modifier.width(4.dp))
                                                            Text("Pause", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                        Button(
                                                            onClick = { ModelDownloadManager.stopDownload(hfSpec.id, context, hfSpec) },
                                                            colors = ButtonDefaults.buttonColors(containerColor = colors.Error.copy(alpha = 0.2f), contentColor = colors.Error),
                                                            shape = RoundedCornerShape(6.dp),
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                            modifier = Modifier.height(28.dp)
                                                        ) {
                                                            Icon(Lucide.Square, contentDescription = "Stop", tint = colors.Error, modifier = Modifier.size(10.dp))
                                                            Spacer(Modifier.width(4.dp))
                                                            Text("Stop", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                                Spacer(Modifier.height(4.dp))
                                                LinearProgressIndicator(
                                                    progress = { dlState?.progress ?: 0f },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(5.dp)
                                                        .clip(RoundedCornerShape(3.dp)),
                                                    color = colors.Primary,
                                                    trackColor = colors.SurfaceVar
                                                )
                                            }
                                        } else if (isPaused) {
                                            val curMb = (dlState?.downloadedBytes ?: 0L) / (1024 * 1024)
                                            val totMb = (dlState?.totalBytes ?: hfSpec.sizeBytesApprox) / (1024 * 1024)
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    "Paused at $curMb/$totMb MB",
                                                    color = Color(0xFFF59E0B),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Button(
                                                        onClick = { ModelDownloadManager.resumeDownload(context, hfSpec) { refresh() } },
                                                        colors = ButtonDefaults.buttonColors(containerColor = colors.Primary, contentColor = colors.OnPrimary),
                                                        shape = RoundedCornerShape(6.dp),
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                        modifier = Modifier.height(28.dp)
                                                    ) {
                                                        Icon(Lucide.Play, contentDescription = "Resume", tint = colors.OnPrimary, modifier = Modifier.size(10.dp))
                                                        Spacer(Modifier.width(4.dp))
                                                        Text("Resume", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                    Button(
                                                        onClick = { ModelDownloadManager.stopDownload(hfSpec.id, context, hfSpec) },
                                                        colors = ButtonDefaults.buttonColors(containerColor = colors.Error.copy(alpha = 0.2f), contentColor = colors.Error),
                                                        shape = RoundedCornerShape(6.dp),
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                        modifier = Modifier.height(28.dp)
                                                    ) {
                                                        Icon(Lucide.Square, contentDescription = "Stop", tint = colors.Error, modifier = Modifier.size(10.dp))
                                                        Spacer(Modifier.width(4.dp))
                                                        Text("Stop", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        } else if (isInstalled) {
                                            val isRunning = telemetry.status == PocketServerStatus.RUNNING &&
                                                    (telemetry.activeModel?.name?.contains(hfSpec.id, ignoreCase = true) == true ||
                                                     telemetry.activeModel?.name?.equals(modelInfo.filename, ignoreCase = true) == true)
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Icon(Lucide.CircleCheck, contentDescription = null, tint = colors.Primary, modifier = Modifier.size(14.dp))
                                                    Text("Installed & Ready", color = colors.Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }

                                                Button(
                                                    onClick = {
                                                        if (isRunning) {
                                                            pocketManager.stopServer()
                                                        } else {
                                                            val target = discoveredModels.firstOrNull { it.name.contains(hfSpec.id, ignoreCase = true) || it.name.equals(modelInfo.filename, ignoreCase = true) }
                                                            if (target != null) {
                                                                pocketManager.startServer(target)
                                                            } else {
                                                                pocketManager.startServerForSpec(hfSpec)
                                                            }
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = if (isRunning) colors.Error else colors.Primary,
                                                        contentColor = if (isRunning) Color.White else colors.OnPrimary
                                                    ),
                                                    shape = RoundedCornerShape(6.dp),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                    modifier = Modifier.height(28.dp)
                                                ) {
                                                    Text(if (isRunning) "Stop" else "Launch", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        } else {
                                            val isFailed = dlState?.status == DownloadStatus.FAILED
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (isFailed) {
                                                    Text(dlState?.errorMessage ?: "Failed", color = colors.Error, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                                } else {
                                                    Spacer(Modifier.width(1.dp))
                                                }

                                                Button(
                                                    onClick = {
                                                        if (modelInfo.oomRisk == OomRiskLevel.HIGH) {
                                                            selectedWarningModel = hfSpec
                                                        } else {
                                                            ModelDownloadManager.startDownload(context, hfSpec) { refresh() }
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = if (isFailed) colors.Error.copy(alpha = 0.2f) else colors.Primary,
                                                        contentColor = if (isFailed) colors.Error else colors.OnPrimary
                                                    ),
                                                    shape = RoundedCornerShape(6.dp),
                                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                                    modifier = Modifier.height(30.dp)
                                                ) {
                                                    Icon(if (isFailed) Lucide.RefreshCw else Lucide.Download, contentDescription = null, modifier = Modifier.size(12.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(if (isFailed) "Retry" else "Download (${formatModelSize(modelInfo.sizeBytes)})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Discovered Local Models ────────────────────────
            item {
                Text(
                    text = "Discovered Local Models (${discoveredModels.size})",
                    color = colors.OnBackground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
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
                            Text("Download a model below (MiniCPM 1B is default) or place .gguf files in /sdcard/Kryptonide/models/", color = colors.Muted, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
                                text = "${model.detectedQuantization} · ${formatModelSize(model.sizeBytes)}",
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
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRunning) colors.Error else colors.Primary,
                                contentColor = if (isRunning) Color.White else colors.OnPrimary
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                if (isRunning) "Stop" else "Launch",
                                color = if (isRunning) Color.White else colors.OnPrimary,
                                fontSize = 12.sp,
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
private fun CatalogModelCard(
    spec: LocalModelSpec,
    colors: KryptonColorPalette,
    context: Context,
    telemetry: PocketServerTelemetry,
    discoveredModels: List<DiscoveredModel>,
    downloadState: ModelDownloadProgress?,
    onWarningRequested: (LocalModelSpec) -> Unit,
    onLaunchRequested: (LocalModelSpec) -> Unit,
    onStopRequested: () -> Unit,
    onRefresh: () -> Unit
) {
    val isInstalled = ModelDownloadManager.isModelInstalled(context, spec) ||
            discoveredModels.any { it.matchedCatalogSpec?.id == spec.id || it.name.lowercase().contains(spec.id) }
    val isDownloading = downloadState?.status == DownloadStatus.DOWNLOADING
    val isPaused = downloadState?.status == DownloadStatus.PAUSED

    val isThisRunning = telemetry.status == PocketServerStatus.RUNNING &&
            (telemetry.activeModel?.matchedCatalogSpec?.id == spec.id ||
             telemetry.activeModel?.name?.contains(spec.id, ignoreCase = true) == true)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.Surface),
        border = BorderStroke(0.8.dp, if (isThisRunning) colors.Primary else colors.Border)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = spec.name,
                    color = colors.OnBackground,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (spec.id == PocketModels.DEFAULT_MODEL_ID) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.Primary)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "Default Brain",
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
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = spec.description,
                color = colors.Muted,
                fontSize = 11.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(6.dp))
            val isRamInsufficient = telemetry.availableRamBytes > 0 && telemetry.availableRamBytes < spec.minRamBytesRequired
            if (isRamInsufficient) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Lucide.TriangleAlert, contentDescription = null, tint = colors.Error, modifier = Modifier.size(11.dp))
                    Text(
                        "Needs ${spec.minRamBytesRequired / (1024 * 1024)}MB RAM (${telemetry.availableRamBytes / (1024 * 1024)}MB Free) · OOM Risk",
                        color = colors.Error,
                        fontSize = 10.sp
                    )
                }
            } else {
                Text(
                    "Min RAM: ${spec.minRamBytesRequired / (1024 * 1024)}MB · Size: ~${formatModelSize(spec.sizeBytesApprox)}",
                    color = colors.Primary,
                    fontSize = 10.sp
                )
            }

            Spacer(Modifier.height(10.dp))

            // Dedicated Action Controls Section
            if (isDownloading) {
                val percent = ((downloadState?.progress ?: 0f) * 100).toInt()
                val curMb = (downloadState?.downloadedBytes ?: 0L) / (1024 * 1024)
                val totMb = (downloadState?.totalBytes ?: spec.sizeBytesApprox) / (1024 * 1024)
                val speedStr = if ((downloadState?.speedMbPerSec ?: 0.0) > 0) String.format(java.util.Locale.US, "%.1f MB/s", downloadState?.speedMbPerSec) else ""

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "$percent% ($curMb/$totMb MB) ${if (speedStr.isNotBlank()) "· $speedStr" else ""}",
                            color = colors.Primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { ModelDownloadManager.pauseDownload(spec.id) },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.SurfaceVar, contentColor = colors.OnBackground),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Icon(Lucide.Pause, contentDescription = "Pause", tint = colors.OnBackground, modifier = Modifier.size(10.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Pause", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { ModelDownloadManager.stopDownload(spec.id, context, spec) },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.Error.copy(alpha = 0.2f), contentColor = colors.Error),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Icon(Lucide.Square, contentDescription = "Stop", tint = colors.Error, modifier = Modifier.size(10.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Stop", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { downloadState?.progress ?: 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = colors.Primary,
                        trackColor = colors.SurfaceVar
                    )
                }
            } else if (isPaused) {
                val curMb = (downloadState?.downloadedBytes ?: 0L) / (1024 * 1024)
                val totMb = (downloadState?.totalBytes ?: spec.sizeBytesApprox) / (1024 * 1024)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Paused at $curMb/$totMb MB",
                        color = Color(0xFFF59E0B),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { ModelDownloadManager.resumeDownload(context, spec) { onRefresh() } },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.Primary, contentColor = colors.OnPrimary),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(Lucide.Play, contentDescription = "Resume", tint = colors.OnPrimary, modifier = Modifier.size(10.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Resume", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { ModelDownloadManager.stopDownload(spec.id, context, spec) },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.Error.copy(alpha = 0.2f), contentColor = colors.Error),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(Lucide.Square, contentDescription = "Stop", tint = colors.Error, modifier = Modifier.size(10.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Stop", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (isInstalled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.SurfaceVar)
                            .border(0.5.dp, colors.Border, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Icon(Lucide.CircleCheck, contentDescription = null, tint = colors.Primary, modifier = Modifier.size(12.dp))
                        Text("Ready in Storage", color = colors.OnBackground, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = {
                            if (isThisRunning) {
                                onStopRequested()
                            } else {
                                onLaunchRequested(spec)
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isThisRunning) colors.Error else colors.Primary,
                            contentColor = if (isThisRunning) Color.White else colors.OnPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            if (isThisRunning) "Stop Daemon" else "Launch Model",
                            color = if (isThisRunning) Color.White else colors.OnPrimary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                val isFailed = downloadState?.status == DownloadStatus.FAILED
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isFailed) {
                        Text(
                            text = downloadState.errorMessage ?: "Download failed",
                            color = colors.Error,
                            fontSize = 10.5.sp,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    Button(
                        onClick = {
                            if (isRamInsufficient) {
                                onWarningRequested(spec)
                            } else {
                                ModelDownloadManager.startDownload(context, spec) {
                                    onRefresh()
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isFailed) colors.Error.copy(alpha = 0.15f) else colors.Primary.copy(alpha = 0.12f),
                            contentColor = if (isFailed) colors.Error else colors.Primary
                        ),
                        border = BorderStroke(0.5.dp, if (isFailed) colors.Error.copy(alpha = 0.5f) else colors.Primary.copy(alpha = 0.4f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(if (isFailed) Lucide.RefreshCw else Lucide.Download, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isFailed) "Retry" else "Download (${formatModelSize(spec.sizeBytesApprox)})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.TelemetryPill(
    label: String,
    value: String,
    sub: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    colors: KryptonColorPalette
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .height(52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.SurfaceVar.copy(alpha = 0.5f))
            .border(0.5.dp, colors.Border.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = colors.Primary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            color = colors.OnBackground,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = sub,
            color = colors.Muted,
            fontSize = 9.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatModelSize(bytes: Long): String {
    val mb = bytes / (1024L * 1024L)
    return if (mb >= 1000L) {
        String.format(java.util.Locale.US, "%.1f GB", mb / 1000.0)
    } else {
        "$mb MB"
    }
}
