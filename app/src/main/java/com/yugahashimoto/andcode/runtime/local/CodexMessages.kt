package com.yugahashimoto.andcode.runtime.local

import android.content.Context
import com.yugahashimoto.andcode.R

/**
 * User-visible text produced by the Codex runtime.
 *
 * Mirrors [ClaudeMessages]: the runtime layer stays free of a [Context] while its failure messages
 * are still translated like the rest of the UI.
 */
interface CodexMessages {
    val runtimeMissing: String
    val notInstalled: String
    val installFailed: String
    val loginFailed: String

    fun processExited(
        exitCode: Int?,
        detail: String?,
    ): String

    /** English fallbacks for unit tests and any construction path without a [Context]. */
    companion object Default : CodexMessages {
        override val runtimeMissing = "The Linux environment is not installed yet"
        override val notInstalled = "Codex is not installed"
        override val installFailed = "Codex installation failed"
        override val loginFailed = "Codex sign-in failed"

        override fun processExited(
            exitCode: Int?,
            detail: String?,
        ): String {
            val cause = detail ?: exitCode?.let { "exit code $it" } ?: "process exited"
            return "Codex stopped before finishing the turn ($cause)"
        }
    }
}

class AndroidCodexMessages(private val context: Context) : CodexMessages {
    override val runtimeMissing get() = context.getString(R.string.claude_error_runtime_missing)
    override val notInstalled get() = context.getString(R.string.codex_error_not_installed)
    override val installFailed get() = context.getString(R.string.codex_error_install_failed)
    override val loginFailed get() = context.getString(R.string.codex_error_login_failed)

    override fun processExited(
        exitCode: Int?,
        detail: String?,
    ): String {
        val cause = detail ?: exitCode?.let { "exit code $it" } ?: "process exited"
        return context.getString(R.string.codex_error_process_exited, cause)
    }
}
