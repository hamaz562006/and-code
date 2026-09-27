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

/** Where a Pi install has got to, so the setup guide can show real progress instead of a dead spinner. */
sealed interface PiInstallStatus {
    data object Idle : PiInstallStatus

    /**
     * [progress] and [step] come from [LocalRuntimeInstaller] when this install also provisions the
     * shared environment (a setup without OpenCode); adding Pi to an existing environment is one
     * step with neither, so both are null there.
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
    /**
     * Installed and not mid-install: an install in flight must not read as ready, even though
     * [installed] can still be true from a previous run while a reinstall is under way.
     */
    val isReady: Boolean get() = installed && install is PiInstallStatus.Idle
}

/**
 * Single owner of the Pi install state.
 *
 * Pi has no separate sign-in step of its own here - it manages its own provider credentials/login
 * inside its own config in the shared rootfs - so this is smaller than `ClaudeCodeController` or
 * `AntigravityController`, and closer in shape to `CodexController` without the sign-in half.
 *
 * Modeled independently of the shared [LocalRuntimeStatus]/`LocalRuntimeManager` state on purpose:
 * that status only ever reaches Ready/Stopped once OpenCode's own server has started, so a
 * Pi-only (no OpenCode) selection would never report completion through it. This controller reads
 * Pi's own installed-ness directly instead, the same way Claude/Antigravity/Codex each track their
 * own binary's presence regardless of whether OpenCode is part of the selection.
 */
class PiController(
    private val installer: LocalRuntimeInstaller,
    private val target: PiTarget,
    /** Required for the same reason as in [AntigravityController]: install is real work with no other lease. */
    private val runtimeWork: RuntimeWorkTracker,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val mutableState = MutableStateFlow(PiUiState())
    val state: StateFlow<PiUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads whether Pi is installed, from the metadata and rootfs on disk. */
    fun refresh() {
        // Best-effort rehydration: nothing above this launch catches what it throws.
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
     * for the whole selection (it must stay one, because a second would race it for the same staging
     * directory), and [LocalRuntimeInstaller] provisions every agent named in it, Pi included. Pi
     * alone, from Settings, is the default.
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
                    installer.install(agents + LocalAgent.PI, installFullDevelopmentTools) { progress, step, _ ->
                        mutableState.update { it.copy(install = PiInstallStatus.Installing(progress, step)) }
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
