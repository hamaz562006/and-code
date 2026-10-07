package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PromptRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Host-side Hermes sessions via non-interactive CLI (`hermes -z` / `hermes chat -q`).
 *
 * Auth is configured by the user under HERMES_HOME (API keys / `hermes setup`).
 */
class HermesRuntime(
    internal val runtimeDirectory: File,
) {
    private val events = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 64)
    private val sessions = ConcurrentHashMap<String, OpenCodeSession>()
    private val messageStore = ConcurrentHashMap<String, MutableList<OpenCodeMessage>>()

    fun events(): Flow<OpenCodeEvent> = events.asSharedFlow()

    fun stopAll() {
        // One-shot processes only.
    }

    fun listSessions(): List<OpenCodeSession> =
        sessions.values.sortedByDescending { it.time.updated ?: it.time.created }

    fun createSession(title: String?): OpenCodeSession {
        val id = "hermes-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val session =
            OpenCodeSession(
                id = id,
                directory = "/workspace",
                title = title?.takeIf { it.isNotBlank() } ?: "Hermes session",
                time = OpenCodeTime(created = now, updated = now),
            )
        sessions[id] = session
        messageStore[id] = mutableListOf()
        return session
    }

    fun listMessages(sessionId: String): List<OpenCodeMessage> =
        messageStore[sessionId]?.toList() ?: emptyList()

    fun send(
        sessionId: String,
        request: PromptRequest,
    ) {
        val text = request.text.trim()
        require(text.isNotEmpty()) { "empty message" }
        require(HermesInstaller.isInstalledIn(runtimeDirectory)) { "Hermes is not installed" }

        val now = System.currentTimeMillis()
        val userInfo =
            OpenCodeMessageInfo(
                id = "user-${UUID.randomUUID()}",
                sessionId = sessionId,
                role = "user",
                time = OpenCodeTime(created = now),
            )
        val userMessage =
            OpenCodeMessage(
                info = userInfo,
                parts =
                    listOf(
                        OpenCodePart(
                            id = "part-${UUID.randomUUID()}",
                            sessionId = sessionId,
                            messageId = userInfo.id,
                            type = "text",
                            text = text,
                        ),
                    ),
            )
        messageStore.getOrPut(sessionId) { mutableListOf() }.add(userMessage)
        events.tryEmit(OpenCodeEvent.MessageUpdated(userInfo))

        // Prefer pure one-shot -z (stdout = final answer only).
        val result =
            HermesInstaller.runOnHost(
                runtimeDirectory = runtimeDirectory,
                args = listOf("-z", text),
                timeoutSeconds = 300L,
            )
        val assistantText =
            result.output.trim().ifBlank {
                if (result.exitCode != 0) {
                    "Hermes failed (exit ${result.exitCode}). Configure a provider with `hermes setup` or set API keys under HERMES_HOME."
                } else {
                    "(empty response)"
                }
            }

        val doneAt = System.currentTimeMillis()
        val assistantInfo =
            OpenCodeMessageInfo(
                id = "assistant-${UUID.randomUUID()}",
                sessionId = sessionId,
                role = "assistant",
                time = OpenCodeTime(created = doneAt),
                agent = "Hermes",
            )
        val assistantMessage =
            OpenCodeMessage(
                info = assistantInfo,
                parts =
                    listOf(
                        OpenCodePart(
                            id = "part-${UUID.randomUUID()}",
                            sessionId = sessionId,
                            messageId = assistantInfo.id,
                            type = "text",
                            text = assistantText,
                        ),
                    ),
            )
        messageStore.getOrPut(sessionId) { mutableListOf() }.add(assistantMessage)
        events.tryEmit(OpenCodeEvent.MessageUpdated(assistantInfo))
        events.tryEmit(OpenCodeEvent.SessionIdle(sessionId))
        sessions[sessionId]?.let { s ->
            sessions[sessionId] = s.copy(time = s.time.copy(updated = doneAt))
        }
        if (result.exitCode != 0 && result.output.isBlank()) {
            error(assistantText)
        }
    }
}
