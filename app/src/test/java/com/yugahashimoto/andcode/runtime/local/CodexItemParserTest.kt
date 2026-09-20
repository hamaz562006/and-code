package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `userMessage`/`error` fixtures below are the real notification lines a live, unauthenticated
 * `codex app-server` (0.155.1) sent back when driven directly over stdio - see docs/CODEX.md. The
 * `agentMessage`/`commandExecution` fixtures could not be captured the same way (they only appear
 * once a turn actually runs against a signed-in account), so they are built from the protocol's own
 * published JSON Schema (`codex app-server generate-json-schema`) instead.
 */
class CodexItemParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(
        sessionId: String,
        method: String,
        params: String,
    ) = CodexItemParser().handleNotification(sessionId, method, json.parseToJsonElement(params).jsonObject)

    @Test
    fun `a userMessage item becomes its own user message`() {
        val parsed =
            parse(
                "t1",
                "item/started",
                """
                {"item":{"type":"userMessage","id":"01a0baa0-2b85-7782-bb09-972122d20be2","clientId":null,
                 "content":[{"type":"text","text":"say hi","text_elements":[]}]},
                 "threadId":"t1","turnId":"01a0baa0-2b38-7a40-a2cf-14411c603b9f","startedAtMs":1789837454213}
                """.trimIndent(),
            )

        val message = parsed.messages.single()
        assertEquals("user", message.info.role)
        assertEquals("say hi", message.text)
    }

    @Test
    fun `a non-retrying error ends the turn`() {
        val parsed =
            parse(
                "t1",
                "error",
                """
                {"error":{"message":"fatal","codexErrorInfo":null,"additionalDetails":null,"misalignment":null},
                 "willRetry":false,"threadId":"t1","turnId":"turn-1"}
                """.trimIndent(),
            )

        assertTrue(parsed.events.any { it is OpenCodeEvent.SessionError })
        assertTrue(parsed.events.any { it is OpenCodeEvent.SessionIdle })
    }

    @Test
    fun `a retrying error does not end the turn`() {
        val parsed =
            parse(
                "t1",
                "error",
                """
                {"error":{"message":"Reconnecting... 2/5","codexErrorInfo":{"responseStreamDisconnected":{"httpStatusCode":401}},
                 "additionalDetails":"unexpected status 401","misalignment":null},
                 "willRetry":true,"threadId":"t1","turnId":"turn-1"}
                """.trimIndent(),
            )

        assertTrue(parsed.events.any { it is OpenCodeEvent.SessionError })
        assertTrue(parsed.events.none { it is OpenCodeEvent.SessionIdle })
    }

    @Test
    fun `an agentMessage item becomes assistant text, grouped by turn`() {
        val parser = CodexItemParser()
        val first =
            parser.handleNotification(
                "t1",
                "item/completed",
                json.parseToJsonElement(
                    """{"item":{"type":"agentMessage","id":"item-1","text":"Hello!","delivery":"async","phase":null,
                        "memoryCitation":null,"questions":null},"threadId":"t1","turnId":"turn-1","completedAtMs":1}""",
                ).jsonObject,
            )

        val message = first.messages.single()
        assertEquals("assistant", message.info.role)
        assertEquals("Hello!", message.text)
        assertEquals("codex-turn-turn-1", message.info.id)
    }

    @Test
    fun `an agentMessage delta streams a part id matching the item's own eventual part id`() {
        val delta =
            parse(
                "t1",
                "item/agentMessage/delta",
                """{"threadId":"t1","turnId":"turn-1","itemId":"item-1","delta":"Hel"}""",
            )

        val event = delta.events.single() as OpenCodeEvent.MessagePartDelta
        assertEquals("item-1-text", event.partId)
    }

    @Test
    fun `an agentMessage delta with no itemId is dropped rather than keyed by turnId`() {
        // itemId is a required field per AgentMessageDeltaNotification's schema; a line missing it
        // is an unrecognized shape, not one this parser should guess a key for (see docs/CODEX.md).
        val delta =
            parse(
                "t1",
                "item/agentMessage/delta",
                """{"threadId":"t1","turnId":"turn-1","delta":"Hel"}""",
            )

        assertTrue(delta.events.isEmpty())
    }

    @Test
    fun `a commandExecution item becomes a tool part with status and output`() {
        val parsed =
            parse(
                "t1",
                "item/completed",
                """
                {"item":{"type":"commandExecution","id":"cmd-1","command":"ls -la","status":"completed",
                 "aggregatedOutput":"total 0\n","exitCode":0,"durationMs":12,"cwd":null,"source":null,
                 "commandActions":null,"pluginId":null,"processId":null,"scriptPath":null},
                 "threadId":"t1","turnId":"turn-2","completedAtMs":1}
                """.trimIndent(),
            )

        val part = parsed.messages.single().parts.single()
        assertEquals("tool", part.type)
        assertEquals("bash", part.tool)
        assertEquals("completed", part.state?.get("status")?.jsonPrimitive?.content)
    }

    @Test
    fun `an item type with no dedicated mapping still surfaces instead of vanishing`() {
        val parsed =
            parse(
                "t1",
                "item/completed",
                """{"item":{"type":"webSearch","id":"ws-1","query":"kotlin coroutines","action":null,"results":null},
                    "threadId":"t1","turnId":"turn-3","completedAtMs":1}""",
            )

        val part = parsed.messages.single().parts.single()
        assertEquals("tool", part.type)
        assertEquals("webSearch", part.tool)
    }

    @Test
    fun `turn completion forgets the in-progress assistant message for that turn`() {
        val parser = CodexItemParser()
        parser.handleNotification(
            "t1",
            "item/completed",
            json.parseToJsonElement(
                """{"item":{"type":"agentMessage","id":"item-1","text":"partial"},"threadId":"t1","turnId":"turn-9","completedAtMs":1}""",
            ).jsonObject,
        )
        parser.handleNotification(
            "t1",
            "turn/completed",
            json.parseToJsonElement("""{"threadId":"t1","turn":{"id":"turn-9"}}""").jsonObject,
        )

        // A later item under the same turn id (a fork or an id reused after eviction) starts a fresh
        // message rather than silently resurrecting the forgotten one's parts.
        val after =
            parser.handleNotification(
                "t1",
                "item/completed",
                json.parseToJsonElement(
                    """{"item":{"type":"agentMessage","id":"item-2","text":"fresh"},"threadId":"t1","turnId":"turn-9","completedAtMs":2}""",
                ).jsonObject,
            )

        assertEquals(1, after.messages.single().parts.size)
        assertEquals("fresh", after.messages.single().text)
    }
}
