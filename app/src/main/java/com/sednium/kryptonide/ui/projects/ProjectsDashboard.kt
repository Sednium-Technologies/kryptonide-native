@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.ui.text.ExperimentalTextApi::class
)

package com.sednium.kryptonide.ui.projects

import com.sednium.kryptonide.utils.StorageUtils
import android.app.Activity
import android.os.Build
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.sednium.kryptonide.R
import com.sednium.kryptonide.data.model.Project
import com.sednium.kryptonide.data.model.ProjectTemplate
import com.sednium.kryptonide.ui.packages.PackageInstallDialog
import com.sednium.kryptonide.ui.packages.PackageInstaller
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

import com.sednium.kryptonide.ui.theme.LocalKryptonColors
import com.composables.icons.lucide.*

val BgDark: Color @Composable get() = LocalKryptonColors.current.Background
val SurfaceDark: Color @Composable get() = LocalKryptonColors.current.Surface
val BorderDark: Color @Composable get() = LocalKryptonColors.current.Border
val TextPrimary: Color @Composable get() = LocalKryptonColors.current.OnBackground
val TextMuted: Color @Composable get() = LocalKryptonColors.current.Muted
val AccentColor: Color @Composable get() = LocalKryptonColors.current.Primary
val AccentLight: Color @Composable get() = LocalKryptonColors.current.Secondary
// Content color for anything filled with AccentColor/AccentLight — NOT a fixed
// white anymore, since Primary/Secondary now theme-flip (Ivory fill in dark
// mode, Charcoal fill in light mode) instead of being a separate accent hue.
val OnAccentColor: Color @Composable get() = LocalKryptonColors.current.OnPrimary
val OnAccentLight: Color @Composable get() = LocalKryptonColors.current.OnSecondary

@Composable
fun Modifier.bounceClick(
    enabled: Boolean = true, 
    onClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy, 
            stiffness = Spring.StiffnessLow
        ),
        label = "bounce"
    )
    val view = LocalView.current

    LaunchedEffect(isPressed) {
        if (isPressed && enabled) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    return this
        .scale(scale)
        .clickable(
            interactionSource = interactionSource, 
            indication = LocalIndication.current, 
            enabled = enabled,
            onClick = { 
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick() 
            }
        )
}

data class TemplateCategory(
    val name: String, 
    val templates: List<TemplateItem>
)

data class TemplateItem(
    val id: String, 
    val templateEnum: ProjectTemplate, 
    val desc: String, 
    val color: Color, 
    val iconRes: Int
)

val DashboardCategories = listOf(
    TemplateCategory(
        "Android Development", 
        listOf(
            TemplateItem("Android (Kotlin)", ProjectTemplate.ANDROID_KOTLIN, "Modern Android app with Kotlin", Color(0xFF22C55E), R.drawable.ic_kotlin),
            TemplateItem("Android (Java)", ProjectTemplate.ANDROID_JAVA, "Classic Android development", Color(0xFFF59E0B), R.drawable.ic_java),
            TemplateItem("Android (Jetpack Compose)", ProjectTemplate.ANDROID_COMPOSE, "Declarative UI toolkit", Color(0xFF10B981), R.drawable.ic_compose)
        )
    ),
    TemplateCategory(
        "Web Development", 
        listOf(
            TemplateItem("HTML/CSS/JS", ProjectTemplate.HTML, "Vanilla web stack", Color(0xFFF97316), R.drawable.ic_html),
            TemplateItem("React", ProjectTemplate.REACT, "React SPA", Color(0xFF3B82F6), R.drawable.ic_react),
            TemplateItem("React + Vite", ProjectTemplate.VITE, "Lightning fast React build", Color(0xFFA855F7), R.drawable.ic_vite),
            TemplateItem("Next.js", ProjectTemplate.NEXTJS, "React framework for production", Color(0xFFE6EDF3), R.drawable.ic_nextjs),
            TemplateItem("PHP", ProjectTemplate.PHP, "Server-side scripting", Color(0xFF6366F1), R.drawable.ic_php)
        )
    ),
    TemplateCategory(
        "Backend & Systems", 
        listOf(
            TemplateItem("Node + Express", ProjectTemplate.NODE, "Backend JS server", Color(0xFF22C55E), R.drawable.ic_node),
            TemplateItem("FastAPI", ProjectTemplate.FASTAPI, "High performance Python API", Color(0xFF14B8A6), R.drawable.ic_fastapi),
            TemplateItem("Python", ProjectTemplate.PYTHON, "General purpose Python", Color(0xFFEAB308), R.drawable.ic_python),
            TemplateItem("C++", ProjectTemplate.CPP, "High performance systems", Color(0xFF3B82F6), R.drawable.ic_cpp),
            TemplateItem("C", ProjectTemplate.C, "Low-level system programming", Color(0xFF60A5FA), R.drawable.ic_c),
            TemplateItem("C#", ProjectTemplate.CSHARP, "Modern .NET application", Color(0xFF8B5CF6), R.drawable.ic_csharp),
            TemplateItem("Rust", ProjectTemplate.RUST, "Safe systems programming", Color(0xFFF97316), R.drawable.ic_rust)
        )
    ),
    TemplateCategory(
        "Other", 
        listOf(
            TemplateItem("Markdown", ProjectTemplate.MARKDOWN, "Documentation and notes", Color(0xFF3B82F6), R.drawable.ic_markdown),
            TemplateItem("JSON", ProjectTemplate.JSON, "Data configuration", Color(0xFF8B949E), R.drawable.ic_json),
            TemplateItem("Blank Project", ProjectTemplate.BLANK, "Start from scratch", Color(0xFF9CA3AF), R.drawable.ic_blank)
        )
    )
)

/** Resolves a picked SAF Uri's display name (e.g. "my-project.zip") via the ContentResolver. */
fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIdx >= 0) cursor.getString(nameIdx) else null
        }
    }.getOrNull()
}

/** Extracts a picked .zip Uri into [destDir]. Returns the number of entries extracted. Guards against zip-slip path traversal. */
fun extractZipToDirectory(context: android.content.Context, zipUri: android.net.Uri, destDir: java.io.File): Int {
    destDir.mkdirs()
    var count = 0
    context.contentResolver.openInputStream(zipUri)?.use { rawStream ->
        java.util.zip.ZipInputStream(rawStream.buffered()).use { zis ->
            var entry = zis.nextEntry
            // Many GitHub-style zip downloads wrap everything in a single top-level
            // "<repo>-<branch>/" folder — strip it so the project root isn't just
            // one lonely subfolder.
            var stripPrefix: String? = null
            while (entry != null) {
                val name = entry.name
                if (stripPrefix == null) {
                    val firstSlash = name.indexOf('/')
                    stripPrefix = if (firstSlash > 0) name.substring(0, firstSlash + 1) else ""
                }
                val relative = if (stripPrefix.isNotEmpty() && name.startsWith(stripPrefix)) name.removePrefix(stripPrefix) else name
                if (relative.isNotBlank()) {
                    val outFile = java.io.File(destDir, relative)
                    // zip-slip guard: refuse entries that would escape destDir
                    if (!outFile.canonicalPath.startsWith(destDir.canonicalPath + java.io.File.separator) && outFile.canonicalPath != destDir.canonicalPath) {
                        zis.closeEntry(); entry = zis.nextEntry; continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out -> zis.copyTo(out) }
                        count++
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    } ?: throw java.io.IOException("Could not open the selected zip file.")
    return count
}

fun getGoogleSignInClient(activity: Activity): GoogleSignInClient {
    val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope("https://www.googleapis.com/auth/drive.file"))

    // requestIdToken() is deliberately NOT called here: this app only needs
    // Drive REST API access via GoogleAccountCredential (account.account),
    // which doesn't need an ID token at all. Calling requestIdToken() with
    // an unconfigured/placeholder Web Client ID (BuildConfig.GOOGLE_CLIENT_ID
    // defaults to "YOUR_WEB_CLIENT_ID" from .env.example until a real .env
    // is set up) reliably fails with ApiException code 10 (DEVELOPER_ERROR)
    // even though sign-in + Drive access don't actually require it.

    return GoogleSignIn.getClient(activity, builder.build())
}

@Composable
fun ProjectsDashboard(
    viewModel: ProjectsViewModel, 
    onOpenProject: (String) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    
    var showCreateDialog by remember { mutableStateOf(false) }
    var showAndroidWizard by remember { mutableStateOf(false) }
    var pendingAndroidTemplate by remember { mutableStateOf<ProjectTemplate?>(null) }
    var pendingAppName by remember { mutableStateOf("") }
    var pendingPackageTemplate by remember { mutableStateOf<ProjectTemplate?>(null) }
    var pendingPackageProjectId by remember { mutableStateOf<String?>(null) }
    
    var showCloneDialog by remember { mutableStateOf(false) }
    var projectToDelete by remember { mutableStateOf<Project?>(null) }
    var projectToRename by remember { mutableStateOf<Project?>(null) }
    var storageType by remember { mutableStateOf("local") }
    
    val view = LocalView.current
    val context = LocalContext.current
    val activity = context as Activity
    
    var googleAccount by remember { mutableStateOf(GoogleSignIn.getLastSignedInAccount(context)) }
    var showProfileMenu by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    
    var isSyncing by remember { mutableStateOf(false) }
    var syncIntervalMinutes by remember { mutableStateOf(0) }
    
    var isImportingZip by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val allActiveProjects = remember(state.projects) {
        val excluded = com.sednium.kryptonide.data.repository.ProjectRepository.KRYPTON_INTERNAL_DIRS
        state.projects.filter { !it.name.startsWith(".") && it.name.lowercase() !in excluded }
    }
    val mostRecentProject = remember(allActiveProjects, state.lastOpenedProjectId) {
        if (state.lastOpenedProjectId != null) {
            allActiveProjects.find { it.id == state.lastOpenedProjectId } ?: allActiveProjects.firstOrNull()
        } else {
            allActiveProjects.firstOrNull()
        }
    }
    val displayProjects = remember(allActiveProjects, searchQuery) {
        if (searchQuery.isBlank()) {
            allActiveProjects
        } else {
            val q = searchQuery.trim()
            allActiveProjects.filter { 
                it.name.contains(q, ignoreCase = true) || 
                it.template.name.contains(q, ignoreCase = true)
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way — build panels degrade gracefully without it */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val zipPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                isImportingZip = true
                try {
                    val suggestedName = queryDisplayName(context, uri)
                        ?.substringBeforeLast(".zip", missingDelimiterValue = "imported-zip")
                        ?.ifBlank { "imported-zip" } ?: "imported-zip"
                    val projectDir = java.io.File(StorageUtils.getExternalStorageDir(), "KryptonIDE/$suggestedName")
                    val fileCount = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        extractZipToDirectory(context, uri, projectDir)
                    }
                    if (fileCount == 0) {
                        projectDir.deleteRecursively()
                        Toast.makeText(context, "No files found in zip. The archive may be corrupted or password-protected.", Toast.LENGTH_LONG).show()
                    } else {
                        val projectId = viewModel.createProjectAndGetId(suggestedName, ProjectTemplate.BLANK)
                        viewModel.importDirectoryIntoProject(projectId, projectDir)
                        Toast.makeText(context, "Imported $fileCount files from zip!", Toast.LENGTH_SHORT).show()
                        showCreateDialog = false
                        onOpenProject(projectId)
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Zip import failed: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    isImportingZip = false
                }
            }
        }
    }

    val signInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (data != null) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                googleAccount = task.getResult(ApiException::class.java)
                Toast.makeText(context, "Connected to Sednium Cloud Core", Toast.LENGTH_SHORT).show()
            } catch (e: com.google.android.gms.common.api.ApiException) {
                val msg = when (e.statusCode) {
                    10    -> "Google Sign-In: Developer Error 10 (Verify SHA-1 fingerprint in Google Cloud Console)"
                    12501 -> "Google Sign-in was cancelled."
                    12500 -> "Google Sign-in failed. Check Google Play Services & connection."
                    7     -> "Network error: check internet connection."
                    4     -> "No Google account configured on device. Please add one in Android Settings."
                    else  -> "Sign-in failed (code ${e.statusCode}): ${e.localizedMessage ?: "Unknown error"}"
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                e.printStackTrace()
            }
        } else {
            Toast.makeText(context, "Google Sign-in was cancelled or unavailable on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    val executeDriveSync: () -> Unit = {
        if (googleAccount != null && !isSyncing) {
            scope.launch {
                isSyncing = true
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                try {
                    val synced = DriveSyncHelper.syncWorkspace(context, googleAccount!!)
                    Toast.makeText(context, "Synced $synced file(s) to Drive!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Sync failed: ${e.message}", Toast.LENGTH_LONG).show()
                    e.printStackTrace()
                } finally {
                    isSyncing = false
                }
            }
        }
    }

    LaunchedEffect(syncIntervalMinutes, googleAccount) {
        if (syncIntervalMinutes > 0 && googleAccount != null) {
            while (true) {
                delay(syncIntervalMinutes * 60 * 1000L)
                isSyncing = true
                try {
                    DriveSyncHelper.syncWorkspace(context, googleAccount!!)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    isSyncing = false
                }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.loadProjects()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(), 
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = 12.dp, bottom = 16.dp), 
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp), 
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(SurfaceDark)
                                .border(1.dp, BorderDark, CircleShape)
                                .bounceClick { showInfoDialog = true },
                            contentAlignment = Alignment.Center
                        ) { 
                            Icon(
                                Lucide.Info, 
                                contentDescription = "Info", 
                                tint = TextPrimary, 
                                modifier = Modifier.size(20.dp)
                            ) 
                        }

                        if (googleAccount == null) {
                            Button(
                                onClick = { 
                                    signInLauncher.launch(getGoogleSignInClient(activity).signInIntent) 
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White, 
                                    contentColor = Color.Black
                                ),
                                shape = RoundedCornerShape(50), 
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_google), 
                                    contentDescription = null, 
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Sign in", 
                                    fontSize = 13.sp, 
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            val rotation by animateFloatAsState(
                                targetValue = if (isSyncing) 360f else 0f, 
                                animationSpec = if (isSyncing) infiniteRepeatable(tween(1200, easing = LinearEasing)) else tween(0),
                                label = "syncRotation"
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically, 
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(SurfaceDark)
                                        .border(1.dp, BorderDark, CircleShape)
                                        .bounceClick { executeDriveSync() },
                                    contentAlignment = Alignment.Center
                                ) { 
                                    Icon(
                                        Lucide.RefreshCw, 
                                        contentDescription = "Sync", 
                                        tint = if (isSyncing) AccentColor else TextPrimary, 
                                        modifier = Modifier
                                            .size(18.dp)
                                            .rotate(rotation)
                                    ) 
                                }

                                Box {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(SurfaceDark)
                                            .border(1.dp, BorderDark, RoundedCornerShape(50))
                                            .bounceClick { showProfileMenu = true }
                                            .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AsyncImage(
                                            model = googleAccount?.photoUrl, 
                                            contentDescription = null, 
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(BgDark)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = googleAccount?.displayName ?: "User", 
                                            color = TextPrimary, 
                                            fontSize = 12.sp, 
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Icon(
                                            Lucide.ChevronDown, 
                                            contentDescription = null, 
                                            tint = TextMuted, 
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    
                                    DropdownMenu(
                                        expanded = showProfileMenu, 
                                        onDismissRequest = { showProfileMenu = false },
                                        modifier = Modifier
                                            .background(SurfaceDark)
                                            .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
                                            .width(250.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 12.dp, horizontal = 16.dp), 
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            AsyncImage(
                                                model = googleAccount?.photoUrl, 
                                                contentDescription = null, 
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(CircleShape)
                                                    .border(1.dp, BorderDark, CircleShape)
                                            )
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                text = googleAccount?.displayName ?: "", 
                                                color = TextPrimary, 
                                                fontSize = 14.sp, 
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = googleAccount?.email ?: "", 
                                                color = TextMuted, 
                                                fontSize = 11.sp, 
                                                maxLines = 1, 
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        HorizontalDivider(color = BorderDark)
                                        Column(
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                        ) {
                                            Text(
                                                text = "BACKUP SCHEDULE", 
                                                color = TextMuted, 
                                                fontSize = 10.sp, 
                                                fontWeight = FontWeight.Bold
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(4.dp), 
                                                modifier = Modifier.padding(top = 4.dp)
                                            ) {
                                                listOf(0 to "Manual", 60 to "1H", 1440 to "Daily").forEach { (min, label) ->
                                                    FilterChip(
                                                        selected = syncIntervalMinutes == min, 
                                                        onClick = { syncIntervalMinutes = min },
                                                        label = { Text(label, fontSize = 11.sp) },
                                                        colors = FilterChipDefaults.filterChipColors(
                                                            selectedContainerColor = AccentColor, 
                                                            selectedLabelColor = OnAccentColor, 
                                                            containerColor = BgDark, 
                                                            labelColor = TextMuted
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                        HorizontalDivider(color = BorderDark)
                                        DropdownMenuItem(
                                            text = { Text("Backup Workspace Now", color = TextPrimary) }, 
                                            leadingIcon = { 
                                                Icon(Lucide.CloudUpload, null, tint = AccentColor) 
                                            }, 
                                            onClick = { 
                                                showProfileMenu = false
                                                executeDriveSync() 
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Sign Out", color = LocalKryptonColors.current.Error) }, 
                                            leadingIcon = { 
                                                Icon(Lucide.LogOut, null, tint = LocalKryptonColors.current.Error) 
                                            }, 
                                            onClick = { 
                                                showProfileMenu = false
                                                getGoogleSignInClient(activity).signOut().addOnCompleteListener { 
                                                    googleAccount = null 
                                                } 
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Image(
                        painter = painterResource(id = R.drawable.icon), 
                        contentDescription = null, 
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .border(1.dp, BorderDark, CircleShape)
                    )
                    Spacer(Modifier.height(16.dp))
                    
                    val gradient = Brush.linearGradient(listOf(AccentLight, AccentLight.copy(alpha = 0.75f), AccentColor))
                    Text(
                        text = "KRYPTONIDE", 
                        style = TextStyle(brush = gradient), 
                        fontSize = 36.sp, 
                        fontWeight = FontWeight.Black, 
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Code Anywhere, Build Anything",
                        color = AccentLight,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = "SEDNIUM MOBILE ENGINE", 
                        color = TextMuted, 
                        fontSize = 10.sp, 
                        fontWeight = FontWeight.Bold, 
                        letterSpacing = 3.sp, 
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                HorizontalDivider(color = BorderDark, thickness = 1.dp)
            }

            item {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth(), 
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    
                    // JUMP BACK IN / RESUME RECENT PROJECT (Hero Card)
                    JumpBackInHeroCard(
                        project = mostRecentProject,
                        onOpenProject = onOpenProject,
                        onCreateClicked = { showCreateDialog = true }
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AccentColor)
                            .bounceClick { showCreateDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Lucide.Plus, 
                                contentDescription = null, 
                                tint = OnAccentColor, 
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "New Project", 
                                color = OnAccentColor, 
                                fontWeight = FontWeight.Medium, 
                                fontSize = 14.sp
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "STORAGE PROVIDER", 
                            fontSize = 12.sp, 
                            fontWeight = FontWeight.SemiBold, 
                            color = TextMuted, 
                            letterSpacing = 1.sp
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Max),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            StorageProviderCard(
                                title = "Local System", 
                                subtitle = "Device Storage", 
                                isActive = storageType == "local", 
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                onClick = { storageType = "local" }, 
                                icon = { 
                                    Icon(
                                        Lucide.HardDrive, 
                                        contentDescription = null, 
                                        tint = if (storageType == "local") AccentColor else TextMuted, 
                                        modifier = Modifier.size(18.dp)
                                    ) 
                                }
                            )
                            StorageProviderCard(
                                title = "GitHub", 
                                subtitle = "Cloud Repos", 
                                isActive = storageType == "github", 
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                onClick = { 
                                    storageType = "github"
                                    showCloneDialog = true 
                                }, 
                                icon = { 
                                    Icon(
                                        painterResource(id = R.drawable.ic_github), 
                                        contentDescription = null, 
                                        tint = if (storageType == "github") AccentColor else TextMuted, 
                                        modifier = Modifier.size(18.dp)
                                    ) 
                                }
                            )
                            StorageProviderCard(
                                title = "Import ZIP",
                                subtitle = if (isImportingZip) "Importing…" else "From device",
                                isActive = storageType == "zip",
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                onClick = {
                                    storageType = "zip"
                                    zipPickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                                },
                                icon = {
                                    if (isImportingZip) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = AccentColor, strokeWidth = 2.dp)
                                    } else {
                                        Icon(
                                            Lucide.FolderArchive,
                                            contentDescription = null,
                                            tint = if (storageType == "zip") AccentColor else TextMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            )
                        }
                    }

                    // PROJECT SEARCH BAR
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceDark)
                            .border(
                                1.dp, 
                                if (searchQuery.isNotEmpty()) AccentColor else BorderDark, 
                                RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Lucide.Search,
                                contentDescription = null,
                                tint = if (searchQuery.isNotEmpty()) AccentColor else TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Normal
                                ),
                                cursorBrush = SolidColor(AccentColor),
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Search projects...",
                                            color = TextMuted,
                                            fontSize = 14.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { searchQuery = "" },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Lucide.X,
                                        contentDescription = "Clear search",
                                        tint = TextMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (searchQuery.isBlank()) "RECENT PROJECTS" else "SEARCH RESULTS (${displayProjects.size})", 
                                fontSize = 12.sp, 
                                fontWeight = FontWeight.SemiBold, 
                                color = TextMuted, 
                                letterSpacing = 1.sp
                            )
                            if (allActiveProjects.isNotEmpty() && searchQuery.isBlank()) {
                                Text(
                                    text = "${allActiveProjects.size} total",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextMuted
                                )
                            }
                        }
                        if (displayProjects.isEmpty()) {
                            if (searchQuery.isNotBlank()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No projects matching \"$searchQuery\"",
                                        color = TextMuted,
                                        fontSize = 13.sp
                                    )
                                }
                            } else {
                                EmptyState(onCreateClicked = { showCreateDialog = true })
                            }
                        }
                    }
                }
            }

            items(displayProjects, key = { it.id }) { project ->
                val templateConfig = remember(project.template) {
                    DashboardCategories.flatMap { it.templates }.find { it.templateEnum == project.template }
                        ?: DashboardCategories.last().templates.first()
                }
                Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                    ProjectCard(
                        project = project, 
                        config = templateConfig, 
                        onClick = { onOpenProject(project.id) }, 
                        onRename = { projectToRename = project },
                        onDelete = { projectToDelete = project }
                    )
                }
            }
        }
    }

    if (showInfoDialog) {
        AppInfoDialog(onDismiss = { showInfoDialog = false })
    }

    if (showCreateDialog) {
        CreateProjectDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, template -> 
                val isAndroid = template == ProjectTemplate.ANDROID_JAVA || 
                                template == ProjectTemplate.ANDROID_KOTLIN || 
                                template == ProjectTemplate.ANDROID_COMPOSE || 
                                template == ProjectTemplate.COMPOSE
                if (isAndroid) {
                    pendingAppName = name
                    pendingAndroidTemplate = template
                    showCreateDialog = false
                    showAndroidWizard = true
                } else {
                    showCreateDialog = false 
                    scope.launch {
                        val newId = viewModel.createProjectAndGetId(
                            name = name, 
                            template = template
                        )
                        if (PackageInstaller.isPackageInstallationNeeded(template)) {
                            pendingPackageProjectId = newId
                            pendingPackageTemplate = template
                        } else {
                            onOpenProject(newId)
                        }
                    }
                }
            }
        )
    }

    // ── THE STREAMLINED SMART WIZARD ──
    if (showAndroidWizard && pendingAndroidTemplate != null) {
        AndroidProjectWizard(
            initialAppName = pendingAppName,
            initialTemplate = pendingAndroidTemplate!!,
            onDismiss = { 
                showAndroidWizard = false
                pendingAndroidTemplate = null 
            },
            onCreate = { appName, pkgName, template, minSdk, useKts ->
                showAndroidWizard = false
                pendingAndroidTemplate = null
                scope.launch {
                    val newId = viewModel.createProjectAndGetId(
                        name = appName, 
                        template = template, 
                        packageName = pkgName, 
                        minSdk = minSdk, 
                        useKts = useKts
                    )
                    if (PackageInstaller.isPackageInstallationNeeded(template)) {
                        pendingPackageProjectId = newId
                        pendingPackageTemplate = template
                    } else {
                        onOpenProject(newId)
                    }
                }
            }
        )
    }

    if (pendingPackageTemplate != null) {
        PackageInstallDialog(
            template = pendingPackageTemplate!!,
            onDismiss = { 
                val targetId = pendingPackageProjectId
                pendingPackageTemplate = null 
                pendingPackageProjectId = null
                if (targetId != null) {
                    onOpenProject(targetId)
                }
            }
        )
    }

    if (showCloneDialog) {
        var repoUrl by remember { mutableStateOf("") }
        var repoToken by remember { mutableStateOf("") }
        var showToken by remember { mutableStateOf(false) }
        var isCloning by remember { mutableStateOf(false) }
        
        Dialog(onDismissRequest = { if (!isCloning) showCloneDialog = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderDark, RoundedCornerShape(24.dp))
                    .padding(24.dp), 
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_github), 
                        contentDescription = null, 
                        tint = TextPrimary, 
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Clone Repository", 
                        color = TextPrimary, 
                        fontSize = 20.sp, 
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                Text(
                    text = "Enter a valid GitHub URL to securely download into your workspace local engine storage location.", 
                    color = TextMuted, 
                    fontSize = 13.sp
                )
                
                OutlinedTextField(
                    value = repoUrl, 
                    onValueChange = { repoUrl = it },
                    placeholder = { 
                        Text("https://github.com/user/repo", color = TextMuted) 
                    }, 
                    singleLine = true, 
                    enabled = !isCloning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BgDark, RoundedCornerShape(12.dp)),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        unfocusedBorderColor = BorderDark, 
                        focusedBorderColor = AccentColor, 
                        textColor = TextPrimary, 
                        cursorColor = AccentColor
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Personal access token (optional — required for private repos)",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                    OutlinedTextField(
                        value = repoToken,
                        onValueChange = { repoToken = it },
                        placeholder = { Text("ghp_xxxxxxxxxxxxxxxxxxxx", color = TextMuted) },
                        singleLine = true,
                        enabled = !isCloning,
                        visualTransformation = if (showToken) androidx.compose.ui.text.input.VisualTransformation.None
                                               else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showToken = !showToken }) {
                                Icon(
                                    if (showToken) Lucide.EyeOff else Lucide.Eye,
                                    contentDescription = if (showToken) "Hide token" else "Show token",
                                    tint = TextMuted
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgDark, RoundedCornerShape(12.dp)),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            unfocusedBorderColor = BorderDark,
                            focusedBorderColor = AccentColor,
                            textColor = TextPrimary,
                            cursorColor = AccentColor
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                if (isCloning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically, 
                        horizontalArrangement = Arrangement.spacedBy(12.dp), 
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp), 
                            color = AccentColor, 
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Parsing paths and downloading payload tree structural objects...", 
                            color = AccentColor, 
                            fontSize = 12.sp
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(), 
                    horizontalArrangement = Arrangement.End, 
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        enabled = !isCloning, 
                        onClick = { 
                            showCloneDialog = false
                            storageType = "local" 
                        }
                    ) { 
                        Text("Cancel", color = TextMuted) 
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = repoUrl.isNotBlank() && !isCloning,
                        onClick = {
                            scope.launch {
                                isCloning = true
                                val extractedName = repoUrl.substringAfterLast("/").substringBefore(".git").ifBlank { "cloned-repo" }
                                
                                try {
                                    val projectDir = java.io.File(StorageUtils.getProjectsDir(), extractedName)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        val cloneCommand = org.eclipse.jgit.api.Git.cloneRepository()
                                            .setURI(repoUrl)
                                            .setDirectory(projectDir)
                                        if (repoToken.isNotBlank()) {
                                            // GitHub accepts the PAT as the HTTPS password with any (or empty)
                                            // username for git-over-HTTPS auth — this is the documented pattern.
                                            cloneCommand.setCredentialsProvider(
                                                org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(repoToken, "")
                                            )
                                        }
                                        cloneCommand.call().close()
                                    }
                                    val projectId = viewModel.createProjectAndGetId(extractedName, com.sednium.kryptonide.data.model.ProjectTemplate.BLANK)
                                    val fileCount = viewModel.importDirectoryIntoProject(projectId, projectDir)
                                    Toast.makeText(context, "Cloned $fileCount files into workspace!", Toast.LENGTH_SHORT).show()
                                    onOpenProject(projectId)
                                } catch (e: org.eclipse.jgit.api.errors.TransportException) {
                                    val msg = if (repoToken.isBlank())
                                        "Clone failed — this may be a private repo. Add a personal access token above and try again."
                                    else
                                        "Clone failed: ${e.message ?: "check the URL and token"}"
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Clone failed: ${e.message}", Toast.LENGTH_LONG).show()
                                } finally {
                                    isCloning = false
                                    showCloneDialog = false
                                    storageType = "local"
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentColor), 
                        shape = RoundedCornerShape(12.dp)
                    ) { 
                        Text("Clone Engine", color = OnAccentColor) 
                    }
                }
            }
        }
    }

    projectToDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { projectToDelete = null },
            title = { 
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Lucide.Trash2, 
                        contentDescription = null, 
                        tint = LocalKryptonColors.current.Error, 
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Delete Project", color = TextPrimary)
                } 
            },
            text = { 
                Text("Are you sure you want to delete \"${p.name}\"? This action cannot be undone.", color = TextMuted) 
            },
            containerColor = SurfaceDark,
            confirmButton = { 
                Button(
                    onClick = { 
                        viewModel.deleteProject(p.id)
                        projectToDelete = null 
                    }, 
                    colors = ButtonDefaults.buttonColors(containerColor = LocalKryptonColors.current.Error)
                ) { 
                    Text("Delete", color = Color.White) 
                } 
            },
            dismissButton = { 
                TextButton(onClick = { projectToDelete = null }) { 
                    Text("Cancel", color = TextMuted) 
                } 
            },
        )
    }

    projectToRename?.let { p ->
        RenameProjectDialog(
            project = p,
            onDismiss = { projectToRename = null },
            onRename = { newName ->
                viewModel.renameProject(p.id, newName)
            }
        )
    }
}

// ─── UI COMPONENTS ────────────────────────────────────────────────────────

@Composable
fun JumpBackInHeroCard(
    project: Project?,
    onOpenProject: (String) -> Unit,
    onCreateClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (project != null) {
        val templateConfig = remember(project.template) {
            DashboardCategories.flatMap { it.templates }.find { it.templateEnum == project.template }
                ?: DashboardCategories.last().templates.first()
        }
        val relativeTime = remember(project.updatedAt) {
            val diff = System.currentTimeMillis() - project.updatedAt
            when {
                diff < 60_000L -> "Just now"
                diff < 3600_000L -> "${diff / 60_000L}m ago"
                diff < 86400_000L -> "${diff / 3600_000L}h ago"
                diff < 604800_000L -> "${diff / 86400_000L}d ago"
                else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(project.updatedAt))
            }
        }

        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(SurfaceDark)
                .border(1.dp, BorderDark, RoundedCornerShape(18.dp))
                .bounceClick { onOpenProject(project.id) }
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Top row: "CONTINUE WORKING" tag + template badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(LocalKryptonColors.current.Success)
                        )
                        Text(
                            text = "CONTINUE WORKING",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            letterSpacing = 1.sp
                        )
                    }

                    // Template badge
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(templateConfig.color.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = templateConfig.iconRes),
                            contentDescription = null,
                            tint = templateConfig.color,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = templateConfig.id,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = templateConfig.color
                        )
                    }
                }

                // Middle & Bottom row: Project title, last updated, and "Resume" button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = project.name,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Last edited $relativeTime",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextMuted
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    // Resume button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(AccentColor)
                            .clickable { onOpenProject(project.id) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Resume",
                                color = OnAccentColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Icon(
                                Lucide.ArrowRight,
                                contentDescription = null,
                                tint = OnAccentColor,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    } else {
        // Welcoming card when zero projects exist
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(SurfaceDark)
                .border(1.dp, BorderDark, RoundedCornerShape(18.dp))
                .bounceClick { onCreateClicked() }
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(AccentColor.copy(alpha = 0.15f))
                            .padding(10.dp)
                    ) {
                        Icon(
                            Lucide.Code,
                            contentDescription = null,
                            tint = AccentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Welcome to Krypton",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Tap to create your first project",
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    }
                }
                Icon(
                    Lucide.Plus,
                    contentDescription = null,
                    tint = AccentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun StorageProviderCard(
    title: String, 
    subtitle: String, 
    isActive: Boolean, 
    modifier: Modifier = Modifier, 
    onClick: () -> Unit, 
    icon: @Composable () -> Unit
) {
    val bgColor = if (isActive) AccentColor.copy(alpha = 0.1f) else SurfaceDark
    val borderColor = if (isActive) AccentColor else BorderDark
    
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .bounceClick(onClick = onClick)
            .padding(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isActive) AccentColor.copy(alpha = 0.2f) else BgDark)
                    .padding(8.dp)
            ) { 
                icon() 
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = title, 
                color = if (isActive) AccentColor else TextPrimary, 
                fontSize = 13.sp, 
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle, 
                color = TextMuted, 
                fontSize = 11.sp, 
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EmptyState(onCreateClicked: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f, 
        targetValue = 1.05f, 
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing), 
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp), 
        horizontalAlignment = Alignment.CenterHorizontally, 
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier.size(80.dp), 
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceDark)
                    .border(2.dp, BorderDark, RoundedCornerShape(16.dp)), 
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Lucide.Code, 
                    contentDescription = null, 
                    tint = TextMuted, 
                    modifier = Modifier.size(32.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .rotate(-12f)
                    .scale(pulseScale)
                    .clip(RoundedCornerShape(16.dp))
                    .background(BgDark)
                    .border(2.dp, AccentColor, RoundedCornerShape(16.dp)), 
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Lucide.Star, 
                    contentDescription = null, 
                    tint = AccentLight, 
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Text(
            text = "No projects yet", 
            color = TextPrimary, 
            fontSize = 18.sp, 
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Your workspace is empty. Create a new project to start building.", 
            color = TextMuted, 
            fontSize = 14.sp, 
            textAlign = TextAlign.Center, 
            modifier = Modifier.width(250.dp)
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(SurfaceDark)
                .border(1.dp, BorderDark, RoundedCornerShape(50))
                .bounceClick(onClick = onCreateClicked)
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Create your first project", 
                color = TextPrimary, 
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun ProjectCard(
    project: Project, 
    config: TemplateItem, 
    onClick: () -> Unit, 
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val view = LocalView.current
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            .bounceClick(onClick = onClick)
            .height(IntrinsicSize.Min)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(6.dp)
                .background(config.color.copy(alpha = 0.8f))
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically, 
                horizontalArrangement = Arrangement.SpaceBetween, 
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = project.name, 
                    color = TextPrimary, 
                    fontSize = 18.sp, 
                    fontWeight = FontWeight.SemiBold, 
                    maxLines = 1, 
                    overflow = TextOverflow.Ellipsis, 
                    modifier = Modifier.weight(1f)
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgDark)
                        .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { /* swallow touches so parent bounceClick is not triggered */ }
                        .padding(horizontal = 4.dp, vertical = 2.dp), 
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button) {
                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                onClick()
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Open", 
                            color = AccentLight, 
                            fontSize = 12.sp, 
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .height(16.dp)
                            .width(1.dp)
                            .background(BorderDark)
                    )
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button) {
                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                onRename()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Lucide.Pencil, 
                            contentDescription = "Rename project", 
                            tint = TextMuted, 
                            modifier = Modifier.size(14.dp)
                        ) 
                    }
                    Box(
                        modifier = Modifier
                            .height(16.dp)
                            .width(1.dp)
                            .background(BorderDark)
                    )
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button) {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                onDelete()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Lucide.Trash2, 
                            contentDescription = "Delete project", 
                            tint = TextMuted, 
                            modifier = Modifier.size(14.dp)
                        ) 
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectStatBubble(
                    label = "TYPE", 
                    value = config.id, // FIX: config.id instead of config.name
                    valueColor = AccentLight
                )
                ProjectStatBubble(
                    label = "MODIFIED", 
                    value = fmt.format(Date(project.updatedAt)), 
                    valueColor = TextPrimary
                )
            }
        }
    }
}

@Composable
fun RenameProjectDialog(
    project: Project,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var newName by remember(project) { mutableStateOf(project.name) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val view = LocalView.current

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(SurfaceDark)
                .border(1.dp, BorderDark, RoundedCornerShape(24.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Lucide.Pencil,
                    contentDescription = null,
                    tint = AccentLight,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Rename Project",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "NEW PROJECT NAME",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = {
                        newName = it
                        errorMessage = null
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentLight,
                        unfocusedBorderColor = BorderDark,
                        cursorColor = AccentLight,
                        focusedContainerColor = BgDark,
                        unfocusedContainerColor = BgDark
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = Color(0xFFEF4444),
                        fontSize = 11.sp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = TextMuted, fontSize = 14.sp)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val trimmed = newName.trim()
                        val excluded = com.sednium.kryptonide.data.repository.ProjectRepository.KRYPTON_INTERNAL_DIRS
                        when {
                            trimmed.isBlank() -> {
                                errorMessage = "Project name cannot be empty"
                            }
                            trimmed.startsWith(".") -> {
                                errorMessage = "Project name cannot start with a dot"
                            }
                            trimmed.lowercase() in excluded -> {
                                errorMessage = "\"$trimmed\" is a reserved system directory name"
                            }
                            trimmed == project.name -> {
                                onDismiss()
                            }
                            else -> {
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                onRename(trimmed)
                                onDismiss()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Rename", color = OnAccentColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun RowScope.ProjectStatBubble(
    label: String, 
    value: String, 
    valueColor: Color
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(BgDark)
            .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Text(
            text = label, 
            color = TextMuted, 
            fontSize = 10.sp, 
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value, 
            color = valueColor, 
            fontSize = 12.sp, 
            fontWeight = FontWeight.SemiBold, 
            maxLines = 1, 
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ─── DIALOGS ──────────────────────────────────────────────────────────────

@Composable
fun AppInfoDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val view = LocalView.current
    val context = view.context
    val coroutineScope = rememberCoroutineScope()
    var isCheckingOta by remember { mutableStateOf(false) }
    var otaReleaseToPrompt by remember { mutableStateOf<com.sednium.kryptonide.ota.OtaReleaseInfo?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(SurfaceDark)
                .border(1.dp, BorderDark, RoundedCornerShape(24.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.icon),
                contentDescription = "App Icon",
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .border(1.dp, BorderDark, CircleShape)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Kryptonide", 
                color = TextPrimary, 
                fontSize = 22.sp, 
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Write Anything, Build Anywhere", 
                color = AccentColor, 
                fontSize = 14.sp, 
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Sednium Mobile Engine", 
                color = AccentLight, 
                fontSize = 13.sp, 
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.height(24.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoRow("Company", "Sednium Technologies", Lucide.Building2) { 
                    uriHandler.openUri("https://sednium.com") 
                }
                InfoRow("Website", "kryptonide.sednium.com", Lucide.Globe) { 
                    uriHandler.openUri("https://kryptonide.sednium.com") 
                }
                InfoRow("GitHub", "kryptonide-native", painter = painterResource(id = R.drawable.ic_github)) { 
                    uriHandler.openUri("https://github.com/Sednium-Technologies/kryptonide-native") 
                }
                InfoRow("Issues", "Report Bug / Feedback", Lucide.CircleDot) { 
                    uriHandler.openUri("https://github.com/Sednium-Technologies/kryptonide-native/issues") 
                }
                InfoRow("Research", "Docs & Specs", Lucide.BookOpen) { 
                    uriHandler.openUri("https://www.sednium.com/kryptonide") 
                }
                InfoRow("Storage", "storage/emulated/0/Kryptonide", Lucide.Folder, isLink = false) {}
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (isCheckingOta) return@Button
                    isCheckingOta = true
                    coroutineScope.launch {
                        val result = com.sednium.kryptonide.ota.OtaUpdateManager.checkUpdateStatus()
                        isCheckingOta = false
                        when (result) {
                            is com.sednium.kryptonide.ota.OtaCheckResult.UpdateAvailable -> {
                                otaReleaseToPrompt = result.release
                            }
                            is com.sednium.kryptonide.ota.OtaCheckResult.UpToDate -> {
                                Toast.makeText(context, "Kryptonide is up to date (v${com.sednium.kryptonide.ota.OtaUpdateManager.getCurrentAppVersion()})", Toast.LENGTH_SHORT).show()
                            }
                            is com.sednium.kryptonide.ota.OtaCheckResult.Error -> {
                                Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isCheckingOta) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = OnAccentColor,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Checking GitHub...", 
                        color = OnAccentColor, 
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Icon(
                        Lucide.RefreshCw, 
                        contentDescription = null, 
                        modifier = Modifier.size(18.dp), 
                        tint = OnAccentColor
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Check for updates", 
                        color = OnAccentColor, 
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    if (otaReleaseToPrompt != null) {
        com.sednium.kryptonide.ota.OtaUpdateDialog(
            release = otaReleaseToPrompt!!,
            onDismiss = { otaReleaseToPrompt = null }
        )
    }
}

@Composable
fun InfoRow(
    label: String, 
    value: String, 
    icon: ImageVector? = null, 
    painter: androidx.compose.ui.graphics.painter.Painter? = null,
    isLink: Boolean = true, 
    onClick: () -> Unit
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgDark)
            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
            .clickable(enabled = isLink) {
                if (isLink) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                }
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceDark), 
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Icon(
                    icon, 
                    contentDescription = null, 
                    tint = TextMuted, 
                    modifier = Modifier.size(16.dp)
                )
            } else if (painter != null) {
                Icon(
                    painter, 
                    contentDescription = null, 
                    tint = TextMuted, 
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label, 
                color = TextMuted, 
                fontSize = 11.sp, 
                fontWeight = FontWeight.Bold
            )
            Text(
                text = value, 
                color = if (isLink) AccentColor else TextPrimary, 
                fontSize = 13.sp, 
                fontWeight = FontWeight.Medium, 
                maxLines = 1, 
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isLink) {
            Icon(
                Lucide.ExternalLink, 
                contentDescription = null, 
                tint = AccentColor, 
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
fun CreateProjectDialog(
    onDismiss: () -> Unit, 
    onCreate: (String, ProjectTemplate) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedTemplate by remember { mutableStateOf(DashboardCategories.first().templates.first()) }
    val view = LocalView.current

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(SurfaceDark)
                .border(1.dp, BorderDark, RoundedCornerShape(24.dp))
                .padding(24.dp), 
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Lucide.Folder, 
                    contentDescription = null, 
                    tint = AccentLight, 
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Create Project", 
                    color = TextPrimary, 
                    fontSize = 20.sp, 
                    fontWeight = FontWeight.SemiBold
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "PROJECT NAME", 
                    color = TextMuted, 
                    fontSize = 12.sp, 
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = name, 
                    onValueChange = { name = it }, 
                    placeholder = { 
                        val defaultSlug = selectedTemplate.id.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                        Text("my-${defaultSlug}-app", color = TextMuted) 
                    }, 
                    singleLine = true, 
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BgDark, RoundedCornerShape(12.dp)), 
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        unfocusedBorderColor = BorderDark, 
                        focusedBorderColor = AccentColor, 
                        textColor = TextPrimary, 
                        cursorColor = AccentColor
                    ), 
                    shape = RoundedCornerShape(12.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "TEMPLATE", 
                    color = TextMuted, 
                    fontSize = 12.sp, 
                    fontWeight = FontWeight.Bold
                )
                TemplateSelectionList(
                    selectedTemplate = selectedTemplate, 
                    onTemplateSelected = { selectedTemplate = it }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(), 
                horizontalArrangement = Arrangement.End, 
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { 
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onDismiss() 
                    }
                ) { 
                    Text("Cancel", color = TextMuted) 
                }
                Spacer(Modifier.width(8.dp))
                
                val isAndroid = selectedTemplate.templateEnum == ProjectTemplate.ANDROID_JAVA || 
                                selectedTemplate.templateEnum == ProjectTemplate.ANDROID_KOTLIN || 
                                selectedTemplate.templateEnum == ProjectTemplate.ANDROID_COMPOSE || 
                                selectedTemplate.templateEnum == ProjectTemplate.COMPOSE
                                
                Button(
                    onClick = { 
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        val defaultSlug = selectedTemplate.id.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                        val finalName = name.trim().ifBlank { "my-${defaultSlug}-app" }
                        onCreate(finalName, selectedTemplate.templateEnum) 
                    }, 
                    colors = ButtonDefaults.buttonColors(containerColor = AccentColor), 
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isAndroid) "Next" else "Create & Open", 
                        color = OnAccentColor
                    )
                }
            }
        }
    }
}

@Composable
fun TemplateSelectionList(
    selectedTemplate: TemplateItem, 
    onTemplateSelected: (TemplateItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BgDark)
            .border(1.dp, BorderDark, RoundedCornerShape(12.dp))
    ) {
        DashboardCategories.forEach { category ->
            item(key = category.name) {
                Text(
                    text = category.name, 
                    color = TextMuted, 
                    fontSize = 12.sp, 
                    fontWeight = FontWeight.Bold, 
                    modifier = Modifier
                        .background(BgDark)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(category.templates, key = { it.id }) { t ->
                TemplateItemRow(
                    t = t, 
                    isSelected = selectedTemplate == t, 
                    onClick = { onTemplateSelected(t) }
                )
            }
        }
    }
}

@Composable
fun TemplateItemRow(
    t: TemplateItem, 
    isSelected: Boolean, 
    onClick: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .bounceClick(onClick = onClick)
                .background(if (isSelected) AccentColor.copy(alpha = 0.1f) else Color.Transparent)
                .padding(16.dp), 
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) t.color.copy(alpha = 0.2f) else SurfaceDark)
                    .border(1.dp, if (isSelected) Color.Transparent else BorderDark, RoundedCornerShape(8.dp)), 
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = t.iconRes), 
                    contentDescription = null, 
                    tint = Color.Unspecified, 
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = t.id, 
                    color = if (isSelected) AccentColor else TextPrimary, 
                    fontSize = 14.sp, 
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = t.desc, 
                    color = if (isSelected) AccentLight else TextMuted, 
                    fontSize = 11.sp
                )
            }
        }
        HorizontalDivider(color = BorderDark, thickness = 1.dp)
    }
}

// ─── STREAMLINED ANDROID PROJECT WIZARD ─────────
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AndroidProjectWizard(
    initialAppName: String,
    initialTemplate: ProjectTemplate,
    onDismiss: () -> Unit,
    onCreate: (appName: String, packageName: String, template: ProjectTemplate, minSdk: Int, useKts: Boolean) -> Unit
) {
    var appName by remember { mutableStateOf(initialAppName) }
    var customPackage by remember { mutableStateOf("") }
    var userModifiedPackage by remember { mutableStateOf(false) }
    
    val displayPackage = if (userModifiedPackage) customPackage else {
        val sanitized = appName.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (sanitized.isEmpty()) "com.example.myapplication" else "com.example.$sanitized"
    }

    // A valid Android/JVM package name: at least two dot-separated segments, each starting
    // with a lowercase letter and containing only lowercase letters/digits/underscore — so
    // this also rejects a trailing "." (an incomplete segment) and consecutive dots.
    val packageNameRegex = remember { Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$") }
    val isPackageValid = packageNameRegex.matches(displayPackage)

    var useKotlinDsl by remember { mutableStateOf(true) }
    
    val sdkList = listOf(
        21 to "API 21: Android 5.0 (Lollipop)",
        22 to "API 22: Android 5.1 (Lollipop)",
        23 to "API 23: Android 6.0 (Marshmallow)",
        24 to "API 24: Android 7.0 (Nougat)",
        26 to "API 26: Android 8.0 (Oreo)",
        28 to "API 28: Android 9.0 (Pie)",
        30 to "API 30: Android 11.0 (R)",
        33 to "API 33: Android 13.0 (Tiramisu)",
        34 to "API 34: Android 14.0 (UpsideDownCake)"
    )
    var selectedSdk by remember { mutableStateOf(sdkList.find { it.first == 24 }!!) }
    var sdkDropdownExpanded by remember { mutableStateOf(false) }

    val templateName = when(initialTemplate) {
        ProjectTemplate.ANDROID_JAVA -> "Android (Java)"
        ProjectTemplate.ANDROID_COMPOSE, ProjectTemplate.COMPOSE -> "Android (Jetpack Compose)"
        else -> "Android (Kotlin)"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Android Configuration", 
                    color = TextPrimary, 
                    fontSize = 20.sp, 
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(24.dp))

                // Shows the user what they picked from the previous screen
                OutlinedTextField(
                    value = templateName, 
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Selected Template", color = AccentLight) },
                    leadingIcon = { Icon(Lucide.Code, null, tint = AccentLight) },
                    singleLine = true, 
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceDark),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        textColor = TextPrimary,
                        disabledTextColor = TextPrimary,
                        focusedBorderColor = AccentColor,
                        unfocusedBorderColor = BorderDark
                    )
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = appName, 
                    onValueChange = { appName = it },
                    label = { Text("App name", color = TextMuted) },
                    leadingIcon = { Icon(Lucide.Smartphone, null, tint = TextMuted) },
                    singleLine = true, 
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        textColor = TextPrimary, 
                        focusedBorderColor = AccentColor,
                        unfocusedBorderColor = BorderDark
                    )
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = displayPackage, 
                    onValueChange = { 
                        customPackage = it
                        userModifiedPackage = true 
                    },
                    label = { Text("Package name", color = TextMuted) },
                    leadingIcon = { Icon(Lucide.LayoutGrid, null, tint = TextMuted) },
                    singleLine = true, 
                    isError = !isPackageValid,
                    supportingText = {
                        if (!isPackageValid) {
                            Text(
                                "Must be at least two lowercase segments separated by dots, " +
                                "e.g. com.example.myapp — no spaces, symbols, or trailing dot.",
                                color = LocalKryptonColors.current.Error,
                                fontSize = 11.sp
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        textColor = TextPrimary, 
                        focusedBorderColor = AccentColor,
                        unfocusedBorderColor = BorderDark,
                        errorBorderColor = LocalKryptonColors.current.Error,
                    )
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = "/storage/emulated/0/Kryptonide",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Save location", color = TextMuted) },
                    singleLine = true, 
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        textColor = TextPrimary, 
                        focusedBorderColor = BorderDark,
                        unfocusedBorderColor = BorderDark,
                        disabledTextColor = TextPrimary
                    )
                )
                Spacer(Modifier.height(20.dp))

                ExposedDropdownMenuBox(
                    expanded = sdkDropdownExpanded, 
                    onExpandedChange = { sdkDropdownExpanded = !sdkDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedSdk.second, 
                        onValueChange = {}, 
                        readOnly = true,
                        label = { Text("Minimum SDK", color = TextPrimary) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sdkDropdownExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            textColor = TextPrimary, 
                            focusedBorderColor = AccentColor,
                            unfocusedBorderColor = BorderDark
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = sdkDropdownExpanded, 
                        onDismissRequest = { sdkDropdownExpanded = false }, 
                        modifier = Modifier.background(SurfaceDark)
                    ) {
                        sdkList.forEach { sdk -> 
                            DropdownMenuItem(
                                text = { Text(sdk.second, color = TextPrimary) }, 
                                onClick = { 
                                    selectedSdk = sdk
                                    sdkDropdownExpanded = false 
                                }
                            ) 
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically, 
                    modifier = Modifier.clickable { useKotlinDsl = !useKotlinDsl }
                ) {
                    Checkbox(
                        checked = useKotlinDsl, 
                        onCheckedChange = null, 
                        colors = CheckboxDefaults.colors(checkedColor = AccentColor)
                    )
                    Text(
                        text = "Use Kotlin Script (.kts) for Gradle files", 
                        color = TextPrimary, 
                        fontSize = 14.sp, 
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Spacer(Modifier.height(32.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(), 
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentLight),
                        border = BorderStroke(1.dp, BorderDark)
                    ) { 
                        Text("Previous") 
                    }
                    Button(
                        enabled = appName.isNotBlank() && isPackageValid,
                        onClick = {
                            onCreate(
                                appName.ifBlank { "My Application" }, 
                                displayPackage, 
                                initialTemplate, 
                                selectedSdk.first, 
                                useKotlinDsl
                            )
                        }, 
                        colors = ButtonDefaults.buttonColors(containerColor = AccentColor)
                    ) { 
                        Text("Finish", color = OnAccentColor) 
                    }
                }
            }
        }
    }
}
