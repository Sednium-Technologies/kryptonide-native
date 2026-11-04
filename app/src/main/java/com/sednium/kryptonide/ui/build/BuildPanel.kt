package com.sednium.kryptonide.ui.build

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.CloudUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sednium.kryptonide.ui.git.GitViewModel
import com.sednium.kryptonide.ui.git.GithubActionsBuildSheet
import com.sednium.kryptonide.ui.theme.LocalKryptonColors
import com.sednium.kryptonide.ui.theme.UrbanistFontFamily

/**
 * On-device Gradle/Android-SDK builds have been completely removed in favor of
 * automated GitHub Actions cloud builds.
 *
 * This panel connects projects directly to GitHub Actions CI/CD workflows,
 * supporting debug and release APK generation entirely in the cloud.
 */
@Composable
fun BuildPanel(
    projectDir:   String,
    gitVm:        GitViewModel,
    onBack:       () -> Unit = {},
    onOpenGitHub: () -> Unit = {}
) {
    val gitState by gitVm.state.collectAsStateWithLifecycle()
    val colors = LocalKryptonColors.current
    val linked = gitState.linkedRepo

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Studio Top Bar for Cloud Build
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .border(width = 1.dp, color = colors.Border)
                .statusBarsPadding()
                .heightIn(min = 54.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(
                    Lucide.ChevronLeft,
                    contentDescription = "Back to Editor",
                    tint = colors.Muted,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "GitHub Actions Cloud Build",
                    color = colors.OnBackground,
                    fontSize = 15.sp,
                    fontFamily = UrbanistFontFamily,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (linked != null) linked else "Cloud CI/CD build runner",
                    color = colors.Muted,
                    fontSize = 11.sp,
                    fontFamily = UrbanistFontFamily
                )
            }
            Icon(
                Lucide.CloudUpload,
                contentDescription = null,
                tint = colors.Primary,
                modifier = Modifier.padding(end = 8.dp).size(22.dp)
            )
        }

        if (linked == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.Primary.copy(alpha = 0.12f))
                        .border(1.dp, colors.Primary.copy(alpha = 0.3f), RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Lucide.CloudUpload,
                        contentDescription = null,
                        tint = colors.Primary,
                        modifier = Modifier.size(40.dp)
                    )
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "GitHub Actions Required",
                    color = colors.OnBackground,
                    fontSize = 18.sp,
                    fontFamily = UrbanistFontFamily,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "On-device Gradle builds have been removed. Android projects now build cleanly in the cloud using GitHub Actions runners.\n\nLink this project to a GitHub repository to trigger builds and download signed APKs.",
                    color = colors.Muted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.OnBackground)
                    ) {
                        Text("Back to Editor")
                    }
                    Button(
                        onClick = onOpenGitHub,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.Primary)
                    ) {
                        Text("Link GitHub Repo", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            val owner = remember(linked) { linked.substringBefore("/") }
            val repo  = remember(linked) { linked.substringAfter("/", missingDelimiterValue = "") }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                GithubActionsBuildSheet(
                    owner     = owner,
                    repo      = repo,
                    branch    = "main",
                    onDismiss = onBack
                )
            }
        }
    }
}
