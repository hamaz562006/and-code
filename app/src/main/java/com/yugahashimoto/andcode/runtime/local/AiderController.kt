package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker
import com.yugahashimoto.andcode.runtime.LocalAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AiderInstallStatus {
    data object Idle : AiderInstallStatus

    data class Installing(
        val progress: Float? = null,
        val step: String? = null,
    ) : AiderInstallStatus

    data object Ready : AiderInstallStatus

    data class Failed(
        val message: String?,
    ) : AiderInstallStatus
}

data class AiderUiState(
    val installed: Boolean = false,
    val version: String? = null,
    val install: AiderInstallStatus = AiderInstallStatus.Idle,
) {
    fun isReady(): Boolean =
        installed &&
            install !is AiderInstallStatus.Installing &&
            install !is AiderInstallStatus.Failed
}

class AiderController(
    private val runtime: AiderRuntime,
    private val target: AiderTarget,
    private val installer: LocalRuntimeInstaller,
    private val runtimeWork: RuntimeWorkTracker,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val mutableState = MutableStateFlow(AiderUiState())
    val state: StateFlow<AiderUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch { runCatching { rehydrate() } }
    }

    private suspend fun rehydrate() {
        val installed = runtime.isInstalled()
        mutableState.update {
            it.copy(
                installed = installed,
                version = runtime.installedVersion(),
                install = if (installed) AiderInstallStatus.Ready else AiderInstallStatus.Idle,
            )
        }
        if (installed) {
            runCatching { target.connect() }
        }
    }

    fun install(agents: Set<LocalAgent> = setOf(LocalAgent.AIDER)) {
        if (mutableState.value.install is AiderInstallStatus.Installing) return
        mutableState.update { it.copy(install = AiderInstallStatus.Installing()) }
        scope.launch {
            runtimeWork.withLease("install-aider") {
                try {
                    val existing = installer.installedMetadata()
                    val othersMissing = (agents - LocalAgent.AIDER).any { existing?.has(it) != true }
                    if (installer.installedRuntime() == null || othersMissing) {
                        installer.install(agents + LocalAgent.AIDER) { progress, step, _ ->
                            mutableState.update {
                                it.copy(install = AiderInstallStatus.Installing(progress, step))
                            }
                        }
                    } else {
                        installer.installAiderOnly { progress, step ->
                            mutableState.update {
                                it.copy(install = AiderInstallStatus.Installing(progress, step))
                            }
                        }
                        installer.recordAgent(LocalAgent.AIDER)
                    }
                    mutableState.update { it.copy(install = AiderInstallStatus.Ready) }
                    runCatching { rehydrate() }
                } catch (e: Exception) {
                    mutableState.update { it.copy(install = AiderInstallStatus.Failed(e.message)) }
                }
            }
        }
    }
}
