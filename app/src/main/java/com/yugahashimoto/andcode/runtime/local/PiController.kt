package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.LocalAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PiInstallStatus {
    data object Idle : PiInstallStatus

    data class Installing(val progress: Float?, val step: Int) : PiInstallStatus

    data class Ready(val version: String) : PiInstallStatus

    data class Failed(val message: String) : PiInstallStatus
}

data class PiControllerState(
    val installed: Boolean = false,
    val version: String? = null,
    val install: PiInstallStatus = PiInstallStatus.Idle,
) {
    fun isReady(): Boolean = installed && install is PiInstallStatus.Ready
}

class PiController(
    private val installer: LocalRuntimeInstaller,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(PiControllerState())
    val state: StateFlow<PiControllerState> = mutableState

    private var installJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (installJob?.isActive == true) return
        scope.launch { runCatching { refreshBlocking() } }
    }

    fun install(
        agents: Set<LocalAgent> = setOf(LocalAgent.PI),
        installFullDevelopmentTools: Boolean = false,
    ) {
        if (installJob?.isActive == true) return
        installJob =
            scope.launch(Dispatchers.IO) {
                runCatching {
                    installer.install(agents + LocalAgent.PI, installFullDevelopmentTools) { progress, step, agent ->
                        if (agent == LocalAgent.PI) {
                            val stepRes =
                                if (progress == null || progress <= 0.96f) {
                                    R.string.install_step_downloading_pi
                                } else {
                                    R.string.install_step_installing_pi
                                }
                            mutableState.value =
                                mutableState.value.copy(
                                    install = PiInstallStatus.Installing(progress, stepRes),
                                )
                        }
                    }
                    installer.recordAgent(LocalAgent.PI)
                }.onSuccess {
                    refreshBlocking()
                }.onFailure { error ->
                    mutableState.value =
                        mutableState.value.copy(
                            install = PiInstallStatus.Failed(error.message ?: "Pi installation failed"),
                        )
                }
            }
    }

    private suspend fun refreshBlocking() =
        withContext(Dispatchers.IO) {
            val rootfs = installer.installedRuntime()?.rootfs
            val version =
                rootfs?.let { PiInstaller.installedVersion(it) }
                    ?: rootfs?.takeIf(PiInstaller::isInstalled)?.let { PiManifest.VERSION }
            mutableState.value =
                mutableState.value.copy(
                    installed = version != null,
                    version = version,
                    install =
                        when {
                            version != null -> PiInstallStatus.Ready(version)
                            mutableState.value.install is PiInstallStatus.Failed -> mutableState.value.install
                            else -> PiInstallStatus.Idle
                        },
                )
        }
}
