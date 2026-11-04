package com.sednium.kryptonide.agent.hardware

import org.junit.Assert.*
import org.junit.Test

class HardwareTopologyTest {

    @Test
    fun testScanTopologyPartitioning() {
        val topology = HardwareTopology.scanTopology()
        assertTrue("Total cores must be >= 1", topology.totalCores >= 1)
        assertNotNull("Performance cores must not be null", topology.performanceCores)
        assertTrue("Should have at least 1 performance core", topology.performanceCores.isNotEmpty())
        assertNotNull("GPU vendor must be identified or fallback", topology.gpuVendor)
    }

    @Test
    fun testKvCacheQuantRecommendationDefaults() {
        // Without Android context, verify enum definitions and properties
        assertEquals(50, LocalKvCacheQuant.Q8_0.memorySavingsPercent)
        assertEquals(75, LocalKvCacheQuant.Q4_0.memorySavingsPercent)
        assertEquals(0, LocalKvCacheQuant.AUTO.memorySavingsPercent)
    }

    @Test
    fun testOortyNativeBridgeFallback() {
        // On local JVM (x86 host), isLoaded should gracefully return false or handle without crash
        val optimalThreads = HardwareTopology.getOptimalThreadCount()
        assertTrue("Optimal thread count should be in 1..4 range", optimalThreads in 1..4)

        val pinned = HardwareTopology.pinThreadToBigCores(0)
        // Returns false on JVM without native library, doesn't crash
        assertNotNull(pinned)
    }
}
