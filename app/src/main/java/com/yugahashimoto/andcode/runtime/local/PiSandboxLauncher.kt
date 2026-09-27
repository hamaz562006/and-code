package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.storage.DeviceStorage
import java.io.File

/**
 * Builds the PRoot invocation that runs `pi --mode rpc` inside the shared Alpine sandbox.
 *
 * Pi is installed via npm into this musl rootfs (see [PiInstaller]); no separate Debian rootfs
 * is required. RPC mode speaks JSONL over stdio (not a TUI), so no PTY is needed — the same
 * shape as [CodexSandboxLauncher].
 */
object PiSandboxLauncher {
    const val PI_BINARY = "/usr/local/bin/pi"

    fun command(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        workspaceHostDir: String,
        arguments: List<String>,
    ): List<String> =
        buildList {
            add(runtime.commandSuite.proot.absolutePath)
            add("--kill-on-exit")
            add("--link2symlink")
            add("-0")
            add("-r")
            add(runtime.rootfs.absolutePath)
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")
            add("-b")
            add("/system")
            add("-b")
            add("$workspaceHostDir:/workspace")
            addAll(DeviceStorage.bindArguments())
            add("-w")
            add("/workspace")
            add(PI_BINARY)
            addAll(arguments)
        }

    fun environment(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        tmp: File,
    ): Map<String, String> =
        localRuntimeEnvironment(runtime.commandSuite.environment(), tmp) +
            mapOf(
                "HOME" to "/root",
                // Pi stores config/credentials under ~/.pi (package.json piConfig.configDir = ".pi")
                "TERM" to "xterm-256color",
                "SSL_CERT_FILE" to "/etc/ssl/certs/ca-certificates.crt",
                "SSL_CERT_DIR" to "/etc/ssl/certs",
                // Ensure the npm global bin is on PATH inside the rootfs
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            )
}
