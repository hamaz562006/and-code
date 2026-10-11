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
 * Single owner of Hermes install state — same control flow as [PiController].
 *
 * Package is the Termux aarch64 `.deb` extracted onto the Android host.
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
        if (!installed) {
            mutableState.update {
                it.copy(
                    installed = false,
                    version = null,
                    // Keep Installing / Failed so setup does not flash "Not installed".
                    install =
                        when (it.install) {
                            is HermesInstallStatus.Installing -> it.install
                            is HermesInstallStatus.Failed -> it.install
                            else -> HermesInstallStatus.Idle
                        },
                )
            }
            return
        }
        mutableState.update {
            it.copy(
                installed = true,
                version = version,
                install =
                    if (it.install is HermesInstallStatus.Installing) {
                        it.install
                    } else {
                        HermesInstallStatus.Ready
                    },
            )
        }
        scope.launch { runCatching { target.connect() } }
    }

    /**
     * Installs Hermes, provisioning the shared Linux environment first when there is none yet.
     * Identical structure to [PiController.install].
     */
    fun install(
        agents: Set<LocalAgent> = setOf(LocalAgent.HERMES),
        developmentToolGroups: Set<DevelopmentToolGroup> = emptySet(),
    ) {
        if (mutableState.value.install is HermesInstallStatus.Installing) return
        mutableState.update { it.copy(install = HermesInstallStatus.Installing()) }
        scope.launch {
            runtimeWork.withLease("hermes-install") {
                try {
                    val existing = installer.installedMetadata()
                    val others = agents - LocalAgent.HERMES
                    val othersMissing = others.any { existing?.has(it) != true }
                    // Hermes is host-side (.deb). Only pull in the shared Alpine sandbox when
                    // another agent that needs it was also requested — never install OpenCode
                    // just because Hermes was selected.
                    if (others.isNotEmpty() && (installer.installedRuntime() == null || othersMissing)) {
                        installer.install(others, developmentToolGroups) { progress, step, _ ->
                            mutableState.update {
                                it.copy(install = HermesInstallStatus.Installing(progress, step))
                            }
                        }
                    } else if (developmentToolGroups.isNotEmpty() && installer.installedRuntime() != null) {
                        installer.installDevelopmentToolGroups(developmentToolGroups)
                    }
                    if (!HermesInstaller.isInstalledIn(runtime.runtimeDirectory)) {
                        HermesInstaller.install(runtime.runtimeDirectory) { fraction ->
                            mutableState.update {
                                it.copy(
                                    install =
                                        HermesInstallStatus.Installing(
                                            progress = fraction,
                                            step = "Installing Hermes…",
                                        ),
                                )
                            }
                        }
                    }
                    installer.recordAgent(LocalAgent.HERMES)
                    mutableState.update { it.copy(install = HermesInstallStatus.Ready) }
                    runCatching { rehydrate() }
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
