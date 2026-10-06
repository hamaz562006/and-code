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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Grok Build as a [RuntimeTarget].
 *
 * Install, API-key auth, and headless chat (`grok -p`) are wired on the Android host.
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

    override suspend fun providerAuthMethods(): Map<String, List<ProviderAuthMethod>> {
        val rootfs = installer.installedRuntime()?.rootfs
        val customIds = rootfs?.let { runtime.listCustomProviders(it) }.orEmpty().map { it.id }
        return buildMap {
            put("xai", listOf(ProviderAuthMethod(type = "api", label = "API key")))
            customIds.forEach { id ->
                put(id, listOf(ProviderAuthMethod(type = "api", label = "API key")))
            }
        }
    }

    override suspend fun listProviders(): ProviderCatalog {
        val rootfs = installer.installedRuntime()?.rootfs
        val xaiModels =
            mapOf(
                "grok-4.5" to OpenCodeModel(id = "grok-4.5", providerId = "xai", name = "Grok 4.5"),
                "grok-4" to OpenCodeModel(id = "grok-4", providerId = "xai", name = "Grok 4"),
                "grok-3" to OpenCodeModel(id = "grok-3", providerId = "xai", name = "Grok 3"),
            )
        val xai = OpenCodeProvider(id = "xai", name = "xAI", models = xaiModels)
        val custom =
            rootfs?.let { runtime.listCustomProviders(it) }.orEmpty().map { entry ->
                OpenCodeProvider(
                    id = entry.id,
                    name = entry.name,
                    models =
                        entry.modelIds.associateWith { mid ->
                            OpenCodeModel(id = mid, providerId = entry.id, name = mid)
                        },
                )
            }
        val all = listOf(xai) + custom
        val connected =
            buildList {
                if (rootfs != null && runtime.hasProviderApiKey(rootfs, "xai")) add("xai")
                custom.forEach { p ->
                    if (rootfs != null && runtime.hasProviderApiKey(rootfs, p.id)) add(p.id)
                }
            }
        val default =
            when {
                "xai" in connected -> mapOf("xai" to "grok-4.5")
                connected.isNotEmpty() -> {
                    val id = connected.first()
                    val model = all.firstOrNull { it.id == id }?.models?.keys?.firstOrNull()
                    if (model != null) mapOf(id to model) else emptyMap()
                }
                else -> emptyMap()
            }
        return ProviderCatalog(all = all, default = default, connected = connected)
    }

    override suspend fun listAgents(): List<OpenCodeAgent> =
        listOf(OpenCodeAgent(name = "Grok Build", description = "xAI Grok Build", native = true))

    override suspend fun setProviderApiKey(
        providerId: String,
        apiKey: String,
        metadata: Map<String, String>,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val rootfs = installer.installedRuntime()?.rootfs ?: return@withContext false
            runtime.setProviderApiKey(rootfs, providerId, apiKey)
            true
        }

    override suspend fun removeProviderAuth(providerId: String): Boolean =
        withContext(Dispatchers.IO) {
            val rootfs = installer.installedRuntime()?.rootfs ?: return@withContext false
            runtime.setProviderApiKey(rootfs, providerId, null)
            true
        }

    /**
     * Accepts the OpenCode-shaped custom-provider patch from Providers UI and stores it under
     * `root/.grok/providers.json` (Grok has no OpenCode HTTP config API).
     */
    override suspend fun updateConfig(patch: JsonObject): kotlinx.serialization.json.JsonElement =
        withContext(Dispatchers.IO) {
            val rootfs = installer.installedRuntime()?.rootfs ?: error("Grok environment is not installed")
            val providers =
                patch["provider"]?.jsonObject
                    ?: error("Grok config update expects a provider object")
            providers.forEach { (providerId, value) ->
                val obj = value.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: providerId
                val baseUrl =
                    obj["options"]?.jsonObject?.get("baseURL")?.jsonPrimitive?.contentOrNull
                        ?: obj["baseUrl"]?.jsonPrimitive?.contentOrNull
                        ?: error("Custom provider needs a base URL")
                val modelIds =
                    obj["models"]?.jsonObject?.keys?.toList().orEmpty().ifEmpty {
                        error("Custom provider needs at least one model id")
                    }
                runtime.registerCustomProvider(rootfs, providerId, name, baseUrl, modelIds)
            }
            patch
        }

    override suspend fun listSessions(directory: String?): List<OpenCodeSession> =
        withContext(Dispatchers.IO) { runtime.listSessions() }

    override suspend fun createSession(
        title: String?,
        directory: String?,
    ): OpenCodeSession =
        withContext(Dispatchers.IO) {
            runtime.createSession(title, directory)
        }

    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> =
        withContext(Dispatchers.IO) { runtime.listMessages(sessionId) }

    override suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    ) {
        withContext(Dispatchers.IO) {
            val rootfs = installer.installedRuntime()?.rootfs ?: error("Linux environment is not installed")
            runtime.send(rootfs, sessionId, request)
        }
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

    override fun events(): Flow<OpenCodeEvent> = runtime.events()
}
