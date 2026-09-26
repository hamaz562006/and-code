package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a Pi install has got to. */
sealed interface PiInstallStatus {
    data object Idle : PiInstallStatus

    /**
     * [progress] and [step] come from [LocalRuntimeInstaller] when this install also provisions the
     * shared environment; adding Pi to an existing environment is one npm install with neither.
     */
    data class Installing(val progress: Float? = null, val step: String? = null) : PiInstallStatus

    /** [message] is null when nothing more specific is known, so the UI shows its own translated default. */
    data class Failed(val message: String?) : PiInstallStatus
}

data class PiUiState(
    val installed: Boolean = false,
    val version: String? = null,
    val install: PiInstallStatus = PiInstallStatus.Idle,
) {
    /** Pi is ready once installed; provider API keys are configured under Settings → Providers. */
    val ready: Boolean get() = installed
}

/**
 * Single owner of the Pi install state.
 *
 * Smaller than [CodexController] because Pi has no dedicated OAuth sign-in: model credentials come
 * from the shared provider store and are written into `~/.pi/agent/auth.json` by [PiRuntime].
 * Installs through the shared [LocalRuntimeInstaller] pipeline (Node prerequisites + npm package).
 */
class PiController(
    private val runtime: PiRuntime,
    private val target: PiTarget,
    private val installer: LocalRuntimeInstaller,
    /** Required for the same reason as in [CodexController]: install is real work with no other lease. */
    private val runtimeWork: RuntimeWorkTracker,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val mutableState = MutableStateFlow(PiUiState())
    val state: StateFlow<PiUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads what is installed. */
    fun refresh() {
        scope.launch { runCatching { rehydrate() } }
    }

    private suspend fun rehydrate() {
        // Always connect, even when nothing is installed: that is what leaves the target
        // Unavailable while Pi is missing, and the drawer's agent switcher hides Unavailable
        // targets.
        target.connect()
        val version = (target.state.value as? RuntimeState.Connected)?.version
        if (version == null) {
            mutableState.update { it.copy(installed = false, version = null) }
            return
        }
        mutableState.update { it.copy(installed = true, version = version) }
    }

    /**
     * Installs Pi, provisioning the shared Linux environment first when there is none yet.
     *
     * [agents] is what the setup guide selected: with no OpenCode among it, this is the one install
     * for the whole selection. Pi alone, from Settings, is the default.
     */
    fun install(
        agents: Set<LocalAgent> = setOf(LocalAgent.PI),
        installFullDevelopmentTools: Boolean = false,
    ) {
        if (mutableState.value.install is PiInstallStatus.Installing) return
        mutableState.update { it.copy(install = PiInstallStatus.Installing()) }
        scope.launch {
            runtimeWork.withLease(INSTALL_LEASE_TAG) {
                runCatching {
                    val existing = installer.installedMetadata()
                    val othersMissing = (agents - LocalAgent.PI).any { existing?.has(it) != true }
                    if (installer.installedRuntime() == null || othersMissing) {
                        installer.install(agents + LocalAgent.PI, installFullDevelopmentTools) { progress, step, _ ->
                            mutableState.update { it.copy(install = PiInstallStatus.Installing(progress, step)) }
                        }
                    } else {
                        if (installFullDevelopmentTools) installer.installFullDevelopmentTools()
                        val installed = installer.installedRuntime() ?: error("Runtime missing after install check")
                        PiInstaller.install(installed, runtime.runtimeDirectory, LocalRuntimeAccessCoordinator())
                        installer.recordAgent(LocalAgent.PI)
                    }
                }
                    .onSuccess {
                        mutableState.update { it.copy(install = PiInstallStatus.Idle) }
                        runCatching { rehydrate() }
                    }
                    .onFailure { error ->
                        val detail = error.cause?.message?.takeIf { it.isNotBlank() }
                        val message = listOfNotNull(error.message, detail).joinToString(": ").ifBlank { null }
                        mutableState.update { it.copy(install = PiInstallStatus.Failed(message)) }
                    }
            }
        }
    }

    private companion object {
        const val INSTALL_LEASE_TAG = "pi-install"
    }
}
