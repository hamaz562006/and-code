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
        stopGateway()
    }

    @Volatile private var gatewayProcess: Process? = null

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
    }

    /**
     * Hermes ships a keyless [opencode-free] provider (OpenCode Zen free tier).
     * Pin it as the default model so chat works without an API key.
     */
    fun ensureOpenCodeFreeDefault() {
        val home = hermesHome()
        val config = File(home, "config.yaml")
        // mimo-v2.5-free is the only free model the packaged Hermes probe found reliable;
        // big-pickle 403/429s unless the request carries the OpenCode CLI User-Agent.
        val defaultYaml =
            "model:\n  provider: opencode-free\n  default: mimo-v2.5-free\nprovider: opencode-free\n"
        if (!config.isFile) {
            config.writeText(defaultYaml)
        } else {
            val text = config.readText()
            if ("opencode-free" !in text) {
                config.writeText(text.trimEnd() + "\n" + defaultYaml)
            } else if ("big-pickle" in text && "mimo-v2.5-free" !in text) {
                config.writeText(text.replace("big-pickle", "mimo-v2.5-free"))
            }
        }
        HermesInstaller.patchOpenCodeFreeClientHeaders(
            HermesInstaller.agentRoot(runtimeDirectory),
        )
    }

    /** Starts `hermes gateway run` so the OpenAI-compatible API listens on :8642. */
    fun startGateway() {
        if (gatewayProcess?.isAlive == true) return
        require(HermesInstaller.isInstalledIn(runtimeDirectory)) { "Hermes is not installed" }
        ensureApiServerEnv()
        ensureOpenCodeFreeDefault()
        val home = hermesHome()
        val usr = File(HermesInstaller.installRoot(runtimeDirectory), "usr")
        val agent = HermesInstaller.agentRoot(runtimeDirectory)
        runCatching {
            HermesInstaller.ensureVenvPythonPublic(usr)
        }
        val python =
            HermesInstaller.resolveBundledPython(agent)
                ?: error("Bundled Python missing for Hermes gateway")
        python.setExecutable(true, false)
        val repo = File(agent, "app")
        val site = File(agent, "venv/lib/python3.14/site-packages")
        val bootstrap =
            "import os, site, sys; sys.argv[0]='hermes'; " +
                "site.addsitedir(os.environ['HERMES_SITE']); " +
                "from hermes_cli.main import main; sys.exit(main())"
        val linker = HermesInstaller.resolveLinker64()
        val command =
            buildList {
                if (linker != null) add(linker)
                add(python.absolutePath)
                add("-P")
                add("-c")
                add(bootstrap)
                add("gateway")
                add("run")
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
        // Give the process a moment; if it dies immediately, clear so the next connect retries.
        Thread.sleep(800L)
        if (gatewayProcess?.isAlive != true) {
            val dead = gatewayProcess
            gatewayProcess = null
            val err =
                runCatching { dead?.inputStream?.bufferedReader()?.readText().orEmpty() }
                    .getOrDefault("")
            // Best-effort: leave a note under HERMES_HOME for debugging.
            runCatching {
                File(home, "gateway-last-error.txt").writeText(err.ifBlank { "(no output, exit early)" })
            }
        }
    }

    fun stopGateway() {
        gatewayProcess?.destroyForcibly()
        gatewayProcess = null
    }

    fun isGatewayAlive(): Boolean = gatewayProcess?.isAlive == true

    fun apiBaseUrl(): String = HermesManifest.apiBaseUrl()

    fun listSessions(): List<OpenCodeSession> = sessions.values.sortedByDescending { it.time.updated ?: it.time.created }

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

        // CLI: hermes -z PROMPT [-m MODEL] [--provider PROVIDER]
        // Do not put flags between -z and PROMPT — that triggers the usage dump.
        val modelId = request.modelId?.takeIf { it.isNotBlank() } ?: "mimo-v2.5-free"
        val providerId = request.providerId?.takeIf { it.isNotBlank() } ?: "opencode-free"
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
        var assistantText =
            result.output.trim().ifBlank {
                if (result.exitCode != 0) {
                    "Hermes failed (exit ${result.exitCode}). Configure a provider API key under Settings → Providers."
                } else {
                    "(empty response)"
                }
            }
        if ("free tier can only be used from within OpenCode" in assistantText) {
            assistantText =
                assistantText +
                "\n\nOpenCode Free is blocked outside the OpenCode client. " +
                "Connect OpenRouter, Anthropic, or another provider with an API key for Hermes."
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
