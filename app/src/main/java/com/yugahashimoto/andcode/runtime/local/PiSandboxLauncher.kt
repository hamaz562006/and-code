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

    fun start(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        workspaceHostDir: String,
        workingDirectory: String,
        arguments: List<String>,
        tmp: File,
        githubToken: String? = null,
    ): Process =
        ProcessBuilder(command(runtime, workspaceHostDir, workingDirectory, arguments))
            .directory(File(workspaceHostDir).parentFile ?: runtime.rootfs)
            .apply {
                environment().clear()
                environment().putAll(environment(runtime, tmp, githubToken))
            }
            .start()

    fun stop(process: Process, runtimeDirectory: File) {
        runCatching { process.destroy() }
        runCatching { process.waitFor(750, java.util.concurrent.TimeUnit.MILLISECONDS) }
        val roots = linkedSetOf<Long>().apply {
            processId(process)?.let(::add)
            addAll(findManagedRuntimeRootPids(runtimeDirectory))
        }
        roots
            .flatMap { rootPid -> processTreePostOrder(rootPid) { pid -> readDirectChildPids(pid) } }
            .distinct()
            .forEach { pid -> runCatching { android.os.Process.killProcess(pid.toInt()) } }
        if (process.isAlive) {
            runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
        }
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
