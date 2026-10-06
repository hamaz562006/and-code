package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PromptRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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

    private val sessions = ConcurrentHashMap<String, OpenCodeSession>()
    private val messageStore = ConcurrentHashMap<String, MutableList<OpenCodeMessage>>()
    private val events =
        MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 64)

    fun events(): Flow<OpenCodeEvent> = events.asSharedFlow()

    fun version(rootfs: File): String? = GrokBuildInstaller.installedVersion(rootfs)

    fun stopAll() {
        // Headless `-p` runs are one-shot processes; nothing long-lived to kill.
    }

    fun listSessions(): List<OpenCodeSession> = sessions.values.sortedByDescending { it.time.updated ?: it.time.created }

    fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession {
        val now = System.currentTimeMillis()
        val id = "grok-${UUID.randomUUID()}"
        val session =
            OpenCodeSession(
                id = id,
                directory = directory ?: "/workspace",
                title = title?.takeIf { it.isNotBlank() } ?: "Grok session",
                time = OpenCodeTime(created = now, updated = now),
            )
        sessions[id] = session
        messageStore[id] = mutableListOf()
        events.tryEmit(OpenCodeEvent.SessionCreated(session))
        return session
    }

    fun listMessages(sessionId: String): List<OpenCodeMessage> = messageStore[sessionId]?.toList() ?: emptyList()

    /**
     * One headless turn via `grok -p` on the Android host.
     * Multi-turn is approximated by prefixing prior user/assistant text into the prompt.
     */
    fun send(
        rootfs: File,
        sessionId: String,
        request: PromptRequest,
    ) {
        val text = request.text.trim()
        require(text.isNotEmpty()) { "empty message" }
        require(GrokBuildInstaller.isInstalledIn(rootfs)) { "Grok Build is not installed" }
        val apiKey = readApiKey(rootfs)
        require(!apiKey.isNullOrBlank()) {
            "Set an xAI API key in Grok Build agent settings (or connect a provider)"
        }

        val now = System.currentTimeMillis()
        val userInfo =
            OpenCodeMessageInfo(
                id = "user-${UUID.randomUUID()}",
                sessionId = sessionId,
                role = "user",
                time = OpenCodeTime(created = now),
            )
        val userMessage =
            OpenCodeMessage(
                info = userInfo,
                parts =
                    listOf(
                        OpenCodePart(
                            id = "part-${UUID.randomUUID()}",
                            sessionId = sessionId,
                            messageId = userInfo.id,
                            type = "text",
                            text = text,
                        ),
                    ),
            )
        messageStore.getOrPut(sessionId) { mutableListOf() }.add(userMessage)
        events.tryEmit(OpenCodeEvent.MessageUpdated(userInfo))

        val prior =
            messageStore[sessionId]
                .orEmpty()
                .dropLast(1)
                .takeLast(12)

        val modelId = request.modelId?.takeIf { it.isNotBlank() }
        val customs = listCustomProviders(rootfs)
        val custom =
            request.providerId?.takeIf { it.isNotBlank() }?.let { pid ->
                customs.firstOrNull { it.id == pid }
            }
                ?: modelId?.let { mid -> customs.firstOrNull { mid in it.modelIds } }
        val assistantText =
            if (custom != null) {
                val key = readProviderApiKey(rootfs, custom.id)
                require(!key.isNullOrBlank()) {
                    "Connect provider \"${custom.name}\" with an API key in Provider settings"
                }
                require(modelId != null) { "Select a model for provider \"${custom.name}\"" }
                chatCompletions(
                    baseUrl = custom.baseUrl,
                    apiKey = key,
                    modelId = modelId,
                    prior = prior,
                    userText = text,
                )
            } else {
                val key = readApiKey(rootfs)
                require(!key.isNullOrBlank()) {
                    "Set an xAI API key in Grok Build agent settings (Provider: xAI)"
                }
                val historyLines =
                    prior.mapNotNull { msg ->
                        val body = msg.parts.mapNotNull { it.text }.joinToString("").trim()
                        if (body.isEmpty()) null else "${msg.info.role}: $body"
                    }
                val history = historyLines.joinToString("\n")
                val prompt =
                    if (history.isBlank()) {
                        text
                    } else {
                        "Previous conversation:\n" + history + "\n\nuser: " + text + "\nassistant:"
                    }
                val workspace = File(runtimeDirectory, "workspace").apply { mkdirs() }
                val args =
                    buildList {
                        add("-p")
                        add(prompt)
                        add("--output-format")
                        add("json")
                        add("--yolo")
                        (modelId ?: "grok-4.5").takeIf { !it.contains('/') }?.let {
                            add("--model")
                            add(it)
                        }
                    }
                val result =
                    GrokBuildInstaller.runOnHost(
                        rootfs = rootfs,
                        args = args,
                        timeoutSeconds = 300L,
                        workingDirectory = workspace,
                        extraEnv =
                            mapOf(
                                "XAI_API_KEY" to key,
                                "HOME" to File(rootfs, "root").absolutePath,
                            ),
                    )
                parseAssistantText(result.output).ifBlank {
                    if (result.exitCode != 0) {
                        "Grok failed (exit ${result.exitCode}): ${result.output.trim().take(1500)}"
                    } else {
                        result.output.trim().ifBlank { "(empty response)" }
                    }
                }
            }

        val doneAt = System.currentTimeMillis()
        val assistantInfo =
            OpenCodeMessageInfo(
                id = "assistant-${UUID.randomUUID()}",
                sessionId = sessionId,
                role = "assistant",
                time = OpenCodeTime(created = doneAt),
                agent = "Grok Build",
            )
        val assistantMessage =
            OpenCodeMessage(
                info = assistantInfo,
                parts =
                    listOf(
                        OpenCodePart(
                            id = "part-${UUID.randomUUID()}",
                            sessionId = sessionId,
                            messageId = assistantInfo.id,
                            type = "text",
                            text = assistantText,
                        ),
                    ),
            )
        messageStore.getOrPut(sessionId) { mutableListOf() }.add(assistantMessage)
        events.tryEmit(OpenCodeEvent.MessageUpdated(assistantInfo))
        events.tryEmit(OpenCodeEvent.SessionIdle(sessionId))
        sessions[sessionId]?.let { s ->
            sessions[sessionId] = s.copy(time = s.time.copy(updated = doneAt))
        }
        if (result.exitCode != 0 && parseAssistantText(result.output).isBlank()) {
            error(assistantText)
        }
    }


    private fun chatCompletions(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        prior: List<OpenCodeMessage>,
        userText: String,
    ): String {
        var root = baseUrl.trim().trimEnd('/')
        for (suffix in listOf("/chat/completions", "/completions")) {
            if (root.endsWith(suffix)) {
                root = root.removeSuffix(suffix)
                break
            }
        }
        if (!root.endsWith("/v1")) {
            // Accept either API root (.../v1) or host root
            root = root.trimEnd('/')
        }
        val url =
            when {
                root.endsWith("/chat/completions") -> root
                root.endsWith("/v1") -> "$root/chat/completions"
                else -> "$root/v1/chat/completions"
            }
        val messages =
            buildJsonArray {
                prior.forEach { msg ->
                    val body = msg.parts.mapNotNull { it.text }.joinToString("").trim()
                    if (body.isEmpty()) return@forEach
                    val role = if (msg.info.role == "assistant") "assistant" else "user"
                    add(
                        buildJsonObject {
                            put("role", role)
                            put("content", body)
                        },
                    )
                }
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", userText)
                    },
                )
            }
        val bodyJson =
            buildJsonObject {
                put("model", modelId)
                put("messages", messages)
            }
        val client =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        val request =
            Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Provider HTTP ${response.code}: ${raw.take(1200)}")
            }
            val content =
                runCatching {
                    json.parseToJsonElement(raw)
                        .jsonObject["choices"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("message")
                        ?.jsonObject
                        ?.get("content")
                        ?.jsonPrimitive
                        ?.contentOrNull
                }.getOrNull()
            return content?.trim()?.ifBlank { null }
                ?: error("Provider returned empty content: ${raw.take(800)}")
        }
    }

    private fun parseAssistantText(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val candidates =
            listOf(trimmed) +
                trimmed.lines().map { it.trim() }.filter { it.startsWith("{") && it.endsWith("}") }
        for (candidate in candidates.asReversed()) {
            val text =
                runCatching {
                    json.parseToJsonElement(candidate).jsonObject["text"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return trimmed
            .lines()
            .filterNot { it.startsWith("WARNING: linker:") }
            .joinToString("\n")
            .trim()
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
