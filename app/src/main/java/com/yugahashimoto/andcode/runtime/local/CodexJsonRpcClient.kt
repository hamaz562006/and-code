package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Speaks Codex's `app-server` JSON-RPC protocol: one JSON object per line on stdin/stdout, request
 * ids correlate a response to its call, and everything without an `id` is a notification.
 *
 * Verified by hand against the real `codex app-server` binary (0.155.1): `initialize` returns
 * immediately with `{userAgent, codexHome, platformFamily, platformOs}`, an out-of-band
 * `configWarning`/`remoteControl/status/changed` notification follows unprompted, and a JSON-RPC
 * error comes back as `{"error": {"code", "message"}, "id"}` - no `Content-Length` framing, unlike
 * LSP. There is no protocol-version negotiation in `initialize`, so none is sent here either.
 */
class CodexJsonRpcClient(
    private val output: OutputStream,
    private val onNotification: (method: String, params: JsonElement?) -> Unit,
    /**
     * Serializes every write to [output]. A constructor parameter (not a class body property) so
     * [onServerRequest]'s own default value below can use it: a default parameter expression can only
     * reference earlier constructor parameters, not class body members - the same restriction that
     * already keeps that default from calling the [respond]/[respondError] instance methods directly.
     */
    private val writeLock: Mutex = Mutex(),
    /**
     * A request the server sent to us (an approval prompt, most often): unlike a notification it
     * carries an `id` and expects a matching [respond] or [respondError] call, but not necessarily
     * before this callback returns - the app answers these from user input, which can take a while.
     */
    private val onServerRequest: (id: JsonElement, method: String, params: JsonElement?) -> Unit = { id, method, _ ->
        // No approval UI wired up: refuse rather than hang the server waiting for a reply forever, in
        // the exact wire shape respond()/respondError() would produce. Routed through writeLock via
        // runBlocking (this callback runs synchronously on the reader thread, not inside a coroutine)
        // so it cannot interleave with a call()/notify()/respond() write and corrupt the
        // newline-delimited JSON stream. Unreached in production, where CodexRuntime always supplies
        // its own onServerRequest, but tests and future callers still go through it.
        runCatching {
            runBlocking {
                writeLock.withLock {
                    output.write(
                        """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"No server-request handler installed for $method"}}""".toByteArray(),
                    )
                    output.write("\n".toByteArray())
                    output.flush()
                }
            }
        }
    },
    private val onClientError: (Throwable) -> Unit = {},
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        },
) {
    private val nextId = AtomicLong(1)

    /**
     * A plain concurrent map, not a coroutine [Mutex]: [receiveLine] runs synchronously on a
     * reader thread and must read this without suspending, while [call] writes to it from whatever
     * coroutine sent the request.
     */
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()

    class RpcError(val code: Long, message: String) : Exception(message)

    /** Sends [method] with [params] and suspends for the matching response's `result`. */
    suspend fun call(
        method: String,
        params: JsonElement? = null,
    ): JsonObject {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        try {
            val message =
                buildJsonObject {
                    put("jsonrpc", JsonPrimitive("2.0"))
                    put("id", JsonPrimitive(id))
                    put("method", JsonPrimitive(method))
                    // Always present: the app-server rejects a request with no `params` at all
                    // ("missing field `params`"), even for methods that take no arguments.
                    put("params", params ?: JsonObject(emptyMap()))
                }
            // writeLine() itself is inside the try, not just deferred.await(): a write that throws
            // (a broken pipe from an already-dead process) must still remove this id from `pending`,
            // or it leaks there until something else happens to call failPending() on this client.
            writeLine(message)
            return deferred.await()
        } finally {
            pending.remove(id)
        }
    }

    /** Sends a notification (no reply expected). */
    suspend fun notify(
        method: String,
        params: JsonElement? = null,
    ) {
        val message =
            buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("method", JsonPrimitive(method))
                params?.let { put("params", it) }
            }
        writeLine(message)
    }

    private suspend fun writeLine(message: JsonObject) {
        val bytes = (json.encodeToString(JsonObject.serializer(), message) + "\n").toByteArray(Charsets.UTF_8)
        writeLock.withLock {
            output.write(bytes)
            output.flush()
        }
    }

    /**
     * Feeds one line of the server's stdout; call this from a dedicated reader loop.
     *
     * A line's shape decides what it is, not merely whether `id` is present: a server-initiated
     * request (an approval prompt) carries both `method` and `id`, so `method` is checked first.
     * Only a bare `id` - a response or error to a call this client made - falls through to the
     * pending-call table.
     */
    fun receiveLine(line: String) {
        if (line.isBlank()) return
        val root =
            runCatching { json.parseToJsonElement(line).jsonObject }.getOrElse {
                onClientError(it)
                return
            }
        val method = (root["method"] as? JsonPrimitive)?.content
        val id = root["id"]
        if (method != null) {
            if (id != null) {
                onServerRequest(id, method, root["params"])
            } else {
                onNotification(method, root["params"])
            }
            return
        }
        val ourId = (id as? JsonPrimitive)?.longOrNullCompat() ?: return
        val deferred = pending[ourId] ?: return
        val error = root["error"]?.jsonObject
        if (error != null) {
            val code = (error["code"] as? JsonPrimitive)?.longOrNullCompat() ?: -1
            val message = (error["message"] as? JsonPrimitive)?.content ?: "Codex app-server error"
            deferred.completeExceptionally(RpcError(code, message))
        } else {
            deferred.complete(root["result"]?.jsonObject ?: JsonObject(emptyMap()))
        }
    }

    /** Replies to a request the server sent us (see [onServerRequest]) with a successful [result]. */
    suspend fun respond(
        id: JsonElement,
        result: JsonElement,
    ) {
        writeLine(
            buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", id)
                put("result", result)
            },
        )
    }

    /** Replies to a request the server sent us (see [onServerRequest]) with an error. */
    suspend fun respondError(
        id: JsonElement,
        code: Long,
        message: String,
    ) {
        writeLine(
            buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", id)
                put(
                    "error",
                    buildJsonObject {
                        put("code", JsonPrimitive(code))
                        put("message", JsonPrimitive(message))
                    },
                )
            },
        )
    }

    /** Fails every call still awaiting a reply, e.g. because the process died. */
    fun failPending(cause: Throwable) {
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
    }

    private fun JsonPrimitive.longOrNullCompat(): Long? = runCatching { long }.getOrNull()
}

/**
 * Runs a reader loop over [lines], feeding [client] until the source ends or [scope] is cancelled.
 *
 * [onEnded] is skipped when this coroutine was cancelled (a deliberate stop, e.g. the app tearing
 * the process down itself): the same distinction [ClaudeCodeRuntime]'s own reader job makes, so a
 * caller does not have to tell an unrequested crash apart from its own shutdown to decide whether an
 * in-flight turn needs to be reported as failed.
 */
fun launchCodexReaderLoop(
    scope: CoroutineScope,
    lines: Sequence<String>,
    client: CodexJsonRpcClient,
    onEnded: (Throwable?) -> Unit,
): Job =
    scope.launch(Dispatchers.IO) {
        val failure = runCatching { lines.forEach(client::receiveLine) }.exceptionOrNull()
        if (failure is CancellationException || !isActive) return@launch
        onEnded(failure)
    }
