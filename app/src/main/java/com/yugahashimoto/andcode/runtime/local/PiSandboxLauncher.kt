package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.storage.DeviceStorage
import java.io.File

object PiSandboxLauncher {
    fun command(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        workspaceHostDir: File,
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
            add("${workspaceHostDir.absolutePath}:/workspace")
            // Empty until user grants storage access
            addAll(DeviceStorage.bindArguments())
            add("-w")
            add(workingDirectory)
            add(PiInstaller.BINARY_PATH)
            addAll(arguments)
        }

    fun environment(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        tmp: File,
        githubToken: String? = null,
    ): Map<String, String> =
        localRuntimeEnvironment(runtime.commandSuite.environment(), tmp) +
            mapOf(
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin",
                "HOME" to "/root",
                "TERM" to "xterm-256color",
                "SSL_CERT_FILE" to "/etc/ssl/certs/ca-certificates.crt",
                "SSL_CERT_DIR" to "/etc/ssl/certs",
                "CI" to "1",
            ) +
            githubToken.orEmpty().takeIf { it.isNotBlank() }?.let { mapOf("GH_TOKEN" to it) }.orEmpty()
}
