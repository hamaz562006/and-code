package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeModel
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Codex has exactly one provider - OpenAI's own hosted models via a ChatGPT/API-key sign-in - so
 * this builds the single-provider [ProviderCatalog] the rest of the app expects from a `model/list`
 * result, verified live: `{"data":[{"id":"gpt-6-astra","displayName":"GPT-6-Astra",...}, ...]}`.
 */
object CodexModels {
    const val PROVIDER_ID = "openai"

    fun catalog(
        modelListResult: JsonObject,
        connected: Boolean,
    ): ProviderCatalog {
        val entries = modelListResult["data"] as? JsonArray ?: JsonArray(emptyList())
        val models =
            entries.mapNotNull { entry ->
                val model = entry as? JsonObject ?: return@mapNotNull null
                val id = model.string("id") ?: return@mapNotNull null
                id to OpenCodeModel(id = id, providerId = PROVIDER_ID, name = model.string("displayName") ?: id)
            }.toMap()
        val provider = OpenCodeProvider(id = PROVIDER_ID, name = "OpenAI (Codex)", models = models)
        return ProviderCatalog(
            all = listOf(provider),
            connected = if (connected) listOf(PROVIDER_ID) else emptyList(),
        )
    }

    /** Codex has one agent - there is no separate plan/build split the way OpenCode's catalogue has. */
    fun agents(): List<OpenCodeAgent> = listOf(OpenCodeAgent(name = "codex", description = "Codex", mode = "primary", native = true))

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
