package com.sednium.kryptonide.agent.hardware

import android.util.Log

enum class SocProfile(val ordinalValue: Int, val displayName: String) {
    AUTO(0, "Auto-Detect"),
    DIMENSITY_DUAL(1, "MediaTek Dimensity Dual Big-Core (6000/700 series)"),
    DIMENSITY_QUAD(2, "MediaTek Dimensity Quad Big-Core (7000/8000/9000 series)"),
    SNAPDRAGON_MID(3, "Snapdragon Mid-Tier (4/6/7s series)"),
    SNAPDRAGON_FLAGSHIP(4, "Snapdragon Flagship (7+/8 series)")
}

object OortyNativeBridge {
    private const val TAG = "OortyNativeBridge"
    private var isNativeLoaded = false

    init {
        try {
            System.loadLibrary("oorty_engine")
            isNativeLoaded = true
            Log.i(TAG, "liboorty_engine.so loaded successfully")
        } catch (e: Throwable) {
            Log.w(TAG, "liboorty_engine.so not available, using pure-Kotlin fallbacks: ${e.message}")
            isNativeLoaded = false
        }
    }

    fun isLoaded(): Boolean = isNativeLoaded

    fun safeBindThreadToBigCores(threadIdx: Int): Boolean {
        if (!isNativeLoaded) return false
        return try {
            nativeBindThreadToBigCores(threadIdx)
        } catch (t: Throwable) {
            false
        }
    }

    fun safeSetSocProfile(profile: SocProfile) {
        if (!isNativeLoaded) return
        try {
            nativeSetSocProfile(profile.ordinalValue)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed setting SoC profile: ${t.message}")
        }
    }

    fun safeGetOptimalThreadCount(): Int {
        if (!isNativeLoaded) return 2
        return try {
            nativeGetOptimalThreadCount()
        } catch (t: Throwable) {
            2
        }
    }

    fun safeDetectGpuVendor(): Int {
        if (!isNativeLoaded) return 0
        return try {
            nativeDetectGpuVendor()
        } catch (t: Throwable) {
            0
        }
    }

    fun safeGetHardwareOptimizationSummary(): String {
        if (!isNativeLoaded) {
            return "Profile: Multi-Core Mobile Engine (Kotlin Fallback)\n" +
                   "Performance Big Cores: 2-4 threads\n" +
                   "GPU Acceleration: Standard Vulkan/OpenCL"
        }
        return try {
            nativeGetHardwareOptimizationSummary()
        } catch (t: Throwable) {
            "Profile: OORTY Mobile Acceleration"
        }
    }

    external fun nativeGetOptimalThreadCount(): Int
    external fun nativeBindThreadToBigCores(threadIdx: Int): Boolean
    external fun nativeDetectGpuVendor(): Int // 1=Mali, 2=Adreno, 3=PowerVR, 0=Unknown
    external fun nativeGetPerformanceCoreCount(): Int
    external fun nativeSetSocProfile(profileType: Int)
    external fun nativeIsMaliG57VulkanSupported(): Boolean
    external fun nativeIsGpuComputeSupported(): Boolean
    external fun nativeGetHardwareOptimizationSummary(): String
}
