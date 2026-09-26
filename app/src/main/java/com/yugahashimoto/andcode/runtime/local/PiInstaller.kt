package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

object PiInstaller {
    const val PI_VERSION = "0.87.1"

    /** Canonical path we expose to the rest of the app. */
    private const val PI_BINARY = "/usr/local/bin/pi"

    /** Alpine's npm global prefix is often /usr, so the bin may land here instead. */
    private const val PI_BINARY_ALPINE = "/usr/bin/pi"
    private const val MIN_NODE_MAJOR = 22
    private const val MIN_NODE_MINOR = 19

    fun isInstalledIn(rootfs: File): Boolean =
        File(rootfs, PI_BINARY.removePrefix("/")).isFile ||
            File(rootfs, PI_BINARY_ALPINE.removePrefix("/")).isFile

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
                // Install under /usr/local so the binary is always at /usr/local/bin/pi.
                // Alpine's default npm prefix is /usr; without --prefix the bin landed in /usr/bin
                // and the final `/usr/local/bin/pi --version` step failed after a successful npm
                // install, producing a log that only showed `node --version` / `npm --version`.
                val shell =
                    """
                    set -e
                    export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                    echo "node=${'$'}(node --version)"
                    echo "npm=${'$'}(npm --version)"
                    node -e 'const v=process.versions.node.split(".").map(Number); if (v[0] < $MIN_NODE_MAJOR || (v[0] === $MIN_NODE_MAJOR && v[1] < $MIN_NODE_MINOR)) throw new Error("Pi requires Node.js >= $MIN_NODE_MAJOR.$MIN_NODE_MINOR; found " + process.versions.node)'
                    npm config set registry https://registry.npmjs.org/
                    npm config set prefix /usr/local
                    mkdir -p /usr/local/bin /usr/local/lib
                    echo "npm install @earendil-works/pi-coding-agent@$PI_VERSION ..."
                    npm install -g --prefix /usr/local --no-fund --no-audit --progress=false \
                      --fetch-retries=3 --fetch-timeout=300000 \
                      --fetch-retry-mintimeout=2000 --fetch-retry-maxtimeout=60000 \
                      @earendil-works/pi-coding-agent@$PI_VERSION
                    if [ ! -x $PI_BINARY ]; then
                      if [ -x $PI_BINARY_ALPINE ]; then
                        ln -sfn $PI_BINARY_ALPINE $PI_BINARY
                      elif [ -f /usr/local/lib/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js ]; then
                        printf '%s\n' '#!/usr/bin/env node' \
                          'require("/usr/local/lib/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js")' \
                          > $PI_BINARY
                        chmod +x $PI_BINARY
                      else
                        echo "Pi binary missing after npm install" >&2
                        ls -la /usr/local/bin /usr/bin 2>/dev/null || true
                        ls -la /usr/local/lib/node_modules/@earendil-works 2>/dev/null || true
                        exit 1
                      fi
                    fi
                    echo "pi binary ok"
                    $PI_BINARY --version
                    """.trimIndent()
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
                        shell,
                    )
                val process =
                    ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.to(log))
                        .apply {
                            environment().putAll(runtime.commandSuite.environment())
                            environment()["PROOT_TMP_DIR"] = prootTmp.absolutePath
                            environment()["PI_VERSION"] = PI_VERSION
                            environment()["npm_config_prefix"] = "/usr/local"
                        }.start()
                if (!process.waitFor(15, TimeUnit.MINUTES)) {
                    process.destroyForcibly()
                    error("Pi installation timed out")
                }
                // Prefer a host-side symlink so isInstalledIn and runtime always see /usr/local/bin/pi.
                ensureLocalBinLink(runtime.rootfs)
                require(process.exitValue() == 0) {
                    "Pi installation failed (exit ${process.exitValue()}): ${logTail(log)}"
                }
                require(isInstalledIn(runtime.rootfs)) {
                    "Pi installation completed without installing pi binary. Log:\n${logTail(log)}"
                }
                logTail(log).lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: PI_VERSION
            }
        }

    private fun ensureLocalBinLink(rootfs: File) {
        val local = File(rootfs, PI_BINARY.removePrefix("/"))
        if (local.isFile) return
        val alpine = File(rootfs, PI_BINARY_ALPINE.removePrefix("/"))
        if (!alpine.isFile) return
        local.parentFile?.mkdirs()
        runCatching {
            local.delete()
            // Relative symlink inside the rootfs so proot resolves it cleanly.
            // /usr/local/bin/pi -> /usr/bin/pi
            java.nio.file.Files.createSymbolicLink(
                local.toPath(),
                java.nio.file.Paths.get("..", "..", "bin", "pi"),
            )
        }.recoverCatching {
            alpine.copyTo(local, overwrite = true)
            local.setExecutable(true)
        }
    }

    private fun logTail(file: File): String =
        if (!file.isFile) {
            "No Pi installation log was produced."
        } else {
            file.readLines().takeLast(40).joinToString("\n").takeLast(8000)
        }
}
