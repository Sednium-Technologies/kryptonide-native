package com.sednium.kryptonide.ui.onboarding

import androidx.compose.runtime.Composable

/**
 * Delegates to [OnboardingScreen] which provides storage permission handling
 * and on-device model setup (Needle-3).
 */
@Composable
fun PermissionOnboardingScreen(onGranted: () -> Unit) {
    OnboardingScreen(onFinished = onGranted)
}

