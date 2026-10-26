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

sealed interface AiderInstallStatus {
    data object Idle : AiderInstallStatus

    data class Installing(
        val progress: Float? = null,
        val step: String? = null,
    ) : AiderInstallStatus

    data object Ready : AiderInstallStatus

    data class Failed(
        val message: String?,
    ) : AiderInstallStatus
}

data class AiderUiState(
    val installed: Boolean = false,
    val version: String? = null,
    val install: AiderInstallStatus = AiderInstallStatus.Idle,
) {
    fun isReady(): Boolean =
        installed &&
            install !is AiderInstallStatus.Installing &&
            install !is AiderInstallStatus.Failed
}

/**
 * Owns Aider install UI state. Mirrors [PiController] / [HermesController] so setup step 3 does not
 * flip back to "Not installed" while an install is in flight (rehydrate must preserve Installing
 * and Failed).
 */
class AiderController(
    private val runtime: AiderRuntime,
    private val target: AiderTarget,
    private val installer: LocalRuntimeInstaller,
    private val runtimeWork: RuntimeWorkTracker,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val mutableState = MutableStateFlow(AiderUiState())
    val state: StateFlow<AiderUiState> = mutableState.asStateFlow()

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
                    installed -> AiderInstallStatus.Ready
                    current.install is AiderInstallStatus.Installing -> current.install
                    current.install is AiderInstallStatus.Failed -> current.install
                    else -> AiderInstallStatus.Idle
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

    fun install(agents: Set<LocalAgent> = setOf(LocalAgent.AIDER)) {
        if (mutableState.value.install is AiderInstallStatus.Installing) return
        mutableState.update { it.copy(install = AiderInstallStatus.Installing(progress = 0f, step = "Starting")) }
        scope.launch {
            runtimeWork.withLease("install-aider") {
                try {
                    val existing = installer.installedMetadata()
                    val othersMissing = (agents - LocalAgent.AIDER).any { existing?.has(it) != true }
                    if (installer.installedRuntime() == null || othersMissing) {
                        installer.install(agents + LocalAgent.AIDER) { progress, step, _ ->
                            mutableState.update {
                                it.copy(install = AiderInstallStatus.Installing(progress, step))
                            }
                        }
                    } else {
                        installer.installAiderOnly { progress, step ->
                            mutableState.update {
                                it.copy(install = AiderInstallStatus.Installing(progress, step))
                            }
                        }
                        installer.recordAgent(LocalAgent.AIDER)
                    }
                    if (!runtime.isInstalled()) {
                        error("Aider install finished but the agent is still not installed")
                    }
                    mutableState.update {
                        it.copy(
                            installed = true,
                            version = runtime.installedVersion(),
                            install = AiderInstallStatus.Ready,
                        )
                    }
                    runCatching { target.connect() }
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Throwable) {
                    val detail = error.cause?.message?.takeIf { it.isNotBlank() }
                    val message = listOfNotNull(error.message, detail).joinToString(": ").ifBlank { null }
                    mutableState.update { it.copy(install = AiderInstallStatus.Failed(message)) }
                }
            }
        }
    }
}
