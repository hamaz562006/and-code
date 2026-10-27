package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal Cline runtime: in-memory sessions; prompts via `cline --message` in the shared rootfs.
 * Fully independent of OpenCode (separate process, config, and [LocalAgent.CLINE] target id).
 */
class ClineRuntime(
    internal val runtimeDirectory: File,
    private val installedRuntime: () -> LocalRuntimeInstaller.InstalledRuntime?,
    private val runShell: (String) -> LocalRuntimeCommandResult,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val events = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 256)
    private val sessions = ConcurrentHashMap<String, OpenCodeSession>()
    private val messageStore = ConcurrentHashMap<String, MutableList<OpenCodeMessage>>()
    private val apiKeys = ConcurrentHashMap<String, String>()

    fun events(): Flow<OpenCodeEvent> = events

    fun isInstalled(): Boolean = ClineInstaller.isInstalledIn(runtimeDirectory)

    fun installedVersion(): String? = ClineInstaller.installedVersion(runtimeDirectory)

    fun announceConnected() {
        scope.launch { events.emit(OpenCodeEvent.ServerConnected) }
    }

    fun setApiKey(
        providerId: String,
        key: String?,
    ) {
        if (key.isNullOrBlank()) apiKeys.remove(providerId) else apiKeys[providerId] = key.trim()
    }

    fun connectedProviderIds(): Set<String> = apiKeys.keys.toSet()

    fun listSessions(): List<OpenCodeSession> = sessions.values.toList()

    suspend fun createSession(
        title: String?,
        directory: String,
    ): OpenCodeSession =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val session =
                OpenCodeSession(
                    id = id,
                    title = title?.ifBlank { null } ?: "Cline",
                    directory = directory,
                    time = OpenCodeTime(created = now, updated = now),
                )
            sessions[id] = session
            messageStore[id] = mutableListOf()
            session
        }

    suspend fun renameSession(
        sessionId: String,
        title: String,
    ): OpenCodeSession =
        withContext(Dispatchers.IO) {
            val current = sessions[sessionId] ?: error("Unknown session")
            val updated =
                current.copy(
                    title = title,
                    time = current.time.copy(updated = System.currentTimeMillis()),
                )
            sessions[sessionId] = updated
            updated
        }

    suspend fun listMessages(sessionId: String): List<OpenCodeMessage> =
        withContext(Dispatchers.IO) {
            messageStore[sessionId]?.toList() ?: emptyList()
        }

    suspend fun deleteSession(sessionId: String): Boolean =
        withContext(Dispatchers.IO) {
            sessions.remove(sessionId) != null.also { messageStore.remove(sessionId) }
        }

    suspend fun send(
        sessionId: String,
        text: String,
    ) = withContext(Dispatchers.IO) {
        val session = sessions[sessionId] ?: error("Unknown session")
        val userId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val userInfo =
            OpenCodeMessageInfo(
                id = userId,
                sessionId = sessionId,
                role = "user",
                time = OpenCodeTime(created = now, updated = now, completed = now),
            )
        val userMsg =
            OpenCodeMessage(
                info = userInfo,
                parts =
                    listOf(
                        OpenCodePart(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            messageId = userId,
                            type = "text",
                            text = text,
                        ),
                    ),
            )
        messageStore.getOrPut(sessionId) { mutableListOf() }.add(userMsg)
        events.emit(OpenCodeEvent.MessageUpdated(userInfo))

        val envExports =
            buildString {
                apiKeys["google"]?.let {
                    append("export GOOGLE_API_KEY='${it.replace("'", "'\\''")}'; ")
                }
                apiKeys["openai"]?.let {
                    append("export OPENAI_API_KEY='${it.replace("'", "'\\''")}'; ")
                }
                apiKeys["anthropic"]?.let {
                    append("export ANTHROPIC_API_KEY='${it.replace("'", "'\\''")}'; ")
                }
                if (apiKeys.containsKey("google")) {
                    append("export AIDER_MODEL=google/gemini-2.5-flash; ")
                }
            }
        val escaped = text.replace("'", "'\\''")
        val cmd =
            "$envExports cd '${session.directory}' 2>/dev/null || cd /workspace; " +
                "cline --yes-always --no-show-model-warnings --message '$escaped' 2>&1"
        val result = runShell(cmd)
        val replyText =
            result.output.ifBlank {
                if (result.exitCode != 0) {
                    "Aider failed (exit ${result.exitCode}). Is it installed and is an API key set?"
                } else {
                    "(empty response)"
                }
            }
        val asstId = UUID.randomUUID().toString()
        val done = System.currentTimeMillis()
        val asstInfo =
            OpenCodeMessageInfo(
                id = asstId,
                sessionId = sessionId,
                role = "assistant",
                time = OpenCodeTime(created = done, updated = done, completed = done),
            )
        val asst =
            OpenCodeMessage(
                info = asstInfo,
                parts =
                    listOf(
                        OpenCodePart(
                            id = UUID.randomUUID().toString(),
                            sessionId = sessionId,
                            messageId = asstId,
                            type = "text",
                            text = replyText,
                        ),
                    ),
            )
        messageStore.getOrPut(sessionId) { mutableListOf() }.add(asst)
        events.emit(OpenCodeEvent.MessageUpdated(asstInfo))
        sessions[sessionId] = session.copy(time = session.time.copy(updated = done))
    }

    fun stopAll() {
        // One-shot CLI; nothing long-lived.
    }
}
