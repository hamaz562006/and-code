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
    val signInChatgptLabel: String
    val signInApiKeyLabel: String
    val signInBrowserInstructions: String

    /** Why a turn ended when the app itself stopped Codex (a sign-out, a new sign-in). */
    val stopped: String

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
        override val signInChatgptLabel = "ChatGPT account"
        override val signInApiKeyLabel = "API key"
        override val signInBrowserInstructions = "Sign in with your ChatGPT account in the browser. This closes when you are done."
        override val stopped = "Codex was stopped before finishing the turn"

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
    override val signInChatgptLabel get() = context.getString(R.string.codex_sign_in_method_chatgpt)
    override val signInApiKeyLabel get() = context.getString(R.string.codex_sign_in_method_api_key)
    override val signInBrowserInstructions get() = context.getString(R.string.codex_sign_in_browser_instructions)
    override val stopped get() = context.getString(R.string.codex_error_stopped)

    override fun processExited(
        exitCode: Int?,
        detail: String?,
    ): String {
        val cause = detail ?: exitCode?.let { "exit code $it" } ?: "process exited"
        return context.getString(R.string.codex_error_process_exited, cause)
    }
}
