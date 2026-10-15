package com.yugahashimoto.andcode.runtime.free

import com.yugahashimoto.andcode.core.api.OpenCodeModel
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request


/**
 * Shared keyless free-model catalog for every agent (not Hermes-only).
 *
 * Primary source: Kilo free models with tool support (same pool AndroidHarness uses).
 * Fallback: a curated static list so the picker is never empty offline.
 */
object FreeModelsCatalog {
    const val PROVIDER_ID = "andcode-free"
    const val PROVIDER_NAME = "Free models"
    const val DEFAULT_MODEL = "kilo-auto/free"

    const val KILO_BASE_URL = "https://api.kilo.ai/api/gateway"
    const val KILO_MODELS_URL = "$KILO_BASE_URL/models"
    const val POLLINATIONS_BASE_URL = "https://text.pollinations.ai/openai"

    /** Static fallback so UI works before/without network (~15 free-oriented ids). */
    val FALLBACK_MODEL_IDS: List<String> =
        listOf(
            "kilo-auto/free",
            "inclusionai/ling-3.1-flash",
            "openai-fast",
            "big-pickle",
            "mimo-v2.6-flash-free",
            "muse-spark-1.2-contributor-free",
            "nemotron-3-ultra-free",
            "qwen/qwen3-coder-next",
            "google/gemma-3-27b-it",
            "meta-llama/llama-3.3-70b-instruct",
            "deepseek/deepseek-r1",
            "mistralai/mistral-small-3.1-24b-instruct",
            "stepfun/step-3.5-flash",
            "minimax/minimax-m2",
            "openrouter/free",
        )

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val cachedIds = AtomicReference(FALLBACK_MODEL_IDS)

    fun modelIds(): List<String> = cachedIds.get()

    fun isFreeProvider(providerId: String?): Boolean =
        providerId == PROVIDER_ID || providerId == "opencode-free"

    fun isFreeModel(modelId: String?): Boolean {
        if (modelId.isNullOrBlank()) return false
        val ids = modelIds()
        return modelId in ids ||
            modelId.endsWith("-free") ||
            modelId.endsWith("/free") ||
            modelId == "big-pickle" ||
            modelId == "openai-fast"
    }

    fun asProvider(): OpenCodeProvider {
        val models =
            modelIds().associateWith { id ->
                OpenCodeModel(
                    id = id,
                    providerId = PROVIDER_ID,
                    name = displayName(id),
                )
            }
        return OpenCodeProvider(
            id = PROVIDER_ID,
            name = PROVIDER_NAME,
            models = models,
        )
    }

    fun baseUrlFor(modelId: String): String =
        if (modelId == "openai-fast") POLLINATIONS_BASE_URL else KILO_BASE_URL

    fun displayName(id: String): String =
        id.substringAfterLast('/').replace('-', ' ').replaceFirstChar { it.uppercase() }

    /**
     * Refresh from Kilo (no API key). Keeps fallback on failure.
     * Safe to call from a background thread.
     */
    fun refreshFromNetwork(client: OkHttpClient = defaultClient()): Int {
        return runCatching {
            val request =
                Request.Builder()
                    .url(KILO_MODELS_URL)
                    .header("User-Agent", "AndCode")
                    .header("HTTP-Referer", "https://github.com/hamaz562006/and-code")
                    .get()
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use 0
                val body = response.body?.string().orEmpty()
                val ids = parseKiloFreeToolModels(body)
                if (ids.isEmpty()) return@use 0
                // Prefer live free+tools list; append openai-fast (Pollinations) if missing.
                val merged =
                    (ids + "openai-fast")
                        .distinct()
                        .take(20)
                cachedIds.set(merged)
                merged.size
            }
        }.getOrDefault(0)
    }

    internal fun parseKiloFreeToolModels(body: String): List<String> {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { element ->
            val entry = element.jsonObject
            if (entry["isFree"]?.jsonPrimitive?.booleanOrNull != true) return@mapNotNull null
            val supportsTools =
                entry["supported_parameters"]?.jsonArray
                    ?.any { it.jsonPrimitive.contentOrNull == "tools" } == true
            if (!supportsTools) return@mapNotNull null
            entry["id"]?.jsonPrimitive?.contentOrNull
        }
    }

    private fun defaultClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
}
