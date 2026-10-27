package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.runtime.RuntimeState
import com.yugahashimoto.andcode.core.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.LocalAgent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ClineTarget(
    private val runtime: ClineRuntime,
) : RuntimeTarget {
    override val id: String = LocalAgent.CLINE.targetId
    override val displayName: String = "Cline"
    override val agent: LocalAgent = LocalAgent.CLINE

    private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Disconnected)
    override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    override suspend fun connect() {
        if (!runtime.isInstalled()) {
            mutableState.value = RuntimeState.Disconnected
            return
        }
        mutableState.value = RuntimeState.Connected
    }

    override suspend fun disconnect() {
        mutableState.value = RuntimeState.Disconnected
    }
}
