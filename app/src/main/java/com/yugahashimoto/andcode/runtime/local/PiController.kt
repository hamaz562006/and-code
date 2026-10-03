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
    val updateAvailable: String? = null,
    val isCheckingUpdate: Boolean = false,
    val updateMessage: String? = null,
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

    fun stop() {
        runtime.stopAll()
        refresh()
    }

    fun restart() {
        runtime.stopAll()
        refresh()
    }

    private suspend fun rehydrate() {
        // Repair npm deps without flipping the UI to "not installed" first.
        val rootfs = installer.installedRuntime()?.rootfs
        if (rootfs != null && PiInstaller.isInstalledIn(rootfs) && !PiInstaller.hasNpmDependencies(rootfs)) {
            runCatching { installer.installPiNpmDependencies() }
        }
        target.connect()
        val version =
            (target.state.value as? RuntimeState.Connected)?.version
                ?: (if (runtime.isInstalled()) runtime.version() else null)
        if (version == null) {
            mutableState.update {
                it.copy(
                    installed = false,
                    version = null,
                    // Keep Failed so the user sees the error instead of a silent "Not installed".
                    install =
                        when (it.install) {
                            is PiInstallStatus.Installing -> it.install
                            is PiInstallStatus.Failed -> it.install
                            else -> PiInstallStatus.Idle
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
        developmentToolGroups: Set<DevelopmentToolGroup> = emptySet(),
    ) {
        if (mutableState.value.install is PiInstallStatus.Installing) return
        mutableState.update { it.copy(install = PiInstallStatus.Installing()) }
        scope.launch {
            runtimeWork.withLease(INSTALL_LEASE_TAG) {
                try {
                    val existing = installer.installedMetadata()
                    val othersMissing = (agents - LocalAgent.PI).any { existing?.has(it) != true }
                    if (installer.installedRuntime() == null || othersMissing) {
                        installer.install(agents + LocalAgent.PI, developmentToolGroups) { progress, step, _ ->
                            mutableState.update { it.copy(install = PiInstallStatus.Installing(progress, step)) }
                        }
                    } else {
                        if (developmentToolGroups.isNotEmpty()) installer.installDevelopmentToolGroups(developmentToolGroups)
                        runCatching { installer.installPackagesIntoActive(listOf("nodejs", "npm", "icu-data-full")) }
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

    fun checkForUpdate() {
        if (mutableState.value.isCheckingUpdate) return
        mutableState.update { it.copy(isCheckingUpdate = true, updateMessage = null) }
        scope.launch {
            runCatching {
                val latest = PiReleaseClient.latestVersion()
                val current = mutableState.value.version ?: runtime.version()
                val available =
                    if (current != null && latest != current && isNewerVersion(latest, current)) {
                        latest
                    } else {
                        null
                    }
                mutableState.update {
                    it.copy(
                        isCheckingUpdate = false,
                        updateAvailable = available,
                        updateMessage =
                            if (available == null) {
                                "Already up to date (${current ?: latest})"
                            } else {
                                null
                            },
                    )
                }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        isCheckingUpdate = false,
                        updateMessage = error.message?.takeIf { m -> m.isNotBlank() } ?: "Update check failed",
                    )
                }
            }
        }
    }

    /** Reinstalls Pi at [updateAvailable] (npm package extract + dependency install). */
    fun updateToLatest() {
        val targetVersion = mutableState.value.updateAvailable ?: return
        if (mutableState.value.install is PiInstallStatus.Installing) return
        mutableState.update {
            it.copy(install = PiInstallStatus.Installing(step = "Updating Pi to $targetVersion"))
        }
        scope.launch {
            runtimeWork.withLease(INSTALL_LEASE_TAG) {
                try {
                    runCatching { installer.installPackagesIntoActive(listOf("nodejs", "npm", "icu-data-full")) }
                    val installed = installer.installedRuntime() ?: error("Linux environment is not installed")
                    PiInstaller.install(
                        rootfs = installed.rootfs,
                        abi = abi,
                        runtimeDirectory = runtime.runtimeDirectory,
                        accessCoordinator = LocalRuntimeAccessCoordinator(),
                        version = targetVersion,
                        onProgress = { fraction ->
                            mutableState.update {
                                it.copy(
                                    install =
                                        PiInstallStatus.Installing(
                                            progress = fraction.coerceIn(0f, 1f),
                                            step = "Updating Pi to $targetVersion",
                                        ),
                                )
                            }
                        },
                    )
                    mutableState.update {
                        it.copy(install = PiInstallStatus.Installing(progress = 0.95f, step = "Updating Pi to $targetVersion"))
                    }
                    installer.installPiNpmDependencies()
                    installer.recordAgent(LocalAgent.PI)
                    mutableState.update {
                        it.copy(
                            install = PiInstallStatus.Ready,
                            updateAvailable = null,
                            version = targetVersion,
                        )
                    }
                    runCatching { rehydrate() }
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Throwable) {
                    mutableState.update {
                        it.copy(install = PiInstallStatus.Failed(error.message))
                    }
                }
            }
        }
    }

    private companion object {
        const val INSTALL_LEASE_TAG = "pi-install"

        fun isNewerVersion(
            latest: String,
            current: String,
        ): Boolean {
            fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').mapNotNull { it.toIntOrNull() }
            val a = parts(latest)
            val b = parts(current)
            val n = maxOf(a.size, b.size)
            for (i in 0 until n) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
