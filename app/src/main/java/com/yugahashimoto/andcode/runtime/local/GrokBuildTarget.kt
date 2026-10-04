package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeFileContent
import com.yugahashimoto.andcode.core.api.OpenCodeFileNode
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeModel
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Grok Build as a [RuntimeTarget].
 *
 * Install + API-key auth are complete. Interactive chat (ACP `grok agent stdio`) is not wired yet.
 * Guest Browser stays off: no localhost HTTP server.
 */
class GrokBuildTarget(
    private val runtime: GrokBuildRuntime,
    private val installer: LocalRuntimeInstaller,
) : RuntimeTarget {
    override val id = LocalAgent.GROK_BUILD.targetId
    override val displayName = "Grok Build"
    override val agent = LocalAgent.GROK_BUILD
    override val kind = BackendKind.LOCAL
    override val type = RuntimeType.LOCAL

    override val capabilities =
        RuntimeCapabilities(
            permissions = false,
            toolEvents = false,
            providerModelList = true,
            browsableHttpServer = false,
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
                val rootfs = installer.installedRuntime()?.rootfs ?: error("Linux environment is not installed")
                require(GrokBuildInstaller.isInstalledIn(rootfs)) { "Grok Build is not installed" }
                val version = GrokBuildInstaller.installedVersion(rootfs) ?: GrokBuildManifest.VERSION
                mutableState.value = RuntimeState.Connected(version)
                OpenCodeHealth(true, version)
            }.onFailure { mutableState.value = RuntimeState.Unavailable(it.message ?: "Grok Build unavailable") }
        }

    override fun disconnect() {
        runtime.stopAll()
        mutableState.value = RuntimeState.Disconnected
    }

    override suspend fun health(): OpenCodeHealth = connect().getOrElse { OpenCodeHealth(false, "") }

    override suspend fun listWorkspaces(): List<WorkspaceRef> =
        listOf(WorkspaceRef(id = "/workspace", name = "workspace", path = "/workspace"))

    override suspend fun listProviders(): ProviderCatalog {
        val rootfs = installer.installedRuntime()?.rootfs
        val hasKey = rootfs != null && runtime.hasApiKey(rootfs)
        val models =
            mapOf(
                "grok-4.5" to OpenCodeModel(id = "grok-4.5", providerId = "xai", name = "Grok 4.5"),
                "grok-4" to OpenCodeModel(id = "grok-4", providerId = "xai", name = "Grok 4"),
                "grok-3" to OpenCodeModel(id = "grok-3", providerId = "xai", name = "Grok 3"),
            )
        val provider = OpenCodeProvider(id = "xai", name = "xAI", models = models)
        return ProviderCatalog(
            all = listOf(provider),
            default = if (hasKey) mapOf("xai" to "grok-4.5") else emptyMap(),
            connected = if (hasKey) listOf("xai") else emptyList(),
        )
    }

    override suspend fun listAgents(): List<OpenCodeAgent> =
        listOf(OpenCodeAgent(name = "Grok Build", description = "xAI Grok Build", native = true))

    override suspend fun setProviderApiKey(
        providerId: String,
        apiKey: String,
        metadata: Map<String, String>,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (providerId != "xai") return@withContext false
            val rootfs = installer.installedRuntime()?.rootfs ?: return@withContext false
            runtime.setApiKey(rootfs, apiKey)
            true
        }

    override suspend fun removeProviderAuth(providerId: String): Boolean =
        withContext(Dispatchers.IO) {
            if (providerId != "xai") return@withContext false
            val rootfs = installer.installedRuntime()?.rootfs ?: return@withContext false
            runtime.setApiKey(rootfs, null)
            true
        }

    override suspend fun listSessions(directory: String?): List<OpenCodeSession> = emptyList()

    override suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession =
        error("Grok Build chat sessions are not wired yet; install and API key work from agent settings")

    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = emptyList()

    override suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    ) {
        error("Grok Build interactive chat is not wired yet (API-key auth is ready)")
    }

    override suspend fun abortSession(sessionId: String): Boolean = false

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean = false

    override suspend fun listFiles(
        directory: String,
        path: String,
    ): List<OpenCodeFileNode> = withContext(Dispatchers.IO) { files.list(directory, path) }

    override suspend fun readFile(
        directory: String,
        path: String,
    ): OpenCodeFileContent = withContext(Dispatchers.IO) { files.read(directory, path) }

    override fun events(): Flow<OpenCodeEvent> = emptyFlow()
}
