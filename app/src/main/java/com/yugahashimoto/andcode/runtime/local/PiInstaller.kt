package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object PiInstaller {
    const val PI_VERSION = "0.87.1"

    /** Canonical path we expose to the rest of the app. */
    private const val PI_BINARY = "/usr/local/bin/pi"

    /** Alpine's npm global prefix is often /usr, so the bin may land here instead. */
    private const val PI_BINARY_ALPINE = "/usr/bin/pi"
    private const val MIN_NODE_MAJOR = 22
    private const val MIN_NODE_MINOR = 19
    private const val PACKAGE_NAME = "@earendil-works/pi-coding-agent"
    private const val TARBALL_URL =
        "https://registry.npmjs.org/@earendil-works/pi-coding-agent/-/pi-coding-agent-$PI_VERSION.tgz"

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
                val npmCache = File(runtimeDirectory, "cache/npm").apply { mkdirs() }
                val log =
                    File(runtimeDirectory, "logs/pi-install.log").apply {
                        parentFile?.mkdirs()
                        delete()
                    }

                // Download the package tarball on the Android host (outside proot) so npm inside
                // the sandbox only has to resolve/install dependencies, not re-fetch the main
                // payload. Exit 137 on prior builds was the OOM killer during a full in-proot
                // `npm install` of this tree.
                val hostTarball = File(npmCache, "pi-coding-agent-$PI_VERSION.tgz")
                downloadTarball(hostTarball)
                val guestTarball = File(runtime.rootfs, "tmp/pi-coding-agent-$PI_VERSION.tgz")
                guestTarball.parentFile?.mkdirs()
                hostTarball.copyTo(guestTarball, overwrite = true)

                // Cap V8 heap and npm concurrency so low-RAM devices do not SIGKILL (exit 137).
                // --ignore-scripts skips native builds (e.g. photon-node) that are not required for
                // the RPC coding-agent path and are a common OOM source on Alpine/arm.
                val shell =
                    """
                    set -e
                    export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                    export NODE_OPTIONS="--max-old-space-size=256"
                    export npm_config_maxsockets=1
                    export npm_config_fund=false
                    export npm_config_audit=false
                    export npm_config_update_notifier=false
                    export npm_config_prefix=/usr/local
                    echo "node=${'$'}(node --version)"
                    echo "npm=${'$'}(npm --version)"
                    node -e 'const v=process.versions.node.split(".").map(Number); if (v[0] < $MIN_NODE_MAJOR || (v[0] === $MIN_NODE_MAJOR && v[1] < $MIN_NODE_MINOR)) throw new Error("Pi requires Node.js >= $MIN_NODE_MAJOR.$MIN_NODE_MINOR; found " + process.versions.node)'
                    npm config set registry https://registry.npmjs.org/
                    npm config set prefix /usr/local
                    mkdir -p /usr/local/bin /usr/local/lib /tmp
                    echo "npm install $PACKAGE_NAME@$PI_VERSION from local tarball ..."
                    npm install -g --prefix /usr/local \
                      --ignore-scripts --no-audit --no-fund --progress=false \
                      --fetch-retries=3 --fetch-timeout=300000 \
                      --fetch-retry-mintimeout=2000 --fetch-retry-maxtimeout=60000 \
                      /tmp/pi-coding-agent-$PI_VERSION.tgz
                    if [ ! -x $PI_BINARY ]; then
                      if [ -x $PI_BINARY_ALPINE ]; then
                        ln -sfn $PI_BINARY_ALPINE $PI_BINARY
                      elif [ -f /usr/local/lib/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js ]; then
                        printf '%s\n' '#!/usr/bin/env node' \
                          'import("file:///usr/local/lib/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js")' \
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
                            environment()["NODE_OPTIONS"] = "--max-old-space-size=256"
                        }.start()
                if (!process.waitFor(20, TimeUnit.MINUTES)) {
                    process.destroyForcibly()
                    error("Pi installation timed out")
                }
                ensureLocalBinLink(runtime.rootfs)
                val exit = process.exitValue()
                require(exit == 0) {
                    val hint =
                        if (exit == 137 || exit == 9) {
                            " (process killed — usually out of memory during npm install)"
                        } else {
                            ""
                        }
                    "Pi installation failed (exit $exit)$hint:\n${logTail(log)}"
                }
                require(isInstalledIn(runtime.rootfs)) {
                    "Pi installation completed without installing pi binary. Log:\n${logTail(log)}"
                }
                logTail(log).lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: PI_VERSION
            }
        }

    private fun downloadTarball(destination: File) {
        if (destination.isFile && destination.length() > 10_000L) return
        destination.parentFile?.mkdirs()
        val tmp = File(destination.parentFile, "${destination.name}.part")
        tmp.delete()
        val connection =
            (URL(TARBALL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 60_000
                readTimeout = 300_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "and-code-pi-installer/$PI_VERSION")
            }
        try {
            connection.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    input.copyTo(output)
                }
            }
            require(tmp.length() > 10_000L) {
                "Downloaded Pi tarball is too small (${tmp.length()} bytes)"
            }
            if (!tmp.renameTo(destination)) {
                tmp.copyTo(destination, overwrite = true)
                tmp.delete()
            }
        } finally {
            connection.disconnect()
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
