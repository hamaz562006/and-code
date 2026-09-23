package com.yugahashimoto.andcode.runtime

import com.yugahashimoto.andcode.R

/**
 * A coding agent that can be installed into the shared Android-local Linux sandbox.
 */
enum class LocalAgent(
    val id: String,
    val displayNameRes: Int,
    val targetId: String,
    val iconRes: Int,
) {
    OPEN_CODE("opencode", R.string.agent_opencode_name, "local-android", R.drawable.ic_agent_opencode),
    CLAUDE_CODE("claude-code", R.string.agent_claude_code_name, "claude-code-local", R.drawable.ic_agent_claude),
    ANTIGRAVITY("antigravity", R.string.agent_antigravity_name, "antigravity-local", R.drawable.ic_agent_antigravity),
    CODEX("codex", R.string.agent_codex_name, "codex-local", R.drawable.ic_agent_codex),
    PI("pi", R.string.agent_pi_name, "pi-local", R.drawable.ic_agent_pi),
    ;

    companion object {
        fun fromId(id: String): LocalAgent? = entries.firstOrNull { it.id == id }
    }
}
