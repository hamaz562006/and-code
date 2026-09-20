package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * These lines are taken verbatim from a real `codex app-server` (0.155.1) session, captured by
 * driving the actual binary over stdio with no fixture or mock server involved - see docs/CODEX.md.
 */
class CodexJsonRpcClientTest {
    @Test
    fun `resolves a pending call from its response line`() =
        runBlocking {
            val output = ByteArrayOutputStream()
            val client = CodexJsonRpcClient(output, onNotification = { _, _ -> })

            val pending = async { client.call("initialize") }
            // Let call() write its request before the "response" is fed back in.
            kotlinx.coroutines.yield()
            client.receiveLine(
                """{"id":1,"result":{"userAgent":"probe/0.155.1","codexHome":"/tmp/.codex","platformFamily":"unix","platformOs":"linux"}}""",
            )

            val result = pending.await()
            assertEquals("linux", result["platformOs"]?.jsonPrimitive?.content)
            assertTrue(output.toString(Charsets.UTF_8.name()).contains("\"method\":\"initialize\""))
        }

    @Test
    fun `a JSON-RPC error fails the matching call`() =
        runBlocking {
            val output = ByteArrayOutputStream()
            val client = CodexJsonRpcClient(output, onNotification = { _, _ -> })

            // A plain async{}.await() would still crash this scope on failure even inside a
            // try/catch around await() - an unhandled child failure cancels the parent job
            // regardless. runCatching inside the coroutine keeps the failure a plain value instead.
            val pending = async { runCatching { client.call("turn/start") } }
            kotlinx.coroutines.yield()
            client.receiveLine(
                """{"error":{"code":-32600,"message":"invalid thread id: invalid character: expected an optional prefix of `urn:uuid:` followed by [0-9a-fA-F-], found `P` at 1"},"id":1}""",
            )

            val error = pending.await().exceptionOrNull()
            assertTrue("expected an RpcError", error is CodexJsonRpcClient.RpcError)
            assertTrue(error!!.message.orEmpty().contains("invalid thread id"))
        }

    @Test
    fun `a line with no id is a notification`() {
        val notifications = mutableListOf<Pair<String, JsonElement?>>()
        val client = CodexJsonRpcClient(ByteArrayOutputStream(), onNotification = { method, params -> notifications += method to params })

        client.receiveLine(
            """{"method":"configWarning","params":{"summary":"Codex could not find bubblewrap on PATH.","details":null},"emittedAtMs":1789837375028}""",
        )

        assertEquals(1, notifications.size)
        assertEquals("configWarning", notifications[0].first)
    }

    @Test
    fun `a line with both method and id is a server request, not a notification`() {
        val notifications = mutableListOf<String>()
        val requests = mutableListOf<Triple<JsonElement, String, JsonElement?>>()
        val client =
            CodexJsonRpcClient(
                ByteArrayOutputStream(),
                onNotification = { method, _ -> notifications += method },
                onServerRequest = { id, method, params -> requests += Triple(id, method, params) },
            )

        client.receiveLine(
            """{"id":7,"method":"item/commandExecution/requestApproval","params":{"threadId":"t1","command":"rm -rf /tmp/x"}}""",
        )

        assertTrue(notifications.isEmpty())
        assertEquals(1, requests.size)
        assertEquals("item/commandExecution/requestApproval", requests[0].second)
        assertEquals(JsonPrimitive(7), requests[0].first)
    }

    @Test
    fun `respond echoes the server request id back with a result`() =
        runBlocking {
            val output = ByteArrayOutputStream()
            val client = CodexJsonRpcClient(output, onNotification = { _, _ -> })

            client.respond(JsonPrimitive(7), JsonPrimitive("approved"))

            val written = output.toString(Charsets.UTF_8.name())
            assertTrue(written.contains("\"id\":7"))
            assertTrue(written.contains("\"result\":\"approved\""))
        }

    @Test
    fun `an unrelated response line for an unknown id is ignored, not thrown`() {
        val client = CodexJsonRpcClient(ByteArrayOutputStream(), onNotification = { _, _ -> })
        // No call() was ever made for id 99; must not throw.
        client.receiveLine("""{"id":99,"result":{}}""")
    }

    @Test
    fun `with no onServerRequest handler, a server request is refused rather than left hanging`() {
        val output = ByteArrayOutputStream()
        val client = CodexJsonRpcClient(output, onNotification = { _, _ -> })

        client.receiveLine("""{"id":7,"method":"item/commandExecution/requestApproval","params":{"threadId":"t1"}}""")

        val written = output.toString(Charsets.UTF_8.name())
        assertTrue(written.contains("\"id\":7"))
        assertTrue(written.contains("\"error\""))
    }
}
