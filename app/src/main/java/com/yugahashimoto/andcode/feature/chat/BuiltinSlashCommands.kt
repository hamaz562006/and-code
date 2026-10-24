package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.runtime.free.FreeModelsCatalog
import com.yugahashimoto.andcode.runtime.free.ModelFallbackStore

/**
 * App-level slash commands available even when no agent backend is connected (feature 5).
 */
object BuiltinSlashCommands {
    val NAMES = setOf("help", "clear", "models", "fallback", "queue")

    fun isBuiltin(name: String): Boolean = name.lowercase() in NAMES

    fun handle(
        name: String,
        args: String,
        clearMessages: () -> Unit,
        queueTexts: () -> List<String>,
    ): String {
        return when (name.lowercase()) {
            "help" ->
                """
                Builtin commands:
                /help — this list
                /clear — clear local chat bubbles (session on server unchanged)
                /models — list free / local models
                /fallback — show model fallback chain status
                /queue — show pending queued prompts
                Agent/skill commands (e.g. /plan) appear when that agent is installed.
                """.trimIndent()
            "clear" -> {
                clearMessages()
                "Chat cleared."
            }
            "models" -> {
                val free = FreeModelsCatalog.modelIds().joinToString("\n") { "• $it" }
                "Free models:\n$free\n\nLocal: configure Ollama at http://127.0.0.1:11434 (provider local-ollama)."
            }
            "fallback" -> {
                val policy = ModelFallbackStore.get()?.load() ?: ModelFallbackStore.defaultPolicy()
                val targets =
                    policy.targets.joinToString("\n") { "• ${it.providerId}/${it.modelId}" }
                        .ifBlank { "(auto free models)" }
                "Fallback enabled=${policy.enabled}, retries=${policy.retries}\n$targets"
            }
            "queue" -> {
                val q = queueTexts()
                if (q.isEmpty()) "Queue is empty."
                else q.mapIndexed { i, t -> "${i + 1}. ${t.take(80)}" }.joinToString("\n")
            }
            else -> "Unknown builtin /$name"
        }
    }
}
