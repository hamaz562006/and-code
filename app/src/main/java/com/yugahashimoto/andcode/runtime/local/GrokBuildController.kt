package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker
import com.yugahashimoto.andcode.runtime.DevelopmentToolGroup
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

sealed interface GrokBuildInstallStatus {
    data object Idle : GrokBuildInstallStatus

    data class Installing(val progress: Float? = null, val step: String? = null) : GrokBuildInstallStatus

    data object Ready : GrokBuildInstallStatus

    data class Failed(val message: String?) : GrokBuildInstallStatus
}

data class GrokBuildUiState(
    val installed: Boolean = false,
    val version: String? = null,
    val install: GrokBuildInstallStatus = GrokBuildInstallStatus.Idle,
    val hasApiKey: Boolean = false,
    val updateAvailable: String? = null,
    val isCheckingUpdate: Boolean = false,
    val updateMessage: String? = null,
) {
    fun isReady(): Boolean =
        installed &&
            install !is GrokBuildInstallStatus.Installing &&
            install !is GrokBuildInstallStatus.Failed
}

/**
 * Owns Grok Build install state (Duro02 Termux binary + API-key auth, no browser login).
 */
class GrokBuildController(
    private val runtime: GrokBuildRuntime,
    private val target: GrokBuildTarget,
    private val installer: LocalRuntimeInstaller,
    private val abi: String = "arm64-v8a",
    private val runtimeWork: RuntimeWorkTracker,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val mutableState = MutableStateFlow(GrokBuildUiState())
    val state: StateFlow<GrokBuildUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch { runCatching { rehydrate() } }
    }

    fun stop() {
        runtime.stopAll()
        refresh()
    }

    fun restart() {
        runtime.stopAll()
        refresh()
    }

    private suspend fun rehydrate() {
        val previous = mutableState.value
        val rootfs = installer.installedRuntime()?.rootfs
        val installed = rootfs != null && GrokBuildInstaller.isInstalledIn(rootfs)
        val version = rootfs?.let { GrokBuildInstaller.installedVersion(it) }
        val hasKey = rootfs?.let { runtime.hasApiKey(it) } == true
        // Preserve Failed so a transient refresh does not look like "Not installed".
        val installStatus =
            when {
                previous.install is GrokBuildInstallStatus.Failed && !installed -> previous.install
                previous.install is GrokBuildInstallStatus.Installing -> previous.install
                installed -> GrokBuildInstallStatus.Ready
                else -> GrokBuildInstallStatus.Idle
            }
        mutableState.update {
            it.copy(
                installed = installed,
                version = version,
                install = installStatus,
                hasApiKey = hasKey,
            )
        }
        if (installed && target.state.value !is RuntimeState.Connected) {
            runCatching { target.connect() }
        }
    }

    fun install(
        agents: Set<LocalAgent> = setOf(LocalAgent.GROK_BUILD),
        developmentGroups: Set<DevelopmentToolGroup> = emptySet(),
    ) {
        if (mutableState.value.install is GrokBuildInstallStatus.Installing) return
        scope.launch {
            mutableState.update {
                it.copy(install = GrokBuildInstallStatus.Installing(progress = 0f, step = null))
            }
            try {
                runtimeWork.track("Installing Grok Build") {
                    installer.install(
                        agents = agents,
                        developmentGroups = developmentGroups,
                        onProgress = { progress, step, agent ->
                            if (agent == null || agent == LocalAgent.GROK_BUILD) {
                                mutableState.update {
                                    it.copy(
                                        install =
                                            GrokBuildInstallStatus.Installing(
                                                progress = progress,
                                                step = step,
                                            ),
                                    )
                                }
                            }
                        },
                    )
                }
                rehydrate()
                mutableState.update {
                    it.copy(install = GrokBuildInstallStatus.Ready)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(install = GrokBuildInstallStatus.Failed(e.message))
                }
            }
        }
    }

    fun setApiKey(apiKey: String?) {
        scope.launch {
            val rootfs = installer.installedRuntime()?.rootfs ?: return@launch
            runtime.setApiKey(rootfs, apiKey)
            mutableState.update { it.copy(hasApiKey = runtime.hasApiKey(rootfs)) }
        }
    }

    fun checkForUpdate() {
        // Pinned release for now; wire GitHub latest later.
        scope.launch {
            mutableState.update { it.copy(isCheckingUpdate = true, updateMessage = null) }
            val current = mutableState.value.version
            val latest = GrokBuildManifest.VERSION
            mutableState.update {
                it.copy(
                    isCheckingUpdate = false,
                    updateAvailable = if (current != null && current != latest) latest else null,
                    updateMessage = if (current == latest) "Up to date ($latest)" else null,
                )
            }
        }
    }
}
