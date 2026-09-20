package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeFileContent
import com.yugahashimoto.andcode.core.api.OpenCodeFileNode
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeSearchMatch
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderAuthAuthorization
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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Adapts [CodexRuntime] to this app's [RuntimeTarget]/`OpenCodeBackend` interface.
 *
 * File browsing reuses [ClaudeWorkspaceFiles] the same way [AntigravityTarget] does: Codex shares
 * the same `/workspace` bind mount as every other local agent, so it needs no target-specific file
 * API. MCP servers, skills, commands, VCS status/diff and config editing are left at
 * `OpenCodeBackend`'s `unsupported()` defaults - the app-server protocol has equivalents for some of
 * these (`mcpServerStatus/list`, `skills/list`, `config/read`), but none were exercised against a
 * live, signed-in account (see docs/CODEX.md), so wiring them up is left for a follow-up rather than
 * guessed at.
 */
class CodexTarget(private val runtime: CodexRuntime) : RuntimeTarget {
    override val id = LocalAgent.CODEX.targetId
    override val displayName = "Codex"
    override val agent = LocalAgent.CODEX
    override val kind = BackendKind.LOCAL
    override val type = RuntimeType.LOCAL

    // permissions is unconditionally true, unlike ClaudeCodeTarget's bridge-gated flag: Codex's
    // command/file-change approvals (CodexRuntime.emitApproval) work whenever the app-server process
    // is up, with no separate bridge-install step. toolEvents is true because CodexItemParser
    // streams commandExecution/fileChange/mcpToolCall items as live "tool" part updates the same way
    // ClaudeStreamJsonParser does. questions stays false: item/tool/requestUserInput has no UI wired
    // up (see CodexRuntime.handleServerRequest's else branch). Each turn is a multiplexed RPC call
    // rather than a fresh process, so forcesQueue does not apply the way it does for Antigravity.
    override val capabilities = RuntimeCapabilities(permissions = true, toolEvents = true)

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
                val version = runtime.version() ?: error("Codex is not installed or incompatible with this ABI")
                mutableState.value = RuntimeState.Connected(version)
                OpenCodeHealth(true, version)
            }.onFailure { mutableState.value = RuntimeState.Unavailable(it.message ?: "Codex unavailable") }
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
    ): OpenCodeSession = withContext(Dispatchers.IO) { runtime.createSession(title, directory ?: "/workspace") }

    override suspend fun renameSession(
        sessionId: String,
        title: String,
    ): OpenCodeSession =
        withContext(Dispatchers.IO) {
            // Unlike createSession, this can't return a locally-patched copy instead of a second round
            // trip: thread/name/set's own response (ThreadSetNameResponse) is an empty object per the
            // protocol schema, so the only way to get back an OpenCodeSession reflecting the new title
            // is to ask the server for it.
            runtime.renameSession(sessionId, title)
            runtime.session(sessionId)
        }

    override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> =
        withContext(Dispatchers.IO) { runtime.listMessages(sessionId) }

    override suspend fun listProviders(): ProviderCatalog =
        withContext(Dispatchers.IO) {
            // Two independent app-server round trips: run them concurrently rather than doubling
            // the latency of a call this app makes on every runtime selection and catalogue refresh.
            val models = async { runtime.modelList() }
            val signedIn = async { runtime.isSignedIn() }
            CodexModels.catalog(models.await(), connected = signedIn.await())
        }

    override suspend fun listAgents(): List<OpenCodeAgent> = CodexModels.agents()

    override suspend fun providerAuthMethods(): Map<String, List<ProviderAuthMethod>> =
        mapOf(CodexModels.PROVIDER_ID to listOf(ProviderAuthMethod(type = "api", label = "API key")))

    override suspend fun setProviderApiKey(
        providerId: String,
        apiKey: String,
        metadata: Map<String, String>,
    ): Boolean {
        if (providerId != CodexModels.PROVIDER_ID) return false
        return withContext(Dispatchers.IO) { runtime.loginWithApiKey(apiKey).isSuccess }
    }

    override suspend fun removeProviderAuth(providerId: String): Boolean {
        if (providerId != CodexModels.PROVIDER_ID) return false
        withContext(Dispatchers.IO) { runtime.logout() }
        return true
    }

    override suspend fun authorizeProvider(
        providerId: String,
        methodIndex: Int,
        inputs: Map<String, String>,
    ): ProviderAuthAuthorization =
        // Codex also supports a ChatGPT browser sign-in (`account/login/start`), not implemented
        // here: it is a device/browser round trip this app has no verified transcript for yet (see
        // docs/CODEX.md), unlike the plain `codex login --with-api-key` path `setProviderApiKey`
        // uses. Only the API-key method is advertised in `providerAuthMethods`, so this is not
        // reachable from the current UI; it exists only to satisfy the interface explicitly rather
        // than silently inheriting `unsupported()`.
        error("Codex only supports API-key sign-in in this app; browser sign-in is not implemented yet")

    override suspend fun sendMessage(
        sessionId: String,
        request: PromptRequest,
    ) {
        withContext(Dispatchers.IO) { runtime.send(sessionId, request.text, request.modelId) }
    }

    override suspend fun abortSession(sessionId: String): Boolean = withContext(Dispatchers.IO) { runtime.abort(sessionId) }

    override suspend fun deleteSession(sessionId: String): Boolean = withContext(Dispatchers.IO) { runtime.deleteSession(sessionId) }

    override suspend fun archiveSession(sessionId: String): OpenCodeSession =
        withContext(Dispatchers.IO) { runtime.archiveSession(sessionId) }

    override suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean,
    ): Boolean = withContext(Dispatchers.IO) { runtime.respondToPermission(permissionId, response, remember) }

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
}
