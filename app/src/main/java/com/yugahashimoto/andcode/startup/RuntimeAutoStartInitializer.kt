package com.yugahashimoto.andcode.startup

import android.content.Context
import androidx.startup.Initializer
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.hasUsableRuntimeSetup
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import com.yugahashimoto.andcode.runtime.local.AdbConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Runs once per process, via [androidx.startup.AppInitializer], to bring the local runtime's
 * ancillary state in line with whatever is already running: syncing the onboarding-completed
 * flag, selecting the runtime once it is [Ready][LocalRuntimeStatus.Ready] if nothing else was
 * chosen, restoring the wireless-debugging link, and warming the provider catalog.
 *
 * It used to also *start* the runtime itself, via [restoreIfConfigured] with
 * [RuntimeAutoStartTrigger.AppLaunch] - but this runs for every process the OS ever creates for the
 * app, including the UI-less one `BOOT_COMPLETED` spins up, which made
 * [RuntimeAutoStartTrigger.BootOrPackageReplaced] refusing that trigger in
 * [RuntimeAutoStartReceiver] pointless: this initializer restarted the runtime a moment later in the
 * very same process anyway. That responsibility now lives solely in
 * [com.yugahashimoto.andcode.AndCodeApplication]'s foreground observer, which only ever sees `true`
 * for an actual activity coming on screen - never for a broadcast-only process.
 *
 * [syncOnboardingCompleted] did *not* move with it, even though it went along for the ride in the
 * previous round: [create] runs synchronously on the main thread, after `LAUNCH_ACTIVITY` but
 * before the first Choreographer frame, so it is the only place that can guarantee the flag is
 * settled before `ui/AndCodeApp.kt`'s `remember { if (settings.onboardingCompleted) ... }` picks
 * the start destination. The foreground observer's own call to it (needed for a later return, not
 * cold start) runs on `Dispatchers.IO` and would otherwise race that first composition.
 */
class RuntimeAutoStartInitializer : Initializer<RuntimeAutoStartInitializer.Result> {
    class Result internal constructor(internal val warmupJob: Job?)

    override fun create(context: Context): Result {
        val app = context.applicationContext as AndCodeApplication
        // Must run synchronously, right here, before anything else - see the class doc for why.
        syncOnboardingCompleted(app)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        var warmupJob: Job? = null
        scope.launch {
            app.localRuntimeManager.state.collect { status ->
                if (status is LocalRuntimeStatus.Ready) {
                    // Never override an explicit selection. The user can switch to a PC connection
                    // while the local runtime is still coming up, and Ready is re-emitted on every
                    // watchdog tick — selecting on each one would pull them back to the phone.
                    app.runtimeRegistry.selectIfUnset(LOCAL_RUNTIME_ID)
                    // The adb server is reborn with the Linux runtime, so restore the persisted
                    // wireless-debugging link as soon as it is reachable. Guarded by the current
                    // state because Ready re-emits on every watchdog tick and a redundant
                    // `adb connect` would spawn a proot process each time.
                    if (app.adbConnectionManager.state.value !is AdbConnectionState.Connected) {
                        scope.launch { app.adbConnectionManager.restoreAndReconnect() }
                    }
                    if (app.runtimeRegistry.selected.value?.id != LOCAL_RUNTIME_ID) return@collect
                    warmupJob?.cancel()
                    warmupJob =
                        scope.launch {
                            repeat(CATALOG_WARMUP_ATTEMPTS) {
                                app.catalogRepository.refresh()
                                delay(CATALOG_WARMUP_DELAY_MS)
                                if (app.catalogRepository.state.value.providers.all.isNotEmpty()) return@launch
                            }
                        }
                }
            }
        }

        return Result(warmupJob)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    companion object {
        /**
         * Reconciles [com.yugahashimoto.andcode.data.connection.SecureSettingsRepository.onboardingCompleted]
         * with whatever local runtime state is actually on disk, independent of whether
         * [restoreIfConfigured] goes on to restart anything. Covers a reinstalled APK (or one that
         * otherwise lost its settings) landing on a device where the runtime, or a remote
         * connection, was already configured - onboarding should not be shown again just because the
         * flag itself did not survive.
         */
        internal fun syncOnboardingCompleted(app: AndCodeApplication) {
            val localRuntimeStatus = app.localRuntimeManager.status()
            val hasRemoteConnection = app.settings.connections().isNotEmpty()
            val setupConfigured =
                hasUsableRuntimeSetup(localRuntimeStatus, hasRemoteConnection) ||
                    // Only asked when OpenCode and the remotes did not already answer, and only of
                    // the metadata file (a read lock): this runs on the main thread at every launch.
                    hasUsableRuntimeSetup(
                        localRuntimeStatus,
                        hasRemoteConnection,
                        hasOtherLocalAgent =
                            runCatching {
                                app.localRuntimeInstaller.installedMetadata()?.let { metadata ->
                                    listOf(
                                        LocalAgent.CLAUDE_CODE,
                                        LocalAgent.ANTIGRAVITY,
                                        LocalAgent.CODEX,
                                        LocalAgent.PI,
                                    ).any(metadata::has)
                                } == true
                            }.getOrDefault(false),
                    )
            if (app.settings.onboardingCompleted != setupConfigured) {
                app.settings.onboardingCompleted = setupConfigured
            }
        }

        /**
         * Reuses the same gate from both the boot/package-replaced receiver
         * ([RuntimeAutoStartReceiver]) and the app's own foreground observer
         * ([com.yugahashimoto.andcode.AndCodeApplication.observeForegroundForRuntimeRestart]).
         *
         * The two callers no longer carry equal weight. While
         * [com.yugahashimoto.andcode.data.connection.SecureSettingsRepository.localRuntimeIdleStopEnabled]
         * is on - the default - [shouldAutoStartLocalRuntime] refuses the receiver outright, for
         * both a reboot and a package replacement, and recovery is left entirely to the next
         * foreground return. The receiver still matters with that setting off, which is the only
         * configuration where a runtime is expected to come back without anyone opening the app.
         */
        internal fun restoreIfConfigured(
            app: AndCodeApplication,
            trigger: RuntimeAutoStartTrigger,
        ): Boolean {
            syncOnboardingCompleted(app)

            if (
                !shouldAutoStartLocalRuntime(
                    onboardingCompleted = app.settings.onboardingCompleted,
                    localRuntimeStatus = app.localRuntimeManager.status(),
                    selectedRuntimeId = app.settings.selectedRuntimeId,
                    trigger = trigger,
                    localRuntimeIdleStopEnabled = app.settings.localRuntimeIdleStopEnabled,
                )
            ) {
                return false
            }

            app.localRuntimeController.start()
            return true
        }

        private const val CATALOG_WARMUP_ATTEMPTS = 4
        private const val CATALOG_WARMUP_DELAY_MS = 2500L
    }
}
