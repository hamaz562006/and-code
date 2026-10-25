package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeCommand
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeFileContent
import com.yugahashimoto.andcode.core.api.OpenCodeFileNode
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeModel
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
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
import java.io.File

/**
 * Aider agent target — separate [LocalAgent.AIDER] / target id from OpenCode.
 */
class AiderTarget(
    private val runtime: AiderRuntime,
) : RuntimeTarget {
    override val id = LocalAgent.AIDER.targetId
    override val displayName = "Aider"
    override val agent = LocalAgent.AIDER
    override val kind = BackendKind.LOCAL
    override val type = RuntimeType.LOCAL

    override val capabilities =
        RuntimeCapabilities(
            permissions = false,
            toolEvents = false,
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
                if (!runtime.isInstalled()) error("Aider is not installed")
                val version = runtime.installedVersion() ?: AiderManifest.VERSION
                val wasConnected = mutableState.value is RuntimeState.Connected
                mutableState.value = RuntimeState.Connected(version)
                if (!wasConnected) runtime.announceConnected()
                OpenCodeHealth(true, version)
            }.onFailure {
                mutableState.value = RuntimeState.Unavailable(it.message ?: "Aider unavailable")
            }
        }

    override fun disconnect() {
        runtime.stopAll()
        mutableState.value = RuntimeState.Disconnected
    }

    override suspend fun health(): OpenCodeHealth = connect().getOrElse { OpenCodeHealth(false, "") }

    override suspend fun listSessions(directory: String?): List<OpenCodeSession> =
        withContext(Dispatchers.IO) { runtime.listSessions() }

    override suspend fun listAgents(): List<OpenCodeAgent> =
        listOf(OpenCodeAgent(name = "aider", description = "Aider", mode = "primary", native = true))

    override suspend fun listProviders(): ProviderCatalog =
        withContext(Dispatchers.IO) {
            val connected = runtime.connectedProviderIds()
            val googleModels =
                listOf("gemini-2.5-flash", "gemini-2.0-flash").associateWith { mid ->
                    OpenCodeModel(id = mid, providerId = "google", name = mid)
                }
            val openaiModels =
                listOf("gpt-4o", "gpt-4o-mini").associateWith { mid ->
                    OpenCodeModel(id = mid, providerId = "openai", name = mid)
                }
            val anthropicModels =
                listOf("claude-sonnet-4-0", "claude-3-5-sonnet-latest").associateWith { mid ->
                    OpenCodeModel(id = mid, providerId = "anthropic", name = mid)
                }
            val all =
                listOf(
                    OpenCodeProvider(id = "google", name = "Google AI Studio", models = googleModels),
                    OpenCodeProvider(id = "openai", name = "OpenAI", models = openaiModels),
                    OpenCodeProvider(id = "anthropic", name = "Anthropic", models = anthropicModels),
                )
            ProviderCatalog(
                all = all,
                default = emptyMap(),
                connected = connected.toList(),
            )
        }

    override suspend fun providerAuthMethods(): Map<String, List<ProviderAuthMethod>> =
        listOf("google", "openai", "anthropic").associateWith {
            listOf(ProviderAuthMethod(type = "api", label = "API key"))
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

    override suspend fun abortSession(sessionId: String): Boolean = true

    override suspend fun deleteSession(sessionId: String): Boolean =
        withContext(Dispatchers.IO) { runtime.deleteSession(sessionId) }

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

    override suspend fun listWorkspaces(): List<WorkspaceRef> =
        listOf(WorkspaceRef("/workspace", "workspace", "/workspace"))

    override suspend fun commands(): List<OpenCodeCommand> = AiderCommandCatalog.commands
}
