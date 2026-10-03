package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeModel
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.ProviderCatalog

/**
 * Providers and models offered when the selected agent is Pi.
 *
 * Pi stores API keys under `~/.pi/agent/auth.json` keyed by these provider ids (see earendil-works/pi
 * providers docs). The catalogue is static so the Providers screen works without an OpenCode HTTP
 * server on :4097.
 */
object PiModels {
    data class SeedProvider(
        val id: String,
        val name: String,
        val models: List<Pair<String, String>>,
    )

    val SEED_PROVIDERS =
        listOf(
            SeedProvider(
                "anthropic",
                "Anthropic",
                listOf(
                    "claude-sonnet-4-20250514" to "Claude Sonnet 4",
                    "claude-3-7-sonnet" to "Claude 3.7 Sonnet",
                    "claude-3-5-sonnet" to "Claude 3.5 Sonnet",
                ),
            ),
            SeedProvider(
                "openai",
                "OpenAI",
                listOf(
                    "gpt-4o" to "GPT-4o",
                    "gpt-4o-mini" to "GPT-4o Mini",
                    "o3-mini" to "o3-mini",
                ),
            ),
            SeedProvider(
                "google",
                "Google",
                listOf(
                    "gemini-2.5-flash" to "Gemini 2.5 Flash",
                    "gemini-2.5-pro" to "Gemini 2.5 Pro",
                ),
            ),
            SeedProvider(
                "openrouter",
                "OpenRouter",
                listOf(
                    "openrouter/auto" to "OpenRouter Auto",
                ),
            ),
            SeedProvider(
                "groq",
                "Groq",
                listOf("llama-3.3-70b-versatile" to "Llama 3.3 70B"),
            ),
            SeedProvider(
                "deepseek",
                "DeepSeek",
                listOf("deepseek-chat" to "DeepSeek Chat"),
            ),
            SeedProvider(
                "mistral",
                "Mistral",
                listOf("mistral-large-latest" to "Mistral Large"),
            ),
            SeedProvider(
                "xai",
                "xAI",
                listOf("grok-2" to "Grok 2"),
            ),
        )

    fun catalog(connectedIds: Set<String> = emptySet()): ProviderCatalog {
        val providers =
            SEED_PROVIDERS.map { seed ->
                val models =
                    seed.models.associate { (id, name) ->
                        id to
                            OpenCodeModel(
                                id = id,
                                providerId = seed.id,
                                name = name,
                            )
                    }
                OpenCodeProvider(id = seed.id, name = seed.name, models = models)
            }
        return ProviderCatalog(
            all = providers,
            default =
                mapOf(
                    "anthropic" to "claude-sonnet-4-20250514",
                    "openai" to "gpt-4o",
                    "google" to "gemini-2.5-flash",
                ),
            connected = connectedIds.toList(),
        )
    }
}
