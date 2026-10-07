package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker
import com.yugahashimoto.andcode.runtime.DevelopmentToolGroup
import com.yugahashimoto.andcode.runtime.LocalAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface HermesInstallStatus {
    data object Idle : HermesInstallStatus

    data class Installing(val progress: Float? = null, val step: String? = null) : HermesInstallStatus

    data object Ready : HermesInstallStatus

    data class Failed(val message: String?) : HermesInstallStatus
}

data class HermesUiState(
    val installed: Boolean = false,
    val version: String? = null,
    val install: HermesInstallStatus = HermesInstallStatus.Idle,
) {
    fun isReady(): Boolean =
        installed &&
            install !is HermesInstallStatus.Installing &&
            install !is HermesInstallStatus.Failed
}

/**
 * Owns Hermes install state. Package is the Termux aarch64 `.deb` run on the host.
 */
class HermesController(
    private val runtime: HermesRuntime,
    private val target: HermesTarget,
    private val installer: LocalRuntimeInstaller,
    private val runtimeWork: RuntimeWorkTracker,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val mutableState = MutableStateFlow(HermesUiState())
    val state: StateFlow<HermesUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch { runCatching { rehydrate() } }
    }

    private fun rehydrate() {
        val dir = runtime.runtimeDirectory
        val installed = HermesInstaller.isInstalledIn(dir)
        val version = HermesInstaller.installedVersion(dir)
        mutableState.update {
            it.copy(
                installed = installed,
                version = version,
                install = if (installed) HermesInstallStatus.Ready else HermesInstallStatus.Idle,
            )
        }
        if (installed) {
            scope.launch { runCatching { target.connect() } }
        }
    }

    fun install(
        agents: Set<LocalAgent> = setOf(LocalAgent.HERMES),
        developmentToolGroups: Set<DevelopmentToolGroup> = emptySet(),
    ) {
        scope.launch {
            runtimeWork.withLease("hermes-install") {
                mutableState.update {
                    it.copy(install = HermesInstallStatus.Installing(0f, "Downloading Hermes…"))
                }
                runCatching {
                    // Shared Alpine rootfs still needed for the sandbox tools UI; Hermes itself
                    // installs on the host via the Termux deb.
                    if (installer.installedRuntime() == null) {
                        installer.install(agents + LocalAgent.HERMES, developmentToolGroups) { p, step, _ ->
                            mutableState.update {
                                it.copy(
                                    install =
                                        HermesInstallStatus.Installing(
                                            progress = p,
                                            step = step,
                                        ),
                                )
                            }
                        }
                    }
                    HermesInstaller.install(runtime.runtimeDirectory) { fraction ->
                        mutableState.update {
                            it.copy(
                                install =
                                    HermesInstallStatus.Installing(
                                        progress = fraction,
                                        step = "Installing Hermes package…",
                                    ),
                            )
                        }
                    }
                    installer.recordAgent(LocalAgent.HERMES)
                    rehydrate()
                    target.connect()
                }.onFailure { err ->
                    mutableState.update {
                        it.copy(install = HermesInstallStatus.Failed(err.message))
                    }
                }
            }
        }
    }
}
