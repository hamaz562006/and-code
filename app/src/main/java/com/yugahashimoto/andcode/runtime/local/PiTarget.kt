package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.McpServer
import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeFileContent
import com.yugahashimoto.andcode.core.api.OpenCodeFileNode
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeSearchMatch
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderAuthMethod
import com.yugahashimoto.andcode.core.api.ProviderCatalog
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * Adapts [PiRuntime] to this app's [RuntimeTarget] interface.
 *
 * File browsing reuses [ClaudeWorkspaceFiles] the same way [CodexTarget] does: Pi shares the same
 * `/workspace` bind mount as every other local agent. Auth is owned by Pi itself under `~/.pi/`
 * inside the rootfs; no AndCode-side OAuth flow is invented here.
 */
class PiTarget(
    private val runtime: PiRuntime,
) : RuntimeTarget {
    override val id = LocalAgent.PI.targetId
    override val displayName = "Pi"
    override val agent = LocalAgent.PI
    override val kind = BackendKind.LOCAL
    override val type = RuntimeType.LOCAL

    // Streaming + tool events are wired through PiRuntime's message_update mapping.
    // Session resume is supported via create/list; provider model list is exposed through PiModels.
    override val capabilities =
        RuntimeCapabilities(
            permissions = false,
            toolEvents = true,
            providerModelList = true,
        )

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
                val version = runtime.version() ?: error("Pi is not installed")
                val wasConnected = mutableState.value is RuntimeState.Connected
                mutableState.value = RuntimeState.Connected(version)
                if (!wasConnected) runtime.announceConnected()
                OpenCodeHealth(true, version)
            }.onFailure { mutableState.value = RuntimeState.Unavailable(it.message ?: "Pi unavailable") }
        }

    override fun disconnect() {
        runtime.stopAll()
        mutableState.value = RuntimeState.Disconnected
    }

    override suspend fun health(): OpenCodeHealth = connect().getOrElse { OpenCodeHealth(false, "") }

    override suspend fun listProviders(): ProviderCatalog =
        PiModels.catalog(connectedIds = runtime.connectedProviderIds())

    override suspend fun providerAuthMethods(): Map<String, List<ProviderAuthMethod>> =
        PiModels.SEED_PROVIDERS.associate { seed ->
            seed.id to listOf(ProviderAuthMethod(type = "api", label = "API key"))
        }

    override suspend fun setProviderApiKey(
        providerId: String,
        apiKey: String,
        metadata: Map<String, String>,
    ): Boolean =
        withContext(Dispatchers.IO) {
            runtime.setApiKey(providerId, apiKey)
            true
        }

    override suspend fun removeProviderAuth(providerId: String): Boolean =
        withContext(Dispatchers.IO) {
            runtime.setApiKey(providerId, null)
            true
        }


    override suspend fun listAgents(): List<OpenCodeAgent> =
        listOf(OpenCodeAgent(name = "pi", description = "Pi", mode = "primary", native = true))

    override suspend fun listSessions(directory: String?): List<OpenCodeSession> = withContext(Dispatchers.IO) { runtime.listSessions() }

    override suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession =
        withContext(Dispatchers.IO) {
            runtime.createSession(title, directory ?: "/workspace")
        }

    override suspend fun renameSession(
        sessionId: String,
        title: String,
    ): OpenCodeSession =
        withContext(Dispatchers.IO) {
            runtime.renameSession(sessionId, title)
        }

    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> =
        withContext(Dispatchers.IO) { runtime.listMessages(sessionId) }

    override suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    ) {
        withContext(Dispatchers.IO) { runtime.send(sessionId, request.text) }
    }

    override suspend fun abortSession(sessionId: String): Boolean = withContext(Dispatchers.IO) { runtime.abort(sessionId) }

    override suspend fun deleteSession(sessionId: String): Boolean = withContext(Dispatchers.IO) { runtime.deleteSession(sessionId) }

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean = false

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

    override suspend fun listWorkspaces(): List<WorkspaceRef> = listOf(WorkspaceRef("/workspace", "workspace", "/workspace"))

    override suspend fun mcpServers(): List<McpServer> = withContext(Dispatchers.IO) { runtime.mcpServers() }

    override suspend fun addMcpServer(body: JsonObject): McpServer =
        withContext(Dispatchers.IO) {
            val name =
                (body["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
                    ?: error("An MCP server needs a name")
            val url = (body["url"] as? JsonPrimitive)?.contentOrNull
            val command = (body["command"] as? JsonPrimitive)?.contentOrNull
            runtime.addMcpServer(name, url, command)
            runtime.mcpServers().firstOrNull { it.name == name } ?: McpServer(name = name)
        }

    override suspend fun disconnectMcpServer(name: String): Boolean = withContext(Dispatchers.IO) { runtime.removeMcpServer(name) }
}
