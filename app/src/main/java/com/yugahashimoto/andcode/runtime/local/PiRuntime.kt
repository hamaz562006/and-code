package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.McpServer
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs Pi inside the shared Alpine/PRoot sandbox over its `--mode rpc` JSONL protocol.
 *
 * Commands go on stdin; `response` records and session events arrive on stdout. Events are mapped
 * into the shared [OpenCodeEvent]/[OpenCodeMessage]/[OpenCodePart] model so the existing chat UI
 * renders Pi sessions with no UI changes. This is event-model reuse only — Pi does not speak HTTP
 * and does not go through any OpenCode backend.
 *
 * Protocol reference: https://github.com/earendil-works/pi/blob/v0.87.1/packages/coding-agent/docs/rpc.md
 */
class PiRuntime(
    internal val runtimeDirectory: File,
    private val installedRuntime: () -> LocalRuntimeInstaller.InstalledRuntime?,
    private val accessCoordinator: LocalRuntimeAccessCoordinator = LocalRuntimeAccessCoordinator(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val events = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 256)
    private val messageStore = ConcurrentHashMap<String, MutableList<OpenCodeMessage>>()
    private val sessions = ConcurrentHashMap<String, OpenCodeSession>()

    private val processLock = Mutex()
    private var serverProcess: ServerProcess? = null
    private var readerJob: Job? = null
    private val requestCounter = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    private class ServerProcess(
        val process: Process,
        val stdin: BufferedWriter,
        val stdout: BufferedReader,
    )

    fun events(): Flow<OpenCodeEvent> = events

    fun announceConnected() {
        scope.launch { events.emit(OpenCodeEvent.ServerConnected) }
    }

    fun isInstalled(): Boolean {
        val runtime = installedRuntime() ?: return false
        return PiInstaller.isInstalledIn(runtime.rootfs)
    }

    fun version(): String? {
        if (!isInstalled()) return null
        val runtime = installedRuntime() ?: return null
        val recorded = PiInstaller.installedVersion(runtime.rootfs)
        if (!recorded.isNullOrBlank()) return recorded
        return runCatching {
            val workspace = File(runtimeDirectory, "workspace").apply { mkdirs() }
            val tmp = File(runtimeDirectory, "tmp").apply { mkdirs() }
            val command =
                PiSandboxLauncher.command(
                    runtime,
                    workspace.absolutePath,
                    listOf("--version"),
                )
            val process =
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .apply {
                        environment().putAll(PiSandboxLauncher.environment(runtime, tmp))
                        environment()["PROOT_TMP_DIR"] =
                            File(runtimeDirectory, "proot-tmp").apply { mkdirs() }.absolutePath
                    }
                    .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val completed = process.waitFor(10, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return PiInstaller.PI_VERSION
            }
            if (process.exitValue() != 0) return PiInstaller.PI_VERSION
            output.lines().firstOrNull { it.isNotBlank() }?.trim() ?: PiInstaller.PI_VERSION
        }.getOrDefault(PiInstaller.PI_VERSION)
    }

    private val authJson =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /** Provider ids that currently have a non-blank key in `~/.pi/agent/auth.json`. */
    fun connectedProviderIds(): Set<String> {
        val rootfs = installedRuntime()?.rootfs ?: return emptySet()
        val file = File(rootfs, "root/.pi/agent/auth.json")
        if (!file.isFile) return emptySet()
        return runCatching {
            val obj = authJson.parseToJsonElement(file.readText()).jsonObject
            obj.mapNotNull { (id, value) ->
                val key = value.jsonObject["key"]?.toString()?.trim('"')
                id.takeIf { !key.isNullOrBlank() }
            }.toSet()
        }.getOrDefault(emptySet())
    }

    /**
     * Writes an OpenAI-compatible custom provider into `~/.pi/agent/models.json` (Pi format).
     * Base URL should be the API root (e.g. `http://host:port/v1`), not `.../chat/completions`.
     */

    data class CustomProviderEntry(
        val id: String,
        val name: String,
        val modelIds: List<String>,
    )

    /** Reads user-defined providers from `~/.pi/agent/models.json`. */
    fun listCustomProviders(): List<CustomProviderEntry> {
        val rootfs = installedRuntime()?.rootfs ?: return emptyList()
        val file = File(rootfs, "root/.pi/agent/models.json")
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = authJson.parseToJsonElement(file.readText()).jsonObject
            val providers = root["providers"]?.jsonObject ?: return emptyList()
            providers.mapNotNull { (id, value) ->
                val obj = value.jsonObject
                val models =
                    obj["models"]?.jsonArray?.mapNotNull { el ->
                        el.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                    }.orEmpty()
                if (models.isEmpty()) return@mapNotNull null
                CustomProviderEntry(
                    id = id,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id,
                    modelIds = models,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun registerCustomProvider(
        id: String,
        name: String,
        baseUrl: String,
        modelIds: List<String>,
    ) {
        val rootfs = installedRuntime()?.rootfs ?: error("Pi environment is not installed")
        val dir = File(rootfs, "root/.pi/agent").apply { mkdirs() }
        val file = File(dir, "models.json")
        val existing =
            if (file.isFile) {
                runCatching { authJson.parseToJsonElement(file.readText()).jsonObject }.getOrDefault(buildJsonObject {})
            } else {
                buildJsonObject {}
            }
        val existingProviders =
            existing["providers"]?.jsonObject ?: buildJsonObject {}
        var normalizedBase = baseUrl.trim().trimEnd('/')
        // Users often paste the full chat completions path; Pi expects the API root.
        for (suffix in listOf("/chat/completions", "/completions")) {
            if (normalizedBase.endsWith(suffix)) {
                normalizedBase = normalizedBase.removeSuffix(suffix)
                break
            }
        }
        val modelsArray =
            kotlinx.serialization.json.buildJsonArray {
                modelIds.forEach { mid ->
                    add(
                        buildJsonObject {
                            put("id", mid)
                            put("name", mid)
                        },
                    )
                }
            }
        val providerEntry =
            buildJsonObject {
                put("baseUrl", normalizedBase)
                put("api", "openai-completions")
                put("apiKey", id) // placeholder; real key goes in auth.json via setApiKey
                put("name", name)
                put("models", modelsArray)
            }
        val updatedProviders =
            buildJsonObject {
                existingProviders.forEach { (k, v) ->
                    if (k != id) put(k, v)
                }
                put(id, providerEntry)
            }
        val updated =
            buildJsonObject {
                existing.forEach { (k, v) ->
                    if (k != "providers") put(k, v)
                }
                put("providers", updatedProviders)
            }
        file.writeText(authJson.encodeToString(JsonObject.serializer(), updated))
    }

    /**
     * Writes or removes an API key in the Pi auth file (`~/.pi/agent/auth.json` inside the rootfs).
     * Format matches earendil-works/pi: `{ "openai": { "type": "api_key", "key": "..." } }`.
     */
    fun setApiKey(
        providerId: String,
        apiKey: String?,
    ) {
        val rootfs = installedRuntime()?.rootfs ?: error("Pi environment is not installed")
        val dir = File(rootfs, "root/.pi/agent").apply { mkdirs() }
        val file = File(dir, "auth.json")
        val existing =
            if (file.isFile) {
                runCatching { authJson.parseToJsonElement(file.readText()).jsonObject }.getOrDefault(
                    buildJsonObject {},
                )
            } else {
                buildJsonObject {}
            }
        val updated =
            buildJsonObject {
                existing.forEach { (k, v) ->
                    if (k != providerId) put(k, v)
                }
                val trimmed = apiKey?.trim().orEmpty()
                if (trimmed.isNotEmpty()) {
                    put(
                        providerId,
                        buildJsonObject {
                            put("type", "api_key")
                            put("key", trimmed)
                        },
                    )
                }
            }
        file.writeText(authJson.encodeToString(JsonObject.serializer(), updated))
    }

    suspend fun install(abi: String): String {
        val runtime = installedRuntime() ?: error("The Linux environment is not installed yet")
        return PiInstaller.install(
            rootfs = runtime.rootfs,
            abi = abi,
            runtimeDirectory = runtimeDirectory,
            accessCoordinator = accessCoordinator,
        )
    }

    suspend fun listSessions(): List<OpenCodeSession> =
        withContext(Dispatchers.IO) {
            sessions.values.sortedByDescending { it.time.updated ?: it.time.created }
        }

    suspend fun createSession(
        title: String?,
        directory: String,
    ): OpenCodeSession =
        withContext(Dispatchers.IO) {
            ensureServer(directory)
            val id = "pi-${UUID.randomUUID()}"
            runCatching {
                call(
                    buildJsonObject {
                        put("type", "new_session")
                    },
                )
            }
            val now = System.currentTimeMillis()
            val session =
                OpenCodeSession(
                    id = id,
                    title = title?.takeIf { it.isNotBlank() } ?: "Pi session",
                    directory = directory,
                    version = version() ?: PiInstaller.PI_VERSION,
                    time = OpenCodeTime(created = now, updated = now),
                )
            sessions[id] = session
            messageStore[id] = mutableListOf()
            events.emit(OpenCodeEvent.SessionCreated(session))
            session
        }

    suspend fun renameSession(
        sessionId: String,
        title: String,
    ): OpenCodeSession =
        withContext(Dispatchers.IO) {
            runCatching {
                call(
                    buildJsonObject {
                        put("type", "set_session_name")
                        put("name", title)
                    },
                )
            }
            val existing = sessions[sessionId] ?: error("Unknown Pi session: $sessionId")
            val updated =
                existing.copy(
                    title = title,
                    time = existing.time.copy(updated = System.currentTimeMillis()),
                )
            sessions[sessionId] = updated
            events.emit(OpenCodeEvent.SessionUpdated(updated))
            updated
        }

    suspend fun deleteSession(sessionId: String): Boolean =
        withContext(Dispatchers.IO) {
            val removed = sessions.remove(sessionId) != null
            messageStore.remove(sessionId)
            removed
        }

    fun listMessages(sessionId: String): List<OpenCodeMessage> = messageStore[sessionId]?.toList() ?: emptyList()

    suspend fun send(
        sessionId: String,
        text: String,
        modelId: String? = null,
    ): Unit =
        withContext(Dispatchers.IO) {
            ensureServer("/workspace")
            if (!modelId.isNullOrBlank()) {
                runCatching {
                    call(
                        buildJsonObject {
                            put("type", "set_model")
                            put("model", modelId)
                        },
                    )
                }
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
            events.emit(OpenCodeEvent.MessageUpdated(userInfo))
            call(
                buildJsonObject {
                    put("type", "prompt")
                    put("message", text)
                },
            )
            sessions[sessionId]?.let { s ->
                sessions[sessionId] = s.copy(time = s.time.copy(updated = System.currentTimeMillis()))
            }
        }

    private fun mcpConfigFile(): File {
        val rootfs = installedRuntime()?.rootfs ?: File(runtimeDirectory, "environment/rootfs")
        return File(rootfs, "root/.pi/mcp.json")
    }

    fun mcpServers(): List<McpServer> = PiMcp.list(mcpConfigFile())

    fun addMcpServer(
        name: String,
        url: String?,
        command: String?,
    ): McpServer = PiMcp.add(mcpConfigFile(), name, url, command)

    fun removeMcpServer(name: String): Boolean = PiMcp.remove(mcpConfigFile(), name)

    suspend fun abort(sessionId: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                call(
                    buildJsonObject {
                        put("type", "abort")
                    },
                )
                true
            }.getOrDefault(false)
        }

    fun stopAll() {
        scope.launch {
            processLock.withLock { stopServerLocked() }
        }
    }

    private suspend fun ensureServer(workspaceDir: String): ServerProcess =
        processLock.withLock {
            serverProcess?.takeIf { it.process.isAlive }?.let { return it }
            stopServerLocked()
            val runtime = installedRuntime() ?: error("Pi runtime is not installed")
            check(PiInstaller.isInstalledIn(runtime.rootfs)) { "Pi binary is not installed in the rootfs" }
            val workspace = File(runtimeDirectory, "workspace").apply { mkdirs() }
            val hostWorkspace =
                if (workspaceDir == "/workspace" || workspaceDir.isBlank()) {
                    workspace
                } else {
                    File(workspaceDir).takeIf { it.isDirectory } ?: workspace
                }
            val tmp = File(runtimeDirectory, "tmp").apply { mkdirs() }
            val command =
                PiSandboxLauncher.command(
                    runtime,
                    hostWorkspace.absolutePath,
                    listOf("--mode", "rpc", "--no-session"),
                )
            val process =
                ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .apply {
                        environment().putAll(PiSandboxLauncher.environment(runtime, tmp))
                        environment()["PROOT_TMP_DIR"] =
                            File(runtimeDirectory, "proot-tmp").apply { mkdirs() }.absolutePath
                    }
                    .start()
            val stdin = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
            val stdout = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            val server = ServerProcess(process, stdin, stdout)
            serverProcess = server
            readerJob =
                scope.launch {
                    try {
                        while (process.isAlive) {
                            val line = stdout.readLine() ?: break
                            handleStdoutLine(line)
                        }
                    } finally {
                        processLock.withLock {
                            if (serverProcess === server) {
                                serverProcess = null
                            }
                        }
                    }
                }
            scope.launch {
                runCatching {
                    process.errorStream.bufferedReader().use { reader ->
                        while (true) {
                            reader.readLine() ?: break
                        }
                    }
                }
            }
            server
        }

    private fun stopServerLocked() {
        readerJob?.cancel()
        readerJob = null
        serverProcess?.let { server ->
            runCatching { server.stdin.close() }
            runCatching {
                if (!server.process.waitFor(3, TimeUnit.SECONDS)) {
                    server.process.destroyForcibly()
                }
            }
        }
        serverProcess = null
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    private suspend fun call(command: JsonObject): JsonObject {
        val id = "req-${requestCounter.incrementAndGet()}"
        val payload =
            buildJsonObject {
                put("id", id)
                command.forEach { (k, v) -> put(k, v) }
            }
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val server = processLock.withLock { serverProcess } ?: error("Pi RPC process is not running")
        withContext(Dispatchers.IO) {
            server.stdin.write(payload.toString())
            server.stdin.write("\n")
            server.stdin.flush()
        }
        return try {
            withTimeout(120_000) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun handleStdoutLine(line: String) {
        if (line.isBlank()) return
        val element = runCatching { json.parseToJsonElement(line) }.getOrNull() ?: return
        val obj = element.jsonObject
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return

        when (type) {
            "response" -> {
                val id = obj["id"]?.jsonPrimitive?.contentOrNull
                if (id != null) {
                    pending.remove(id)?.complete(obj)
                }
            }
            "message_update" -> {
                val assistantEvent = obj["assistantMessageEvent"]?.jsonObject
                val deltaType = assistantEvent?.get("type")?.jsonPrimitive?.contentOrNull
                val delta = assistantEvent?.get("delta")?.jsonPrimitive?.contentOrNull
                if (deltaType == "text_delta" && !delta.isNullOrEmpty()) {
                    val sessionId = sessions.keys.lastOrNull() ?: return
                    val messageId = "assistant-$sessionId"
                    val partId = "part-$messageId"
                    val store = messageStore.getOrPut(sessionId) { mutableListOf() }
                    val existing = store.find { it.info.id == messageId && it.info.role == "assistant" }
                    if (existing == null) {
                        val info =
                            OpenCodeMessageInfo(
                                id = messageId,
                                sessionId = sessionId,
                                role = "assistant",
                                time = OpenCodeTime(created = System.currentTimeMillis()),
                            )
                        store.add(
                            OpenCodeMessage(
                                info = info,
                                parts =
                                    listOf(
                                        OpenCodePart(
                                            id = partId,
                                            sessionId = sessionId,
                                            messageId = messageId,
                                            type = "text",
                                            text = delta,
                                        ),
                                    ),
                            ),
                        )
                        events.emit(OpenCodeEvent.MessageUpdated(info))
                    } else {
                        val prevText = existing.parts.firstOrNull()?.text.orEmpty()
                        val updatedPart =
                            OpenCodePart(
                                id = partId,
                                sessionId = sessionId,
                                messageId = messageId,
                                type = "text",
                                text = prevText + delta,
                            )
                        val idx = store.indexOfFirst { it.info.id == messageId }
                        if (idx >= 0) {
                            store[idx] = existing.copy(parts = listOf(updatedPart))
                        }
                    }
                    events.emit(
                        OpenCodeEvent.MessagePartDelta(
                            sessionId = sessionId,
                            messageId = messageId,
                            partId = partId,
                            field = "text",
                            delta = delta,
                        ),
                    )
                }
            }
            "agent_settled", "agent_end" -> {
                val sessionId = sessions.keys.lastOrNull() ?: return
                events.emit(OpenCodeEvent.SessionIdle(sessionId))
                events.emit(OpenCodeEvent.SessionStatusChanged(sessionId, "idle"))
            }
            else -> {
                // Other session events accepted but not yet fully mapped.
            }
        }
    }
}
