package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker
import com.yugahashimoto.andcode.runtime.LocalAgent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ClineInstallStatus {
    data object Idle : ClineInstallStatus

    data class Installing(
        val progress: Float? = null,
        val step: String? = null,
    ) : ClineInstallStatus

    data object Ready : ClineInstallStatus

    data class Failed(
        val message: String?,
    ) : ClineInstallStatus
}

data class ClineUiState(
    val installed: Boolean = false,
    val version: String? = null,
    val install: ClineInstallStatus = ClineInstallStatus.Idle,
) {
    fun isReady(): Boolean =
        installed &&
            install !is ClineInstallStatus.Installing &&
            install !is ClineInstallStatus.Failed
}

class ClineController(
    private val runtime: ClineRuntime,
    private val target: ClineTarget,
    private val installer: LocalRuntimeInstaller,
    private val runtimeWork: RuntimeWorkTracker,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val mutableState = MutableStateFlow(ClineUiState())
    val state: StateFlow<ClineUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch { runCatching { rehydrate() } }
    }

    private suspend fun rehydrate() {
        val installed = runtime.isInstalled()
        val version = runtime.installedVersion()
        mutableState.update { current ->
            val install =
                when {
                    installed -> ClineInstallStatus.Ready
                    current.install is ClineInstallStatus.Installing -> current.install
                    current.install is ClineInstallStatus.Failed -> current.install
                    else -> ClineInstallStatus.Idle
                }
            current.copy(installed = installed, version = version, install = install)
        }
        if (installed) {
            runCatching { target.connect() }
        }
    }

    fun install(agents: Set<LocalAgent> = setOf(LocalAgent.CLINE)) {
        if (mutableState.value.install is ClineInstallStatus.Installing) return
        mutableState.update { it.copy(install = ClineInstallStatus.Installing(progress = 0f, step = "Starting")) }
        scope.launch {
            runtimeWork.withLease("install-cline") {
                try {
                    if (installer.installedRuntime() == null) {
                        installer.install(agents + LocalAgent.CLINE) { progress, step, _ ->
                            mutableState.update {
                                it.copy(install = ClineInstallStatus.Installing(progress, step))
                            }
                        }
                    } else {
                        installer.installClineOnly { progress, step ->
                            mutableState.update {
                                it.copy(install = ClineInstallStatus.Installing(progress, step))
                            }
                        }
                    }
                    if (!runtime.isInstalled()) {
                        error("Cline install finished but the agent is still not installed")
                    }
                    mutableState.update {
                        it.copy(
                            installed = true,
                            version = runtime.installedVersion(),
                            install = ClineInstallStatus.Ready,
                        )
                    }
                    runCatching { target.connect() }
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Throwable) {
                    val detail = error.cause?.message?.takeIf { it.isNotBlank() }
                    val message = listOfNotNull(error.message, detail).joinToString(": ").ifBlank { null }
                    mutableState.update { it.copy(install = ClineInstallStatus.Failed(message)) }
                }
            }
        }
    }
}
