package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.McpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * Pi's MCP server configuration manager.
 *
 * Persists configured MCP servers in `~/.pi/mcp.json` inside Pi's home directory.
 */
object PiMcp {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun list(configFile: File): List<McpServer> {
        if (!configFile.exists()) return emptyList()
        val text = runCatching { configFile.readText() }.getOrNull() ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return emptyList()
        val servers = root["mcpServers"] as? JsonObject ?: return emptyList()
        return servers.entries.mapNotNull { (name, elem) ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val command = obj.string("command")
            val url = obj.string("url")
            val args = (obj["args"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val fullCommand =
                if (command != null) {
                    if (args.isNotEmpty()) "$command ${args.joinToString(" ")}" else command
                } else {
                    null
                }
            McpServer(
                name = name,
                status = "enabled",
                type = if (url != null) "remote" else "local",
                command = fullCommand,
                url = url,
            )
        }
    }

    fun add(
        configFile: File,
        name: String,
        url: String?,
        command: String?,
    ): McpServer {
        configFile.parentFile?.mkdirs()
        val existing =
            if (configFile.exists()) {
                runCatching { json.parseToJsonElement(configFile.readText()) }.getOrNull() as? JsonObject
                    ?: buildJsonObject {}
            } else {
                buildJsonObject {}
            }
        val existingServers = (existing["mcpServers"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val serverObj =
            buildJsonObject {
                if (!url.isNullOrBlank()) {
                    put("url", JsonPrimitive(url.trim()))
                } else if (!command.isNullOrBlank()) {
                    val parts = command.trim().split("\\s+".toRegex())
                    put("command", JsonPrimitive(parts.first()))
                    if (parts.size > 1) {
                        put("args", JsonArray(parts.drop(1).map { JsonPrimitive(it) }))
                    }
                }
            }
        existingServers[name] = serverObj
        val updated =
            buildJsonObject {
                existing.forEach { (k, v) -> if (k != "mcpServers") put(k, v) }
                put("mcpServers", JsonObject(existingServers))
            }
        configFile.writeText(json.encodeToString(JsonObject.serializer(), updated))
        return McpServer(
            name = name,
            status = "enabled",
            type = if (url != null) "remote" else "local",
            command = command,
            url = url,
        )
    }

    fun remove(
        configFile: File,
        name: String,
    ): Boolean {
        if (!configFile.exists()) return false
        val text = runCatching { configFile.readText() }.getOrNull() ?: return false
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return false
        val servers = (root["mcpServers"] as? JsonObject)?.toMutableMap() ?: return false
        if (servers.remove(name) != null) {
            val updated =
                buildJsonObject {
                    root.forEach { (k, v) -> if (k != "mcpServers") put(k, v) }
                    put("mcpServers", JsonObject(servers))
                }
            configFile.writeText(json.encodeToString(JsonObject.serializer(), updated))
            return true
        }
        return false
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
