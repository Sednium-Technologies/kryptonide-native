package com.sednium.kryptonide.utils

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KryptonHapticsTest {

    @Test
    fun testHeadlessExecutionDoesNotCrash() {
        // Must safely execute without throwing in headless/unit environments
        try {
            KryptonHaptics.tick(null, null)
            KryptonHaptics.click(null, null)
            KryptonHaptics.success(null, null)
        } catch (e: Exception) {
            fail("KryptonHaptics must not throw in headless environment: ${e.message}")
        }
    }

    @Test
    fun testIsHapticsEnabledDefault() {
        // When context is null, should default to true
        assertTrue(KryptonHaptics.isHapticsEnabled(null))

        val context: Context = ApplicationProvider.getApplicationContext()
        // Default in SettingsStore is true
        assertTrue(KryptonHaptics.isHapticsEnabled(context))
    }

    @Test
    fun testRobolectricExecutionWithRealContextAndView() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val view = View(context)

        // All semantic tactile feedback methods should execute safely
        KryptonHaptics.tick(context, view)
        KryptonHaptics.click(context, view)
        KryptonHaptics.success(context, view)
    }

    @Test
    fun testRobolectricExecutionWithContextOnly() {
        val context: Context = ApplicationProvider.getApplicationContext()

        KryptonHaptics.tick(context, null)
        KryptonHaptics.click(context, null)
        KryptonHaptics.success(context, null)
    }

    @Test
    fun testRobolectricExecutionWithViewOnly() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val view = View(context)

        KryptonHaptics.tick(null, view)
        KryptonHaptics.click(null, view)
        KryptonHaptics.success(null, view)
    }
}
