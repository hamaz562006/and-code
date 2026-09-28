package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiModelsTest {
    @Test
    fun `catalog includes pi provider with expected default model`() {
        val catalog = PiModels.catalog()
        assertEquals(1, catalog.all.size)
        val provider = catalog.all.first()
        assertEquals(PiModels.PROVIDER_ID, provider.id)
        assertEquals("Pi", provider.name)
        assertEquals(PiModels.DEFAULT_MODEL, catalog.default[PiModels.PROVIDER_ID])
        assertTrue(catalog.connected.contains(PiModels.PROVIDER_ID))
    }

    @Test
    fun `catalog models contain expected thinking variants`() {
        val catalog = PiModels.catalog()
        val provider = catalog.all.first()
        val defaultModel = provider.models[PiModels.DEFAULT_MODEL]
        assertNotNull(defaultModel)
        assertEquals("Gemini 2.5 Flash", defaultModel?.name)
        assertTrue(defaultModel?.variants?.containsKey("off") == true)
        assertTrue(defaultModel?.variants?.containsKey("low") == true)
        assertTrue(defaultModel?.variants?.containsKey("medium") == true)
        assertTrue(defaultModel?.variants?.containsKey("high") == true)
    }

    @Test
    fun `catalog accepts custom model list`() {
        val custom = listOf("custom-1" to "Custom Model 1")
        val catalog = PiModels.catalog(custom)
        val provider = catalog.all.first()
        assertEquals(1, provider.models.size)
        assertEquals("Custom Model 1", provider.models["custom-1"]?.name)
    }
}
