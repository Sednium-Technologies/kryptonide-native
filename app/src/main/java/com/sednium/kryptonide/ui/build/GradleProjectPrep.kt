package com.sednium.kryptonide.ui.build

import android.content.Context
import java.io.File

/**
 * KryptonIDE's Android project templates (ProjectRepository.kt) generate a
 * minimal build.gradle(.kts) / settings.gradle(.kts) but never a Gradle
 * wrapper, and never declare `pluginManagement { repositories { ... } }` —
 * so a project fresh out of the template picker can't actually be built by
 * Gradle yet: there's no `gradlew`, and even a global `gradle` binary would
 * fail to resolve the AGP/Kotlin plugins with "Plugin not found".
 *
 * This object patches both gaps just-in-time, right before the first build,
 * rather than changing what gets written at project-creation time — that
 * way it also fixes projects that were already created before this existed,
 * or imported from GitHub without a wrapper.
 */
object GradleProjectPrep {

    private const val WRAPPER_ASSET_DIR = "gradle-wrapper-template"

    /** Returns true if a wrapper is present after this call (existing or freshly installed). */
    fun ensureWrapper(context: Context, root: File, log: (String) -> Unit): Boolean {
        val gradlew = File(root, "gradlew")
        if (gradlew.exists()) {
            gradlew.setExecutable(true)
            return true
        }

        log("No Gradle wrapper found — installing bundled wrapper (Gradle 8.7)…")
        return runCatching {
            copyAsset(context, "$WRAPPER_ASSET_DIR/gradlew", File(root, "gradlew"))
            copyAsset(context, "$WRAPPER_ASSET_DIR/gradlew.bat", File(root, "gradlew.bat"))
            copyAsset(
                context,
                "$WRAPPER_ASSET_DIR/gradle/wrapper/gradle-wrapper.properties",
                File(root, "gradle/wrapper/gradle-wrapper.properties")
            )
            copyAsset(
                context,
                "$WRAPPER_ASSET_DIR/gradle/wrapper/gradle-wrapper.jar",
                File(root, "gradle/wrapper/gradle-wrapper.jar")
            )
            File(root, "gradlew").setExecutable(true)
            log("Wrapper installed.")
            true
        }.getOrElse { e ->
            log("✗ Failed to install Gradle wrapper: ${e.message}")
            false
        }
    }

    private fun copyAsset(context: Context, assetPath: String, dest: File) {
        dest.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /**
     * Ensures the root `settings.gradle(.kts)` declares repositories for
     * plugin + dependency resolution. If a `pluginManagement`/
     * `dependencyResolutionManagement` block already exists we leave the
     * file untouched (respect whatever the person configured).
     */
    fun ensureRepositories(root: File, log: (String) -> Unit) {
        val kts = File(root, "settings.gradle.kts")
        val groovy = File(root, "settings.gradle")
        val target = when {
            kts.exists()    -> kts
            groovy.exists() -> groovy
            else            -> return
        }

        val text = runCatching { target.readText() }.getOrNull() ?: return
        val patched = patchRepositoriesText(text, isKts = target == kts) ?: return
        log("Patching ${target.name} — adding plugin/dependency repositories…")
        runCatching { target.writeText(patched) }
            .onFailure { e -> log("✗ Failed to patch ${target.name}: ${e.message}") }
    }

    /**
     * Pure text-transform version of the above, for callers that hold
     * settings.gradle(.kts) content in memory rather than as a real on-disk
     * File — specifically GitViewModel.push(), which sources what it pushes
     * from the editor's in-memory FileNode list, not a live disk scan.
     * Returns null if no patch is needed (a pluginManagement block already
     * exists) or applicable (blank text).
     */
    fun patchRepositoriesText(text: String, isKts: Boolean): String? {
        if (text.contains("pluginManagement")) return null
        val prelude = if (isKts) KTS_REPOS_PRELUDE else GROOVY_REPOS_PRELUDE
        return prelude + text
    }

    private val KTS_REPOS_PRELUDE = """
        pluginManagement {
            repositories {
                google()
                mavenCentral()
                gradlePluginPortal()
            }
        }
        dependencyResolutionManagement {
            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            repositories {
                google()
                mavenCentral()
            }
        }

    """.trimIndent() + "\n\n"

    private val GROOVY_REPOS_PRELUDE = """
        pluginManagement {
            repositories {
                google()
                mavenCentral()
                gradlePluginPortal()
            }
        }
        dependencyResolutionManagement {
            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            repositories {
                google()
                mavenCentral()
            }
        }

    """.trimIndent() + "\n\n"

    /** Writes/updates local.properties with sdk.dir so AGP finds the SDK without relying on env vars alone. */
    fun ensureLocalProperties(root: File, sdkHome: String?, log: (String) -> Unit) {
        if (sdkHome == null) return
        val file = File(root, "local.properties")
        val existing = if (file.exists()) runCatching { file.readText() }.getOrDefault("") else ""
        if (existing.contains("sdk.dir=")) return
        runCatching {
            file.appendText((if (existing.isNotEmpty() && !existing.endsWith("\n")) "\n" else "") + "sdk.dir=$sdkHome\n")
            log("local.properties → sdk.dir=$sdkHome")
        }.onFailure { e -> log("✗ Failed to write local.properties: ${e.message}") }
    }
}
