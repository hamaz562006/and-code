package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

object PiInstaller {
    const val PI_VERSION = "0.87.1"
    private const val PI_BINARY = "/usr/local/bin/pi"

    fun isInstalledIn(rootfs: File): Boolean = File(rootfs, PI_BINARY.removePrefix("/")).isFile

    suspend fun install(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        runtimeDirectory: File,
        accessCoordinator: LocalRuntimeAccessCoordinator,
    ): String =
        withContext(Dispatchers.IO) {
            accessCoordinator.write {
                val prootTmp = File(runtimeDirectory, "proot-tmp").apply { mkdirs() }
                val apkCache = File(runtimeDirectory, "cache/apk").apply { mkdirs() }
                val log =
                    File(runtimeDirectory, "logs/pi-install.log").apply {
                        parentFile?.mkdirs()
                        delete()
                    }
                val command =
                    listOf(
                        runtime.commandSuite.proot.absolutePath,
                        "--kill-on-exit",
                        "--link2symlink",
                        "-0",
                        "-r",
                        runtime.rootfs.absolutePath,
                        "-b",
                        "/dev",
                        "-b",
                        "/proc",
                        "-b",
                        "/sys",
                        "-b",
                        "/system",
                        "-b",
                        "${apkCache.absolutePath}:/var/cache/apk",
                        "-w",
                        "/root",
                        "/bin/sh",
                        "-lc",
                        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
                            "/sbin/apk --cache-dir /var/cache/apk add nodejs-current npm && " +
                            "npm install -g --ignore-scripts --no-fund --no-audit @earendil-works/pi-coding-agent@${PI_VERSION} && " +
                            "node --version && npm --version && /usr/local/bin/pi --version",
                    )
                val process =
                    ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.to(log))
                        .apply {
                            environment().putAll(runtime.commandSuite.environment())
                            environment()["PROOT_TMP_DIR"] = prootTmp.absolutePath
                            environment()["PI_VERSION"] = PI_VERSION
                        }.start()
                if (!process.waitFor(15, TimeUnit.MINUTES)) {
                    process.destroyForcibly()
                    error("Pi installation timed out")
                }
                require(process.exitValue() == 0) {
                    "Pi installation failed: ${logTail(log)}"
                }
                require(isInstalledIn(runtime.rootfs)) {
                    "Pi installation completed without installing /usr/local/bin/pi"
                }
                logTail(log).lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: PI_VERSION
            }
        }

    private fun logTail(file: File): String =
        if (!file.isFile) {
            "No Pi installation log was produced."
        } else {
            file.readLines().takeLast(30).joinToString("\n").takeLast(6000)
        }
}
