package com.sednium.kryptonide.utils

import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks whether any Activity in the application is currently in foreground (started/resumed).
 */
object AppLifecycleTracker {
    private val startedActivities = AtomicInteger(0)

    val isAppInBackground: Boolean
        get() = startedActivities.get() <= 0

    val isAppInForeground: Boolean
        get() = startedActivities.get() > 0

    fun onActivityStarted() {
        startedActivities.incrementAndGet()
    }

    fun onActivityStopped() {
        startedActivities.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }
}
