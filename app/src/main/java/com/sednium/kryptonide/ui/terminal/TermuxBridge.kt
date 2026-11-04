package com.sednium.kryptonide.ui.terminal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import java.io.File

/**
 * Bridge between Krypton IDE and Termux (com.termux).
 *
 * Provides:
 * - Installation checks and download/install intents (F-Droid / GitHub)
 * - RUN_COMMAND intent execution via com.termux.app.RunCommandService
 * - Launching Termux directly into the current project directory
 */
object TermuxBridge {

    const val TERMUX_PACKAGE_NAME = "com.termux"
    const val TERMUX_SERVICE_NAME = "com.termux.app.RunCommandService"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

    // Termux RUN_COMMAND extras
    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    const val EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"

    const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
    const val TERMUX_HOME_PATH = "/data/data/com.termux/files/home"

    /**
     * Checks whether Termux is installed on the device.
     */
    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE_NAME, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns an intent to download Termux from F-Droid or GitHub Releases.
     */
    fun getTermuxInstallIntent(): Intent {
        val uri = Uri.parse("https://f-droid.org/packages/com.termux/")
        return Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * Launches Termux directly in the specified project directory.
     * Uses RUN_COMMAND with BACKGROUND = false so Termux opens in foreground
     * running bash with working directory set to [workDir].
     */
    fun openTermuxAt(context: Context, workDir: String): Boolean {
        if (!isTermuxInstalled(context)) {
            Toast.makeText(context, "Termux is not installed. Tap 'Install Termux' to get it.", Toast.LENGTH_LONG).show()
            context.startActivity(getTermuxInstallIntent())
            return false
        }

        return try {
            val intent = Intent().apply {
                component = ComponentName(TERMUX_PACKAGE_NAME, TERMUX_SERVICE_NAME)
                action = ACTION_RUN_COMMAND
                putExtra(EXTRA_COMMAND_PATH, TERMUX_BASH_PATH)
                putExtra(EXTRA_ARGUMENTS, arrayOf("--login"))
                putExtra(EXTRA_WORKDIR, workDir.ifBlank { TERMUX_HOME_PATH })
                putExtra(EXTRA_BACKGROUND, false)
                putExtra(EXTRA_SESSION_ACTION, "0")
                putExtra(EXTRA_COMMAND_LABEL, "Krypton IDE Shell")
                putExtra(EXTRA_COMMAND_DESCRIPTION, "Interactive shell in $workDir")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startService(intent)
            Toast.makeText(context, "Opening Termux in $workDir…", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            // Fallback: normal package launch
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE_NAME)
                if (launchIntent != null) {
                    launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(launchIntent)
                    true
                } else false
            } catch (ex: Exception) {
                Toast.makeText(context, "Failed to launch Termux: ${ex.message}", Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    /**
     * Dispatches a command to Termux via RUN_COMMAND service.
     *
     * @param context Android context
     * @param command Command string to run (e.g. "git status" or "pkg install python")
     * @param workDir Directory where command should execute
     * @param background true to run in background, false to open in Termux foreground terminal
     */
    fun executeInTermux(
        context: Context,
        command: String,
        workDir: String,
        background: Boolean = false
    ): Boolean {
        if (!isTermuxInstalled(context)) {
            Toast.makeText(context, "Termux is not installed.", Toast.LENGTH_SHORT).show()
            return false
        }

        return try {
            val intent = Intent().apply {
                component = ComponentName(TERMUX_PACKAGE_NAME, TERMUX_SERVICE_NAME)
                action = ACTION_RUN_COMMAND
                putExtra(EXTRA_COMMAND_PATH, TERMUX_BASH_PATH)
                putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
                putExtra(EXTRA_WORKDIR, workDir.ifBlank { TERMUX_HOME_PATH })
                putExtra(EXTRA_BACKGROUND, background)
                putExtra(EXTRA_SESSION_ACTION, "0")
                putExtra(EXTRA_COMMAND_LABEL, "Krypton: $command")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startService(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(context, "Termux dispatch failed: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
