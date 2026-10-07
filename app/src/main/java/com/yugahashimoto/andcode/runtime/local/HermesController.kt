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
     * Follows the same control flow as [GrokBuildController.install].
     */
    fun install(
        agents: Set<LocalAgent> = setOf(LocalAgent.HERMES),
        developmentToolGroups: Set<DevelopmentToolGroup> = emptySet(),
    ) {
        if (mutableState.value.install is HermesInstallStatus.Installing) return
        // Flip UI to Installing *before* launching the coroutine so step 3 never sits on Idle.
        mutableState.update {
            it.copy(install = HermesInstallStatus.Installing(0f, "Preparing Hermes…"))
        }
        scope.launch {
            runtimeWork.withLease("hermes-install") {
                try {
                    val existing = installer.installedMetadata()
                    val othersMissing = (agents - LocalAgent.HERMES).any { existing?.has(it) != true }
                    if (installer.installedRuntime() == null || othersMissing) {
                        // Shared Alpine rootfs + any other selected agents + Hermes deb (via installer).
                        installer.install(agents + LocalAgent.HERMES, developmentToolGroups) { progress, step, _ ->
                            mutableState.update {
                                it.copy(install = HermesInstallStatus.Installing(progress, step))
                            }
                        }
                    } else if (developmentToolGroups.isNotEmpty()) {
                        installer.installDevelopmentToolGroups(developmentToolGroups)
                    }

                    // Always ensure the host-side Hermes package is present (idempotent if already done).
                    if (!HermesInstaller.isInstalledIn(runtime.runtimeDirectory)) {
                        HermesInstaller.install(runtime.runtimeDirectory) { fraction ->
                            mutableState.update {
                                it.copy(
                                    install =
                                        HermesInstallStatus.Installing(
                                            progress = fraction,
                                            step = "Downloading Hermes (~160 MB)…",
                                        ),
                                )
                            }
                        }
                    }
                    installer.recordAgent(LocalAgent.HERMES)

                    val version = HermesInstaller.installedVersion(runtime.runtimeDirectory)
                    require(HermesInstaller.isInstalledIn(runtime.runtimeDirectory)) {
                        "Hermes binary missing after install"
                    }
                    mutableState.update {
                        it.copy(
                            installed = true,
                            version = version,
                            install = HermesInstallStatus.Ready,
                        )
                    }
                    runCatching { target.connect() }
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Throwable) {
                    val detail = error.cause?.message?.takeIf { it.isNotBlank() }
                    val message =
                        listOfNotNull(error.message, detail).joinToString(": ").ifBlank {
                            "Hermes install failed"
                        }
                    mutableState.update {
                        it.copy(install = HermesInstallStatus.Failed(message))
                    }
                }
            }
        }
    }
}
