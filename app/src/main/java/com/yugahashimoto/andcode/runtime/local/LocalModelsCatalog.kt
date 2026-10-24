package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeModel
import com.yugahashimoto.andcode.core.api.OpenCodeProvider

/**
 * Local OpenAI-compatible endpoint (Ollama, LM Studio, etc.) — feature 7 alternative to GGUF-in-app.
 * Full on-device GGUF is not shipped; point this provider at a local server instead.
 */
object LocalModelsCatalog {
    const val PROVIDER_ID = "local-ollama"
    const val PROVIDER_NAME = "Local (Ollama / OpenAI-compatible)"
    const val DEFAULT_BASE_URL = "http://127.0.0.1:11434/v1"
    const val DEFAULT_MODEL = "llama3.2"

    fun asProvider(
        models: List<String> = listOf(DEFAULT_MODEL, "qwen2.5-coder", "deepseek-r1"),
    ): OpenCodeProvider {
        val map =
            models.associateWith { id ->
                OpenCodeModel(id = id, providerId = PROVIDER_ID, name = id)
            }
        return OpenCodeProvider(id = PROVIDER_ID, name = PROVIDER_NAME, models = map)
    }
}
