package com.sednium.kryptonide.agent.hardware

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import java.io.File

enum class GpuVendor(val label: String) {
    MALI("ARM Mali"),
    ADRENO("Qualcomm Adreno"),
    POWERVR("PowerVR"),
    UNKNOWN("Generic / Software")
}

enum class LocalKvCacheQuant(val displayName: String, val memorySavingsPercent: Int) {
    AUTO("Auto / FP16", 0),
    Q8_0("Q8_0 (8-bit Quantized)", 50),
    Q4_0("Q4_0 (4-bit Quantized)", 75)
}

data class CpuTopology(
    val totalCores: Int,
    val performanceCores: List<Int>,
    val efficiencyCores: List<Int>,
    val maxFreqKhz: Long,
    val gpuVendor: GpuVendor
)

data class HardwareTelemetry(
    val cpuTopology: CpuTopology,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val isThermalThrottling: Boolean,
    val batteryTemperatureCelsius: Float,
    val optimalThreads: Int,
    val isNativeAccelerated: Boolean
)

object HardwareTopology {
    private const val TAG = "HardwareTopology"

    fun scanTopology(): CpuTopology {
        val totalCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val freqs = mutableMapOf<Int, Long>()
        var maxFreq = 0L

        for (i in 0 until totalCores) {
            val freqFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            if (freqFile.exists() && freqFile.canRead()) {
                try {
                    val freq = freqFile.readText().trim().toLongOrNull() ?: 0L
                    freqs[i] = freq
                    if (freq > maxFreq) maxFreq = freq
                } catch (e: Throwable) {
                    Log.d(TAG, "Cannot read cpu $i max freq: ${e.message}")
                }
            }
        }

        val performanceCores = mutableListOf<Int>()
        val efficiencyCores = mutableListOf<Int>()

        if (maxFreq > 0L) {
            val threshold = (maxFreq * 0.85).toLong()
            for (i in 0 until totalCores) {
                val f = freqs[i] ?: 0L
                if (f >= threshold) {
                    performanceCores.add(i)
                } else {
                    efficiencyCores.add(i)
                }
            }
        } else {
            // Fallback for big.LITTLE heuristic (e.g. 8-core: upper half are big cores)
            val half = totalCores / 2
            for (i in 0 until totalCores) {
                if (i >= half) {
                    performanceCores.add(i)
                } else {
                    efficiencyCores.add(i)
                }
            }
        }

        val gpu = detectGpuVendor()

        return CpuTopology(
            totalCores = totalCores,
            performanceCores = performanceCores.ifEmpty { (0 until totalCores).toList() },
            efficiencyCores = efficiencyCores,
            maxFreqKhz = maxFreq,
            gpuVendor = gpu
        )
    }

    fun detectGpuVendor(): GpuVendor {
        if (OortyNativeBridge.isLoaded()) {
            return when (OortyNativeBridge.nativeDetectGpuVendor()) {
                1 -> GpuVendor.MALI
                2 -> GpuVendor.ADRENO
                3 -> GpuVendor.POWERVR
                else -> detectGpuVendorFromSysfs()
            }
        }
        return detectGpuVendorFromSysfs()
    }

    private fun detectGpuVendorFromSysfs(): GpuVendor {
        return when {
            File("/dev/mali0").exists() || File("/dev/mali").exists() -> GpuVendor.MALI
            File("/dev/kgsl-3d0").exists() || File("/dev/kgsl").exists() -> GpuVendor.ADRENO
            File("/dev/pvr_sync").exists() -> GpuVendor.POWERVR
            else -> GpuVendor.UNKNOWN
        }
    }

    fun getMemoryInfo(context: Context): ActivityManager.MemoryInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        return memInfo
    }

    fun getAvailableRamBytes(context: Context): Long {
        return getMemoryInfo(context).availMem
    }

    fun getTotalRamBytes(context: Context): Long {
        return getMemoryInfo(context).totalMem
    }

    fun getBatteryTemperature(context: Context): Float {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            temp / 10.0f
        } catch (e: Throwable) {
            0.0f
        }
    }

    fun isThermalThrottling(context: Context): Boolean {
        val temp = getBatteryTemperature(context)
        if (temp >= 43.0f) return true

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isPowerSaveMode == true) return true

        val thermalZones = listOf("/sys/class/thermal/thermal_zone0/temp", "/sys/class/thermal/thermal_zone1/temp")
        for (tz in thermalZones) {
            val file = File(tz)
            if (file.exists() && file.canRead()) {
                try {
                    val raw = file.readText().trim().toLongOrNull() ?: 0L
                    val c = if (raw > 1000) raw / 1000f else raw.toFloat()
                    if (c >= 65.0f) return true
                } catch (_: Throwable) {}
            }
        }
        return false
    }

    fun getOptimalThreadCount(): Int {
        if (OortyNativeBridge.isLoaded()) {
            val count = OortyNativeBridge.nativeGetOptimalThreadCount()
            if (count > 0) return count
        }
        val top = scanTopology()
        return top.performanceCores.size.coerceIn(1, 4)
    }

    fun pinThreadToBigCores(threadIdx: Int): Boolean {
        if (OortyNativeBridge.isLoaded()) {
            return OortyNativeBridge.nativeBindThreadToBigCores(threadIdx)
        }
        return false
    }

    fun isLowRamDevice(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am?.isLowRamDevice == true) return true
        val mem = getMemoryInfo(context)
        val totalMb = mem.totalMem / (1024 * 1024)
        return totalMb <= 4096 // 4GB or less
    }

    /**
     * Calculates safe context length (n_ctx) based on free memory and model size
     * to eliminate Out-Of-Memory (LMK) kills during inference.
     */
    fun calculateOptimalContextLength(context: Context, modelParamBillion: Float): Int {
        if (isLowRamDevice(context)) {
            // Strict context clamp on budget/older phones to prevent LMK killing the IDE
            return if (modelParamBillion > 3.0f) 1024 else 2048
        }

        val freeBytes = getAvailableRamBytes(context)
        val freeMb = freeBytes / (1024 * 1024)

        // Estimated memory consumption per 1024 context tokens in Q4_K_M:
        // ~120MB per 1k context for 1B-3B models, ~280MB per 1k context for 7B models.
        val per1kContextMb = if (modelParamBillion > 4.0f) 280 else 140
        val modelWeightMb = (modelParamBillion * 600).toInt()
        val usableRamMb = (freeMb - modelWeightMb - 400).coerceAtLeast(200)

        val estimatedCtx = (usableRamMb / per1kContextMb) * 1024
        return when {
            estimatedCtx >= 8192 -> 8192
            estimatedCtx >= 4096 -> 4096
            estimatedCtx >= 2048 -> 2048
            else -> 1024
        }
    }

    /**
     * Determines optimal KV cache quantization format based on RAM availability.
     * Following OORTY research: Q8_0 is default golden standard (50% memory bandwidth savings, <0.1% PPL drop).
     * On low-RAM devices (<=4GB), Q4_0 is recommended (75% bandwidth reduction).
     */
    fun recommendKvCacheQuant(context: Context): LocalKvCacheQuant {
        return if (isLowRamDevice(context) || getAvailableRamBytes(context) < 2L * 1024 * 1024 * 1024) {
            LocalKvCacheQuant.Q4_0
        } else {
            LocalKvCacheQuant.Q8_0
        }
    }

    fun getTelemetry(context: Context): HardwareTelemetry {
        val topology = scanTopology()
        val mem = getMemoryInfo(context)
        val batteryTemp = getBatteryTemperature(context)
        val isThermal = isThermalThrottling(context)
        val optimalThreads = getOptimalThreadCount()

        return HardwareTelemetry(
            cpuTopology = topology,
            totalRamBytes = mem.totalMem,
            availableRamBytes = mem.availMem,
            isThermalThrottling = isThermal,
            batteryTemperatureCelsius = batteryTemp,
            optimalThreads = optimalThreads,
            isNativeAccelerated = OortyNativeBridge.isLoaded()
        )
    }
}
