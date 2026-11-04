package com.sednium.kryptonide.agent.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sednium.kryptonide.R
import com.sednium.kryptonide.ui.MainActivity

object AiNotificationHelper {
    const val CHANNEL_ID_ONGOING = "krypton_ai_ongoing_v2"
    const val CHANNEL_ID_COMPLETE = "krypton_ai_complete_v2"
    @Deprecated("Use CHANNEL_ID_ONGOING or CHANNEL_ID_COMPLETE")
    const val CHANNEL_ID = CHANNEL_ID_ONGOING

    private const val NOTIFICATION_ID_PROGRESS = 1001
    private const val NOTIFICATION_ID_COMPLETION = 1002

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // Channel A: Silent Ongoing Status for Foreground Service
            val ongoingChannel = NotificationChannel(
                CHANNEL_ID_ONGOING,
                "Krypton AI Ongoing Tasks",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Silent ongoing notification while AI is generating"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(ongoingChannel)

            // Channel B: Background Completion Alert (Only when app is in background)
            val completeChannel = NotificationChannel(
                CHANNEL_ID_COMPLETE,
                "Krypton AI Task Completion",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Heads-up alert when background generation finishes"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(completeChannel)
        }
    }

    fun createNotificationChannel(context: Context) = createNotificationChannels(context)

    /**
     * Cleans raw AI response text into a clean 1-2 line preview for system notifications:
     * - Strips <think>...</think> and unclosed <think>... blocks
     * - Strips <status>...</status> tags
     * - Strips <edit file="...">...</edit> blocks
     * - Strips Markdown headers, code fences, and symbols
     * - Compacts into a single clean line capped at 120 chars
     */
    fun cleanPreviewText(raw: String): String {
        var text = raw

        // 1. Strip <think> blocks
        text = text.replace(Regex("""<think>[\s\S]*?<\/think>""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<think>[\s\S]*$""", RegexOption.IGNORE_CASE), "")

        // 2. Strip <status> blocks
        text = text.replace(Regex("""<status>[\s\S]*?<\/status>""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<status>[\s\S]*$""", RegexOption.IGNORE_CASE), "")

        // 3. Strip <edit> blocks
        text = text.replace(Regex("""<edit[\s\S]*?<\/edit>""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""<edit[\s\S]*$""", RegexOption.IGNORE_CASE), "")

        // 4. Strip <plan> blocks
        text = text.replace(Regex("""<plan>[\s\S]*?<\/plan>""", RegexOption.IGNORE_CASE), "")

        // 5. Clean markdown headers, code fences, bold/italics, and symbols
        text = text.replace(Regex("""```[\s\S]*?```"""), "")
        text = text.replace(Regex("""^[#*`\-> ]+""", RegexOption.MULTILINE), "")
        text = text.replace("`", "")
        text = text.replace("**", "")
        text = text.replace("*", "")

        // 6. Compact whitespace
        val cleanLine = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()

        if (cleanLine.isBlank()) {
            return "Generation complete. Tap to view the response."
        }
        return if (cleanLine.length > 120) cleanLine.take(117) + "..." else cleanLine
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun showProgressNotification(context: Context, statusMessage: String = "⚡ Krypton AI is generating...") {
        if (!hasNotificationPermission(context)) return
        createNotificationChannels(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "ai")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_ONGOING)
            .setSmallIcon(R.drawable.ic_notification_krypton)
            .setContentTitle("⚡ Krypton AI is generating...")
            .setContentText(statusMessage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID_PROGRESS, notification)
    }

    fun cancelProgressNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(NOTIFICATION_ID_PROGRESS)
    }

    fun showCompletionNotification(context: Context, previewText: String) {
        // STRICT INVARIANT: Only notify if the app is currently in background!
        if (com.sednium.kryptonide.utils.AppLifecycleTracker.isAppInForeground) {
            return
        }
        if (!hasNotificationPermission(context)) return
        createNotificationChannels(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "ai")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_COMPLETE)
            .setSmallIcon(R.drawable.ic_notification_krypton)
            .setContentTitle("✅ Krypton AI finished responding")
            .setContentText(previewText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(previewText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID_COMPLETION, notification)
    }
}
