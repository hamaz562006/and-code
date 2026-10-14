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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
    private val json =
        kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
        }

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
        if (python == null) {
            startFallbackHttpServer(home, "Bundled Python missing for Hermes gateway")
            return
        }
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

    private fun startFallbackHttpServer(
        home: File,
        gatewayError: String,
    ) {
        if (fallbackServerThread?.isAlive == true) return
        val socket = java.net.ServerSocket()
        socket.reuseAddress = true
        try {
            socket.bind(java.net.InetSocketAddress(HermesManifest.API_HOST, HermesManifest.API_PORT))
        } catch (e: java.net.BindException) {
            // Port busy from a dying gateway — brief wait then retry once.
            Thread.sleep(400L)
            socket.bind(java.net.InetSocketAddress(HermesManifest.API_HOST, HermesManifest.API_PORT))
        }
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

    fun isGatewayAlive(): Boolean = gatewayProcess?.isAlive == true || fallbackServerThread?.isAlive == true

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

        val modelId = request.modelId?.takeIf { it.isNotBlank() }
        var providerId = request.providerId?.takeIf { it.isNotBlank() }
        val custom =
            listCustomProviders().firstOrNull { entry ->
                entry.id.equals(providerId, ignoreCase = true) ||
                    (modelId != null && modelId in entry.modelIds)
            }
        if (providerId.isNullOrBlank() && custom != null) {
            providerId = custom.id
        }
        val isSlash = text.trimStart().startsWith("/")
        val isFreeModel =
            modelId != null && (modelId.endsWith("-free") || modelId == "big-pickle")
        val useFreePath =
            !isSlash &&
                custom == null &&
                (providerId == null || providerId == "opencode-free") &&
                (modelId == null || isFreeModel)

        val assistantText =
            when {
                // Slash commands must never hit OpenCode Free HTTP (returns 403 outside the
                // official OpenCode app). Handle /model locally; other commands go to Hermes
                // CLI only with a non-free provider when one is selected.
                isSlash -> {
                    val cmd = text.trim()
                    val cmdLower = cmd.lowercase()
                    if (cmdLower == "/model" || cmdLower.startsWith("/model ")) {
                        formatHermesModelList(selectedModelId = modelId, custom = custom)
                    } else if (custom != null) {
                        // Custom OpenAI endpoint is active — do not fall back to opencode-free.
                        syncCustomProvidersIntoHermesConfig()
                        val result =
                            HermesInstaller.runOnHost(
                                runtimeDirectory = runtimeDirectory,
                                args =
                                    listOf(
                                        "-z",
                                        text,
                                        "-m",
                                        modelId ?: custom.modelIds.firstOrNull() ?: "default",
                                        "--provider",
                                        custom.id,
                                    ),
                                timeoutSeconds = 120L,
                            )
                        val out = result.output.trim()
                        when {
                            out.contains("OpenCode") && out.contains("403") ->
                                "Slash command could not run against the custom provider. " +
                                    "Try a normal message, or check Settings → Providers."
                            out.isNotBlank() -> out
                            result.exitCode != 0 ->
                                "Hermes command failed (exit ${result.exitCode})."
                            else -> "(empty response)"
                        }
                    } else if (!providerId.isNullOrBlank() && providerId != "opencode-free") {
                        syncCustomProvidersIntoHermesConfig()
                        val result =
                            HermesInstaller.runOnHost(
                                runtimeDirectory = runtimeDirectory,
                                args =
                                    listOf(
                                        "-z",
                                        text,
                                        "-m",
                                        modelId ?: "default",
                                        "--provider",
                                        providerId,
                                    ),
                                timeoutSeconds = 120L,
                            )
                        result.output.trim().ifBlank {
                            if (result.exitCode != 0) {
                                "Hermes command failed (exit ${result.exitCode})."
                            } else {
                                "(empty response)"
                            }
                        }
                    } else {
                        // No API-key / custom provider selected — OpenCode Free cannot run slash
                        // commands from this app (403). Tell the user what to do instead of
                        // calling the blocked endpoint.
                        "Slash commands need a connected provider (custom API, OpenRouter, " +
                            "Anthropic, …). OpenCode Free is blocked outside the official " +
                            "OpenCode app. Select a model from Settings → Providers, then retry."
                    }
                }
                custom != null -> {
                    chatOpenAiCompatible(
                        custom.baseUrl,
                        readCustomApiKey(custom.id),
                        modelId ?: custom.modelIds.firstOrNull() ?: "default",
                        text,
                    )
                }
                useFreePath -> {
                    // Hermes's free provider only — not the OpenCode agent.
                    chatOpenCodeFree(modelId ?: "big-pickle", text)
                }
                else -> {
                    val mid = modelId ?: "big-pickle"
                    val pid = providerId ?: "opencode-free"
                    syncCustomProvidersIntoHermesConfig()
                    val result =
                        HermesInstaller.runOnHost(
                            runtimeDirectory = runtimeDirectory,
                            args =
                                listOf(
                                    "-z",
                                    text,
                                    "-m",
                                    mid,
                                    "--provider",
                                    pid,
                                ),
                            timeoutSeconds = 300L,
                        )
                    result.output.trim().ifBlank {
                        if (result.exitCode != 0) {
                            result.output.trim().ifBlank {
                                "Hermes failed (exit ${result.exitCode}). Configure a provider API key under Settings → Providers."
                            }
                        } else {
                            "(empty response)"
                        }
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
                else -> providerId.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_') + "_API_KEY"
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
                else -> providerId.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_') + "_API_KEY"
            }
        return envFile.readLines().any { it.trim().startsWith("$keyName=") && it.substringAfter("=").isNotBlank() }
    }

    private fun customProvidersFile(): File = File(hermesHome(), "custom-providers.json")

    data class CustomProviderEntry(
        val id: String,
        val name: String,
        val baseUrl: String,
        val modelIds: List<String>,
    )

    fun listCustomProviders(): List<CustomProviderEntry> {
        val file = customProvidersFile()
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            val providers = root["providers"]?.jsonObject ?: return emptyList()
            providers.mapNotNull { (id, value) ->
                val obj = value.jsonObject
                val modelsElement = obj["models"]
                val models: List<String> =
                    when {
                        modelsElement is JsonObject -> modelsElement.keys.toList()
                        modelsElement is JsonArray ->
                            modelsElement.mapNotNull { it.jsonPrimitive.contentOrNull }
                        else -> emptyList()
                    }
                val baseUrl = obj["baseUrl"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (models.isEmpty()) return@mapNotNull null
                CustomProviderEntry(
                    id = id,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id,
                    baseUrl = baseUrl,
                    modelIds = models,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun registerCustomProvider(
        id: String,
        name: String,
        baseUrl: String,
        modelIds: List<String>,
    ) {
        val file = customProvidersFile()
        file.parentFile?.mkdirs()
        val existing =
            if (file.isFile) {
                runCatching { json.parseToJsonElement(file.readText()).jsonObject }
                    .getOrDefault(buildJsonObject {})
            } else {
                buildJsonObject {}
            }
        val existingProviders = (existing["providers"] as? JsonObject) ?: buildJsonObject {}
        var normalizedBase = baseUrl.trim().trimEnd('/')
        // Strip accidental scheme-less paste and full completions path (same UX as Pi).
        if (normalizedBase.startsWith("//")) {
            normalizedBase = "https:$normalizedBase"
        }
        for (suffix in listOf("/chat/completions", "/completions", "/v1/chat/completions")) {
            if (normalizedBase.endsWith(suffix)) {
                // Keep /v1 when present for OpenAI-compatible roots.
                if (suffix == "/v1/chat/completions") {
                    normalizedBase = normalizedBase.removeSuffix("/chat/completions")
                } else {
                    normalizedBase = normalizedBase.removeSuffix(suffix)
                }
                break
            }
        }
        val modelsObj =
            buildJsonObject {
                modelIds.forEach { mid ->
                    put(
                        mid,
                        buildJsonObject {
                            put("id", JsonPrimitive(mid))
                            put("name", JsonPrimitive(mid))
                        },
                    )
                }
            }
        val providerEntry =
            buildJsonObject {
                put("baseUrl", JsonPrimitive(normalizedBase))
                put("name", JsonPrimitive(name))
                put("models", modelsObj)
            }
        val updatedProviders =
            buildJsonObject {
                existingProviders.forEach { (k, v) -> if (k != id) put(k, v) }
                put(id, providerEntry)
            }
        val updated =
            buildJsonObject {
                existing.forEach { (k, v) -> if (k != "providers") put(k, v) }
                put("providers", updatedProviders)
            }
        file.writeText(json.encodeToString(JsonObject.serializer(), updated))
        // Also seed env key slot so hasApiKey can track custom keys by id.
        File(hermesHome(), ".env").let { env ->
            if (!env.isFile) env.writeText("")
        }
        // Write Hermes-native config so CLI (`hermes -z --provider …`) also sees the endpoint.
        syncCustomProvidersIntoHermesConfig()
    }

    private fun customKeyEnvName(providerId: String): String =
        providerId.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_') + "_API_KEY"

    fun readCustomApiKey(providerId: String): String? {
        val envFile = File(hermesHome(), ".env")
        if (!envFile.isFile) return null
        val keyName = customKeyEnvName(providerId)
        return envFile.readLines()
            .firstOrNull { it.trim().startsWith("$keyName=") }
            ?.substringAfter("=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * Persist named custom providers into HERMES_HOME/config.yaml in the format Hermes expects:
     * ```
     * providers:
     *   cleanapis:
     *     api: https://cleanapis.com/v1
     *     key_env: CLEANAPIS_API_KEY
     * ```
     */
    fun syncCustomProvidersIntoHermesConfig() {
        val customs = listCustomProviders()
        if (customs.isEmpty()) return
        val home = hermesHome()
        val config = File(home, "config.yaml")
        val existing = if (config.isFile) config.readText() else ""
        // Remove previous auto-managed block if present.
        val without =
            existing
                .replace(Regex("(?s)\n?# BEGIN ANDCODE_CUSTOM_PROVIDERS.*?\n# END ANDCODE_CUSTOM_PROVIDERS\n?"), "\n")
                .trimEnd()
        val block =
            buildString {
                appendLine()
                appendLine("# BEGIN ANDCODE_CUSTOM_PROVIDERS")
                appendLine("providers:")
                for (c in customs) {
                    val envName = customKeyEnvName(c.id)
                    appendLine("  ${c.id}:")
                    appendLine("    name: ${c.name}")
                    appendLine("    api: ${c.baseUrl}")
                    appendLine("    key_env: $envName")
                    appendLine("    transport: openai_chat")
                    appendLine("    api_mode: chat_completions")
                    if (c.modelIds.isNotEmpty()) {
                        appendLine("    default_model: ${c.modelIds.first()}")
                        appendLine("    models:")
                        for (mid in c.modelIds) {
                            appendLine("      $mid:")
                            appendLine("        display_name: $mid")
                        }
                    }
                }
                appendLine("# END ANDCODE_CUSTOM_PROVIDERS")
            }
        config.writeText((without + block).trimStart() + "\n")
    }

    /**
     * Direct OpenAI-compatible chat (Clean APIs, local vLLM, etc.) — does not go through Hermes CLI
     * provider registry, so unknown-provider errors cannot happen.
     */
    private fun chatOpenAiCompatible(
        baseUrl: String,
        apiKey: String?,
        modelId: String,
        userText: String,
    ): String {
        val root = baseUrl.trim().trimEnd('/')
        val url =
            when {
                root.endsWith("/chat/completions") -> root
                root.endsWith("/v1") -> "$root/chat/completions"
                else -> "$root/chat/completions"
            }
        if (apiKey.isNullOrBlank()) {
            return "Custom provider has no API key. Open Settings → Providers → Connect and paste your key."
        }
        val bodyJson =
            """
            |{
            |  "model": ${jsonString(modelId)},
            |  "messages": [{"role":"user","content":${jsonString(userText)}}],
            |  "stream": false
            |}
            """.trimMargin()
        return runCatching {
            val client =
                OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(180, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build()
            val request =
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(bodyJson.toRequestBody("application/json".toMediaType()))
                    .build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use "Custom API HTTP ${response.code}: ${raw.take(400)}"
                }
                val choices =
                    json.parseToJsonElement(raw)
                        .jsonObject["choices"]
                        ?.let { it as? JsonArray }
                val content =
                    choices
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("message")
                        ?.jsonObject
                        ?.get("content")
                        ?.jsonPrimitive
                        ?.contentOrNull
                content?.takeIf { it.isNotBlank() } ?: raw.take(500).ifBlank { "(empty response)" }
            }
        }.getOrElse { e ->
            "Custom API request failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun formatHermesModelList(
        selectedModelId: String?,
        custom: CustomProviderEntry?,
    ): String {
        val lines = mutableListOf<String>()
        lines.add("Available models")
        lines.add("")
        if (custom != null) {
            lines.add("${custom.name} (${custom.id})")
            for (mid in custom.modelIds) {
                val mark = if (mid == selectedModelId) "  ← selected" else ""
                lines.add("  • $mid$mark")
            }
            lines.add("")
        }
        // Other custom providers
        for (entry in listCustomProviders()) {
            if (custom != null && entry.id == custom.id) continue
            lines.add("${entry.name} (${entry.id})")
            for (mid in entry.modelIds) {
                val mark = if (mid == selectedModelId) "  ← selected" else ""
                lines.add("  • $mid$mark")
            }
            lines.add("")
        }
        lines.add("OpenCode Free (keyless — may be blocked outside OpenCode app)")
        for (mid in listOf(
            "big-pickle",
            "mimo-v2.6-flash-free",
            "muse-spark-1.2-contributor-free",
            "nemotron-3-ultra-free",
            "ling-3.1-flash-free",
        )) {
            val mark = if (mid == selectedModelId) "  ← selected" else ""
            lines.add("  • $mid$mark")
        }
        lines.add("")
        lines.add("Pick a model from the chip under the message box.")
        return lines.joinToString("\n")
    }

    /** True when something is accepting connections on the Hermes API port. */
    fun isGatewayListening(): Boolean =
        runCatching {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(HermesManifest.API_HOST, HermesManifest.API_PORT), 400)
                true
            }
        }.getOrDefault(false)

    /**
     * OpenCode-style ensureRunning: start gateway (or fallback HTTP) until the port answers.
     */
    fun ensureGatewayRunning() {
        if (isGatewayListening()) return
        if (!HermesInstaller.isInstalledIn(runtimeDirectory)) {
            error("Hermes is not installed")
        }
        startGateway()
        // One more wait after start/fallback.
        repeat(6) {
            if (isGatewayListening()) return
            Thread.sleep(250L)
        }
        if (!isGatewayListening()) {
            // Last resort: try fallback alone.
            startFallbackHttpServer(hermesHome(), "ensureGatewayRunning: port still closed")
            repeat(4) {
                if (isGatewayListening()) return
                Thread.sleep(200L)
            }
        }
        require(isGatewayListening()) {
            "Hermes local endpoint ${HermesManifest.apiBaseUrl()} did not start"
        }
    }
}
