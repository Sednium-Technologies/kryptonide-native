package com.sednium.kryptonide.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sednium.kryptonide.R
import com.sednium.kryptonide.ui.MainActivity

/**
 * Posts and updates a single ongoing notification for a build's progress —
 * used by both the on-device Gradle builder ([com.sednium.kryptonide.ui.build.BuildEngine])
 * and the GitHub Actions cloud builder
 * ([com.sednium.kryptonide.ui.git.GithubActionsBuildEngine]), so the person
 * can back out of KryptonIDE while a build runs and still see progress.
 */
object BuildNotifier {

    private const val CHANNEL_ID = "krypton_build_progress"
    const val NOTIFICATION_ID = 4201

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Build Progress", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progress updates while Kryptonide builds your app"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** True if notifications can actually be shown — caller can use this to skip silently pre-API33 permission prompts. */
    fun canNotify(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun progress(context: Context, title: String, message: String, indeterminate: Boolean = true, percent: Int = 0) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val notification = baseBuilder(context, title, message)
            .setOngoing(true)
            .setProgress(100, percent, indeterminate)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    fun success(context: Context, title: String, message: String) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val notification = baseBuilder(context, title, message)
            .setOngoing(false)
            .setAutoCancel(true)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    fun failure(context: Context, title: String, message: String) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val notification = baseBuilder(context, title, message)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun baseBuilder(context: Context, title: String, message: String): NotificationCompat.Builder {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
    }
}
