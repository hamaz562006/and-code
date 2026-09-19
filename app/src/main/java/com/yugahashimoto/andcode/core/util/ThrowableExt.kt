package com.yugahashimoto.andcode.core.util

fun Throwable.safeMessage(fallback: String = "Unknown error"): String = message?.takeIf { it.isNotBlank() } ?: fallback

/**
 * True when this looks like "the workspace isn't a git repository" rather than a real failure.
 *
 * A git-backed target (Claude Code, Antigravity's stub) surfaces this as a plain error message
 * rather than a typed error, so this string match is the shared way both the workspace Explorer and
 * the chat's diff view recognize it and fall back to a file-list-only view instead of showing it as
 * a hard error.
 */
fun Throwable.isNonGitWorkspaceError(): Boolean {
    val text = message.orEmpty().lowercase()
    return "git" in text && ("not" in text || "repository" in text)
}
