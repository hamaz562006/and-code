package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeFileContent
import com.yugahashimoto.andcode.core.api.OpenCodeFileNode
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeModelReference
import com.yugahashimoto.andcode.core.api.OpenCodeSearchMatch
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.RuntimeCapabilities
import com.yugahashimoto.andcode.runtime.RuntimeState
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.RuntimeType
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class PiTarget(internal val runtime: PiRuntime) : RuntimeTarget {
    override val id = LocalAgent.PI.targetId
    override val displayName = "Pi"
    override val agent = LocalAgent.PI
    override val kind = BackendKind.LOCAL
    override val type = RuntimeType.LOCAL
    override val capabilities = RuntimeCapabilities(toolEvents = true)

    private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Disconnected)
    override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    private val files =
        ClaudeWorkspaceFiles(
            workspaceHostDir = File(runtime.runtimeDirectory, "workspace"),
            rootfsHostDir = File(runtime.runtimeDirectory, "environment/rootfs"),
        )

    override suspend fun connect(): Result<OpenCodeHealth> =
        withContext(Dispatchers.IO) {
            runCatching {
                val version = runtime.version() ?: error("Pi is not installed or incompatible with this ABI")
                mutableState.value = RuntimeState.Connected(version)
                OpenCodeHealth(true, version)
            }.onFailure {
                mutableState.value = RuntimeState.Unavailable(it.message ?: "Pi unavailable")
            }
        }

    override fun disconnect() {
        runtime.stopAll()
        mutableState.value = RuntimeState.Disconnected
    }

    override suspend fun health(): OpenCodeHealth = connect().getOrElse { OpenCodeHealth(false, "") }

    override suspend fun listSessions(directory: String?): List<OpenCodeSession> =
        runtime.listSessions(directory).map {
            OpenCodeSession(
                it.id,
                directory = it.directory,
                title = it.title ?: DEFAULT_TITLE,
                time = OpenCodeTime(it.createdAt, it.updatedAt),
            )
        }

    override suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession {
        val id = UUID.randomUUID().toString()
        val dir = directory ?: "/workspace"
        withContext(Dispatchers.IO) {
            runtime.create(id, dir, title, null)
        }
        val now = System.currentTimeMillis()
        return OpenCodeSession(
            id,
            directory = dir,
            title = title ?: DEFAULT_TITLE,
            time = OpenCodeTime(now, now),
        )
    }

    override suspend fun renameSession(
        sessionId: String,
        title: String,
    ): OpenCodeSession {
        withContext(Dispatchers.IO) { runtime.setSessionTitle(sessionId, title) }
        val record = runtime.findSession(sessionId) ?: error("Pi session not found")
        return OpenCodeSession(
            record.id,
            directory = record.directory,
            title = record.title ?: DEFAULT_TITLE,
            time = OpenCodeTime(record.createdAt, record.updatedAt),
        )
    }

    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> {
        val messages = runtime.listMessages(sessionId)
        val model = runtime.findSession(sessionId)?.model ?: return messages
        val reference = OpenCodeModelReference(PiModels.PROVIDER_ID, model)
        return messages.map { message ->
            if (message.info.role == "assistant" && message.info.model == null) {
                message.copy(info = message.info.copy(model = reference))
            } else {
                message
            }
        }
    }

    override suspend fun listProviders(): ProviderCatalog =
        withContext(Dispatchers.IO) { PiModels.catalog() }

    override suspend fun listAgents(): List<OpenCodeAgent> =
        listOf(OpenCodeAgent("pi", "Pi", "primary", true))

    override suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    ) {
        val record = runtime.findSession(sessionId) ?: error("Pi session not found")
        val needsTitle = record.title == null
        if (needsTitle) {
            titleFromPrompt(request.text)?.let { title ->
                withContext(Dispatchers.IO) { runtime.setSessionTitle(sessionId, title) }
            }
        }
        val model = request.modelId ?: record.model
        if (model != record.model) {
            withContext(Dispatchers.IO) { runtime.setSessionModel(sessionId, model) }
        }

        runtime.send(
            sessionId = sessionId,
            directory = record.directory,
            prompt = request.text,
            model = model,
            attachments = request.attachments,
        ).getOrThrow()
    }

    private fun titleFromPrompt(prompt: String): String? {
        val firstLine = prompt.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty) ?: return null
        return if (firstLine.length <= TITLE_LENGTH) firstLine else firstLine.take(TITLE_LENGTH).trimEnd() + "…"
    }

    override suspend fun abortSession(sessionId: String): Boolean {
        runtime.abort(sessionId)
        return true
    }

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean = false

    override suspend fun deleteSession(sessionId: String): Boolean =
        withContext(Dispatchers.IO) { runtime.remove(sessionId) }

    override suspend fun archiveSession(sessionId: String): OpenCodeSession {
        val record = withContext(Dispatchers.IO) { runtime.archive(sessionId) } ?: error("Pi session not found")
        return OpenCodeSession(
            record.id,
            directory = record.directory,
            title = record.title ?: DEFAULT_TITLE,
            time = OpenCodeTime(record.createdAt, record.updatedAt),
        )
    }

    override fun events(): Flow<OpenCodeEvent> = runtime.events()

    override suspend fun listFiles(
        directory: String,
        path: String,
    ): List<OpenCodeFileNode> = withContext(Dispatchers.IO) { files.list(directory, path) }

    override suspend fun readFile(
        directory: String,
        path: String,
    ): OpenCodeFileContent = withContext(Dispatchers.IO) { files.read(directory, path) }

    override suspend fun findFiles(
        directory: String,
        query: String,
        includeDirectories: Boolean?,
        type: String?,
        limit: Int?,
    ): List<String> = withContext(Dispatchers.IO) { files.find(directory, query, includeDirectories, limit) }

    override suspend fun searchText(
        directory: String,
        pattern: String,
    ): List<OpenCodeSearchMatch> = withContext(Dispatchers.IO) { files.search(directory, pattern) }

    override suspend fun listWorkspaces(): List<WorkspaceRef> =
        runtime.workspacePaths().map {
            WorkspaceRef(it, it.substringAfterLast('/').ifBlank { it }, it)
        }.distinctBy { it.id }

    private companion object {
        const val DEFAULT_TITLE = "Pi"
        const val TITLE_LENGTH = 40
    }
}
