package com.yugahashimoto.andcode.ui

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.RuntimeTarget

@Composable
fun runtimeTargetLabel(target: RuntimeTarget): String {
    val agent = target.agent ?: return target.displayName
    return stringResource(R.string.local_agent_on_device, stringResource(agent.displayNameRes))
}

@DrawableRes
fun runtimeAgentIcon(agent: LocalAgent?): Int =
    when (agent) {
        LocalAgent.CLAUDE_CODE -> R.drawable.ic_agent_claude
        LocalAgent.OPEN_CODE -> R.drawable.ic_agent_opencode
        LocalAgent.ANTIGRAVITY -> R.drawable.ic_agent_antigravity
        LocalAgent.CODEX -> R.drawable.ic_agent_codex
        LocalAgent.PI -> R.drawable.ic_agent_pi
        null -> R.drawable.ic_runtime_remote
    }
