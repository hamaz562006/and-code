package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Host-side helpers for the Grok Build binary in the shared rootfs.
 *
 * Auth is API-key only: [setApiKey] writes `XAI_API_KEY` into `root/.grok/env` so sandbox
 * launches can source it without browser login.
 */
class GrokBuildRuntime(
    val runtimeDirectory: File,
) {
    private val envRelative = "root/.grok/env"
    private val keyFileRelative = "root/.grok/api_key"

    fun version(rootfs: File): String? = GrokBuildInstaller.installedVersion(rootfs)

    fun stopAll() {
        // Process-backed chat not wired yet; nothing to kill.
    }

    fun hasApiKey(rootfs: File): Boolean {
        val keyFile = File(rootfs, keyFileRelative)
        if (keyFile.isFile && keyFile.readText().trim().isNotEmpty()) return true
        val env = File(rootfs, envRelative)
        if (!env.isFile) return false
        return env.readLines().any { line ->
            line.trim().startsWith("XAI_API_KEY=") && line.substringAfter("=").trim().isNotEmpty()
        }
    }

    fun setApiKey(
        rootfs: File,
        apiKey: String?,
    ) {
        val trimmed = apiKey?.trim().orEmpty()
        val keyFile = File(rootfs, keyFileRelative)
        val envFile = File(rootfs, envRelative)
        keyFile.parentFile?.mkdirs()
        if (trimmed.isEmpty()) {
            keyFile.delete()
            if (envFile.isFile) {
                val lines =
                    envFile.readLines().filterNot { it.trim().startsWith("XAI_API_KEY=") }
                if (lines.isEmpty()) envFile.delete() else envFile.writeText(lines.joinToString("\n") + "\n")
            }
            return
        }
        keyFile.writeText(trimmed)
        // env file for shells / future process launcher
        val other =
            if (envFile.isFile) {
                envFile.readLines().filterNot { it.trim().startsWith("XAI_API_KEY=") }
            } else {
                emptyList()
            }
        envFile.writeText((other + "XAI_API_KEY=$trimmed").joinToString("\n") + "\n")
    }

    fun readApiKey(rootfs: File): String? {
        val keyFile = File(rootfs, keyFileRelative)
        if (keyFile.isFile) return keyFile.readText().trim().ifBlank { null }
        val env = File(rootfs, envRelative)
        if (!env.isFile) return null
        return env.readLines()
            .firstOrNull { it.trim().startsWith("XAI_API_KEY=") }
            ?.substringAfter("=")
            ?.trim()
            ?.ifBlank { null }
    }
}
