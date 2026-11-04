package com.sednium.kryptonide.ui.build

import android.content.Context
import java.io.File

/**
 * Detects an on-device JDK + Android SDK + POSIX shell toolchain that a real
 * Gradle build can run against.
 *
 * Sandboxes, tried in this order:
 *  - AndroidIDE (`com.itsaky.androidide`) — if already installed, reuse its
 *    JDK + Android SDK + shell directly.
 *  - AndroidIDE-Rv2 (`com.tom.rv2ide`) — a separate fork/app; same idea.
 *  - Termux (`com.termux`) — same idea, if installed instead.
 *
 * All three are Termux-lineage sandboxes built with *their own* package id
 * baked in at bootstrap-build time, so their binaries are already
 * executable in place and exec-rerouting for compiled tools (not just shell
 * scripts) already works correctly — no special handling needed to launch
 * them.
 *
 * KryptonIDE previously also tried installing its own private shell by
 * downloading the stock upstream termux/termux-packages bootstrap into its
 * own app storage — that content assumes it always lives at
 * `/data/data/com.termux/...`, an assumption baked into compiled code
 * inside libtermux-exec.so itself (not just text/shebangs, which could be
 * patched), so exec-rerouting for compiled tools inside it (id, grep, find,
 * curl, dpkg, ...) could never be made reliable. That path — and the
 * system-linker-exec workaround it needed just to launch bash from
 * noexec-mounted app storage in the first place — has been removed.
 * Building/installing without one of the sandboxes above already present
 * now goes through Cloud Build (GitHub Actions) instead; see
 * [com.sednium.kryptonide.ui.git.GithubActionsBuildEngine].
 */
object ToolchainDetector {

    enum class Kind { ANDROIDIDE, ANDROIDIDE_RV2, TERMUX, NONE }

    data class Toolchain(
        val kind:      Kind,
        val prefix:    String,   // .../files/usr equivalent
        val home:      String,   // .../files/home equivalent
        val bashBin:   File,
        val javaHome:  String?,  // resolved JDK home, if found
        val sdkHome:   String?,  // resolved Android SDK root, if found
        val isUsable:  Boolean   // bash + java both present; SDK is best-effort
    )

    private const val ANDROIDIDE_PREFIX = "/data/data/com.itsaky.androidide/files/usr"
    private const val ANDROIDIDE_HOME   = "/data/data/com.itsaky.androidide/files/home"
    // AndroidIDE-Rv2 — a separate app (package com.tom.rv2ide) from stock
    // AndroidIDE. Its bootstrap is built with its own package id baked in
    // at build time, so it works correctly out of the box like the others.
    private const val ANDROIDIDE_RV2_PREFIX = "/data/data/com.tom.rv2ide/files/usr"
    private const val ANDROIDIDE_RV2_HOME   = "/data/data/com.tom.rv2ide/files/home"
    private const val TERMUX_PREFIX     = "/data/data/com.termux/files/usr"
    private const val TERMUX_HOME       = "/data/data/com.termux/files/home"

    /** Manual overrides set by the user in the Build panel, if auto-detect fails. */
    data class Overrides(
        val javaHome: String? = null,
        val sdkHome:  String? = null
    )

    fun detect(context: Context, overrides: Overrides = Overrides()): Toolchain {
        detectIn(Kind.ANDROIDIDE, ANDROIDIDE_PREFIX, ANDROIDIDE_HOME, overrides)
            ?.let { return it }

        detectIn(Kind.ANDROIDIDE_RV2, ANDROIDIDE_RV2_PREFIX, ANDROIDIDE_RV2_HOME, overrides)
            ?.let { return it }

        detectIn(Kind.TERMUX, TERMUX_PREFIX, TERMUX_HOME, overrides)
            ?.let { return it }

        // Nothing usable anywhere — report NONE with any manual overrides
        // the user supplied so BuildEngine can still try. Callers (BuildPanel,
        // ToolchainSetupPanel) should offer Cloud Build as the path forward
        // here rather than any kind of local install.
        return Toolchain(
            kind     = Kind.NONE,
            prefix   = "",
            home     = "",
            bashBin  = File(""),
            javaHome = overrides.javaHome,
            sdkHome  = overrides.sdkHome,
            isUsable = false
        )
    }

    private fun detectIn(kind: Kind, prefix: String, home: String, overrides: Overrides): Toolchain? {
        val bash = File("$prefix/bin/bash")
        if (!bash.exists() || !bash.canExecute()) return null

        val javaHome = overrides.javaHome
            ?: findJavaHome(prefix)

        val sdkHome = overrides.sdkHome
            ?: findSdkHome(prefix, home)

        return Toolchain(
            kind     = kind,
            prefix   = prefix,
            home     = home,
            bashBin  = bash,
            javaHome = javaHome,
            sdkHome  = sdkHome,
            isUsable = javaHome != null
        )
    }

    /** `java`/`javac` on the sandbox's own PATH is the reliable signal a JDK is set up. */
    private fun findJavaHome(prefix: String): String? {
        val direct = File("$prefix/bin/javac")
        if (direct.exists()) return prefix // PREFIX itself acts as JAVA_HOME (bin/, lib/ under it)

        val optDir = File("$prefix/opt")
        val candidate = optDir.listFiles { f -> f.isDirectory && f.name.contains("jdk", ignoreCase = true) || f.name.contains("openjdk", ignoreCase = true) }
            ?.firstOrNull { File(it, "bin/javac").exists() }
        return candidate?.absolutePath
    }

    /** Look for a real Android SDK (needs at least `platforms/` + `build-tools/`). */
    private fun findSdkHome(prefix: String, home: String): String? {
        val candidates = listOf(
            "$home/android-sdk",
            "$home/Android/Sdk",
            "$prefix/opt/android-sdk",
            "$prefix/share/android-sdk"
        )
        return candidates.firstOrNull { path ->
            val f = File(path)
            f.isDirectory && (File(f, "platforms").isDirectory || File(f, "build-tools").isDirectory)
        }
    }

    /** Build the full replaced-environment map, mirroring TerminalPanel.buildTermuxEnvironment(). */
    fun buildEnvironment(tc: Toolchain, extra: Map<String, String> = emptyMap()): Map<String, String> {
        if (tc.kind == Kind.NONE) return extra

        val path = buildString {
            append("${tc.prefix}/bin:${tc.prefix}/bin/applets")
            append(":/system/bin:/system/xbin")
        }

        val base = mutableMapOf(
            "HOME"            to tc.home,
            "PREFIX"          to tc.prefix,
            "TERM"            to "xterm-256color",
            "LANG"            to "en_US.UTF-8",
            "LC_ALL"          to "en_US.UTF-8",
            "SHELL"           to "${tc.prefix}/bin/bash",
            "PATH"            to path,
            "LD_LIBRARY_PATH" to "${tc.prefix}/lib",
            "LD_PRELOAD"      to "${tc.prefix}/lib/libtermux-exec.so",
            "TMPDIR"          to "${tc.prefix}/tmp",
            "ANDROID_DATA"    to "/data",
            "ANDROID_ROOT"    to "/system"
        )
        tc.javaHome?.let { base["JAVA_HOME"] = it }
        tc.sdkHome?.let {
            base["ANDROID_HOME"] = it
            base["ANDROID_SDK_ROOT"] = it
        }
        base.putAll(extra)
        return base
    }

    fun statusSummary(tc: Toolchain): String = when (tc.kind) {
        Kind.ANDROIDIDE     -> "AndroidIDE sandbox" + (if (tc.isUsable) " — JDK found" else " — JDK not found, run: pkg install openjdk-17")
        Kind.ANDROIDIDE_RV2 -> "AndroidIDE-Rv2 sandbox" + (if (tc.isUsable) " — JDK found" else " — JDK not found, run: pkg install openjdk-17")
        Kind.TERMUX         -> "Termux sandbox" + (if (tc.isUsable) " — JDK found" else " — JDK not found, run: pkg install openjdk-17")
        Kind.NONE           -> "No local toolchain found — use Cloud Build, or install AndroidIDE / Termux"
    }

    /**
     * Builds the argv for launching `bash` in the given sandbox. All
     * remaining sandbox kinds (AndroidIDE / AndroidIDE-Rv2 / Termux) have
     * their binaries already executable in place — their own app
     * deliberately exposes them world-executable for exactly this kind of
     * cross-app invocation — so a plain direct exec is all that's ever
     * needed here now.
     */
    fun buildProcessCommand(tc: Toolchain, bashArgs: List<String>): List<String> =
        listOf("${tc.prefix}/bin/bash") + bashArgs

    /** Spawns bash in the sandbox. Returns null if the process couldn't be started at all. */
    fun spawnBash(tc: Toolchain, bashArgs: List<String>, workDir: File?, extraEnv: Map<String, String> = emptyMap()): Process? {
        val env = buildEnvironment(tc, extraEnv)
        return runCatching {
            ProcessBuilder(buildProcessCommand(tc, bashArgs))
                .apply { workDir?.let { directory(it) } }
                .redirectErrorStream(false)
                .apply { environment().clear(); environment().putAll(env) }
                .start()
        }.getOrNull()
    }
}
