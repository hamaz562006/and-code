package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `modelListJson` is the real `model/list` result from a live, unauthenticated codex app-server (0.155.1). */
class CodexModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val modelListJson =
        """{"data":[{"id":"gpt-6-astra","model":"gpt-6-astra","displayName":"GPT-6-Astra",
            "description":"Our most capable model for complex, demanding work."}]}"""

    @Test
    fun `builds a single openai provider from model list`() {
        val catalog = CodexModels.catalog(json.parseToJsonElement(modelListJson).jsonObject, connected = true)

        val provider = catalog.all.single()
        assertEquals(CodexModels.PROVIDER_ID, provider.id)
        assertTrue(provider.models.containsKey("gpt-6-astra"))
        assertEquals("GPT-6-Astra", provider.models.getValue("gpt-6-astra").name)
        assertEquals(listOf(CodexModels.PROVIDER_ID), catalog.connected)
    }

    @Test
    fun `an unauthenticated catalog reports the provider as not connected`() {
        val catalog = CodexModels.catalog(json.parseToJsonElement(modelListJson).jsonObject, connected = false)

        assertTrue(catalog.connected.isEmpty())
    }
}
