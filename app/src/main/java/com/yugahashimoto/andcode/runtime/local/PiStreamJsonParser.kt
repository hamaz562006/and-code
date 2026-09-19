package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeMessageInfo
import com.yugahashimoto.andcode.core.api.OpenCodeModelReference
import com.yugahashimoto.andcode.core.api.OpenCodePart
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

class PiStreamJsonParser(
    private val sessionId: String,
    private val json: Json,
) {
    data class Parsed(
        val events: List<OpenCodeEvent> = emptyList(),
        val messages: List<OpenCodeMessage> = emptyList(),
        val resolvedModel: String? = null,
        val turnFinished: Boolean = false,
        val errorMessage: String? = null,
    )

    private var currentAssistantMessageId: String? = null
    private val messageTextBuilder = StringBuilder()
    private val openTools = linkedMapOf<String, OpenCodePart>()
    private val messagesById = linkedMapOf<String, OpenCodeMessage>()

    @Volatile
    var turnFinished: Boolean = false
        private set

    fun beginTurn() {
        turnFinished = false
        currentAssistantMessageId = null
        messageTextBuilder.clear()
        openTools.clear()
    }

    fun parse(line: String): Parsed {
        if (line.isBlank()) return Parsed()
        val root = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return Parsed()
        val type = root.string("type") ?: return Parsed()

        return when (type) {
            "message_start" -> parseMessageStart(root)
            "message_update", "text_delta", "stream_event" -> parseMessageUpdate(root)
            "tool_execution_start", "tool_start", "tool_use" -> parseToolStart(root)
            "tool_execution_update" -> parseToolUpdate(root)
            "tool_execution_end", "tool_end", "tool_result" -> parseToolEnd(root)
            "message_end" -> parseMessageEnd(root)
            "agent_end", "turn_end" -> parseTurnEnd()
            "response" -> parseResponse(root)
            "error", "session_error" -> parseError(root)
            else -> Parsed()
        }
    }

    private fun parseMessageStart(root: JsonObject): Parsed {
        val message = root["message"] as? JsonObject
        val messageId = message?.string("id") ?: root.string("id") ?: newMessageId()
        currentAssistantMessageId = messageId
        val model = message?.string("model") ?: root.string("model")
        messageTextBuilder.clear()
        openTools.clear()

        val initialMessage =
            OpenCodeMessage(
                info =
                    OpenCodeMessageInfo(
                        id = messageId,
                        sessionId = sessionId,
                        role = "assistant",
                        time = now(),
                        agent = "pi",
                        model = model?.let { OpenCodeModelReference("pi", it) },
                    ),
                parts = emptyList(),
            )
        messagesById[messageId] = initialMessage
        return Parsed(
            messages = listOf(initialMessage),
            resolvedModel = model,
        )
    }

    private fun parseMessageUpdate(root: JsonObject): Parsed {
        val messageId = currentAssistantMessageId ?: newMessageId().also { currentAssistantMessageId = it }
        val delta = extractDelta(root) ?: return Parsed()
        if (delta.isEmpty()) return Parsed()

        messageTextBuilder.append(delta)
        val textPartId = "$messageId-text"
        val textPart =
            OpenCodePart(
                id = textPartId,
                sessionId = sessionId,
                messageId = messageId,
                type = "text",
                text = messageTextBuilder.toString(),
            )

        val parts = buildList {
            add(textPart)
            addAll(openTools.values)
        }

        val existing = messagesById[messageId]
        val updatedMessage =
            OpenCodeMessage(
                info =
                    existing?.info ?: OpenCodeMessageInfo(
                        id = messageId,
                        sessionId = sessionId,
                        role = "assistant",
                        time = now(),
                        agent = "pi",
                    ),
                parts = parts,
            )
        messagesById[messageId] = updatedMessage

        val deltaEvent = OpenCodeEvent.MessagePartDelta(sessionId, messageId, textPartId, "text", delta)
        val updateEvent = OpenCodeEvent.MessagePartUpdated(textPart)
        return Parsed(
            events = listOf(deltaEvent, updateEvent),
            messages = listOf(updatedMessage),
        )
    }

    private fun extractDelta(root: JsonObject): String? {
        val assistantEvent = root["assistantMessageEvent"] as? JsonObject
        return assistantEvent?.string("delta")
            ?: assistantEvent?.string("text")
            ?: root.string("delta")
            ?: (root["delta"] as? JsonObject)?.string("text")
            ?: root.string("text")
            ?: (root["event"] as? JsonObject)?.let { (it["delta"] as? JsonObject)?.string("text") }
            ?: run {
                val content = (root["content"] as? JsonArray)
                    ?: (root["message"] as? JsonObject)?.get("content") as? JsonArray
                content?.mapNotNull { (it as? JsonObject)?.string("text") }?.joinToString("")?.takeIf { it.isNotEmpty() }
            }
    }

    private fun parseToolStart(root: JsonObject): Parsed {
        val messageId = currentAssistantMessageId ?: newMessageId().also { currentAssistantMessageId = it }
        val toolCallId = root.string("toolCallId") ?: root.string("callID") ?: root.string("id") ?: "tool-${UUID.randomUUID()}"
        val toolName = root.string("toolName") ?: root.string("name") ?: root.string("tool") ?: "tool"
        val args = (root["args"] as? JsonObject) ?: (root["input"] as? JsonObject) ?: JsonObject(emptyMap())

        val toolPart =
            OpenCodePart(
                id = toolCallId,
                sessionId = sessionId,
                messageId = messageId,
                type = "tool",
                tool = toolName,
                callID = toolCallId,
                state =
                    mapOf(
                        "status" to JsonPrimitive("running"),
                        "input" to args,
                        "tool" to JsonPrimitive(toolName),
                    ),
            )
        openTools[toolCallId] = toolPart

        val updatedMessage = updateMessageWithCurrentParts(messageId)
        return Parsed(
            events = listOf(OpenCodeEvent.MessagePartUpdated(toolPart)),
            messages = listOfNotNull(updatedMessage),
        )
    }

    private fun parseToolUpdate(root: JsonObject): Parsed {
        val toolCallId = root.string("toolCallId") ?: root.string("callID") ?: root.string("id") ?: return Parsed()
        val existing = openTools[toolCallId] ?: return Parsed()
        val delta = root.string("delta") ?: root.string("output") ?: return Parsed()

        val currentOutput = (existing.state?.get("output") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val toolPart =
            existing.copy(
                state =
                    existing.state.orEmpty() +
                        mapOf(
                            "output" to JsonPrimitive(currentOutput + delta),
                        ),
            )
        openTools[toolCallId] = toolPart

        val updatedMessage = updateMessageWithCurrentParts(existing.messageId.orEmpty())
        return Parsed(
            events = listOf(OpenCodeEvent.MessagePartUpdated(toolPart)),
            messages = listOfNotNull(updatedMessage),
        )
    }

    private fun parseToolEnd(root: JsonObject): Parsed {
        val toolCallId =
            root.string("toolCallId") ?: root.string("callID") ?: root.string("id") ?: root.string("tool_use_id")
                ?: return Parsed()
        val existing = openTools[toolCallId]
        val messageId = existing?.messageId ?: currentAssistantMessageId ?: newMessageId().also { currentAssistantMessageId = it }
        val isError = root.boolean("isError") ?: root.boolean("is_error") ?: false
        val output = root.contentText("result") ?: root.contentText("output") ?: ""
        val toolName = existing?.tool ?: root.string("toolName") ?: root.string("name") ?: "tool"

        val toolPart =
            OpenCodePart(
                id = toolCallId,
                sessionId = sessionId,
                messageId = messageId,
                type = "tool",
                tool = toolName,
                callID = toolCallId,
                state =
                    (existing?.state.orEmpty()) +
                        mapOf(
                            "status" to JsonPrimitive(if (isError) "error" else "completed"),
                            "output" to JsonPrimitive(output),
                        ),
            )
        openTools[toolCallId] = toolPart

        val updatedMessage = updateMessageWithCurrentParts(messageId)
        return Parsed(
            events = listOf(OpenCodeEvent.MessagePartUpdated(toolPart)),
            messages = listOfNotNull(updatedMessage),
        )
    }

    private fun parseMessageEnd(root: JsonObject): Parsed {
        val message = root["message"] as? JsonObject
        val model = message?.string("model") ?: root.string("model")
        val messageId = currentAssistantMessageId ?: return Parsed(resolvedModel = model)

        // If a complete text is provided in the message object, reconcile it
        val fullContent = (message?.get("content") as? JsonArray)?.mapNotNull {
            (it as? JsonObject)?.string("text")
        }?.joinToString("")

        if (!fullContent.isNullOrBlank() && fullContent != messageTextBuilder.toString()) {
            messageTextBuilder.clear()
            messageTextBuilder.append(fullContent)
            val textPart =
                OpenCodePart(
                    id = "$messageId-text",
                    sessionId = sessionId,
                    messageId = messageId,
                    type = "text",
                    text = fullContent,
                )
            val updatedMessage = updateMessageWithCurrentParts(messageId)
            return Parsed(
                events = listOf(OpenCodeEvent.MessagePartUpdated(textPart)),
                messages = listOfNotNull(updatedMessage),
                resolvedModel = model,
            )
        }

        return Parsed(resolvedModel = model)
    }

    private fun parseTurnEnd(): Parsed {
        turnFinished = true
        val settled = settleOpenTools()
        return Parsed(
            events = settled.events + listOf(OpenCodeEvent.SessionIdle(sessionId)),
            messages = settled.messages,
            turnFinished = true,
        )
    }

    private fun parseResponse(root: JsonObject): Parsed {
        val success = root.boolean("success") ?: true
        if (!success) {
            turnFinished = true
            val error = root.string("error") ?: root.string("message") ?: "Pi command failed"
            val settled = settleOpenTools(error)
            return Parsed(
                events = settled.events + listOf(OpenCodeEvent.SessionError(sessionId, error), OpenCodeEvent.SessionIdle(sessionId)),
                messages = settled.messages,
                turnFinished = true,
                errorMessage = error,
            )
        }
        // If response is for a prompt command and turn isn't ended yet
        val command = root.string("command")
        if (command == "prompt") {
            turnFinished = true
            val settled = settleOpenTools()
            return Parsed(
                events = settled.events + listOf(OpenCodeEvent.SessionIdle(sessionId)),
                messages = settled.messages,
                turnFinished = true,
            )
        }
        return Parsed()
    }

    private fun parseError(root: JsonObject): Parsed {
        turnFinished = true
        val error = root.string("error") ?: root.string("message") ?: "Pi reported an error"
        val settled = settleOpenTools(error)
        return Parsed(
            events = settled.events + listOf(OpenCodeEvent.SessionError(sessionId, error), OpenCodeEvent.SessionIdle(sessionId)),
            messages = settled.messages,
            turnFinished = true,
            errorMessage = error,
        )
    }

    private data class SettledTools(
        val messages: List<OpenCodeMessage>,
        val events: List<OpenCodeEvent>,
    )

    private fun settleOpenTools(error: String? = null): SettledTools {
        if (openTools.isEmpty()) return SettledTools(emptyList(), emptyList())
        val updatedEvents = mutableListOf<OpenCodeEvent>()

        openTools.entries.forEach { (callId, part) ->
            val status = (part.state?.get("status") as? JsonPrimitive)?.contentOrNull
            if (status == "running" || status == "pending") {
                val finalStatus = if (error != null) "error" else "completed"
                val settledState = part.state.orEmpty() + mapOf("status" to JsonPrimitive(finalStatus)) +
                    (if (error != null) mapOf("error" to JsonPrimitive(error)) else emptyMap())
                val settledPart = part.copy(state = settledState)
                openTools[callId] = settledPart
                updatedEvents.add(OpenCodeEvent.MessagePartUpdated(settledPart))
            }
        }

        val updatedMessages = mutableListOf<OpenCodeMessage>()
        currentAssistantMessageId?.let { messageId ->
            updateMessageWithCurrentParts(messageId)?.let(updatedMessages::add)
        }
        return SettledTools(updatedMessages, updatedEvents)
    }

    private fun updateMessageWithCurrentParts(messageId: String): OpenCodeMessage? {
        val existing = messagesById[messageId]
        val textPart =
            if (messageTextBuilder.isNotEmpty()) {
                OpenCodePart(
                    id = "$messageId-text",
                    sessionId = sessionId,
                    messageId = messageId,
                    type = "text",
                    text = messageTextBuilder.toString(),
                )
            } else {
                null
            }

        val parts = buildList {
            textPart?.let(::add)
            addAll(openTools.values)
        }

        val updated =
            OpenCodeMessage(
                info =
                    existing?.info ?: OpenCodeMessageInfo(
                        id = messageId,
                        sessionId = sessionId,
                        role = "assistant",
                        time = now(),
                        agent = "pi",
                    ),
                parts = parts,
            )
        messagesById[messageId] = updated
        return updated
    }

    private fun newMessageId(): String = "pi-${UUID.randomUUID()}"

    private fun now(): OpenCodeTime {
        val timestamp = System.currentTimeMillis()
        return OpenCodeTime(timestamp, timestamp)
    }

    private companion object {
        fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

        fun JsonObject.contentText(key: String): String? {
            val element = this[key] ?: return null
            return when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonArray ->
                    element.jsonArray.joinToString("\n") { item ->
                        (item as? JsonObject)?.let { it["text"] as? JsonPrimitive }?.contentOrNull ?: item.toString()
                    }
                else -> element.toString()
            }
        }
    }
}
