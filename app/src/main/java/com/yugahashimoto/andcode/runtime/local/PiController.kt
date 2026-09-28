package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.RuntimeState
import kotlinx.coroutines.CancellationException
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

    data class Installing(val progress: Float? = null, val step: String? = null) : PiInstallStatus

    /** Successful install (or already installed after rehydrate). Not [Idle] so UI can treat ready distinctly from "never started". */
    data object Ready : PiInstallStatus

    data class Failed(val message: String?) : PiInstallStatus
}

data class PiUiState(
    val installed: Boolean = false,
    val version: String? = null,
    val install: PiInstallStatus = PiInstallStatus.Idle,
) {
    /**
     * True when the binary is installed and no install is currently failing or in flight.
     */
    fun isReady(): Boolean = installed && install !is PiInstallStatus.Installing && install !is PiInstallStatus.Failed
}

/**
 * Single owner of the Pi install state.
 *
 * Backed by the official earendil-works/pi standalone release archive.
 */
class PiController(
    private val runtime: PiRuntime,
    private val target: PiTarget,
    private val installer: LocalRuntimeInstaller,
    private val abi: String = "arm64-v8a",
    private val runtimeWork: RuntimeWorkTracker,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val mutableState = MutableStateFlow(PiUiState())
    val state: StateFlow<PiUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch { runCatching { rehydrate() } }
    }

    private suspend fun rehydrate() {
        target.connect()
        val version =
            (target.state.value as? RuntimeState.Connected)?.version
                ?: (if (runtime.isInstalled()) runtime.version() else null)
        if (version == null) {
            mutableState.update {
                it.copy(
                    installed = false,
                    version = null,
                    install = if (it.install is PiInstallStatus.Installing) it.install else PiInstallStatus.Idle,
                )
            }
            return
        }
        mutableState.update {
            it.copy(
                installed = true,
                version = version,
                install =
                    if (it.install is PiInstallStatus.Installing) {
                        it.install
                    } else {
                        PiInstallStatus.Ready
                    },
            )
        }
    }

    /**
     * Installs Pi, provisioning the shared Linux environment first when there is none yet.
     *
     * [agents] is the setup selection: with no OpenCode among it, this is the one install for the
     * whole selection (must stay one — a second would race the same staging directory).
     */
    fun install(
        agents: Set<LocalAgent> = setOf(LocalAgent.PI),
        installFullDevelopmentTools: Boolean = false,
    ) {
        if (mutableState.value.install is PiInstallStatus.Installing) return
        mutableState.update { it.copy(install = PiInstallStatus.Installing()) }
        scope.launch {
            runtimeWork.withLease(INSTALL_LEASE_TAG) {
                try {
                    val existing = installer.installedMetadata()
                    val othersMissing = (agents - LocalAgent.PI).any { existing?.has(it) != true }
                    if (installer.installedRuntime() == null || othersMissing) {
                        installer.install(agents + LocalAgent.PI, installFullDevelopmentTools) { progress, step, _ ->
                            mutableState.update { it.copy(install = PiInstallStatus.Installing(progress, step)) }
                        }
                    } else {
                        if (installFullDevelopmentTools) installer.installFullDevelopmentTools()
                        runCatching { installer.installPackagesIntoActive(listOf("gcompat")) }
                        runtime.install(abi)
                        installer.recordAgent(LocalAgent.PI)
                    }
                    mutableState.update { it.copy(install = PiInstallStatus.Ready) }
                    runCatching { rehydrate() }
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Throwable) {
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
