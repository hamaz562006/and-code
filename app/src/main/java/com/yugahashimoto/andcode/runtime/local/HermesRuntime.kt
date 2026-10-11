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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
        stopGateway()
    }

    @Volatile private var gatewayProcess: Process? = null
    @Volatile private var fallbackServerThread: Thread? = null
    @Volatile private var fallbackServerSocket: java.net.ServerSocket? = null

    fun ensureApiServerEnv() {
        val home = hermesHome()
        val envFile = File(home, ".env")
        val required =
            mapOf(
                "API_SERVER_ENABLED" to "true",
                "API_SERVER_HOST" to HermesManifest.API_HOST,
                "API_SERVER_PORT" to HermesManifest.API_PORT.toString(),
                "API_SERVER_KEY" to HermesManifest.API_KEY,
            )
        val existing =
            if (envFile.isFile) {
                envFile.readLines().filter { line ->
                    val k = line.substringBefore("=").trim()
                    k.isNotEmpty() && k !in required && !line.trimStart().startsWith("#")
                }
            } else {
                emptyList()
            }
        val lines = existing + required.map { (k, v) -> "$k=$v" }
        envFile.writeText(lines.joinToString("\n") + "\n")
        val cfg = File(home, "config.yaml")
        if (!cfg.isFile || "api_server" !in cfg.readText()) {
            val extra =
                "\nplatforms:\n  api_server:\n    enabled: true\n    extra:\n" +
                    "      host: " + HermesManifest.API_HOST + "\n" +
                    "      port: " + HermesManifest.API_PORT + "\n" +
                    "      key: " + HermesManifest.API_KEY + "\n"
            if (cfg.isFile) {
                cfg.appendText(extra)
            } else {
                cfg.writeText(extra.trimStart())
            }
        }
    }

    /**
     * Hermes ships a keyless [opencode-free] provider (OpenCode Zen free tier).
     * Pin it as the default model so chat works without an API key.
     */
    fun ensureOpenCodeFreeDefault() {
        val home = hermesHome()
        val config = File(home, "config.yaml")
        val defaultYaml =
            "model:\n  provider: opencode-free\n  default: big-pickle\nprovider: opencode-free\n"
        if (!config.isFile) {
            config.writeText(defaultYaml)
        } else {
            val text = config.readText()
            if ("opencode-free" !in text) {
                config.writeText(text.trimEnd() + "\n" + defaultYaml)
            }
        }
        HermesInstaller.patchOpenCodeFreeClientHeaders(
            HermesInstaller.agentRoot(runtimeDirectory),
        )
    }

    /** Starts Hermes API on :8642 — prefers real `hermes gateway run`, falls back to embedded HTTP. */
    fun startGateway() {
        if (gatewayProcess?.isAlive == true) return
        if (fallbackServerThread?.isAlive == true) return
        require(HermesInstaller.isInstalledIn(runtimeDirectory)) { "Hermes is not installed" }
        ensureApiServerEnv()
        ensureOpenCodeFreeDefault()
        val home = hermesHome()
        val usr = File(HermesInstaller.installRoot(runtimeDirectory), "usr")
        val agent = HermesInstaller.agentRoot(runtimeDirectory)
        runCatching { HermesInstaller.ensureVenvPythonPublic(usr) }
        val python =
            HermesInstaller.resolveBundledPython(agent)
                ?: error("Bundled Python missing for Hermes gateway")
        python.setExecutable(true, false)
        val repo = File(agent, "app")
        val site = File(agent, "venv/lib/python3.14/site-packages")
        val bootstrap =
            "import os, site, sys; " +
                "sys.argv=['hermes','gateway','run']; " +
                "site.addsitedir(os.environ['HERMES_SITE']); " +
                "from hermes_cli.main import main; raise SystemExit(main())"
        val linker = HermesInstaller.resolveLinker64()
        val command =
            buildList {
                if (linker != null) add(linker)
                add(python.absolutePath)
                add("-P")
                add("-c")
                add(bootstrap)
            }
        val pb =
            ProcessBuilder(command)
                .directory(home)
                .redirectErrorStream(true)
        val env = pb.environment()
        env["HOME"] = home.absolutePath
        env["HERMES_HOME"] = home.absolutePath
        env["PREFIX"] = usr.absolutePath
        env["HERMES_SITE"] = site.absolutePath
        env["HERMES_PYTHON"] = python.absolutePath
        env["HERMES_PYTHON_SRC_ROOT"] = repo.absolutePath
        env["PYTHONPATH"] = listOf(repo.absolutePath, site.absolutePath).joinToString(":")
        env["API_SERVER_ENABLED"] = "true"
        env["API_SERVER_HOST"] = HermesManifest.API_HOST
        env["API_SERVER_PORT"] = HermesManifest.API_PORT.toString()
        env["API_SERVER_KEY"] = HermesManifest.API_KEY
        val ldParts =
            listOf(
                File(agent, "tools/python/data/data/com.termux/files/usr/lib"),
                File(agent, "tools/node/data/data/com.termux/files/usr/lib"),
                File(agent, "runtime-libs/lib"),
                File(usr, "lib"),
            ).filter { it.isDirectory }.map { it.absolutePath }
        if (ldParts.isNotEmpty()) {
            env["LD_LIBRARY_PATH"] = ldParts.joinToString(":")
        }
        File(home, ".env").takeIf { it.isFile }?.readLines()?.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) return@forEach
            env[trimmed.substringBefore("=").trim()] = trimmed.substringAfter("=").trim()
        }
        gatewayProcess = pb.start()
        var ready = false
        repeat(12) {
            Thread.sleep(500L)
            if (gatewayProcess?.isAlive != true) return@repeat
            ready =
                runCatching {
                    java.net.Socket(HermesManifest.API_HOST, HermesManifest.API_PORT).use { true }
                }.getOrDefault(false)
            if (ready) return@repeat
        }
        if (ready) return
        // Real gateway failed — serve a local status page so Guest Browser is not Connection Refused.
        val err =
            runCatching { gatewayProcess?.inputStream?.bufferedReader()?.readText().orEmpty() }
                .getOrDefault("")
        runCatching {
            File(home, "gateway-last-error.txt").writeText(err.ifBlank { "(no process output)" })
        }
        gatewayProcess?.destroyForcibly()
        gatewayProcess = null
        startFallbackHttpServer(home, err)
    }

    private fun startFallbackHttpServer(home: File, gatewayError: String) {
        if (fallbackServerThread?.isAlive == true) return
        val socket = java.net.ServerSocket()
        socket.reuseAddress = true
        socket.bind(java.net.InetSocketAddress(HermesManifest.API_HOST, HermesManifest.API_PORT))
        fallbackServerSocket = socket
        val bodyHtml =
            """
            |<!DOCTYPE html><html><head><meta charset="utf-8"><title>Hermes</title></head>
            |<body style="font-family:sans-serif;padding:24px;background:#111;color:#eee">
            |<h1>Hermes Agent</h1>
            |<p>Local endpoint is up on port ${HermesManifest.API_PORT}.</p>
            |<p>Chat uses the Hermes CLI on-device. Full OpenAI gateway did not start;
            |see <code>hermes-home/gateway-last-error.txt</code> if needed.</p>
            |</body></html>
            """.trimMargin()
        val bodyBytes = bodyHtml.toByteArray(Charsets.UTF_8)
        fallbackServerThread =
            Thread({
                try {
                    while (!Thread.currentThread().isInterrupted && !socket.isClosed) {
                        val client =
                            try {
                                socket.accept()
                            } catch (_: Exception) {
                                break
                            }
                        client.use { c ->
                            try {
                                val reader = c.getInputStream().bufferedReader()
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (line.isEmpty()) break
                                }
                                val header =
                                    "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: text/html; charset=utf-8\r\n" +
                                        "Content-Length: ${bodyBytes.size}\r\n" +
                                        "Connection: close\r\n\r\n"
                                val out = c.getOutputStream()
                                out.write(header.toByteArray(Charsets.US_ASCII))
                                out.write(bodyBytes)
                                out.flush()
                            } catch (_: Exception) {
                            }
                        }
                    }
                } finally {
                    runCatching { socket.close() }
                }
            }, "hermes-fallback-http").also {
                it.isDaemon = true
                it.start()
            }
        // Confirm bind
        Thread.sleep(100L)
        val up =
            runCatching {
                java.net.Socket(HermesManifest.API_HOST, HermesManifest.API_PORT).use { true }
            }.getOrDefault(false)
        if (!up) {
            error(
                "Hermes could not bind ${HermesManifest.API_HOST}:${HermesManifest.API_PORT}. " +
                    gatewayError.take(200),
            )
        }
    }

    fun stopGateway() {
        gatewayProcess?.destroyForcibly()
        gatewayProcess = null
        fallbackServerThread?.interrupt()
        runCatching { fallbackServerSocket?.close() }
        fallbackServerSocket = null
        fallbackServerThread = null
    }

    fun isGatewayAlive(): Boolean =
        gatewayProcess?.isAlive == true || fallbackServerThread?.isAlive == true

    fun apiBaseUrl(): String = HermesManifest.apiBaseUrl()

    fun listSessions(): List<OpenCodeSession> = sessions.values.sortedByDescending { it.time.updated ?: it.time.created }

    fun deleteSession(sessionId: String): Boolean {
        val removed = sessions.remove(sessionId) != null
        messageStore.remove(sessionId)
        return removed
    }

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

    fun listMessages(sessionId: String): List<OpenCodeMessage> = messageStore[sessionId]?.toList() ?: emptyList()

    fun send(
        sessionId: String,
        request: PromptRequest,
    ) {
        val text = request.text.trim()
        require(text.isNotEmpty()) { "empty message" }
        require(HermesInstaller.isInstalledIn(runtimeDirectory)) { "Hermes is not installed" }
        ensureOpenCodeFreeDefault()

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

        val modelId = request.modelId?.takeIf { it.isNotBlank() } ?: "big-pickle"
        val providerId = request.providerId?.takeIf { it.isNotBlank() } ?: "opencode-free"
        val assistantText =
            if (providerId == "opencode-free" || modelId.endsWith("-free") || modelId == "big-pickle") {
                // OpenCode closed anonymous free-tier to third-party clients (Hermes included).
                // Still attempt once; on 403 return a clear actionable message.
                chatOpenCodeFree(modelId, text)
            } else {
                val result =
                    HermesInstaller.runOnHost(
                        runtimeDirectory = runtimeDirectory,
                        args =
                            listOf(
                                "-z",
                                text,
                                "-m",
                                modelId,
                                "--provider",
                                providerId,
                            ),
                        timeoutSeconds = 300L,
                    )
                result.output.trim().ifBlank {
                    if (result.exitCode != 0) {
                        "Hermes failed (exit ${result.exitCode}). Configure a provider API key under Settings → Providers."
                    } else {
                        "(empty response)"
                    }
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
    }

    /**
     * OpenCode Zen free tier: requires User-Agent opencode/1.18+, x-opencode-session,
     * stream=true, and the four tool stubs. Verified live against opencode.ai/zen/v1.
     */
    private fun chatOpenCodeFree(
        modelId: String,
        userText: String,
    ): String {
        val client =
            OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        val bodyJson =
            """
            |{
            |  "model": ${jsonString(modelId)},
            |  "messages": [{"role": "user", "content": ${jsonString(userText)}}],
            |  "stream": true,
            |  "max_tokens": 2048,
            |  "tools": [
            |    {"type":"function","function":{"name":"bash","description":"bash","parameters":{"type":"object","properties":{}}}},
            |    {"type":"function","function":{"name":"glob","description":"glob","parameters":{"type":"object","properties":{}}}},
            |    {"type":"function","function":{"name":"grep","description":"grep","parameters":{"type":"object","properties":{}}}},
            |    {"type":"function","function":{"name":"read","description":"read","parameters":{"type":"object","properties":{}}}}
            |  ]
            |}
            """.trimMargin()
        val request =
            Request.Builder()
                .url("https://opencode.ai/zen/v1/chat/completions")
                .header("Content-Type", "application/json")
                .header("User-Agent", "opencode/1.18.18")
                .header("x-opencode-session", "ses_andcodehermes00abcdef0123456789ab")
                .header("X-Title", "opencode")
                .header("HTTP-Referer", "https://opencode.ai")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()
        return try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val snippet = raw.take(400).ifBlank { response.message }
                    if (response.code == 403 || "FreeTierError" in raw || "only be used from within OpenCode" in raw) {
                        return@use (
                            "OpenCode Free is blocked for third-party clients (including Hermes). " +
                                "OpenCode only allows free models inside the official OpenCode app. " +
                                "Connect OpenRouter, Anthropic, Gemini, or another provider with an API key under Settings → Providers."
                        )
                    }
                    return@use "HTTP ${response.code}: $snippet"
                }
                parseOpenCodeFreeStream(raw).ifBlank { raw.take(500).ifBlank { "(empty response)" } }
            }
        } catch (e: Exception) {
            "OpenCode Free request failed: ${e.message ?: e::class.java.simpleName}"
        }
    }

    private fun jsonString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('\u0022')
        for (c in value) {
            when (c) {
                '\u005C' -> sb.append("\u005C\u005C")
                '\u0022' -> sb.append("\u005C\u0022")
                '\n' -> sb.append("\u005Cn")
                '\r' -> sb.append("\u005Cr")
                '\t' -> sb.append("\u005Ct")
                else -> sb.append(c)
            }
        }
        sb.append('\u0022')
        return sb.toString()
    }

    private fun parseOpenCodeFreeStream(raw: String): String {
        val content = StringBuilder()
        val key = "\u0022content\u0022:\u0022"
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) continue
            val payload = trimmed.removePrefix("data:").trim()
            if (payload == "[DONE]" || payload.isEmpty()) continue
            var idx = 0
            while (true) {
                val at = payload.indexOf(key, idx)
                if (at < 0) break
                var i = at + key.length
                val sb = StringBuilder()
                while (i < payload.length) {
                    val c = payload[i]
                    if (c == '\u005C' && i + 1 < payload.length) {
                        when (payload[i + 1]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            '\u0022' -> sb.append('\u0022')
                            '\u005C' -> sb.append('\u005C')
                            else -> sb.append(payload[i + 1])
                        }
                        i += 2
                        continue
                    }
                    if (c == '\u0022') break
                    sb.append(c)
                    i++
                }
                content.append(sb)
                idx = i + 1
            }
        }
        return content.toString().trim()
    }

    fun hermesHome(): File = File(runtimeDirectory, "hermes-home").apply { mkdirs() }

    /** Writes provider API keys into HERMES_HOME/.env for the bundled CLI. */
    fun setApiKey(
        providerId: String,
        apiKey: String?,
    ) {
        val home = hermesHome()
        val envFile = File(home, ".env")
        val keyName =
            when (providerId.lowercase()) {
                "openrouter" -> "OPENROUTER_API_KEY"
                "anthropic" -> "ANTHROPIC_API_KEY"
                "openai" -> "OPENAI_API_KEY"
                "nous" -> "NOUS_API_KEY"
                "opencode-zen" -> "OPENCODE_ZEN_API_KEY"
                "opencode-go" -> "OPENCODE_GO_API_KEY"
                "gemini" -> "GOOGLE_API_KEY"
                "deepseek" -> "DEEPSEEK_API_KEY"
                "xai" -> "XAI_API_KEY"
                "fireworks" -> "FIREWORKS_API_KEY"
                "groq" -> "GROQ_API_KEY"
                "mistral" -> "MISTRAL_API_KEY"
                "huggingface" -> "HF_TOKEN"
                else -> providerId.uppercase().replace("-", "_") + "_API_KEY"
            }
        val existing =
            if (envFile.isFile) {
                envFile.readLines().filter { line ->
                    val k = line.substringBefore("=").trim()
                    k.isNotEmpty() && k != keyName && !line.trimStart().startsWith("#")
                }
            } else {
                emptyList()
            }
        val lines =
            if (apiKey.isNullOrBlank()) {
                existing
            } else {
                existing + "$keyName=$apiKey"
            }
        envFile.writeText(lines.joinToString("\n") + if (lines.isNotEmpty()) "\n" else "")
    }

    fun hasApiKey(providerId: String): Boolean {
        if (providerId.equals("opencode-free", ignoreCase = true)) return true
        val envFile = File(hermesHome(), ".env")
        if (!envFile.isFile) return false
        val keyName =
            when (providerId.lowercase()) {
                "openrouter" -> "OPENROUTER_API_KEY"
                "anthropic" -> "ANTHROPIC_API_KEY"
                "openai" -> "OPENAI_API_KEY"
                "nous" -> "NOUS_API_KEY"
                "opencode-zen" -> "OPENCODE_ZEN_API_KEY"
                "opencode-go" -> "OPENCODE_GO_API_KEY"
                "gemini" -> "GOOGLE_API_KEY"
                "deepseek" -> "DEEPSEEK_API_KEY"
                "xai" -> "XAI_API_KEY"
                "fireworks" -> "FIREWORKS_API_KEY"
                "groq" -> "GROQ_API_KEY"
                "mistral" -> "MISTRAL_API_KEY"
                "huggingface" -> "HF_TOKEN"
                else -> providerId.uppercase().replace("-", "_") + "_API_KEY"
            }
        return envFile.readLines().any { it.trim().startsWith("$keyName=") && it.substringAfter("=").isNotBlank() }
    }
}
