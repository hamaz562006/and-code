package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.McpServer
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
            providerModelList = true,
            // Hermes gateway OpenAI API on :8642 (not OpenCode :4097).
            browsableHttpServer = true,
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
                runtime.ensureApiServerEnv()
                runtime.ensureOpenCodeFreeDefault()
                runtime.startGateway()
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
        runtime.stopGateway()
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

    override suspend fun commands(): List<com.yugahashimoto.andcode.core.api.OpenCodeCommand> =
        HermesCommandCatalog.commands

    override suspend fun deleteSession(sessionId: String): Boolean = withContext(Dispatchers.IO) { runtime.deleteSession(sessionId) }

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean = false

    override fun events(): Flow<OpenCodeEvent> = runtime.events()

    override suspend fun listProviders(): ProviderCatalog {
        runtime.ensureOpenCodeFreeDefault()
        val freeModels =
            listOf(
                "big-pickle",
                "mimo-v2.6-flash-free",
                "muse-spark-1.2-contributor-free",
                "nemotron-3-ultra-free",
                "ling-3.1-flash-free",
            ).associateWith { id ->
                OpenCodeModel(id = id, providerId = "opencode-free", name = id)
            }
        val free =
            OpenCodeProvider(
                id = "opencode-free",
                name = "OpenCode Free",
                models = freeModels,
            )
        val others =
            listOf(
                "openrouter" to "OpenRouter",
                "nous" to "Nous Portal",
                "anthropic" to "Anthropic",
                "openai" to "OpenAI",
                "opencode-zen" to "OpenCode Zen",
                "opencode-go" to "OpenCode Go",
                "gemini" to "Google Gemini",
                "deepseek" to "DeepSeek",
                "xai" to "xAI",
                "custom" to "Custom (OpenAI-compatible)",
            ).map { (id, name) -> OpenCodeProvider(id = id, name = name, models = emptyMap()) }
        return ProviderCatalog(
            all = listOf(free) + others,
            connected = emptyList(),
            default = mapOf("opencode-free" to "big-pickle"),
        )
    }

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

    override suspend fun mcpServers(): List<McpServer> = emptyList()

    override suspend fun addMcpServer(body: JsonObject): McpServer = error("Hermes MCP is not configured in this build")

    override suspend fun connectMcpServer(name: String): Boolean = false

    override suspend fun disconnectMcpServer(name: String): Boolean = false

    override suspend fun providerAuthMethods(): Map<String, List<ProviderAuthMethod>> =
        listOf(
            "openrouter",
            "nous",
            "anthropic",
            "openai",
            "opencode-go",
            "gemini",
            "deepseek",
            "xai",
            "fireworks",
            "groq",
            "mistral",
            "huggingface",
            "custom",
        ).associateWith {
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

    override suspend fun listFiles(
        directory: String,
        path: String,
    ): List<OpenCodeFileNode> = withContext(Dispatchers.IO) { files.list(directory, path) }

    override suspend fun readFile(
        directory: String,
        path: String,
    ): OpenCodeFileContent = withContext(Dispatchers.IO) { files.read(directory, path) }
}
