package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker
import com.yugahashimoto.andcode.runtime.DevelopmentToolGroup
import com.yugahashimoto.andcode.runtime.LocalAgent
import kotlinx.coroutines.CancellationException
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
        // Never clobber an in-flight install — refresh can race the setup UI.
        if (mutableState.value.install is HermesInstallStatus.Installing) return
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

    /**
     * Installs Hermes, provisioning the shared Linux environment first when there is none yet.
     * Mirrors [GrokBuildController.install] / [PiController.install].
     */
    fun install(
        agents: Set<LocalAgent> = setOf(LocalAgent.HERMES),
        developmentToolGroups: Set<DevelopmentToolGroup> = emptySet(),
    ) {
        if (mutableState.value.install is HermesInstallStatus.Installing) return
        mutableState.update {
            it.copy(install = HermesInstallStatus.Installing(0f, "Preparing Hermes…"))
        }
        scope.launch {
            runtimeWork.withLease("hermes-install") {
                try {
                    val existing = installer.installedMetadata()
                    val othersMissing = (agents - LocalAgent.HERMES).any { existing?.has(it) != true }
                    if (installer.installedRuntime() == null || othersMissing) {
                        installer.install(agents + LocalAgent.HERMES, developmentToolGroups) { progress, step, _ ->
                            mutableState.update {
                                it.copy(install = HermesInstallStatus.Installing(progress, step))
                            }
                        }
                    } else {
                        if (developmentToolGroups.isNotEmpty()) {
                            installer.installDevelopmentToolGroups(developmentToolGroups)
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
                    }
                    // LocalRuntimeInstaller already runs HermesInstaller when HERMES is requested;
                    // if we only provisioned via installer.install above, ensure binary is present.
                    if (!HermesInstaller.isInstalledIn(runtime.runtimeDirectory)) {
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
                    }
                    mutableState.update {
                        it.copy(
                            installed = true,
                            version = HermesInstaller.installedVersion(runtime.runtimeDirectory),
                            install = HermesInstallStatus.Ready,
                        )
                    }
                    runCatching { target.connect() }
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Throwable) {
                    val detail = error.cause?.message?.takeIf { it.isNotBlank() }
                    val message = listOfNotNull(error.message, detail).joinToString(": ").ifBlank { null }
                    mutableState.update { it.copy(install = HermesInstallStatus.Failed(message)) }
                }
            }
        }
    }
}
