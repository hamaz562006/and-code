package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeFileContent
import com.yugahashimoto.andcode.core.api.OpenCodeFileNode
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.core.api.OpenCodeSearchMatch
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.PromptRequest
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

class PiTarget(private val runtime: PiRuntime) : RuntimeTarget {
    override val id = LocalAgent.PI.targetId
    override val displayName = "Pi"
    override val agent = LocalAgent.PI
    override val kind = BackendKind.LOCAL
    override val type = RuntimeType.LOCAL
    override val capabilities = RuntimeCapabilities(toolEvents = true, providerModelList = true, resume = true)

    private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Disconnected)
    override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    override suspend fun connect(): Result<OpenCodeHealth> = withContext(Dispatchers.IO) {
        runCatching {
            val health = runtime.connect()
            if (!health.healthy) error("Pi is not installed")
            mutableState.value = RuntimeState.Connected(health.version)
            health
        }.onFailure { mutableState.value = RuntimeState.Unavailable(it.message ?: "Pi unavailable") }
    }

    override fun disconnect() {
        kotlinx.coroutines.runBlocking { runtime.stopAll() }
        mutableState.value = RuntimeState.Disconnected
    }

    override suspend fun health() = runtime.connect()
    override suspend fun listSessions(directory: String?) = runtime.listSessions(directory)
    override suspend fun createSession(title: String?, directory: String?) = runtime.createSession(title, directory)
    override suspend fun listMessages(sessionId: String) = runtime.listMessages(sessionId)
    override suspend fun listProviders() = ProviderCatalog(all = runtime.availableModels().groupBy { it.providerId.orEmpty() }.filterKeys(String::isNotBlank).map { (id, models) -> OpenCodeProvider(id, id, models.associateBy { it.id }) })
    override suspend fun listAgents() = listOf(OpenCodeAgent("pi", "Pi", "primary", true))
    override suspend fun sendMessage(sessionId: String, request: PromptRequest) = runtime.sendMessage(sessionId, request)
    override suspend fun abortSession(sessionId: String) = runtime.abort(sessionId)
    override suspend fun renameSession(sessionId: String, title: String) = runtime.renameSession(sessionId, title)
    override suspend fun deleteSession(sessionId: String) = runtime.deleteSession(sessionId)
    override suspend fun respondToPermission(sessionId: String, permissionId: String, response: PermissionResponse, remember: Boolean): Boolean = false

    override suspend fun listFiles(directory: String, path: String): List<OpenCodeFileNode> =
        withContext(Dispatchers.IO) { ClaudeWorkspaceFiles(File(runtime.runtimeDirectory, "workspace"), File(runtime.runtimeDirectory, "environment/rootfs")).list(directory, path) }

    override suspend fun readFile(directory: String, path: String): OpenCodeFileContent =
        withContext(Dispatchers.IO) { ClaudeWorkspaceFiles(File(runtime.runtimeDirectory, "workspace"), File(runtime.runtimeDirectory, "environment/rootfs")).read(directory, path) }

    override suspend fun searchText(directory: String, pattern: String): List<OpenCodeSearchMatch> =
        withContext(Dispatchers.IO) { ClaudeWorkspaceFiles(File(runtime.runtimeDirectory, "workspace"), File(runtime.runtimeDirectory, "environment/rootfs")).search(directory, pattern) }

    override fun events(): Flow<OpenCodeEvent> = runtime.events()

    override suspend fun listWorkspaces(): List<WorkspaceRef> =
        listSessions().mapNotNull { it.directory?.let { path -> WorkspaceRef(path, path.substringAfterLast('/').ifBlank { path }, path) } }.distinctBy { it.id }
}
