package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeModel
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import kotlinx.serialization.json.JsonPrimitive

/**
 * Models and providers offered for the Pi coding agent.
 *
 * Backed by the official earendil-works/pi standalone release.
 */
object PiModels {
    const val PROVIDER_ID = "pi"
    const val DEFAULT_MODEL = "gemini-2.5-flash"

    val DEFAULT_MODELS =
        listOf(
            "gemini-2.5-flash" to "Gemini 2.5 Flash",
            "gemini-2.5-pro" to "Gemini 2.5 Pro",
            "gemini-3.1-pro-preview" to "Gemini 3.1 Pro Preview",
            "gemini-3.5-flash" to "Gemini 3.5 Flash",
            "gemini-3.7-flash" to "Gemini 3.7 Flash",
            "gemini-3.8-flash" to "Gemini 3.8 Flash",
            "claude-3-7-sonnet" to "Claude 3.7 Sonnet",
            "claude-3-5-sonnet" to "Claude 3.5 Sonnet",
            "gpt-4o" to "GPT-4o",
            "gpt-4o-mini" to "GPT-4o Mini",
        )

    private val THINKING_LEVELS = listOf("off", "low", "medium", "high")

    fun catalog(models: List<Pair<String, String>> = DEFAULT_MODELS): ProviderCatalog {
        val modelMap =
            models.associate { (id, name) ->
                id to
                    OpenCodeModel(
                        id = id,
                        providerId = PROVIDER_ID,
                        name = name,
                        variants = THINKING_LEVELS.associateWith { JsonPrimitive(it) },
                    )
            }
        return ProviderCatalog(
            all =
                listOf(
                    OpenCodeProvider(
                        id = PROVIDER_ID,
                        name = "Pi",
                        models = modelMap,
                    ),
                ),
            default = mapOf(PROVIDER_ID to DEFAULT_MODEL),
            connected = listOf(PROVIDER_ID),
        )
    }
}
