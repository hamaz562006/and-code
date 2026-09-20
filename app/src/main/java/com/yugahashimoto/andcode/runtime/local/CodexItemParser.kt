package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PermissionRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Translates Codex `app-server` thread/turn/item notifications (JSON-RPC v2 "app-server protocol")
 * into this app's [OpenCodeEvent]/[OpenCodeMessage] model.
 *
 * Verified against the real protocol schema (`codex app-server generate-json-schema`, 0.155.1) and
 * against a live, unauthenticated `codex app-server` process for the shapes that do not require an
 * API key: `thread/start`, `turn/start`, `item/started`/`item/completed` for a `userMessage`, and the
 * `error` notification's retry shape. See docs/CODEX.md for the exact transcripts.
 *
 * Codex's turn is not one message the way Claude Code's is: a single turn can carry several items
 * (reasoning, a shell command, an `agentMessage`) that this app renders as one assistant bubble with
 * several parts, the same grouping [ClaudeStreamJsonParser] uses for a Claude Code turn's tool calls
 * and text. A `userMessage` item is its own message instead, keyed by the item's own id.
 */
class CodexItemParser {
    data class Parsed(
        val events: List<OpenCodeEvent> = emptyList(),
        val messages: List<OpenCodeMessage> = emptyList(),
    )

    /**
     * Assistant messages assembled so far, keyed by turn id, so a later item in the same turn merges
     * in. Evicted once the turn ends (see [forgetTurn]): the persisted truth for a finished turn is
     * Codex's own `thread/items/list`, so nothing here needs to outlive the turn it was built for.
     */
    private val assistantMessages = linkedMapOf<String, OpenCodeMessage>()

    fun handleNotification(
        sessionId: String,
        method: String,
        params: JsonObject?,
    ): Parsed {
        val body = params ?: return Parsed()
        return when (method) {
            "item/started", "item/completed" -> handleItem(sessionId, body)
            "item/agentMessage/delta" -> handleAgentMessageDelta(sessionId, body)
            "turn/completed" -> handleTurnCompleted(sessionId, body)
            "turn/started" -> Parsed(events = listOf(OpenCodeEvent.SessionStatusChanged(sessionId, "busy")))
            "error" -> handleError(sessionId, body)
            else -> Parsed()
        }
    }

    private fun handleTurnCompleted(
        sessionId: String,
        body: JsonObject,
    ): Parsed {
        // TurnCompletedNotification's schema carries a full `turn` object (not a flat `turnId` the
        // way item/error notifications do) - confirmed against `codex app-server
        // generate-json-schema`'s TurnCompletedNotification definition, see docs/CODEX.md.
        body["turn"]?.jsonObject?.string("id")?.let(::forgetTurn)
        return Parsed(events = listOf(OpenCodeEvent.SessionIdle(sessionId)))
    }

    /** Drops the in-progress assistant message this parser was assembling for [turnId], if any. */
    fun forgetTurn(turnId: String) {
        assistantMessages.remove(assistantMessageId(turnId))
    }

    private fun handleAgentMessageDelta(
        sessionId: String,
        body: JsonObject,
    ): Parsed {
        val turnId = body.string("turnId") ?: return Parsed()
        // AgentMessageDeltaNotification's schema marks itemId required (no null in its type), so a
        // missing one means a shape this parser does not recognize - dropped rather than defaulted
        // to turnId, which would key this delta's streamed part differently from the "$id-text" id
        // itemToPart's agentMessage case builds once the item itself completes, leaving two parts
        // (stale streamed text plus the final text) that never coalesce into one.
        val itemId = body.string("itemId") ?: return Parsed()
        val delta = body.string("delta") ?: return Parsed()
        val messageId = assistantMessageId(turnId)
        return Parsed(events = listOf(OpenCodeEvent.MessagePartDelta(sessionId, messageId, "$itemId-text", "text", delta)))
    }

    private fun handleError(
        sessionId: String,
        body: JsonObject,
    ): Parsed {
        val error = body["error"]?.jsonObject
        val message = error?.string("message") ?: "Codex reported an error"
        val willRetry = (body["willRetry"] as? JsonPrimitive)?.content == "true"
        // A retryable transport hiccup ("Reconnecting... 2/5") is not the end of the turn - only
        // report it, and let a later `turn/completed` (success) or a non-retrying `error` (failure)
        // decide whether the chat goes idle. Ending the turn here on every reconnect attempt would
        // flip a still-running turn back to idle mid-retry.
        if (willRetry) return Parsed(events = listOf(OpenCodeEvent.SessionError(sessionId, message)))
        body.string("turnId")?.let(::forgetTurn)
        return Parsed(events = listOf(OpenCodeEvent.SessionError(sessionId, message), OpenCodeEvent.SessionIdle(sessionId)))
    }

    private fun handleItem(
        sessionId: String,
        body: JsonObject,
    ): Parsed {
        val item = body["item"]?.jsonObject ?: return Parsed()
        val turnId = body.string("turnId") ?: return Parsed()
        return when (item.string("type")) {
            "userMessage" -> handleUserMessage(sessionId, item)
            else -> handleAssistantItem(sessionId, turnId, item)
        }
    }

    private fun handleUserMessage(
        sessionId: String,
        item: JsonObject,
    ): Parsed {
        val id = item.string("id") ?: return Parsed()
        val text = (item["content"] as? JsonArray)?.joinToString("") { (it as? JsonObject)?.string("text").orEmpty() }.orEmpty()
        val message =
            OpenCodeMessage(
                info = OpenCodeMessageInfo(id = id, sessionId = sessionId, role = "user", time = now()),
                parts = listOf(OpenCodePart(id = "$id-text", sessionId = sessionId, messageId = id, type = "text", text = text)),
            )
        return Parsed(events = message.parts.map(OpenCodeEvent::MessagePartUpdated), messages = listOf(message))
    }

    private fun handleAssistantItem(
        sessionId: String,
        turnId: String,
        item: JsonObject,
    ): Parsed {
        val messageId = assistantMessageId(turnId)
        val part = itemToPart(sessionId, messageId, item) ?: return Parsed()
        val existing = assistantMessages[messageId]
        val mergedParts =
            if (existing != null) {
                val byId = linkedMapOf<String?, OpenCodePart>()
                existing.parts.forEach { byId[it.id] = it }
                byId[part.id] = part
                byId.values.toList()
            } else {
                listOf(part)
            }
        val message =
            OpenCodeMessage(
                info =
                    existing?.info ?: OpenCodeMessageInfo(
                        id = messageId,
                        sessionId = sessionId,
                        role = "assistant",
                        time = now(),
                        agent = "codex",
                    ),
                parts = mergedParts,
            )
        assistantMessages[messageId] = message
        return Parsed(events = listOf(OpenCodeEvent.MessagePartUpdated(part)), messages = listOf(message))
    }

    /**
     * One [OpenCodePart] per item type this app already has a part shape for.
     *
     * `commandExecution`/`fileChange`/`mcpToolCall` reuse the "tool" part shape
     * [ClaudeStreamJsonParser] already renders (`state.status`/`input`/`output`/`error`), so the
     * existing tool-call UI needs no Codex-specific branch. Item types this app cannot yet render
     * meaningfully (`webSearch`, `imageGeneration`, `subAgentActivity`, ...) fall through to a plain
     * "tool" part carrying the raw item JSON, rather than disappearing silently.
     */
    private fun itemToPart(
        sessionId: String,
        messageId: String,
        item: JsonObject,
    ): OpenCodePart? {
        val id = item.string("id") ?: return null
        return when (item.string("type")) {
            "agentMessage" ->
                OpenCodePart(
                    id = "$id-text",
                    sessionId = sessionId,
                    messageId = messageId,
                    type = "text",
                    text = item.string("text").orEmpty(),
                )
            "reasoning" ->
                OpenCodePart(
                    id = "$id-reasoning",
                    sessionId = sessionId,
                    messageId = messageId,
                    type = "reasoning",
                    text = reasoningText(item),
                )
            "plan" ->
                OpenCodePart(
                    id = "$id-plan",
                    sessionId = sessionId,
                    messageId = messageId,
                    type = "text",
                    text = item.string("text").orEmpty(),
                )
            "commandExecution" ->
                OpenCodePart(
                    id = id,
                    sessionId = sessionId,
                    messageId = messageId,
                    type = "tool",
                    tool = "bash",
                    callID = id,
                    state =
                        mapOf(
                            "status" to JsonPrimitive(mapCommandStatus(item.string("status"))),
                            "input" to JsonPrimitive(item.string("command").orEmpty()),
                            "output" to JsonPrimitive(item.string("aggregatedOutput").orEmpty()),
                        ),
                )
            "fileChange" ->
                OpenCodePart(
                    id = id,
                    sessionId = sessionId,
                    messageId = messageId,
                    type = "tool",
                    tool = "patch",
                    callID = id,
                    state =
                        mapOf(
                            "status" to JsonPrimitive(mapCommandStatus(item.string("status"))),
                            "input" to (item["changes"] ?: JsonObject(emptyMap())),
                        ),
                )
            "mcpToolCall" ->
                OpenCodePart(
                    id = id,
                    sessionId = sessionId,
                    messageId = messageId,
                    type = "tool",
                    tool = item.string("tool") ?: "mcp",
                    callID = id,
                    state =
                        mapOf(
                            "status" to JsonPrimitive(mapCommandStatus(item.string("status"))),
                            "input" to (item["arguments"] ?: JsonObject(emptyMap())),
                            "output" to (item["result"] ?: JsonPrimitive("")),
                        ),
                )
            // userMessage is routed to handleUserMessage before this is reached; every other item
            // type (webSearch, imageGeneration, subAgentActivity, dynamicToolCall,
            // collabAgentToolCall, sleep, imageView, functionCallOutput, contextCompaction,
            // hookPrompt, enteredReviewMode, exitedReviewMode) has no dedicated rendering yet - carry
            // it as a generic tool part with the raw item JSON rather than dropping it.
            else ->
                OpenCodePart(
                    id = id,
                    sessionId = sessionId,
                    messageId = messageId,
                    type = "tool",
                    tool = item.string("type") ?: "codex",
                    callID = id,
                    state = mapOf("status" to JsonPrimitive("completed"), "input" to item),
                )
        }
    }

    private fun reasoningText(item: JsonObject): String {
        val summary = item["summary"] as? JsonArray
        if (summary != null) {
            return summary.joinToString("\n") { element ->
                when (element) {
                    is JsonObject -> element.string("text").orEmpty()
                    is JsonPrimitive -> element.contentOrNull.orEmpty()
                    else -> ""
                }
            }
        }
        return item.string("content").orEmpty()
    }

    /**
     * Normalizes to exactly the values [ClaudeMessageStore.settleRunningTools] understands
     * ("running"/"pending" mean in-flight, anything else is terminal): the real in-progress status
     * string is unconfirmed (see docs/CODEX.md - these item shapes come from the schema, not a live
     * signed-in run), so an unrecognized non-completed/non-error value is treated as still running
     * rather than passed through - passing it through verbatim would leave the tool part stuck
     * showing that raw status forever if the app-server dies mid-command, since settling only
     * rewrites a part whose status is exactly "running" or "pending".
     */
    private fun mapCommandStatus(status: String?): String =
        when (status) {
            "completed" -> "completed"
            "failed", "error" -> "error"
            else -> "running"
        }

    private fun assistantMessageId(turnId: String): String = "codex-turn-$turnId"

    private fun now(): OpenCodeTime {
        val timestamp = System.currentTimeMillis()
        return OpenCodeTime(timestamp, timestamp)
    }

    companion object {
        /** Builds the [PermissionRequest] this app's permission UI shows for a command-execution approval. */
        fun commandApprovalToPermissionRequest(
            sessionId: String,
            requestId: String,
            params: JsonObject,
        ): PermissionRequest =
            PermissionRequest(
                id = requestId,
                sessionId = sessionId,
                permission = "bash",
                patterns = listOfNotNull(params.string("command")),
                metadata = params,
            )

        /** Builds the [PermissionRequest] this app's permission UI shows for a file-change approval. */
        fun fileChangeApprovalToPermissionRequest(
            sessionId: String,
            requestId: String,
            params: JsonObject,
        ): PermissionRequest =
            PermissionRequest(
                id = requestId,
                sessionId = sessionId,
                permission = "edit",
                metadata = params,
            )

        internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
