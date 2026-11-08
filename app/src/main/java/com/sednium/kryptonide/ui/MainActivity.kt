package com.sednium.kryptonide.ui

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import com.sednium.kryptonide.ui.splash.KryptonSplashScreen
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import com.sednium.kryptonide.data.model.ProjectTemplate
import com.sednium.kryptonide.ui.ai.AiViewModel
import com.sednium.kryptonide.ui.editor.EditorScreen
import com.sednium.kryptonide.ui.editor.EditorViewModel
import com.sednium.kryptonide.ui.git.GitViewModel
import com.sednium.kryptonide.ui.plugins.PluginManager
import com.sednium.kryptonide.ui.onboarding.PermissionOnboardingScreen
import com.sednium.kryptonide.ui.projects.ProjectsDashboard
import com.sednium.kryptonide.ui.projects.ProjectsViewModel
import com.sednium.kryptonide.ui.settings.SettingsViewModel
import com.sednium.kryptonide.ui.settings.FontManager
import com.sednium.kryptonide.ui.terminal.TerminalViewModel
import com.sednium.kryptonide.ui.theme.KryptonTheme
import com.sednium.kryptonide.ui.theme.LocalKryptonColors
import java.io.File
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sednium.kryptonide.utils.StorageUtils

class MainActivity : ComponentActivity() {

    private val projectsVm:  ProjectsViewModel  by viewModels()
    private val editorVm:    EditorViewModel    by viewModels()
    private val aiVm:        AiViewModel        by viewModels()
    private val gitVm:       GitViewModel       by viewModels()
    private val settingsVm:  SettingsViewModel  by viewModels()
    private val terminalVm:  TerminalViewModel  by viewModels()
    private val pluginManager: PluginManager    by viewModels()
    private val fontManager:   FontManager      by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("krypton_crash", android.content.Context.MODE_PRIVATE)
        val lastCrash = prefs.getString("last_crash", null)

        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            getSharedPreferences("krypton_crash", android.content.Context.MODE_PRIVATE)
                .edit().putString("last_crash", sw.toString()).commit()
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(1)
        }

        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        com.sednium.kryptonide.agent.pocket.LocalGgufServerManager.appContext = applicationContext
        com.sednium.kryptonide.agent.notifications.AiNotificationHelper.createNotificationChannel(this)

        handleNavigationIntent(intent)

        setContent {
            if (lastCrash != null) {
                var showCrash by remember { mutableStateOf(true) }
                if (showCrash) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                            Text("Oops! The app crashed last time:", color = androidx.compose.ui.graphics.Color.Red)
                            Button(onClick = {
                                prefs.edit().remove("last_crash").apply()
                                showCrash = false
                            }) { Text("Clear Crash and Continue") }
                            SelectionContainer {
                                Text(lastCrash, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    return@setContent
                }
            }

            // Observe the theme mode preference from SettingsViewModel.
            val settingsState by settingsVm.state.collectAsState()
            val themeMode = settingsState.themeMode
            val useDynamicColor = settingsState.useDynamicColor
            
            val isDark = when (themeMode) {
                1 -> false
                2 -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            
            KryptonTheme(isDark = isDark, useDynamicColor = useDynamicColor) {
                val colors = LocalKryptonColors.current
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color    = colors.Background
                ) {
                    var showBootSplash by remember { mutableStateOf(true) }

                    Box(modifier = Modifier.fillMaxSize()) {
                        KryptonApp(
                            projectsVm    = projectsVm,
                            editorVm      = editorVm,
                            aiVm          = aiVm,
                            gitVm         = gitVm,
                            settingsVm    = settingsVm,
                            terminalVm    = terminalVm,
                            pluginManager = pluginManager,
                            fontManager   = fontManager,
                            isDark        = isDark
                        )

                        if (showBootSplash) {
                            KryptonSplashScreen(
                                isDark = isDark,
                                minDurationMs = 2000L,
                                onFinished = {
                                    showBootSplash = false
                                    val onboardingPrefs = getSharedPreferences("krypton_onboarding", android.content.Context.MODE_PRIVATE)
                                    val isComplete = onboardingPrefs.getBoolean("onboarding_complete", false)
                                    if (isComplete) {
                                        requestNotificationPermissionIfNeeded()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        com.sednium.kryptonide.utils.AppLifecycleTracker.onActivityStarted()
        com.sednium.kryptonide.agent.notifications.AiNotificationHelper.cancelProgressNotification(this)
    }

    override fun onStop() {
        super.onStop()
        com.sednium.kryptonide.utils.AppLifecycleTracker.onActivityStopped()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun handleNavigationIntent(intent: Intent?) {
        val target = intent?.getStringExtra("navigate_to")
        if (!target.isNullOrBlank()) {
            com.sednium.kryptonide.utils.NavigationBus.navigate(target)
        }
    }
}

@Composable
fun KryptonApp(
    projectsVm:    ProjectsViewModel,
    editorVm:      EditorViewModel,
    aiVm:          AiViewModel,
    gitVm:         GitViewModel,
    settingsVm:    SettingsViewModel,
    terminalVm:    TerminalViewModel,
    pluginManager: PluginManager,
    fontManager:   FontManager = viewModel(),
    isDark:        Boolean = true
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val onboardingPrefs = remember { context.getSharedPreferences("krypton_onboarding", android.content.Context.MODE_PRIVATE) }
    var isOnboardingComplete by remember {
        mutableStateOf(
            onboardingPrefs.getBoolean("onboarding_complete", false) &&
            (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R || StorageUtils.isManagerSafe())
        )
    }

    if (!isOnboardingComplete) {
        com.sednium.kryptonide.ui.onboarding.OnboardingScreen(
            onFinished = {
                onboardingPrefs.edit().putBoolean("onboarding_complete", true).apply()
                isOnboardingComplete = true
                (context as? MainActivity)?.requestNotificationPermissionIfNeeded()
            }
        )
        return
    }

    val projectsState  by projectsVm.state.collectAsState()
    val currentProjectId = projectsState.currentProjectId

    var postUpgradeNotes by remember { mutableStateOf<String?>(null) }
    var availableOtaRelease by remember { mutableStateOf<com.sednium.kryptonide.ota.OtaReleaseInfo?>(null) }

    LaunchedEffect(Unit) {
        val notes = com.sednium.kryptonide.ota.OtaUpdateManager.checkPostUpgrade(context)
        if (notes != null) {
            postUpgradeNotes = notes
        } else {
            // Background silent check for updates on startup
            kotlinx.coroutines.delay(3500L)
            val result = com.sednium.kryptonide.ota.OtaUpdateManager.checkUpdateStatus()
            if (result is com.sednium.kryptonide.ota.OtaCheckResult.UpdateAvailable) {
                availableOtaRelease = result.release
            }
        }
    }

    LaunchedEffect(currentProjectId) {
        if (currentProjectId != null) {
            editorVm.loadProject(currentProjectId)
            aiVm.loadForProject(currentProjectId)
            val project = projectsState.projects.firstOrNull { it.id == currentProjectId }
            gitVm.init(editorVm, currentProjectId, project?.githubRepo)
            val pName     = project?.name ?: currentProjectId
            terminalVm.init(pName)
        } else {
            editorVm.unloadProject()
            aiVm.clearSessions()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (currentProjectId == null) {
            ProjectsDashboard(
                viewModel     = projectsVm,
                onOpenProject = {
                    projectsVm.openProject(it)
                }
            )
        } else {
            val currentProject = projectsState.projects.firstOrNull { it.id == currentProjectId }
            EditorScreen(
                editorVm      = editorVm,
                renderEngine  = viewModel(),
                fontManager   = fontManager,
                aiVm          = aiVm,
                gitVm         = gitVm,
                settingsVm    = settingsVm,
                pluginManager = pluginManager,
                projectName   = currentProject?.name ?: "Sednium Workspace",
                projectDir    = currentProject?.path?.takeIf { it.isNotBlank() }
                                    ?: "/sdcard/Kryptonide/${currentProject?.name ?: ""}",
                template      = currentProject?.template ?: ProjectTemplate.BLANK,
                onBack        = { projectsVm.closeProject() }
            )
        }

        if (postUpgradeNotes != null) {
            com.sednium.kryptonide.ota.WelcomeBackDialog(
                versionName = com.sednium.kryptonide.ota.OtaUpdateManager.getCurrentAppVersion(),
                releaseNotes = postUpgradeNotes ?: "",
                onDismiss = {
                    com.sednium.kryptonide.ota.OtaUpdateManager.dismissPostUpgrade(context)
                    postUpgradeNotes = null
                }
            )
        }

        if (availableOtaRelease != null) {
            com.sednium.kryptonide.ota.OtaUpdateDialog(
                release = availableOtaRelease!!,
                onDismiss = { availableOtaRelease = null }
            )
        }
    }
}
