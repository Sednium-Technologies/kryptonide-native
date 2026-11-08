package com.sednium.kryptonide.utils

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import com.sednium.kryptonide.data.storage.SettingsStore

/**
 * High-performance hardware haptic feedback engine for KryptonIDE.
 *
 * Utilizes a dual-layer triggering strategy:
 * 1. View-level tactile feedback via [View.performHapticFeedback] with [HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING]
 *    to bypass device-level "Touch Haptics" disabling in system settings.
 * 2. Direct hardware [Vibrator] / [VibratorManager] execution via [VibrationEffect] primitives
 *    to deliver crisp, immediate tactile micro-ticks, clicks, and success confirmations.
 *
 * Respects user in-app preference [SettingsStore.getHapticsSync].
 */
object KryptonHaptics {

    /**
     * Crisp 8ms micro-tap for symbol bar keys (Tab, {, }, Emmet, arrows),
     * file tree items, and streaming token milestones.
     */
    fun tick(context: Context? = null, view: View? = null) {
        performHaptic(
            context = context,
            view = view,
            viewFeedbackConstant = HapticFeedbackConstants.KEYBOARD_TAP,
            durationMs = 8L,
            amplitude = 75,
            predefinedEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) VibrationEffect.EFFECT_TICK else null
        )
    }

    /**
     * Solid 14ms tactile click for Bottom Navigation tab switches (Edit, GitHub, AI, Plugins, Settings),
     * AI Send / Stop button, Copy / Insert / Apply code buttons, and Model Switcher selection.
     */
    fun click(context: Context? = null, view: View? = null) {
        performHaptic(
            context = context,
            view = view,
            viewFeedbackConstant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.KEYBOARD_TAP,
            durationMs = 14L,
            amplitude = 150,
            predefinedEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) VibrationEffect.EFFECT_CLICK else null
        )
    }

    /**
     * Double-pulse confirmation when Apply patches a file, when a model finishes
     * loading into RAM, or when a Git commit succeeds.
     */
    fun success(context: Context? = null, view: View? = null) {
        if (!isHapticsEnabled(context)) return

        runCatching {
            // View pulse
            val flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view?.performHapticFeedback(HapticFeedbackConstants.CONFIRM, flags)
            } else {
                view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, flags)
            }

            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val timings = longArrayOf(0, 16, 60, 24)
                val amplitudes = intArrayOf(0, 160, 0, 220)
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 16, 60, 24), -1)
            }
        }
    }

    private fun performHaptic(
        context: Context?,
        view: View?,
        viewFeedbackConstant: Int,
        durationMs: Long,
        amplitude: Int,
        predefinedEffect: Int?
    ) {
        if (!isHapticsEnabled(context)) return

        runCatching {
            // Layer 1: View performHapticFeedback with FLAG_IGNORE_VIEW_SETTING
            val flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            view?.performHapticFeedback(viewFeedbackConstant, flags)

            // Layer 2: Direct Hardware Vibrator Execution
            val vibrator = getVibrator(context) ?: return
            if (!vibrator.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && predefinedEffect != null) {
                vibrator.vibrate(VibrationEffect.createPredefined(predefinedEffect))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        }
    }

    fun isHapticsEnabled(context: Context?): Boolean {
        if (context == null) return true
        return runCatching {
            SettingsStore(context).getHapticsSync()
        }.getOrDefault(true)
    }

    private fun getVibrator(context: Context?): Vibrator? {
        if (context == null) return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }.getOrNull()
    }
}
