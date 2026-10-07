package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeFileContent
import com.yugahashimoto.andcode.core.api.OpenCodeFileNode
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeProvider
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.PromptRequest
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
 * Runtime target for Hermes Agent (host-side Termux package, not Alpine PRoot).
 */
class HermesTarget(
    private val runtime: HermesRuntime,
) : RuntimeTarget {
    override val id = LocalAgent.HERMES.targetId
    override val displayName = "Hermes"
    override val agent = LocalAgent.HERMES
    override val kind = BackendKind.LOCAL
    override val type = RuntimeType.LOCAL

    override val capabilities =
        RuntimeCapabilities(
            permissions = false,
            toolEvents = false,
            providerModelList = false,
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
                require(HermesInstaller.isInstalledIn(runtime.runtimeDirectory)) {
                    "Hermes is not installed"
                }
                val version =
                    HermesInstaller.installedVersion(runtime.runtimeDirectory)
                        ?: HermesManifest.VERSION
                mutableState.value = RuntimeState.Connected(version)
                OpenCodeHealth(true, version)
            }.onFailure {
                mutableState.value = RuntimeState.Unavailable(it.message ?: "Hermes unavailable")
            }
        }

    override fun disconnect() {
        runtime.stopAll()
        mutableState.value = RuntimeState.Disconnected
    }

    override suspend fun health(): OpenCodeHealth = connect().getOrElse { OpenCodeHealth(false, "") }

    override suspend fun listSessions(directory: String?): List<OpenCodeSession> = withContext(Dispatchers.IO) { runtime.listSessions() }

    override suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession =
        withContext(Dispatchers.IO) {
            runtime.createSession(title)
        }

    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> =
        withContext(Dispatchers.IO) {
            runtime.listMessages(sessionId)
        }

    override suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    ) {
        withContext(Dispatchers.IO) { runtime.send(sessionId, request) }
    }

    override suspend fun abortSession(sessionId: String): Boolean = true

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean = false

    override fun events(): Flow<OpenCodeEvent> = runtime.events()

    override suspend fun listProviders(): ProviderCatalog =
        ProviderCatalog(
            all =
                listOf(
                    OpenCodeProvider(
                        id = "openrouter",
                        name = "OpenRouter (via Hermes)",
                        models = emptyMap(),
                    ),
                    OpenCodeProvider(
                        id = "nous",
                        name = "Nous Portal (via Hermes)",
                        models = emptyMap(),
                    ),
                ),
            default = emptyMap(),
            connected = emptyList(),
        )

    override suspend fun listAgents(): List<OpenCodeAgent> =
        listOf(OpenCodeAgent(name = "hermes", description = "Hermes", mode = "primary", native = true))

    override suspend fun listWorkspaces(): List<WorkspaceRef> =
        listOf(
            WorkspaceRef(
                id = "workspace",
                name = "workspace",
                path = File(runtime.runtimeDirectory, "workspace").absolutePath,
            ),
        )

    override suspend fun listFiles(
        directory: String,
        path: String,
    ): List<OpenCodeFileNode> = withContext(Dispatchers.IO) { files.list(directory, path) }

    override suspend fun readFile(
        directory: String,
        path: String,
    ): OpenCodeFileContent = withContext(Dispatchers.IO) { files.read(directory, path) }
}
