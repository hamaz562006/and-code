package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.storage.DeviceStorage
import java.io.File

object PiSandboxLauncher {
    const val PI_BINARY = "/usr/local/bin/pi"

    fun command(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        workspaceHostDir: String,
        workingDirectory: String,
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
            add(workingDirectory)
            add(PI_BINARY)
            addAll(arguments)
        }

    fun environment(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        tmp: File,
        githubToken: String? = null,
    ): Map<String, String> =
        localRuntimeEnvironment(runtime.commandSuite.environment(), tmp) +
            mapOf(
                "HOME" to "/root",
                "PI_CODING_AGENT_DIR" to "/root/.pi/agent",
                "PI_SKIP_VERSION_CHECK" to "1",
                "PI_TELEMETRY" to "0",
                "TERM" to "xterm-256color",
                "SSL_CERT_FILE" to "/etc/ssl/certs/ca-certificates.crt",
                "SSL_CERT_DIR" to "/etc/ssl/certs",
            ) + githubToken.orEmpty().takeIf { it.isNotBlank() }?.let { mapOf("GH_TOKEN" to it) }.orEmpty()
}
