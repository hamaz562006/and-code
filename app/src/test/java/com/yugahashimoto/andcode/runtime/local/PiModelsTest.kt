package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiModelsTest {
    @Test
    fun `catalog includes seeded API providers`() {
        val catalog = PiModels.catalog()
        assertTrue(catalog.all.size >= 4)
        val ids = catalog.all.map { it.id }.toSet()
        assertTrue(ids.contains("anthropic"))
        assertTrue(ids.contains("openai"))
        assertTrue(ids.contains("google"))
        assertEquals("claude-sonnet-4-20250514", catalog.default["anthropic"])
    }

    @Test
    fun `catalog marks connected providers`() {
        val catalog = PiModels.catalog(connectedIds = setOf("openai", "anthropic"))
        assertTrue(catalog.connected.contains("openai"))
        assertTrue(catalog.connected.contains("anthropic"))
        assertEquals(2, catalog.connected.size)
    }

    @Test
    fun `seed providers expose at least one model each`() {
        PiModels.SEED_PROVIDERS.forEach { seed ->
            assertTrue(seed.models.isNotEmpty())
            val provider = PiModels.catalog().all.first { it.id == seed.id }
            assertEquals(seed.models.size, provider.models.size)
        }
    }
}
