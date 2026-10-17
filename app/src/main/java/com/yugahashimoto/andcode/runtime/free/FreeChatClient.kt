package com.yugahashimoto.andcode.runtime.free

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Keyless OpenAI-compatible chat for [FreeModelsCatalog] models (all agents),
 * with ordered model fallback on quota / rate-limit / hard failures.
 */
object FreeChatClient {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

    fun chat(
        modelId: String,
        userText: String,
    ): String =
        chatOnce(modelId, userText).getOrElse { e ->
            "Free model request failed: ${e.message ?: e.javaClass.simpleName}"
        }

    /**
     * Tries [targets] in order. On switchable failures (HTTP 429/503, quota text, empty body)
     * moves to the next model. Partial successful text is never discarded for a retry.
     */
    fun chatWithFallback(
        targets: List<FallbackTarget>,
        userText: String,
        retriesPerModel: Int = 1,
    ): FallbackAttemptResult {
        val chain =
            targets.filter { it.modelId.isNotBlank() }.ifEmpty {
                listOf(FallbackTarget(FreeModelsCatalog.PROVIDER_ID, FreeModelsCatalog.DEFAULT_MODEL))
            }
        val attempted = mutableListOf<String>()
        var lastError = "No models to try"
        for ((index, target) in chain.withIndex()) {
            val attempts = (1 + retriesPerModel.coerceIn(0, 5)).coerceAtLeast(1)
            repeat(attempts) { attempt ->
                attempted += target.modelId
                val result = chatOnce(target.modelId, userText)
                result.onSuccess { text ->
                    if (text.isNotBlank() && !isFailureMessage(text)) {
                        return FallbackAttemptResult(
                            modelId = target.modelId,
                            providerId = target.providerId,
                            text = if (index == 0) text else "$text\n\n_(fallback: ${target.modelId})_",
                            switched = index > 0,
                            attempted = attempted.toList(),
                        )
                    }
                    lastError = text.ifBlank { "Empty response from ${target.modelId}" }
                }.onFailure { e ->
                    lastError = e.message ?: e.javaClass.simpleName
                    if (!isSwitchableError(lastError) && attempt == attempts - 1 && index == chain.lastIndex) {
                        // non-switchable on last model
                    }
                }
                val err = lastError
                if (!isSwitchableError(err) && result.isFailure) {
                    // auth / permanent errors: still try next model in chain
                }
            }
        }
        val first = chain.first()
        return FallbackAttemptResult(
            modelId = first.modelId,
            providerId = first.providerId,
            text = "Free model request failed after ${attempted.size} attempt(s): $lastError",
            switched = false,
            attempted = attempted.toList(),
        )
    }

    private fun chatOnce(
        modelId: String,
        userText: String,
    ): Result<String> {
        val model = modelId.ifBlank { FreeModelsCatalog.DEFAULT_MODEL }
        val base = FreeModelsCatalog.baseUrlFor(model).trimEnd('/')
        val url = "$base/chat/completions"
        val payload =
            buildJsonObject {
                put("model", model)
                put(
                    "messages",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", userText)
                            },
                        )
                    },
                )
            }.toString()
        val request =
            Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("User-Agent", "AndCode")
                .header("HTTP-Referer", "https://github.com/hamaz562006/and-code")
                .header("X-Title", "AndCode")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("HTTP ${response.code}: ${raw.take(400)}")
                }
                parseAssistantContent(raw) ?: error("Empty response body")
            }
        }
    }

    private fun parseAssistantContent(raw: String): String? {
        val root = json.parseToJsonElement(raw).jsonObject
        val choices = root["choices"] as? JsonArray ?: return null
        val message =
            choices.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                ?: return null
        return message["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun isFailureMessage(text: String): Boolean = text.startsWith("Free model request failed")

    private fun isSwitchableError(message: String): Boolean {
        val m = message.lowercase()
        return "429" in m ||
            "503" in m ||
            "502" in m ||
            "quota" in m ||
            "rate limit" in m ||
            "rate_limit" in m ||
            "unavailable" in m ||
            "overloaded" in m ||
            "empty response" in m ||
            "timeout" in m
    }
}
