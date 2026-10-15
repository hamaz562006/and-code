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
 * Keyless OpenAI-compatible chat for [FreeModelsCatalog] models (all agents).
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
    ): String {
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
                    return@use "Free model request failed (HTTP ${response.code}): ${raw.take(400)}"
                }
                parseAssistantContent(raw) ?: raw.take(500).ifBlank { "(empty response)" }
            }
        }.getOrElse { e ->
            "Free model request failed: ${e.message ?: e.javaClass.simpleName}"
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
}
