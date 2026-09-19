package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiStreamJsonParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parser() = PiStreamJsonParser("session-1", json)

    @Test
    fun `reads message_start and sets model and role`() {
        val parser = parser()
        val parsed = parser.parse("""{"type":"message_start","message":{"id":"msg-1","model":"claude-3-5-sonnet"}}""")

        assertEquals("claude-3-5-sonnet", parsed.resolvedModel)
        assertEquals(1, parsed.messages.size)
        assertEquals("msg-1", parsed.messages[0].info.id)
        assertEquals("assistant", parsed.messages[0].info.role)
        assertEquals("claude-3-5-sonnet", parsed.messages[0].info.model?.modelId)
    }

    @Test
    fun `accumulates text deltas on message_update`() {
        val parser = parser()
        parser.parse("""{"type":"message_start","message":{"id":"msg-1","model":"gpt-4o"}}""")

        val update1 = parser.parse("""{"type":"message_update","delta":"Hello "}""")
        assertEquals(2, update1.events.size)
        assertTrue(update1.events[0] is OpenCodeEvent.MessagePartDelta)
        assertEquals("Hello ", (update1.events[0] as OpenCodeEvent.MessagePartDelta).delta)
        assertEquals("Hello ", update1.messages[0].parts[0].text)

        val update2 = parser.parse("""{"type":"message_update","delta":"world!"}""")
        assertEquals("world!", (update2.events[0] as OpenCodeEvent.MessagePartDelta).delta)
        assertEquals("Hello world!", update2.messages[0].parts[0].text)
    }

    @Test
    fun `tracks tool execution start, delta update, and end`() {
        val parser = parser()
        parser.parse("""{"type":"message_start","message":{"id":"msg-1"}}""")

        val toolStart =
            parser.parse(
                """{"type":"tool_execution_start","toolCallId":"call-123","toolName":"bash","args":{"command":"ls"}}""",
            )
        assertEquals(1, toolStart.events.size)
        val toolPart = toolStart.messages[0].parts.first { it.type == "tool" }
        assertEquals("call-123", toolPart.callID)
        assertEquals("bash", toolPart.tool)
        assertEquals("running", toolPart.state?.get("status")?.jsonPrimitive?.content)

        val toolUpdate =
            parser.parse(
                """{"type":"tool_execution_update","toolCallId":"call-123","delta":"README.md\n"}""",
            )
        assertEquals(1, toolUpdate.events.size)
        val updatedPart = toolUpdate.messages[0].parts.first { it.type == "tool" }
        assertEquals("README.md\n", updatedPart.state?.get("output")?.jsonPrimitive?.content)

        val toolEnd =
            parser.parse(
                """{"type":"tool_execution_end","toolCallId":"call-123","result":"README.md\nbuild.gradle\n"}""",
            )
        assertEquals(1, toolEnd.events.size)
        val completedPart = toolEnd.messages[0].parts.first { it.type == "tool" }
        assertEquals("completed", completedPart.state?.get("status")?.jsonPrimitive?.content)
        assertEquals("README.md\nbuild.gradle\n", completedPart.state?.get("output")?.jsonPrimitive?.content)
    }

    @Test
    fun `turn_end settles any open running tools and finishes turn`() {
        val parser = parser()
        parser.parse("""{"type":"message_start","message":{"id":"msg-1"}}""")
        parser.parse("""{"type":"tool_execution_start","toolCallId":"call-999","toolName":"read_file"}""")

        val end = parser.parse("""{"type":"turn_end"}""")
        assertTrue(end.turnFinished)
        assertTrue(parser.turnFinished)

        // Settle open tool
        val toolPart = end.messages[0].parts.first { it.type == "tool" }
        assertEquals("completed", toolPart.state?.get("status")?.jsonPrimitive?.content)

        // Emits idle event
        assertTrue(end.events.any { it is OpenCodeEvent.SessionIdle })
    }

    @Test
    fun `error response marks turn as finished with session error`() {
        val parser = parser()
        parser.parse("""{"type":"message_start","message":{"id":"msg-1"}}""")

        val error = parser.parse("""{"type":"response","success":false,"error":"API key invalid"}""")
        assertTrue(error.turnFinished)
        assertEquals("API key invalid", error.errorMessage)
        assertTrue(error.events.any { it is OpenCodeEvent.SessionError })
        assertTrue(error.events.any { it is OpenCodeEvent.SessionIdle })
    }
}
