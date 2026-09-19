package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PromptAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID

@Serializable
data class PiSessionRecord(
    @SerialName("id") val id: String,
    @SerialName("directory") val directory: String,
    @SerialName("title") val title: String? = null,
    @SerialName("model") val model: String? = null,
    @SerialName("createdAt") val createdAt: Long = System.currentTimeMillis(),
    @SerialName("updatedAt") val updatedAt: Long = createdAt,
    @SerialName("archived") val archived: Boolean = false,
)

class PiRuntime(
    val runtimeDirectory: File,
    private val installedRuntimeProvider: () -> LocalRuntimeInstaller.InstalledRuntime?,
    private val accessCoordinator: LocalRuntimeAccessCoordinator = LocalRuntimeAccessCoordinator(),
    private val githubToken: () -> String? = { null },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        },
) {
    private val workspaceHostDir = File(runtimeDirectory, "workspace").apply { mkdirs() }
    private val prootTmp = File(runtimeDirectory, "proot-tmp").apply { mkdirs() }
    private val sessionsFile = File(runtimeDirectory, "pi-sessions.json")
    private val messageStore = ClaudeMessageStore(File(runtimeDirectory, "pi-messages.json"), json)
    private val records = linkedMapOf<String, PiSessionRecord>()

    private val events = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 64)
    fun events(): Flow<OpenCodeEvent> = events.asSharedFlow()

    private val sessions = mutableMapOf<String, SessionProcess>()

    private data class SessionProcess(
        val process: Process,
        val readerJob: Job,
        val parser: PiStreamJsonParser,
        val directory: String,
        val model: String?,
    )

    init {
        loadSessions()
    }

    fun isInstalled(): Boolean {
        val rootfs = installedRuntimeProvider()?.rootfs ?: return false
        return PiInstaller.isInstalled(rootfs)
    }

    fun version(): String? {
        val rootfs = installedRuntimeProvider()?.rootfs ?: return null
        return if (PiInstaller.isInstalled(rootfs)) {
            PiInstaller.installedVersion(rootfs) ?: PiManifest.VERSION
        } else {
            null
        }
    }

    @Synchronized
    fun send(
        sessionId: String,
        directory: String,
        prompt: String,
        model: String?,
        attachments: List<PromptAttachment> = emptyList(),
    ): Result<Unit> =
        runCatching {
            val session = ensureProcess(sessionId, directory.ifBlank { "/workspace" }, model)
            session.parser.beginTurn()
            recordUserMessage(sessionId, prompt, attachments)

            val requestId = UUID.randomUUID().toString()
            val command =
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(requestId),
                        "type" to JsonPrimitive("prompt"),
                        "message" to JsonPrimitive(prompt),
                    ),
                )

            session.process.outputStream.apply {
                write((json.encodeToString(JsonObject.serializer(), command) + "\n").toByteArray(Charsets.UTF_8))
                flush()
            }
            Unit
        }.onFailure { error ->
            events.tryEmit(OpenCodeEvent.SessionError(sessionId, error.message))
            events.tryEmit(OpenCodeEvent.SessionIdle(sessionId))
        }

    @Synchronized
    fun abort(sessionId: String): Boolean {
        val session = sessions[sessionId] ?: return false
        runCatching {
            val abortCommand =
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(UUID.randomUUID().toString()),
                        "type" to JsonPrimitive("abort"),
                    ),
                )
            session.process.outputStream.apply {
                write((json.encodeToString(JsonObject.serializer(), abortCommand) + "\n").toByteArray(Charsets.UTF_8))
                flush()
            }
        }
        events.tryEmit(OpenCodeEvent.SessionIdle(sessionId))
        return true
    }

    @Synchronized
    fun stop(sessionId: String) {
        sessions.remove(sessionId)?.let { session ->
            session.readerJob.cancel()
            if (session.process.isAlive) {
                session.process.destroyForcibly()
            }
        }
        messageStore.flush()
    }

    @Synchronized
    fun stopAll() {
        sessions.keys.toList().forEach(::stop)
    }

    @Synchronized
    fun abortAll() {
        sessions.keys.toList().forEach(::abort)
    }

    @Synchronized
    fun listMessages(sessionId: String): List<OpenCodeMessage> {
        if (sessions[sessionId]?.process?.isAlive != true) {
            return messageStore.settleRunningTools(sessionId, "Pi process terminated")
        }
        return messageStore.list(sessionId)
    }

    @Synchronized
    fun deleteSessionData(sessionId: String) {
        stop(sessionId)
        records.remove(sessionId)
        persistSessions()
        messageStore.remove(sessionId)
    }

    @Synchronized
    fun listSessions(directory: String?): List<PiSessionRecord> {
        return records.values
            .filter { !it.archived && (directory == null || it.directory == directory) }
            .sortedByDescending { it.updatedAt }
    }

    @Synchronized
    fun findSession(sessionId: String): PiSessionRecord? = records[sessionId]

    @Synchronized
    fun create(
        id: String,
        directory: String,
        title: String?,
        model: String?,
    ): PiSessionRecord {
        val now = System.currentTimeMillis()
        val record =
            PiSessionRecord(
                id = id,
                directory = directory,
                title = title,
                model = model,
                createdAt = now,
                updatedAt = now,
            )
        records[id] = record
        persistSessions()
        return record
    }

    @Synchronized
    fun setSessionTitle(
        sessionId: String,
        title: String,
    ) {
        val existing = records[sessionId] ?: return
        records[sessionId] = existing.copy(title = title, updatedAt = System.currentTimeMillis())
        persistSessions()
    }

    @Synchronized
    fun setSessionModel(
        sessionId: String,
        model: String?,
    ) {
        val existing = records[sessionId] ?: return
        records[sessionId] = existing.copy(model = model, updatedAt = System.currentTimeMillis())
        persistSessions()
    }

    @Synchronized
    fun archive(sessionId: String): PiSessionRecord? {
        stop(sessionId)
        val existing = records[sessionId] ?: return null
        val updated = existing.copy(archived = true, updatedAt = System.currentTimeMillis())
        records[sessionId] = updated
        persistSessions()
        return updated
    }

    @Synchronized
    fun remove(sessionId: String): Boolean {
        deleteSessionData(sessionId)
        return true
    }

    @Synchronized
    fun workspacePaths(): List<String> =
        (records.values.map { it.directory } + listOf("/workspace")).distinct()

    private fun ensureProcess(
        sessionId: String,
        directory: String,
        model: String?,
    ): SessionProcess {
        val existing = sessions[sessionId]
        if (existing != null && existing.process.isAlive && existing.directory == directory && existing.model == model) {
            return existing
        }
        stop(sessionId)

        val runtime = installedRuntimeProvider() ?: error("Pi runtime environment is not ready")
        require(PiInstaller.isInstalled(runtime.rootfs)) { "Pi is not installed" }

        val arguments =
            buildList {
                add("--mode")
                add("rpc")
                if (model != null && model != "default") {
                    add("--model")
                    add(model)
                }
            }

        val command =
            PiSandboxLauncher.command(
                runtime = runtime,
                workspaceHostDir = workspaceHostDir,
                workingDirectory = directory,
                arguments = arguments,
            )

        val builder = ProcessBuilder(command)
        builder.environment().clear()
        builder.environment().putAll(
            PiSandboxLauncher.environment(
                runtime = runtime,
                tmp = prootTmp,
                githubToken = githubToken(),
            ),
        )

        val logDir = File(runtimeDirectory, "logs").apply { mkdirs() }
        builder.redirectError(File(logDir, "pi-stderr.log"))

        val process = builder.start()
        val parser = PiStreamJsonParser(sessionId, json)

        val readerJob =
            scope.launch {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        handleLine(sessionId, parser, line, model)
                    }
                }.exceptionOrNull().takeUnless { it is CancellationException }
                messageStore.flush()

                synchronized(this@PiRuntime) {
                    if (sessions[sessionId]?.process === process) {
                        sessions.remove(sessionId)
                    }
                }

                if (!isActive) return@launch
                if (!parser.turnFinished) {
                    val exitCode = runCatching { process.exitValue() }.getOrNull()
                    events.tryEmit(OpenCodeEvent.SessionError(sessionId, "Pi process exited ($exitCode)"))
                    events.tryEmit(OpenCodeEvent.SessionIdle(sessionId))
                }
            }

        val sessionProcess = SessionProcess(process, readerJob, parser, directory, model)
        sessions[sessionId] = sessionProcess
        return sessionProcess
    }

    private fun handleLine(
        sessionId: String,
        parser: PiStreamJsonParser,
        line: String,
        requestedModel: String?,
    ) {
        if (line.isBlank()) return
        val parsed = parser.parse(line)
        parsed.messages.forEach { message -> messageStore.upsert(sessionId, message) }
        parsed.events.forEach(events::tryEmit)
        if (parsed.turnFinished) {
            messageStore.flush()
        }
    }

    private fun recordUserMessage(
        sessionId: String,
        prompt: String,
        attachments: List<PromptAttachment>,
    ) {
        val timestamp = System.currentTimeMillis()
        val messageId = "user-${UUID.randomUUID()}"
        val parts =
            buildList {
                add(OpenCodePart("$messageId-text", sessionId, messageId, "text", text = prompt))
                attachments.forEachIndexed { index, attachment ->
                    add(
                        OpenCodePart(
                            id = "$messageId-file-$index",
                            sessionId = sessionId,
                            messageId = messageId,
                            type = "file",
                            filename = attachment.filename,
                            mime = attachment.mime,
                            url = attachment.url,
                        ),
                    )
                }
            }
        messageStore.upsert(
            sessionId,
            OpenCodeMessage(
                info =
                    OpenCodeMessageInfo(
                        id = messageId,
                        sessionId = sessionId,
                        role = "user",
                        time = OpenCodeTime(timestamp, timestamp),
                    ),
                parts = parts,
            ),
        )
    }

    private fun loadSessions() {
        runCatching {
            if (!sessionsFile.exists()) return
            val loaded = json.decodeFromString(ListSerializer(PiSessionRecord.serializer()), sessionsFile.readText())
            loaded.forEach { records[it.id] = it }
        }
    }

    private fun persistSessions() {
        runCatching {
            sessionsFile.parentFile?.mkdirs()
            val text = json.encodeToString(ListSerializer(PiSessionRecord.serializer()), records.values.toList())
            sessionsFile.writeText(text)
        }
    }
}
