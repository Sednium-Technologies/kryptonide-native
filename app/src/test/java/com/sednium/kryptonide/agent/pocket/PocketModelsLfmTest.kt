package com.sednium.kryptonide.agent.pocket

import org.junit.Assert.*
import org.junit.Test

class PocketModelsLfmTest {

    @Test
    fun testMiniCpm1BIsBaseBrain() {
        val spec = PocketModels.findCatalogSpec(PocketModels.MINICPM_MODEL_ID)
        assertNotNull("MiniCPM 1B model must be present in catalog", spec)
        assertEquals(PocketModels.MINICPM_MODEL_ID, spec?.id)
        assertTrue(spec?.name?.contains("MiniCPM 1B") == true)
        assertEquals(ModelFormat.GGUF, spec?.format)
        assertEquals(1.08f, spec?.parameterCountBillion)
        assertEquals("Q4_K_M", spec?.quantization)
        assertTrue("Approx size should be ~650MB", spec!!.sizeBytesApprox in (600L * 1024 * 1024)..(700L * 1024 * 1024))
        assertTrue("MiniCPM 1B must be marked as SystemOne", spec.isSystemOne)
        assertEquals(4, spec.recommendedThreads)
        assertNotNull(spec.downloadUrl)
    }

    @Test
    fun testLfmAndNeedleRemovedFromCatalog() {
        assertNull("LFM-250M must not be in catalog", PocketModels.findCatalogSpec(PocketModels.LFM_250M_MODEL_ID))
        assertNull("Needle 3 must not be in catalog", PocketModels.findCatalogSpec(PocketModels.NEEDLE_3_MODEL_ID))
    }

    @Test
    fun testDefaultModelIsMiniCpm1B() {
        val defaultModel = PocketModels.getDefaultModel()
        assertEquals(PocketModels.MINICPM_MODEL_ID, defaultModel.id)
        assertTrue(defaultModel.isSystemOne)

        // Ensure all catalog model IDs are unique
        val ids = PocketModels.PREDEFINED_CATALOG.map { it.id }
        assertEquals(ids.size, ids.distinct().size)

        // Verify SystemOne models count
        val systemOneModels = PocketModels.PREDEFINED_CATALOG.filter { it.isSystemOne }
        assertTrue("Must have MiniCPM and lightweight coders", systemOneModels.isNotEmpty())
    }
}
