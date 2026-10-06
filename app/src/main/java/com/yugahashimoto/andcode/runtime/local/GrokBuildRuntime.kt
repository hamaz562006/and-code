package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Host-side helpers for the Grok Build binary in the shared rootfs.
 *
 * Auth is API-key only (no browser login). Built-in provider is `xai` via [XAI_API_KEY];
 * custom OpenAI-compatible providers live in `root/.grok/providers.json` (same UI path as Pi).
 */
class GrokBuildRuntime(
    val runtimeDirectory: File,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
        }
    private val envRelative = "root/.grok/env"
    private val keyFileRelative = "root/.grok/api_key"
    private val providersRelative = "root/.grok/providers.json"
    private val keysDirRelative = "root/.grok/keys"

    fun version(rootfs: File): String? = GrokBuildInstaller.installedVersion(rootfs)

    fun stopAll() {
        // Process-backed chat not wired yet; nothing to kill.
    }

    fun hasApiKey(rootfs: File): Boolean = !readApiKey(rootfs).isNullOrBlank()

    fun setApiKey(
        rootfs: File,
        apiKey: String?,
    ) {
        setProviderApiKey(rootfs, "xai", apiKey)
    }

    fun readApiKey(rootfs: File): String? = readProviderApiKey(rootfs, "xai")

    fun hasProviderApiKey(
        rootfs: File,
        providerId: String,
    ): Boolean = !readProviderApiKey(rootfs, providerId).isNullOrBlank()

    fun readProviderApiKey(
        rootfs: File,
        providerId: String,
    ): String? {
        val id = providerId.trim()
        if (id.isEmpty()) return null
        if (id == "xai") {
            val keyFile = File(rootfs, keyFileRelative)
            if (keyFile.isFile) return keyFile.readText().trim().ifBlank { null }
            val env = File(rootfs, envRelative)
            if (!env.isFile) return null
            return env.readLines()
                .firstOrNull { it.trim().startsWith("XAI_API_KEY=") }
                ?.substringAfter("=")
                ?.trim()
                ?.ifBlank { null }
        }
        val keyFile = File(rootfs, "$keysDirRelative/$id")
        return keyFile.takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null }
    }

    fun setProviderApiKey(
        rootfs: File,
        providerId: String,
        apiKey: String?,
    ) {
        val id = providerId.trim()
        require(id.isNotEmpty()) { "provider id required" }
        val trimmed = apiKey?.trim().orEmpty()
        if (id == "xai") {
            val keyFile = File(rootfs, keyFileRelative)
            val envFile = File(rootfs, envRelative)
            keyFile.parentFile?.mkdirs()
            if (trimmed.isEmpty()) {
                keyFile.delete()
                if (envFile.isFile) {
                    val lines = envFile.readLines().filterNot { it.trim().startsWith("XAI_API_KEY=") }
                    if (lines.isEmpty()) envFile.delete() else envFile.writeText(lines.joinToString("\n") + "\n")
                }
                return
            }
            keyFile.writeText(trimmed)
            val other =
                if (envFile.isFile) {
                    envFile.readLines().filterNot { it.trim().startsWith("XAI_API_KEY=") }
                } else {
                    emptyList()
                }
            envFile.writeText((other + "XAI_API_KEY=$trimmed").joinToString("\n") + "\n")
            return
        }
        val keyFile = File(rootfs, "$keysDirRelative/$id")
        keyFile.parentFile?.mkdirs()
        if (trimmed.isEmpty()) {
            keyFile.delete()
        } else {
            keyFile.writeText(trimmed)
        }
    }

    data class CustomProviderEntry(
        val id: String,
        val name: String,
        val baseUrl: String,
        val modelIds: List<String>,
    )

    fun listCustomProviders(rootfs: File): List<CustomProviderEntry> {
        val file = File(rootfs, providersRelative)
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            val providers = root["providers"]?.jsonObject ?: return emptyList()
            providers.mapNotNull { (id, value) ->
                val obj = value.jsonObject
                val models =
                    obj["models"]?.jsonArray?.mapNotNull { el ->
                        el.jsonPrimitive.contentOrNull
                    }.orEmpty().ifEmpty {
                        obj["models"]?.jsonObject?.keys?.toList().orEmpty()
                    }
                if (models.isEmpty()) return@mapNotNull null
                CustomProviderEntry(
                    id = id,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id,
                    baseUrl = obj["baseUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    modelIds = models,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun registerCustomProvider(
        rootfs: File,
        id: String,
        name: String,
        baseUrl: String,
        modelIds: List<String>,
    ) {
        require(id.isNotBlank()) { "provider id required" }
        require(modelIds.isNotEmpty()) { "at least one model id required" }
        var normalizedBase = baseUrl.trim().trimEnd('/')
        for (suffix in listOf("/chat/completions", "/completions")) {
            if (normalizedBase.endsWith(suffix)) {
                normalizedBase = normalizedBase.removeSuffix(suffix)
                break
            }
        }
        require(normalizedBase.isNotBlank()) { "base URL required" }

        val file = File(rootfs, providersRelative)
        file.parentFile?.mkdirs()
        val existing =
            if (file.isFile) {
                runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrDefault(buildJsonObject {})
            } else {
                buildJsonObject {}
            }
        val existingProviders = existing["providers"]?.jsonObject ?: buildJsonObject {}
        val updatedProviders =
            buildJsonObject {
                existingProviders.forEach { (k, v) -> put(k, v) }
                put(
                    id,
                    buildJsonObject {
                        put("name", name.ifBlank { id })
                        put("baseUrl", normalizedBase)
                        put(
                            "models",
                            buildJsonArray {
                                modelIds.forEach { add(JsonPrimitive(it)) }
                            },
                        )
                    },
                )
            }
        file.writeText(
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject { put("providers", updatedProviders) },
            ),
        )
    }

    fun removeCustomProvider(
        rootfs: File,
        id: String,
    ) {
        val file = File(rootfs, providersRelative)
        if (!file.isFile) return
        runCatching {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            val providers = root["providers"]?.jsonObject ?: return
            if (id !in providers) return
            val updated =
                buildJsonObject {
                    providers.forEach { (k, v) ->
                        if (k != id) put(k, v)
                    }
                }
            if (updated.isEmpty()) {
                file.delete()
            } else {
                file.writeText(
                    json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject { put("providers", updated) },
                    ),
                )
            }
        }
        setProviderApiKey(rootfs, id, null)
    }
}
