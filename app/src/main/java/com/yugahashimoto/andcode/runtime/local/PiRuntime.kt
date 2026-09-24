package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodeModel
import com.yugahashimoto.andcode.core.api.OpenCodeModelReference
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PromptRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class PiRuntime(
    internal val runtimeDirectory: File,
    private val installedRuntimeProvider: () -> LocalRuntimeInstaller.InstalledRuntime?,
    private val accessCoordinator: LocalRuntimeAccessCoordinator = LocalRuntimeAccessCoordinator(),
    private val providerCredentials: () -> Map<String, String> = { emptyMap() },
    private val customProviders: () -> List<CustomProviderDefinition> = { emptyList() },
    private val githubToken: () -> String? = { null },
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    private val events = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 256)
    private val processes = ConcurrentHashMap<String, PiProcess>()
    private val streamingAssistantMessages = ConcurrentHashMap<String, String>()
    private val messageStore = ClaudeMessageStore(File(runtimeDirectory, "pi-messages.json"), json)

    private data class PiProcess(val sessionId: String, val process: Process, val pending: ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<JsonObject>>, val directory: String)

    fun events(): Flow<OpenCodeEvent> = events.asSharedFlow()

    fun isInstalled(): Boolean = installedRuntimeProvider()?.rootfs?.let(PiInstaller::isInstalledIn) == true

    fun version(): String? {
        val runtime = installedRuntimeProvider() ?: return null
        if (!PiInstaller.isInstalledIn(runtime.rootfs)) return null
        val result =
            LocalRuntimeCommandRunner(
                runtimeDirectory,
                installedRuntimeProvider,
                accessCoordinator,
            ).runShell("/usr/local/bin/pi --version", 60L)
        return result.output.lineSequence().map(String::trim).firstOrNull(String::isNotBlank)
    }

    suspend fun install(): String {
        val runtime = installedRuntimeProvider() ?: error("Linux environment is not installed")
        return PiInstaller.install(runtime, runtimeDirectory, accessCoordinator)
    }

    suspend fun connect(): OpenCodeHealth =
        if (!isInstalled()) OpenCodeHealth(false, "") else OpenCodeHealth(true, version() ?: PiInstaller.PI_VERSION)

    suspend fun listSessions(directory: String? = null): List<OpenCodeSession> =
        withContext(Dispatchers.IO) {
            val root = File(requireRuntime().rootfs, "root/.pi/agent/sessions")
            if (!root.isDirectory) return@withContext emptyList()
            root.walkTopDown().filter { it.isFile && it.extension == "jsonl" }.mapNotNull { readSession(it, directory) }
                .sortedByDescending { it.time.updated ?: it.time.created }.toList()
        }

    suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession {
        val process = startProcess(null, normalizeDirectory(directory), title)
        if (!title.isNullOrBlank()) {
            send(
                process,
                buildJsonObject {
                    put("type", "set_session_name")
                    put("name", title)
                },
            )
        }
        return session(process.sessionId)
    }

    suspend fun session(sessionId: String): OpenCodeSession =
        listSessions().firstOrNull {
            it.id == sessionId
        } ?: error("Pi session not found: $sessionId")

    suspend fun listMessages(sessionId: String): List<OpenCodeMessage> {
        val process = ensureProcess(sessionId)
        val data = send(process, buildJsonObject { put("type", "get_messages") })["data"]?.jsonObject
        val messages =
            (data?.get("messages") as? JsonArray)
                ?.mapNotNull { parseMessage(it, process.sessionId) }
                .orEmpty()
        messages.forEach { messageStore.upsert(sessionId, it) }
        messageStore.flush()
        return messages
    }

    suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    ) {
        val process = ensureProcess(sessionId)
        if (!request.providerId.isNullOrBlank() && !request.modelId.isNullOrBlank()) {
            val modelResponse =
                send(
                    process,
                    buildJsonObject {
                        put("type", "set_model")
                        put("provider", request.providerId)
                        put("modelId", request.modelId)
                    },
                )
            if ((modelResponse["success"] as? JsonPrimitive)?.booleanOrNull == false) {
                error(
                    (modelResponse["error"] as? JsonPrimitive)?.content ?: "Pi rejected the selected model",
                )
            }
        }
        val response =
            send(
                process,
                buildJsonObject {
                    put("type", "prompt")
                    put("message", request.text)
                    val images =
                        request.attachments.mapNotNull { attachment ->
                            val prefix = "data:${attachment.mime};base64,"
                            if (!attachment.mime.startsWith("image/") || !attachment.url.startsWith(prefix)) return@mapNotNull null
                            buildJsonObject {
                                put("type", "image")
                                put("data", attachment.url.removePrefix(prefix))
                                put("mimeType", attachment.mime)
                            }
                        }
                    if (images.isNotEmpty()) put("images", JsonArray(images))
                },
            )
        if ((response["success"] as? JsonPrimitive)?.booleanOrNull == false) {
            error(
                (response["error"] as? JsonPrimitive)?.content ?: "Pi rejected the prompt",
            )
        }
    }

    suspend fun abort(sessionId: String): Boolean {
        processes[sessionId]?.let {
            send(it, buildJsonObject { put("type", "abort") })
            return true
        }
        return false
    }

    suspend fun renameSession(
        sessionId: String,
        title: String,
    ): OpenCodeSession {
        val process = ensureProcess(sessionId)
        send(
            process,
            buildJsonObject {
                put("type", "set_session_name")
                put("name", title)
            },
        )
        return session(sessionId)
    }

    suspend fun deleteSession(sessionId: String): Boolean {
        processes.remove(sessionId)?.let { stopProcess(it) }
        val file = findSessionFile(sessionId) ?: return false
        return File(requireRuntime().rootfs, file.removePrefix("/")).delete()
    }

    suspend fun availableModels(): List<OpenCodeModel> {
        val process = startProcess(null, "/workspace", null, noSession = true)
        return try {
            parseModels(send(process, buildJsonObject { put("type", "get_available_models") })["data"]?.jsonObject?.get("models"))
        } finally {
            stopProcess(process)
        }
    }

    suspend fun stopAll() {
        processes.values.toList().forEach { stopProcess(it) }
        processes.clear()
    }

    private suspend fun ensureProcess(sessionId: String): PiProcess {
        processes[sessionId]?.let { return it }
        val file = findSessionFile(sessionId) ?: error("Pi session file not found: $sessionId")
        val cwd = readSessionCwd(file) ?: "/workspace"
        return startProcess(file, cwd, null)
    }

    private suspend fun startProcess(
        sessionFile: String?,
        directory: String,
        title: String?,
        noSession: Boolean = false,
    ): PiProcess {
        val runtime = requireRuntime()
        syncProviderCredentials(runtime.rootfs)
        syncCustomProviders(runtime.rootfs)
        val pending = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<JsonObject>>()
        val args =
            buildList {
                add("--mode")
                add("rpc")
                if (noSession) {
                    add("--no-session")
                } else if (sessionFile != null) {
                    add("--session")
                    add(sessionFile)
                } else {
                    add("--session-dir")
                    add("/root/.pi/agent/sessions")
                }
                if (!title.isNullOrBlank()) {
                    add("--name")
                    add(title)
                }
            }
        val builder =
            ProcessBuilder(
                PiSandboxLauncher.command(
                    runtime,
                    File(runtimeDirectory, "workspace").apply {
                        mkdirs()
                    }.absolutePath,
                    directory,
                    args,
                ),
            ).directory(runtimeDirectory)
        builder.environment().clear()
        builder.environment().putAll(
            PiSandboxLauncher.environment(runtime, File(runtimeDirectory, "proot-tmp").apply { mkdirs() }, githubToken()),
        )
        val process = builder.start()
        var currentSessionId = "pending-" + UUID.randomUUID()
        val reader =
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    process.inputStream.bufferedReader().useLines {
                            lines ->
                        lines.forEach { handleLine(it, pending) { currentSessionId } }
                    }
                }.also {
                    val error =
                        it.exceptionOrNull()
                            ?: IllegalStateException("Pi RPC process exited before completing a pending request")
                    pending.values.forEach { deferred -> deferred.completeExceptionally(error) }
                    pending.clear()
                    processes.remove(currentSessionId)
                }
            }
        val provisional = PiProcess(currentSessionId, process, pending, directory)
        val stateResponse =
            runCatching { send(provisional, buildJsonObject { put("type", "get_state") }) }.getOrElse {
                process.destroyForcibly()
                reader.cancel()
                error("Pi RPC handshake failed: ${it.message}")
            }
        val data = stateResponse["data"]?.jsonObject ?: error("Pi get_state returned no data")
        val id = data["sessionId"]?.jsonPrimitive?.content ?: error("Pi get_state returned no sessionId")
        val actualCwd = directory
        currentSessionId = id
        val piProcess = PiProcess(id, process, pending, actualCwd)
        processes[id] = piProcess
        events.tryEmit(OpenCodeEvent.ServerConnected)
        return piProcess
    }

    private fun handleLine(
        line: String,
        pending: ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<JsonObject>>,
        sessionId: () -> String,
    ) {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        when (obj["type"]?.jsonPrimitive?.content) {
            "response" -> obj["id"]?.jsonPrimitive?.content?.let { pending.remove(it)?.complete(obj) }
            else -> mapEvent(obj, sessionId())?.let { events.tryEmit(it) }
        }
    }

    private fun mapEvent(
        obj: JsonObject,
        sessionId: String,
    ): OpenCodeEvent? {
        val type = obj["type"]?.jsonPrimitive?.content ?: return null
        return when (type) {
            "message_start", "message_end" ->
                parseMessage(obj["message"], sessionId)?.also {
                    val messageType = obj["message"]?.jsonObject?.get("role")?.jsonPrimitive?.content
                    if (type == "message_start" && messageType == "assistant") {
                        streamingAssistantMessages[sessionId] = it.info.id
                    }
                    if (type == "message_end" && messageType == "assistant") {
                        streamingAssistantMessages.remove(sessionId)
                    }
                    messageStore.upsert(sessionId, it)
                    if (type == "message_end") messageStore.flush()
                }?.let { OpenCodeEvent.MessageUpdated(it.info) }
            "message_update" -> {
                val update = obj["assistantMessageEvent"]?.jsonObject ?: return null
                val messageId = streamingAssistantMessages[sessionId] ?: return null
                val contentIndex = update["contentIndex"]?.jsonPrimitive?.content ?: return null
                when (update["type"]?.jsonPrimitive?.content) {
                    "text_delta" -> {
                        val delta = update["delta"]?.jsonPrimitive?.contentOrNull ?: return null
                        OpenCodeEvent.MessagePartDelta(
                            sessionId,
                            messageId,
                            "$messageId-text-$contentIndex",
                            "text",
                            delta,
                        )
                    }
                    "toolcall_start", "toolcall_end" -> {
                        val toolCallId =
                            update["id"]?.jsonPrimitive?.content
                                ?: update["toolCall"]?.jsonObject?.get("id")?.jsonPrimitive?.content
                                ?: return null
                        val toolName =
                            update["toolName"]?.jsonPrimitive?.content
                                ?: update["toolCall"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                                ?: "tool"
                        OpenCodeEvent.MessagePartUpdated(
                            OpenCodePart(
                                id = toolCallId,
                                sessionId = sessionId,
                                messageId = messageId,
                                type = "tool",
                                tool = toolName,
                                callID = toolCallId,
                                state =
                                    mapOf(
                                        "status" to
                                            JsonPrimitive(
                                                if (update["type"] == JsonPrimitive("toolcall_end")) "completed" else "running",
                                            ),
                                    ),
                            ),
                        )
                    }
                    else -> null
                }
            }
            "tool_execution_start", "tool_execution_end" -> {
                val toolCallId = obj["toolCallId"]?.jsonPrimitive?.content ?: return null
                val toolName = obj["toolName"]?.jsonPrimitive?.content ?: "tool"
                val messageId = streamingAssistantMessages[sessionId] ?: "pi-tool-${toolCallId}"
                OpenCodeEvent.MessagePartUpdated(
                    OpenCodePart(
                        id = toolCallId,
                        sessionId = sessionId,
                        messageId = messageId,
                        type = "tool",
                        tool = toolName,
                        callID = toolCallId,
                        state =
                            mapOf(
                                "status" to
                                    JsonPrimitive(
                                        if (type == "tool_execution_end") "completed" else "running",
                                    ),
                            ),
                    ),
                )
            }
            "agent_settled" -> OpenCodeEvent.SessionIdle(sessionId)
            else -> null
        }
    }

    private fun parseMessage(
        element: JsonElement?,
        fallbackSessionId: String? = null,
    ): OpenCodeMessage? {
        val obj = element as? JsonObject ?: return null
        val role = obj["role"]?.jsonPrimitive?.content ?: return null
        val id = obj["id"]?.jsonPrimitive?.content ?: "pi-${UUID.randomUUID()}"
        val model =
            (obj["model"] as? JsonObject)?.let {
                OpenCodeModelReference(it["provider"]?.jsonPrimitive?.content ?: "", it["id"]?.jsonPrimitive?.content ?: "")
            }
        val parts =
            (obj["content"] as? JsonArray)?.mapNotNull { p ->
                val part = p as? JsonObject ?: return@mapNotNull null
                when (part["type"]?.jsonPrimitive?.content) {
                    "text" -> OpenCodePart(type = "text", text = part["text"]?.jsonPrimitive?.content)
                    "thinking" -> OpenCodePart(type = "reasoning", text = part["thinking"]?.jsonPrimitive?.content)
                    "toolCall" ->
                        OpenCodePart(
                            type = "tool",
                            tool = part["name"]?.jsonPrimitive?.content,
                            callID = part["id"]?.jsonPrimitive?.content,
                            state = mapOf("status" to JsonPrimitive("completed")),
                        )
                    else -> null
                }
            }.orEmpty()
        val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull ?: fallbackSessionId ?: "unknown"
        return OpenCodeMessage(
            OpenCodeMessageInfo(id, sessionId, role, OpenCodeTime(System.currentTimeMillis(), System.currentTimeMillis()), model = model),
            parts,
        )
    }

    private fun parseModels(element: JsonElement?): List<OpenCodeModel> =
        (element as? JsonArray)?.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val provider = obj["provider"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            OpenCodeModel(id, provider, obj["name"]?.jsonPrimitive?.content ?: id)
        }.orEmpty()

    private fun readSession(
        file: File,
        directory: String?,
    ): OpenCodeSession? {
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        val header = runCatching { json.parseToJsonElement(lines.firstOrNull() ?: return null).jsonObject }.getOrNull() ?: return null
        if (header["type"]?.jsonPrimitive?.content != "session") return null
        val cwd = header["cwd"]?.jsonPrimitive?.content ?: return null
        if (directory != null && directory != cwd) return null
        val id = header["id"]?.jsonPrimitive?.content ?: return null
        val title =
            lines.drop(1).mapNotNull {
                runCatching {
                    json.parseToJsonElement(it).jsonObject
                }.getOrNull()
            }.firstOrNull { it["type"]?.jsonPrimitive?.content == "session_name" }?.get("name")?.jsonPrimitive?.content ?: "Pi"
        return OpenCodeSession(
            id,
            directory = cwd,
            title = title,
            path = file.absolutePath,
            version = PiInstaller.PI_VERSION,
            time = OpenCodeTime(file.lastModified(), file.lastModified()),
        )
    }

    private fun findSessionFile(sessionId: String): String? {
        val root = File(requireRuntime().rootfs, "root/.pi/agent/sessions")
        val file =
            root.walkTopDown().firstOrNull {
                it.isFile && it.extension == "jsonl" &&
                    runCatching {
                        it.readLines().firstOrNull()?.contains("\"id\":\"$sessionId\"") == true
                    }.getOrDefault(false)
            } ?: return null
        return "/" + file.relativeTo(requireRuntime().rootfs).path.replace(File.separatorChar, '/')
    }

    private fun readSessionCwd(sessionFile: String): String? =
        File(requireRuntime().rootfs, sessionFile.removePrefix("/")).takeIf(File::isFile)?.useLines { lines ->
            runCatching {
                json.parseToJsonElement(lines.first()).jsonObject["cwd"]?.jsonPrimitive?.content
            }.getOrNull()
        }

    private fun normalizeDirectory(directory: String?): String {
        val value = directory?.trim().orEmpty()
        if (value.isBlank() || value == "/") return "/workspace"
        require(value == "/workspace" || value.startsWith("/workspace/"))
        return value.trimEnd('/').ifBlank { "/workspace" }
    }

    private fun syncProviderCredentials(rootfs: File) {
        val auth = File(rootfs, "root/.pi/agent/auth.json")
        val existing = runCatching { json.parseToJsonElement(auth.readText()).jsonObject.toMutableMap() }.getOrElse { mutableMapOf() }
        providerCredentials().forEach {
                (provider, key) ->
            if (key.isBlank()) {
                existing.remove(provider)
            } else {
                existing[provider] =
                    buildJsonObject {
                        put("type", "api_key")
                        put("key", key)
                    }
            }
        }
        auth.parentFile?.mkdirs()
        auth.writeText(JsonObject(existing).toString())
        auth.setReadable(false, false)
        auth.setWritable(false, false)
        auth.setReadable(true, true)
        auth.setWritable(true, true)
    }

    private fun syncCustomProviders(rootfs: File) {
        val modelsFile = File(rootfs, "root/.pi/agent/models.json")
        val existing =
            runCatching {
                json.parseToJsonElement(modelsFile.readText()).jsonObject
            }.getOrElse { mutableMapOf<String, JsonElement>() }.toMutableMap()
        val providers =
            (existing["providers"] as? JsonObject)?.toMutableMap()
                ?: mutableMapOf()
        customProviders().map { it.normalized() }.forEach { provider ->
            val apiKey = providerCredentials()[provider.id]?.trim().orEmpty()
            if (apiKey.isEmpty()) return@forEach
            providers[provider.id] =
                buildJsonObject {
                    put("baseUrl", provider.baseUrl)
                    put("api", "openai-completions")
                    put(
                        "models",
                        kotlinx.serialization.json.buildJsonArray {
                            provider.models.forEach { modelId ->
                                add(
                                    buildJsonObject {
                                        put("id", modelId)
                                    },
                                )
                            }
                        },
                    )
                }
        }
        if (providers.isEmpty() && existing["providers"] == null) return
        existing["providers"] = JsonObject(providers)
        modelsFile.parentFile?.mkdirs()
        modelsFile.writeText(JsonObject(existing).toString())
    }

    private fun requireRuntime() = installedRuntimeProvider() ?: error("Linux environment is not installed")

    private suspend fun send(
        process: PiProcess,
        command: JsonObject,
    ): JsonObject {
        val id = UUID.randomUUID().toString()
        val request =
            buildJsonObject {
                command.forEach { (k, v) -> put(k, v) }
                put("id", id)
            }
        val deferred = kotlinx.coroutines.CompletableDeferred<JsonObject>()
        process.pending[id] = deferred
        try {
            synchronized(process.process.outputStream) {
                process.process.outputStream.write((request.toString() + "\n").toByteArray())
                process.process.outputStream.flush()
            }
        } catch (error: Throwable) {
            process.pending.remove(id, deferred)
            throw error
        }
        return try {
            kotlinx.coroutines.withTimeout(60_000L) { deferred.await() }
        } finally {
            process.pending.remove(id, deferred)
        }
    }

    private fun stopProcess(process: PiProcess) {
        runCatching { process.process.destroy() }
        runCatching { process.process.waitFor(5, TimeUnit.SECONDS) }
        if (process.process.isAlive) process.process.destroyForcibly()
        processes.remove(process.sessionId)
    }
}
