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

sealed interface CecliInstallStatus {
    data object Idle : CecliInstallStatus

    data class Installing(
        val progress: Float? = null,
        val step: String? = null,
    ) : CecliInstallStatus

    data object Ready : CecliInstallStatus

    data class Failed(
        val message: String?,
    ) : CecliInstallStatus
}

data class CecliUiState(
    val installed: Boolean = false,
    val version: String? = null,
    val install: CecliInstallStatus = CecliInstallStatus.Idle,
) {
    fun isReady(): Boolean =
        installed &&
            install !is CecliInstallStatus.Installing &&
            install !is CecliInstallStatus.Failed
}

/**
 * Owns Cecli install UI state. Mirrors [PiController] / [HermesController] so setup step 3 does not
 * flip back to "Not installed" while an install is in flight (rehydrate must preserve Installing
 * and Failed).
 */
class CecliController(
    private val runtime: CecliRuntime,
    private val target: CecliTarget,
    private val installer: LocalRuntimeInstaller,
    private val runtimeWork: RuntimeWorkTracker,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val mutableState = MutableStateFlow(CecliUiState())
    val state: StateFlow<CecliUiState> = mutableState.asStateFlow()

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
                    installed -> CecliInstallStatus.Ready
                    current.install is CecliInstallStatus.Installing -> current.install
                    current.install is CecliInstallStatus.Failed -> current.install
                    else -> CecliInstallStatus.Idle
                }
            current.copy(
                installed = installed,
                version = version,
                install = install,
            )
        }
        if (installed) {
            runCatching { target.connect() }
        }
    }

    fun install(agents: Set<LocalAgent> = setOf(LocalAgent.CECLI)) {
        if (mutableState.value.install is CecliInstallStatus.Installing) return
        mutableState.update { it.copy(install = CecliInstallStatus.Installing(progress = 0f, step = "Starting")) }
        scope.launch {
            runtimeWork.withLease("install-cecli") {
                try {
                    val existing = installer.installedMetadata()
                    val othersMissing = (agents - LocalAgent.CECLI).any { existing?.has(it) != true }
                    if (installer.installedRuntime() == null || othersMissing) {
                        installer.install(agents + LocalAgent.CECLI) { progress, step, _ ->
                            mutableState.update {
                                it.copy(install = CecliInstallStatus.Installing(progress, step))
                            }
                        }
                    } else {
                        installer.installCecliOnly { progress, step ->
                            mutableState.update {
                                it.copy(install = CecliInstallStatus.Installing(progress, step))
                            }
                        }
                        installer.recordAgent(LocalAgent.CECLI)
                    }
                    if (!runtime.isInstalled()) {
                        error("Cecli install finished but the agent is still not installed")
                    }
                    mutableState.update {
                        it.copy(
                            installed = true,
                            version = runtime.installedVersion(),
                            install = CecliInstallStatus.Ready,
                        )
                    }
                    runCatching { target.connect() }
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Throwable) {
                    val detail = error.cause?.message?.takeIf { it.isNotBlank() }
                    val message = listOfNotNull(error.message, detail).joinToString(": ").ifBlank { null }
                    mutableState.update { it.copy(install = CecliInstallStatus.Failed(message)) }
                }
            }
        }
    }
}
