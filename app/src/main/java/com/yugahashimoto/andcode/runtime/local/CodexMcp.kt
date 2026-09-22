package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.McpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Codex's MCP server list, managed through its own `codex mcp` subcommand.
 *
 * Servers live in `~/.codex/config.toml` (`[mcp_servers.<name>]`), which also holds the rest of the
 * user's Codex configuration, so this never edits that file directly: `codex mcp add/remove` rewrite
 * it safely and `codex mcp list --json` reads it back. Like Claude Code and Antigravity, a configured
 * server is simply used - there is no separate connect/disconnect - so removal deletes the entry.
 */
object CodexMcp {
    /** `codex mcp list --json`: a JSON array, `[]` when nothing is configured (verified, 0.142.5). */
    const val LIST_SCRIPT = "${CodexSandboxLauncher.CODEX_BINARY} mcp list --json 2>/dev/null"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses `codex mcp list --json`. Each entry carries `name`, `enabled` and a `transport` of type
     * `stdio` (`command`, `args`) or `streamable_http` (`url`). Output that is not a JSON array - an
     * older binary, or noise on stdout - reads as no servers rather than failing the screen.
     */
    fun parseList(output: String): List<McpServer> {
        val start = output.indexOf('[')
        if (start < 0) return emptyList()
        val root = runCatching { json.parseToJsonElement(output.substring(start)) }.getOrNull() as? JsonArray ?: return emptyList()
        return root.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val name = entry.text("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val transport = entry["transport"] as? JsonObject
            val url = transport?.text("url")?.takeIf(String::isNotBlank)
            val command =
                transport?.text("command")?.takeIf(String::isNotBlank)?.let { executable ->
                    val args = (transport["args"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    (listOf(executable) + args).joinToString(" ")
                }
            val enabled = (entry["enabled"] as? JsonPrimitive)?.booleanOrNull != false
            McpServer(
                name = name,
                status = if (enabled) "enabled" else "disabled",
                type = if (url != null) "remote" else "local",
                command = command,
                url = url,
                error = entry.text("disabled_reason")?.takeIf(String::isNotBlank),
            )
        }
    }

    /**
     * The `codex mcp add` invocation for a URL (streamable HTTP) or a local command, or null when
     * neither is given. Everything after `--` is the server's own command line, so - exactly as
     * [ClaudeMcpParser.addScript] does - it is passed through as typed rather than quoted as one
     * argument.
     */
    fun addScript(
        name: String,
        url: String?,
        command: String?,
    ): String? {
        val safeName = shellQuote(name)
        return when {
            !url.isNullOrBlank() -> "${CodexSandboxLauncher.CODEX_BINARY} mcp add $safeName --url ${shellQuote(url.trim())} 2>&1"
            !command.isNullOrBlank() -> "${CodexSandboxLauncher.CODEX_BINARY} mcp add $safeName -- ${command.trim()} 2>&1"
            else -> null
        }
    }

    fun removeScript(name: String): String = "${CodexSandboxLauncher.CODEX_BINARY} mcp remove ${shellQuote(name)} 2>&1"

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
