package com.sednium.kryptonide.ui.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.composables.icons.lucide.*
import com.sednium.kryptonide.agent.pocket.DownloadStatus
import com.sednium.kryptonide.agent.pocket.LocalGgufServerManager
import com.sednium.kryptonide.agent.pocket.ModelDownloadManager
import com.sednium.kryptonide.agent.pocket.PocketModels
import com.sednium.kryptonide.ui.theme.KryptonColorPalette
import com.sednium.kryptonide.ui.theme.LocalKryptonColors
import com.sednium.kryptonide.utils.StorageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

enum class OnboardingStep {
    STORAGE_PERMISSION,
    NEEDLE_AI_SETUP
}

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalKryptonColors.current

    val initialNeedsStorage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !StorageUtils.isManagerSafe()
    var currentStep by remember {
        mutableStateOf(if (initialNeedsStorage) OnboardingStep.STORAGE_PERMISSION else OnboardingStep.NEEDLE_AI_SETUP)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (StorageUtils.isManagerSafe() && currentStep == OnboardingStep.STORAGE_PERMISSION) {
                    currentStep = OnboardingStep.NEEDLE_AI_SETUP
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (StorageUtils.isManagerSafe() && currentStep == OnboardingStep.STORAGE_PERMISSION) {
            currentStep = OnboardingStep.NEEDLE_AI_SETUP
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.Background)
    ) {
        AnimatedContent(
            targetState = currentStep,
            label = "OnboardingStepTransition"
        ) { step ->
            when (step) {
                OnboardingStep.STORAGE_PERMISSION -> {
                    StoragePermissionStep(
                        colors = colors,
                        onGranted = {
                            currentStep = OnboardingStep.NEEDLE_AI_SETUP
                        }
                    )
                }
                OnboardingStep.NEEDLE_AI_SETUP -> {
                    NeedleAiSetupStep(
                        colors = colors,
                        onCompleted = {
                            val prefs = context.getSharedPreferences("krypton_onboarding", Context.MODE_PRIVATE)
                            prefs.edit().putBoolean("onboarding_complete", true).apply()
                            onFinished()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StoragePermissionStep(
    colors: KryptonColorPalette,
    onGranted: () -> Unit
) {
    val context = LocalContext.current

    fun requestAccess() {
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
            )
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: Exception) {
                Toast.makeText(context, "Please grant All Files Access in Settings.", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(colors.Primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Lucide.FolderOpen,
                contentDescription = null,
                tint = colors.Primary,
                modifier = Modifier.size(44.dp)
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Storage Access Needed",
            color = colors.OnBackground,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = "Kryptonide reads, writes, and compiles project files directly on your device's storage. Without this, projects cannot be created or edited.",
            color = colors.Muted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        Spacer(Modifier.height(28.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.Surface)
                .border(0.5.dp, colors.Border, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OnboardingFeatureRow(
                icon = Lucide.Save,
                title = "Real files, not a sandbox",
                body = "Projects are saved as real files in storage that you can backup, share, or open anywhere.",
                colors = colors
            )
            OnboardingFeatureRow(
                icon = Lucide.Folder,
                title = "Import & export freely",
                body = "Clone GitHub repos, import zip archives, and export builds with complete freedom.",
                colors = colors
            )
            OnboardingFeatureRow(
                icon = Lucide.Zap,
                title = "One-time setup",
                body = "Required only once on first launch so the IDE can work seamlessly with local files.",
                colors = colors
            )
        }

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = { requestAccess() },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.Primary)
        ) {
            Text("Grant Storage Access", color = colors.OnPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun NeedleAiSetupStep(
    colors: KryptonColorPalette,
    onCompleted: () -> Unit
) {
    val context = LocalContext.current
    val needleSpec = remember {
        PocketModels.PREDEFINED_CATALOG.find { it.id == "needle-3-q4" }
            ?: PocketModels.PREDEFINED_CATALOG.first()
    }

    var isInstalled by remember {
        mutableStateOf(ModelDownloadManager.isModelInstalled(context, needleSpec))
    }

    val downloadStates by ModelDownloadManager.downloadStates.collectAsState()
    val dlState = downloadStates[needleSpec.id]

    LaunchedEffect(dlState?.status) {
        if (dlState?.status == DownloadStatus.COMPLETED) {
            isInstalled = true
            // Warm up model in RAM immediately
            CoroutineScope(Dispatchers.IO).launch {
                LocalGgufServerManager.preloadModel(context)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(colors.Primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Lucide.Cpu,
                contentDescription = null,
                tint = colors.Primary,
                modifier = Modifier.size(44.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = "Needle-3 On-Device AI Setup",
            color = colors.OnBackground,
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = "Needle-3 is a lightning-fast, ultra-compact ~30MB tool-calling model built for mobile. Download it now to enable instant local coding and assistance offline!",
            color = colors.Muted,
            fontSize = 13.5.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.Surface)
                .border(0.5.dp, colors.Border, RoundedCornerShape(16.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            OnboardingFeatureRow(
                icon = Lucide.Zap,
                title = "Sub-15ms Execution",
                body = "Runs 100% locally on your phone's processor without consuming internet data or battery.",
                colors = colors
            )
            OnboardingFeatureRow(
                icon = Lucide.ShieldCheck,
                title = "Zero Latency & 100% Private",
                body = "Code edits, syntax fixes, and tool execution never leave your device.",
                colors = colors
            )
            OnboardingFeatureRow(
                icon = Lucide.HardDrive,
                title = "Ultra-Lightweight (~34 MB)",
                body = "Requires only ~256MB RAM. Safe and fluid on any Android device.",
                colors = colors
            )
        }

        Spacer(Modifier.height(24.dp))

        // Download / Progress / Completed Action Area
        if (isInstalled) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.Primary.copy(alpha = 0.1f))
                    .border(0.5.dp, colors.Primary.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Lucide.Check, null, tint = colors.Primary, modifier = Modifier.size(18.dp))
                    Text(
                        "Needle-3 is ready & primed in RAM",
                        color = colors.Primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    "Local inference is ready for instant offline coding.",
                    color = colors.Muted,
                    fontSize = 11.5.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onCompleted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.Primary)
            ) {
                Text("Continue to Kryptonide", color = colors.OnPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        } else if (dlState?.status == DownloadStatus.DOWNLOADING) {
            val progress = dlState?.progress ?: 0f
            val dlMb = (dlState?.downloadedBytes ?: 0L) / (1024.0 * 1024.0)
            val totMb = (dlState?.totalBytes ?: needleSpec.sizeBytesApprox) / (1024.0 * 1024.0)
            val speed = dlState?.speedMbPerSec ?: 0.0

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.Surface)
                    .border(0.5.dp, colors.Border, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Downloading Needle-3...",
                        color = colors.OnBackground,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${(progress * 100).toInt()}%",
                        color = colors.Primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = colors.Primary,
                    trackColor = colors.SurfaceVar
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        String.format(Locale.US, "%.1f MB / %.1f MB", dlMb, totMb),
                        color = colors.Muted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (speed > 0) {
                        Text(
                            String.format(Locale.US, "%.1f MB/s", speed),
                            color = colors.Primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            TextButton(
                onClick = {
                    ModelDownloadManager.cancelDownload(needleSpec.id)
                    onCompleted()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel & skip for now", color = colors.Muted, fontSize = 13.sp)
            }
        } else {
            Button(
                onClick = {
                    ModelDownloadManager.startDownload(context, needleSpec) {
                        isInstalled = true
                        CoroutineScope(Dispatchers.IO).launch {
                            LocalGgufServerManager.preloadModel(context)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.Primary)
            ) {
                Icon(Lucide.Download, null, modifier = Modifier.size(18.dp), tint = colors.OnPrimary)
                Spacer(Modifier.width(8.dp))
                Text("Download Needle-3 (~34 MB)", color = colors.OnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(10.dp))

            TextButton(
                onClick = onCompleted,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Skip for now (use cloud keys or setup later)", color = colors.Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun OnboardingFeatureRow(
    icon: ImageVector,
    title: String,
    body: String,
    colors: KryptonColorPalette
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.Primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = colors.Primary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = colors.OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(body, color = colors.Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
    }
}
