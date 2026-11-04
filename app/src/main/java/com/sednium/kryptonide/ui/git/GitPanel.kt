package com.sednium.kryptonide.ui.git

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import com.composables.icons.lucide.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sednium.kryptonide.ui.theme.KryptonColors
import com.sednium.kryptonide.ui.theme.LocalKryptonColors
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import com.sednium.kryptonide.R
import com.sednium.kryptonide.ota.GitHubIssuesView

// ── Official GitHub Octocat Mark (SVG path data converted to Compose Path) ────
// Source: https://github.com/logos — simplified to 1024x1024 viewBox
// This is the correct round-headed Octocat silhouette, not a freehand approx.
@Composable
fun GitHubIcon(modifier: Modifier = Modifier, tint: Color = LocalKryptonColors.current.OnBackground) {
    Icon(
        painter = painterResource(id = R.drawable.ic_github),
        contentDescription = "GitHub Mark",
        modifier = modifier,
        tint = tint
    )
}

private fun getLanguageColor(language: String?): Color {
    return when (language?.lowercase()) {
        "kotlin" -> Color(0xFFA97BFF)
        "java" -> Color(0xFFB07219)
        "python" -> Color(0xFF3572A5)
        "javascript", "js" -> Color(0xFFF1E05A)
        "typescript", "ts" -> Color(0xFF3178C6)
        "html" -> Color(0xFFE34C26)
        "css" -> Color(0xFF563D7C)
        "c++", "cpp" -> Color(0xFFF34B7D)
        "c" -> Color(0xFF555555)
        "c#" -> Color(0xFF178600)
        "go" -> Color(0xFF00ADD8)
        "rust" -> Color(0xFFDEA584)
        "ruby" -> Color(0xFF701516)
        "swift" -> Color(0xFFF05138)
        "dart" -> Color(0xFF00B4AB)
        "shell", "bash" -> Color(0xFF89E051)
        else -> Color(0xFF8B949E)
    }
}

// ── GitPanel ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitPanel(gitVm: GitViewModel) {
    val state  by gitVm.state.collectAsStateWithLifecycle()
    val colors = LocalKryptonColors.current
    var selectedGitSubTab by remember { mutableStateOf(0) } // 0 = Repository, 1 = Issues

    Column(modifier = Modifier.fillMaxSize().background(colors.Background)) {

        // Header
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.Surface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GitHubIcon(
                modifier = Modifier.size(20.dp),
                tint     = if (colors.isDark) Color.White else Color(0xFF24292F)
            )
            Spacer(Modifier.width(8.dp))
            Text("GitHub", color = colors.OnBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (state.githubUser != null) {
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                        .background(colors.Success.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(colors.Success))
                    Spacer(Modifier.width(4.dp))
                    Text("Connected", color = colors.Success, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { gitVm.loadRepos() }, modifier = Modifier.size(28.dp)) {
                    Icon(Lucide.RefreshCw, "Refresh", tint = colors.Muted, modifier = Modifier.size(15.dp))
                }
            }
        }

        HorizontalDivider(color = colors.Border, thickness = 0.5.dp)

        // Sub-Tabs: Repository vs Issues (kryptonide-native)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.SurfaceVar)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (selectedGitSubTab == 0) colors.Surface else Color.Transparent)
                    .clickable { selectedGitSubTab = 0 }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Lucide.GitBranch, null, tint = if (selectedGitSubTab == 0) colors.Primary else colors.Muted, modifier = Modifier.size(13.dp))
                    Text(
                        "Repository",
                        color = if (selectedGitSubTab == 0) colors.OnSurface else colors.Muted,
                        fontSize = 12.sp,
                        fontWeight = if (selectedGitSubTab == 0) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (selectedGitSubTab == 1) colors.Surface else Color.Transparent)
                    .clickable { selectedGitSubTab = 1 }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Lucide.CircleDot, null, tint = if (selectedGitSubTab == 1) colors.Primary else colors.Muted, modifier = Modifier.size(13.dp))
                    Text(
                        "Issues",
                        color = if (selectedGitSubTab == 1) colors.OnSurface else colors.Muted,
                        fontSize = 12.sp,
                        fontWeight = if (selectedGitSubTab == 1) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        if (selectedGitSubTab == 1) {
            val effectiveToken = remember(state.tokenInput, state.githubUser) {
                gitVm.getStoredGithubToken().ifBlank { state.tokenInput.ifBlank { null } }
            }
            GitHubIssuesView(
                githubToken = effectiveToken,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            state.statusMessage?.let { msg ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(if (state.isError) colors.Error.copy(0.1f) else colors.Success.copy(0.1f))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (state.isError) Lucide.CircleAlert else Lucide.CircleCheck,
                        null, tint = if (state.isError) colors.Error else colors.Success,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(msg, color = if (state.isError) colors.Error else colors.Success, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { gitVm.clearStatus() }, modifier = Modifier.size(20.dp)) {
                        Icon(Lucide.X, null, tint = colors.Muted, modifier = Modifier.size(11.dp))
                    }
                }
            }

            LazyColumn(
                modifier            = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding      = PaddingValues(vertical = 14.dp)
            ) {
            if (state.githubUser == null) {
                item {
                    GitSignInSection(
                        state,
                        { gitVm.setTokenInput(it) },
                        { gitVm.signIn() },
                        { gitVm.startDeviceSignIn() },
                        { gitVm.cancelDeviceSignIn() },
                        colors
                    )
                }
            } else {
                item { GitUserSection(state, { gitVm.signOut() }, colors) }
                item {
                    LinkedRepoSection(
                        linkedRepo     = state.linkedRepo,
                        repos          = state.repos,
                        isLoadingRepos = state.isLoadingRepos,
                        isPushing      = state.isPushing,
                        onLoadRepos    = { gitVm.loadRepos() },
                        onLinkRepo     = { gitVm.linkRepo(it) },
                        onUnlink       = { gitVm.unlinkRepo() },
                        onPush         = { gitVm.push(it) },
                        onCreateRepo   = { n, d, p -> gitVm.createRepo(n, d, p) },
                        colors         = colors
                    )
                }
                state.linkedRepo?.let { linked ->
                    item {
                        var showCloudBuild by remember { mutableStateOf(false) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.Surface)
                                .border(1.dp, colors.Border, RoundedCornerShape(12.dp))
                                .padding(14.dp)
                        ) {
                            Text("Cloud Build", color = colors.OnBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Build a debug APK on GitHub's own runners instead of on-device. Push your latest changes first.",
                                color = colors.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                            )
                            Button(
                                onClick = { showCloudBuild = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.Primary)
                            ) {
                                Icon(Lucide.CloudUpload, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Build via GitHub Actions")
                            }
                        }

                        if (showCloudBuild) {
                            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                            ModalBottomSheet(
                                onDismissRequest = { showCloudBuild = false },
                                sheetState = sheetState,
                                containerColor = colors.Surface
                            ) {
                                val parts = linked.split("/")
                                if (parts.size == 2) {
                                    GithubActionsBuildSheet(
                                        owner = parts[0],
                                        repo = parts[1],
                                        onDismiss = { showCloudBuild = false }
                                    )
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

// ── Sign-in section ───────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GitSignInSection(
    state:            GitUiState,
    onTokenChange:    (String) -> Unit,
    onSignIn:         () -> Unit,
    onDeviceSignIn:   () -> Unit,
    onCancelDevice:   () -> Unit,
    colors:           com.sednium.kryptonide.ui.theme.KryptonColorPalette
) {
    val context = LocalContext.current
    val tokenUrl = "https://github.com/settings/tokens/new?scopes=repo,read:user&description=Kryptonide"
    var showToken by remember { mutableStateOf(false) }
    var showPatFallback by remember { mutableStateOf(false) }
    var justCopied by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    LaunchedEffect(justCopied) {
        if (justCopied) {
            kotlinx.coroutines.delay(2000)
            justCopied = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

        // ── Top Hero Card ──────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.Surface),
            border = BorderStroke(1.dp, colors.Border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // GitHub Emblem in accented container
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(if (colors.isDark) Color(0xFF1E2228) else Color(0xFFE8E5DC))
                        .border(1.5.dp, colors.Border, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    GitHubIcon(
                        modifier = Modifier.size(36.dp),
                        tint = colors.OnBackground
                    )
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "GitHub Account",
                    color = colors.OnBackground,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = if (state.isAwaitingDeviceApproval)
                        "Confirm the one-time activation code in your browser to authorize Krypton IDE."
                    else
                        "Connect your account to sync repositories, push commits, and build APKs in the cloud.",
                    color = colors.Muted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )

                if (!state.isAwaitingDeviceApproval) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = colors.Border.copy(alpha = 0.5f), thickness = 0.5.dp)
                    Spacer(Modifier.height(12.dp))

                    // Feature highlights
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GitFeaturePill(
                            icon = Lucide.CloudUpload,
                            title = "GitHub Actions Cloud Build",
                            desc = "Compile release & debug APKs in the cloud without local toolchains",
                            colors = colors
                        )
                        GitFeaturePill(
                            icon = Lucide.GitCommitHorizontal,
                            title = "Smart Git Diff & Push",
                            desc = "Direct Git Data API synchronization for fast, reliable pushes",
                            colors = colors
                        )
                        GitFeaturePill(
                            icon = Lucide.ShieldCheck,
                            title = "Encrypted & Revocable",
                            desc = "Tokens stored in Android Keystore with AES-256 encryption",
                            colors = colors
                        )
                    }
                }
            }
        }

        // ── Device Flow vs Main CTA ─────────────────────────────────
        AnimatedContent(
            targetState = state.isAwaitingDeviceApproval,
            transitionSpec = {
                fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(150))
            },
            label = "DeviceFlowTransition"
        ) { awaiting ->
            if (awaiting && state.deviceUserCode != null) {
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulseAlpha"
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.Surface),
                    border = BorderStroke(1.dp, colors.Primary.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Live pulsating status badge
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(colors.Success.copy(alpha = 0.12f))
                                .border(0.5.dp, colors.Success.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(colors.Success.copy(alpha = pulseAlpha))
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Awaiting GitHub Authorization",
                                color = colors.Success,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Text(
                            text = "Your One-Time Device Code",
                            color = colors.Muted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )

                        // Big Monospace Code Display with Copy Action
                        Surface(
                            onClick = {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(state.deviceUserCode))
                                justCopied = true
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (colors.isDark) Color(0xFF1B1E23) else Color(0xFFE8E5DC),
                            border = BorderStroke(1.dp, if (justCopied) colors.Success else colors.Border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 14.dp, horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = state.deviceUserCode.chunked(4).joinToString(" - "),
                                    color = if (justCopied) colors.Success else colors.OnBackground,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 2.sp
                                )

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (justCopied) Lucide.Check else Lucide.Copy,
                                        contentDescription = "Copy code",
                                        tint = if (justCopied) colors.Success else colors.Muted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = if (justCopied) "Copied!" else "Copy",
                                        color = if (justCopied) colors.Success else colors.Muted,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        // Primary Action Button: Open GitHub
                        Button(
                            onClick = {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(state.deviceUserCode))
                                justCopied = true
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(state.deviceVerificationUri ?: "https://github.com/login/device")
                                    )
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.Primary,
                                contentColor = colors.OnPrimary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                Lucide.ExternalLink,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Open GitHub to Approve",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Timer and Cancel
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val mins = state.deviceSecondsRemaining / 60
                            val secs = state.deviceSecondsRemaining % 60
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Lucide.Timer,
                                    contentDescription = null,
                                    tint = colors.Muted,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Expires in ${mins}m ${secs.toString().padStart(2, '0')}s",
                                    color = colors.Muted,
                                    fontSize = 12.sp
                                )
                            }

                            TextButton(
                                onClick = onCancelDevice,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("Cancel", color = colors.Error, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            } else {
                // ── Idle State (Primary CTA + PAT option) ──
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onDeviceSignIn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.Primary,
                            contentColor = colors.OnPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        GitHubIcon(
                            modifier = Modifier.size(20.dp),
                            tint = colors.OnPrimary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Sign in with GitHub",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // PAT Toggle
                    OutlinedButton(
                        onClick = { showPatFallback = !showPatFallback },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, colors.Border),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.OnBackground)
                    ) {
                        Icon(
                            imageVector = Lucide.KeyRound,
                            contentDescription = null,
                            tint = colors.Muted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (showPatFallback) "Hide Token Options" else "Sign In with Personal Access Token",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.weight(1f))
                        Icon(
                            imageVector = if (showPatFallback) Lucide.ChevronUp else Lucide.ChevronDown,
                            contentDescription = null,
                            tint = colors.Muted,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    AnimatedVisibility(
                        visible = showPatFallback,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.Surface),
                            border = BorderStroke(1.dp, colors.Border)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Personal Access Token (Classic or Fine-Grained)",
                                    color = colors.OnBackground,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )

                                Surface(
                                    onClick = {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tokenUrl)))
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = colors.Primary.copy(alpha = 0.08f),
                                    border = BorderStroke(0.5.dp, colors.Primary.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "Generate Token with required scopes",
                                                color = colors.OnBackground,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                "Opens GitHub with 'repo' and 'read:user' pre-selected",
                                                color = colors.Muted,
                                                fontSize = 11.sp
                                            )
                                        }
                                        Icon(
                                            Lucide.ExternalLink,
                                            contentDescription = null,
                                            tint = colors.Primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                OutlinedTextField(
                                    value = state.tokenInput,
                                    onValueChange = onTokenChange,
                                    placeholder = { Text("ghp_xxxxxxxxxxxxxxxxxxxx", color = colors.Muted, fontSize = 12.sp) },
                                    label = { Text("Personal Access Token", fontSize = 12.sp) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    trailingIcon = {
                                        IconButton(onClick = { showToken = !showToken }) {
                                            Icon(
                                                if (showToken) Lucide.EyeOff else Lucide.Eye,
                                                contentDescription = if (showToken) "Hide token" else "Show token",
                                                tint = colors.Muted
                                            )
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = TextFieldDefaults.outlinedTextFieldColors(
                                        focusedBorderColor = colors.Primary,
                                        unfocusedBorderColor = colors.Border,
                                        textColor = colors.OnBackground,
                                        cursorColor = colors.Primary,
                                    ),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                                )

                                Button(
                                    onClick = onSignIn,
                                    enabled = state.tokenInput.isNotBlank(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colors.Primary,
                                        contentColor = colors.OnPrimary,
                                        disabledContainerColor = colors.Border
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Authenticate with Token", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }

        state.statusMessage?.let {
            Text(it, color = if (state.isError) colors.Error else colors.Success, fontSize = 12.sp)
        }
    }
}

@Composable
private fun GitFeaturePill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    colors: com.sednium.kryptonide.ui.theme.KryptonColorPalette
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.Primary.copy(alpha = 0.08f))
                .border(0.5.dp, colors.Border, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.Primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.OnBackground,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = desc,
                color = colors.Muted,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}

// ── User section ──────────────────────────────────────────────────────────────
@Composable
private fun GitUserSection(
    state:    GitUiState,
    onSignOut: () -> Unit,
    colors:   com.sednium.kryptonide.ui.theme.KryptonColorPalette
) {
    val context = LocalContext.current
    val user = state.githubUser ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.Surface),
        border = BorderStroke(1.dp, colors.Border)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                if (user.avatar_url.isNotBlank()) {
                    AsyncImage(
                        model = user.avatar_url,
                        contentDescription = user.login,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .border(1.dp, colors.Border, CircleShape)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(colors.Primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            user.login.take(1).uppercase(),
                            color = colors.OnPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(colors.Success)
                        .border(1.5.dp, colors.Surface, CircleShape)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = user.name?.takeIf { it.isNotBlank() } ?: user.login,
                        color = colors.OnBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (user.html_url.isNotBlank()) {
                        Icon(
                            Lucide.ExternalLink,
                            contentDescription = "Open profile",
                            tint = colors.Muted,
                            modifier = Modifier
                                .size(13.dp)
                                .clickable {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(user.html_url))
                                    context.startActivity(intent)
                                }
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "@${user.login}",
                        color = colors.Muted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(colors.SurfaceVar)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            "${user.public_repos} repos",
                            color = colors.Muted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            TextButton(
                onClick = onSignOut,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text("Sign out", color = colors.Error, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ── Linked repo section ───────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkedRepoSection(
    linkedRepo:     String?,
    repos:          List<com.sednium.kryptonide.data.repository.GitHubRepo>,
    isLoadingRepos: Boolean,
    isPushing:      Boolean,
    onLoadRepos:    () -> Unit,
    onLinkRepo:     (String) -> Unit,
    onUnlink:       () -> Unit,
    onPush:         (String) -> Unit,
    onCreateRepo:   (String, String, Boolean) -> Unit,
    colors:         com.sednium.kryptonide.ui.theme.KryptonColorPalette
) {
    val context = LocalContext.current
    var commitMsg        by remember { mutableStateOf("") }
    var showRepoPicker   by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val activeRepoObj = remember(linkedRepo, repos) {
        repos.firstOrNull { it.full_name == linkedRepo }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (linkedRepo != null) {
            // ── Active Linked Repository Hero Card ─────────────────────────
            val parts = linkedRepo.split("/")
            val owner = parts.getOrNull(0) ?: ""
            val name = parts.getOrNull(1) ?: linkedRepo

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = colors.Surface),
                border = BorderStroke(1.dp, colors.Primary.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.Primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            GitHubIcon(modifier = Modifier.size(20.dp), tint = colors.Primary)
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (owner.isNotBlank()) {
                                    Text(
                                        "$owner /",
                                        color = colors.Muted,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Normal
                                    )
                                }
                                Text(
                                    name,
                                    color = colors.OnBackground,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(Modifier.height(3.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Live Linked Status Pill
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(colors.Success.copy(alpha = 0.12f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(colors.Success))
                                    Text("LINKED", color = colors.Success, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }

                                // Branch Pill
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(colors.SurfaceVar)
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(Lucide.GitBranch, null, tint = colors.Muted, modifier = Modifier.size(10.dp))
                                    Text(
                                        activeRepoObj?.default_branch ?: "main",
                                        color = colors.Muted,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                // Visibility Pill
                                val isPrivate = activeRepoObj?.private ?: false
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (isPrivate) Color(0xFFE3B341).copy(alpha = 0.12f) else colors.SurfaceVar)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        if (isPrivate) "Private" else "Public",
                                        color = if (isPrivate) Color(0xFFE3B341) else colors.Muted,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        // Top right quick actions
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    val url = activeRepoObj?.html_url ?: "https://github.com/$linkedRepo"
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    Lucide.ExternalLink,
                                    "Open on GitHub",
                                    tint = colors.Muted,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            IconButton(
                                onClick = onUnlink,
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    Lucide.Unlink,
                                    "Unlink",
                                    tint = colors.Muted,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }

                    if (activeRepoObj?.description?.isNotBlank() == true) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            activeRepoObj.description,
                            color = colors.Muted,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 15.sp
                        )
                    }

                    if (activeRepoObj?.language != null || (activeRepoObj?.stargazers_count ?: 0) > 0) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            activeRepoObj?.language?.let { lang ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(getLanguageColor(lang)))
                                    Text(lang, color = colors.Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            if ((activeRepoObj?.stargazers_count ?: 0) > 0) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(Lucide.Star, null, tint = colors.Muted, modifier = Modifier.size(11.dp))
                                    Text("${activeRepoObj?.stargazers_count ?: 0}", color = colors.Muted, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }

            // ── Commit & Push Card ─────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = colors.Surface),
                border = BorderStroke(1.dp, colors.Border)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Lucide.Upload, null, tint = colors.Primary, modifier = Modifier.size(15.dp))
                            Text("Commit & Push", color = colors.OnBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(
                            onClick = { onLoadRepos(); showRepoPicker = true },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text("Switch Repo", color = colors.Primary, fontSize = 11.sp)
                        }
                    }

                    OutlinedTextField(
                        value         = commitMsg,
                        onValueChange = { commitMsg = it },
                        placeholder   = { Text("Describe code changes...", color = colors.Muted, fontSize = 12.sp) },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                        shape         = RoundedCornerShape(10.dp),
                        colors        = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor   = colors.Primary,
                            unfocusedBorderColor = colors.Border,
                            textColor            = colors.OnBackground,
                            cursorColor          = colors.Primary,
                        ),
                    )

                    Button(
                        onClick  = { if (commitMsg.isNotBlank()) onPush(commitMsg) },
                        enabled  = !isPushing && commitMsg.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = colors.Primary, contentColor = colors.OnPrimary),
                        shape    = RoundedCornerShape(10.dp)
                    ) {
                        if (isPushing) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.OnPrimary)
                            Spacer(Modifier.width(6.dp))
                            Text("Pushing changes…", color = colors.OnPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        } else {
                            GitHubIcon(Modifier.size(14.dp), tint = colors.OnPrimary)
                            Spacer(Modifier.width(6.dp))
                            Text("Push to ${activeRepoObj?.default_branch ?: "main"}", color = colors.OnPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else {
            // ── No Repository Connected Card ───────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = colors.Surface),
                border = BorderStroke(1.dp, colors.Border)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(colors.Primary.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Lucide.FolderOpen, null, tint = colors.Primary, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "No Repository Connected",
                        color = colors.OnBackground,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Link a GitHub repository to push your code, manage remote branches, and trigger automated builds.",
                        color = colors.Muted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick  = { onLoadRepos(); showRepoPicker = true },
                            modifier = Modifier.weight(1f).height(42.dp),
                            shape    = RoundedCornerShape(10.dp),
                            border   = BorderStroke(1.dp, colors.Border)
                        ) {
                            Icon(Lucide.Link, null, Modifier.size(14.dp), tint = colors.OnBackground)
                            Spacer(Modifier.width(6.dp))
                            Text("Link Existing", color = colors.OnBackground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick  = { showCreateDialog = true },
                            modifier = Modifier.weight(1f).height(42.dp),
                            colors   = ButtonDefaults.buttonColors(containerColor = colors.Primary, contentColor = colors.OnPrimary),
                            shape    = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Lucide.Plus, null, Modifier.size(14.dp), tint = colors.OnPrimary)
                            Spacer(Modifier.width(6.dp))
                            Text("New Repo", fontSize = 12.sp, color = colors.OnPrimary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }

    // ── Upgraded Repository Picker ModalBottomSheet ───────────────────────────────
    if (showRepoPicker) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var repoSearchQuery by remember { mutableStateOf("") }
        val filteredRepos = remember(repos, repoSearchQuery) {
            if (repoSearchQuery.isBlank()) repos
            else repos.filter {
                it.name.contains(repoSearchQuery, ignoreCase = true) ||
                it.full_name.contains(repoSearchQuery, ignoreCase = true) ||
                (it.description?.contains(repoSearchQuery, ignoreCase = true) == true)
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showRepoPicker = false },
            sheetState = sheetState,
            containerColor = colors.Surface,
            dragHandle = null
        ) {
            Column(modifier = Modifier.fillMaxHeight(0.85f)) {
                // Drag handle & Header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.Border)
                            .align(Alignment.CenterHorizontally)
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Select Repository",
                                color = colors.OnBackground,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(colors.SurfaceVar)
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    "${repos.size}",
                                    color = colors.Muted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        IconButton(
                            onClick = { showRepoPicker = false },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Lucide.X, "Close", tint = colors.Muted, modifier = Modifier.size(16.dp))
                        }
                    }

                    // Search input
                    if (repos.size > 3 || repoSearchQuery.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.Background)
                                .border(0.5.dp, colors.Border, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Lucide.Search, null, tint = colors.Muted, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(8.dp))
                            androidx.compose.foundation.text.BasicTextField(
                                value = repoSearchQuery,
                                onValueChange = { repoSearchQuery = it },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(color = colors.OnBackground, fontSize = 12.sp),
                                modifier = Modifier.weight(1f),
                                decorationBox = { innerTextField ->
                                    if (repoSearchQuery.isEmpty()) {
                                        Text("Filter repositories...", color = colors.Muted, fontSize = 12.sp)
                                    }
                                    innerTextField()
                                }
                            )
                            if (repoSearchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { repoSearchQuery = "" },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Lucide.X, "Clear", tint = colors.Muted, modifier = Modifier.size(13.dp))
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = colors.Border.copy(alpha = 0.5f), thickness = 0.5.dp)

                if (isLoadingRepos) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = colors.Primary, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                            Spacer(Modifier.height(12.dp))
                            Text("Loading repositories from GitHub…", color = colors.Muted, fontSize = 12.sp)
                        }
                    }
                } else if (filteredRepos.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                if (repoSearchQuery.isNotBlank()) Lucide.SearchX else Lucide.FolderOpen,
                                null,
                                tint = colors.Muted,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                if (repoSearchQuery.isNotBlank()) "No repositories matching \"$repoSearchQuery\""
                                else "No repositories found",
                                color = colors.OnBackground,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (repoSearchQuery.isNotBlank()) "Check the spelling or clear the filter"
                                else "Create a repository on GitHub first.",
                                color = colors.Muted,
                                fontSize = 12.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredRepos, key = { it.full_name }) { repo ->
                            val isCurrentlyLinked = repo.full_name == linkedRepo
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onLinkRepo(repo.full_name)
                                        showRepoPicker = false
                                    },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isCurrentlyLinked) colors.Primary.copy(alpha = 0.08f) else colors.Surface
                                ),
                                border = BorderStroke(
                                    if (isCurrentlyLinked) 1.dp else 0.5.dp,
                                    if (isCurrentlyLinked) colors.Primary else colors.Border.copy(alpha = 0.7f)
                                )
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isCurrentlyLinked) colors.Primary.copy(alpha = 0.15f) else colors.SurfaceVar),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                if (repo.private) Lucide.Lock else Lucide.FolderOpen,
                                                null,
                                                tint = if (isCurrentlyLinked) colors.Primary else (if (repo.private) Color(0xFFE3B341) else colors.Primary),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        Spacer(Modifier.width(10.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    repo.name,
                                                    color = colors.OnBackground,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (isCurrentlyLinked) {
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(4.dp))
                                                            .background(colors.Success.copy(alpha = 0.15f))
                                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                                    ) {
                                                        Text("LINKED", color = colors.Success, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                                // Visibility badge
                                                if (repo.private) {
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(4.dp))
                                                            .background(Color(0xFFE3B341).copy(alpha = 0.15f))
                                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                                    ) {
                                                        Text("Private", color = Color(0xFFE3B341), fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
                                                    }
                                                }
                                            }
                                            Text(
                                                repo.full_name.substringBefore('/'),
                                                color = colors.Muted,
                                                fontSize = 11.sp
                                            )
                                        }

                                        Icon(
                                            Lucide.ChevronRight,
                                            null,
                                            tint = colors.Muted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    if (repo.description?.isNotBlank() == true) {
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            repo.description,
                                            color = colors.Muted,
                                            fontSize = 11.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            lineHeight = 15.sp
                                        )
                                    }

                                    Spacer(Modifier.height(8.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        repo.language?.let { lang ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(getLanguageColor(lang)))
                                                Text(lang, color = colors.Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                            }
                                        }

                                        if (repo.stargazers_count > 0) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                                            ) {
                                                Icon(Lucide.Star, null, tint = colors.Muted, modifier = Modifier.size(11.dp))
                                                Text("${repo.stargazers_count}", color = colors.Muted, fontSize = 10.sp)
                                            }
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Icon(Lucide.GitBranch, null, tint = colors.Muted, modifier = Modifier.size(10.dp))
                                            Text(repo.default_branch, color = colors.Muted, fontSize = 10.sp)
                                        }

                                        if (repo.updated_at.isNotBlank()) {
                                            Text(
                                                "Updated ${repo.updated_at.take(10)}",
                                                color = colors.Muted.copy(alpha = 0.7f),
                                                fontSize = 10.sp
                                            )
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

    // ── Upgraded Create Repository Dialog ─────────────────────────────────────────
    if (showCreateDialog) {
        var repoName  by remember { mutableStateOf("") }
        var repoDesc  by remember { mutableStateOf("") }
        var isPrivate by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor   = colors.Surface,
            shape            = RoundedCornerShape(16.dp),
            title            = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GitHubIcon(Modifier.size(18.dp), colors.OnBackground)
                    Text("Create New Repository", color = colors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = repoName,
                        onValueChange = { repoName = it },
                        label = { Text("Repository name") },
                        placeholder = { Text("my-krypton-project") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = colors.Primary,
                            unfocusedBorderColor = colors.Border,
                            textColor = colors.OnBackground
                        )
                    )
                    OutlinedTextField(
                        value = repoDesc,
                        onValueChange = { repoDesc = it },
                        label = { Text("Description (optional)") },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = colors.Primary,
                            unfocusedBorderColor = colors.Border,
                            textColor = colors.OnBackground
                        )
                    )

                    // Visibility segmented selection
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.Background)
                            .border(0.5.dp, colors.Border, RoundedCornerShape(10.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (!isPrivate) colors.Primary.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable { isPrivate = false }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Lucide.FolderOpen, null, tint = if (!isPrivate) colors.Primary else colors.Muted, modifier = Modifier.size(14.dp))
                                Text("Public", color = if (!isPrivate) colors.Primary else colors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isPrivate) Color(0xFFE3B341).copy(alpha = 0.15f) else Color.Transparent)
                                .clickable { isPrivate = true }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Lucide.Lock, null, tint = if (isPrivate) Color(0xFFE3B341) else colors.Muted, modifier = Modifier.size(14.dp))
                                Text("Private", color = if (isPrivate) Color(0xFFE3B341) else colors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (repoName.isNotBlank()) {
                            onCreateRepo(repoName.trim(), repoDesc.trim(), isPrivate)
                            showCreateDialog = false
                        }
                    },
                    enabled = repoName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.Primary, contentColor = colors.OnPrimary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Create Repository", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel", color = colors.Muted)
                }
            }
        )
    }
}
