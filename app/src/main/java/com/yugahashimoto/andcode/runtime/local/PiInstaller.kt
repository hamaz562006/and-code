package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Installs the Pi coding agent into the shared Alpine rootfs via npm.
 *
 * Pi's official Linux release binaries are glibc-linked and cannot run inside this musl rootfs
 * without a separate Debian environment (out of scope). The npm package
 * `@earendil-works/pi-coding-agent` is therefore installed instead, with Node.js/npm provisioned
 * only when Pi is among the requested agents — never added to the shared
 * [LocalRuntimeInstaller.REQUIRED_RUNTIME_PACKAGES] / [LocalRuntimeInstaller.OPTIONAL_DEVELOPMENT_PACKAGES]
 * lists that affect every agent.
 *
 * Does not read or write anything under `root/.config/opencode/`.
 */
object PiInstaller {
    /** Single source of truth for the pinned Pi package version. */
    const val PI_VERSION = "0.87.1"

    const val PI_BINARY = "pi"
    private const val BIN_DIR = "usr/local/bin"
    private const val PACKAGE_NAME = "@earendil-works/pi-coding-agent"

    fun isInstalledIn(rootfs: File): Boolean {
        val binary = File(rootfs, "$BIN_DIR/$PI_BINARY")
        return binary.isFile && binary.canExecute()
    }

    /**
     * Provisions Node.js/npm (isolated, only for Pi) then installs the pinned package globally,
     * verifying the resolved version matches [PI_VERSION] exactly. Fail-closed on mismatch.
     */
    suspend fun install(
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
        runtimeDirectory: File,
        accessCoordinator: LocalRuntimeAccessCoordinator,
    ): String =
        withContext(Dispatchers.IO) {
            accessCoordinator.write {
                ensureNodeJs(rootfs, suite, runtimeDirectory)
                npmInstallPi(rootfs, suite, runtimeDirectory)
                verifyInstalledVersion(rootfs, suite, runtimeDirectory)
            }
            PI_VERSION
        }

    private fun ensureNodeJs(
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
        runtimeDirectory: File,
    ) {
        val nodeCheck = runInRootfs(rootfs, suite, runtimeDirectory, "command -v node && command -v npm")
        if (nodeCheck.exitCode == 0) return
        val result =
            runInRootfs(
                rootfs,
                suite,
                runtimeDirectory,
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
                    "/sbin/apk --cache-dir /var/cache/apk add nodejs npm && " +
                    "/usr/sbin/update-ca-certificates",
                timeoutMinutes = 15,
            )
        check(result.exitCode == 0) {
            "Unable to install nodejs/npm for Pi.\n\nLast log lines:\n${result.log.takeLast(4000)}"
        }
    }

    private fun npmInstallPi(
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
        runtimeDirectory: File,
    ) {
        val result =
            runInRootfs(
                rootfs,
                suite,
                runtimeDirectory,
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
                    "npm install -g --ignore-scripts $PACKAGE_NAME@$PI_VERSION",
                timeoutMinutes = 20,
            )
        check(result.exitCode == 0) {
            "npm install of $PACKAGE_NAME@$PI_VERSION failed.\n\nLast log lines:\n${result.log.takeLast(4000)}"
        }
        val binary = File(rootfs, "$BIN_DIR/$PI_BINARY")
        // npm global bin may land under /usr/local/bin or a prefix; ensure a known path exists.
        if (!binary.isFile) {
            val which =
                runInRootfs(
                    rootfs,
                    suite,
                    runtimeDirectory,
                    "command -v pi || true",
                )
            val resolved = which.log.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            if (resolved != null && resolved != "/usr/local/bin/pi") {
                runInRootfs(
                    rootfs,
                    suite,
                    runtimeDirectory,
                    "ln -sf '$resolved' /usr/local/bin/pi && chmod +x /usr/local/bin/pi",
                )
            }
        }
        check(File(rootfs, "$BIN_DIR/$PI_BINARY").isFile) {
            "Pi install reported success but $BIN_DIR/$PI_BINARY is missing"
        }
        File(rootfs, "$BIN_DIR/$PI_BINARY").setExecutable(true, false)
    }

    /**
     * Confirms the globally resolved package version equals [PI_VERSION]. Any mismatch fails closed
     * so a wrong or tampered install is never treated as successful.
     */
    private fun verifyInstalledVersion(
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
        runtimeDirectory: File,
    ) {
        val result =
            runInRootfs(
                rootfs,
                suite,
                runtimeDirectory,
                "npm ls -g $PACKAGE_NAME --json --depth=0 2>/dev/null || true",
            )
        val resolved =
            runCatching {
                val root = JSONObject(result.log)
                val deps = root.optJSONObject("dependencies") ?: return@runCatching null
                val pkg = deps.optJSONObject(PACKAGE_NAME) ?: return@runCatching null
                pkg.optString("version").takeIf { it.isNotBlank() }
            }.getOrNull()
        check(resolved == PI_VERSION) {
            "Pi version mismatch after install: expected $PI_VERSION, resolved ${resolved ?: "(missing)"}. " +
                "Install failed closed."
        }
    }

    private data class CommandResult(val exitCode: Int, val log: String)

    private fun runInRootfs(
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
        runtimeDirectory: File,
        shellCommand: String,
        timeoutMinutes: Long = 20,
    ): CommandResult {
        val prootTmp = File(runtimeDirectory, "proot-tmp").apply { mkdirs() }
        val apkCache = File(runtimeDirectory, "cache/apk").apply { mkdirs() }
        File(rootfs, "var/cache/apk").mkdirs()
        val logFile =
            File(runtimeDirectory, "logs/pi-install.log").apply {
                parentFile?.mkdirs()
            }
        val command =
            listOf(
                suite.proot.absolutePath,
                "--kill-on-exit",
                "--link2symlink",
                "-0",
                "-r",
                rootfs.absolutePath,
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
                shellCommand,
            )
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .apply {
                    environment().putAll(suite.environment())
                    environment()["PROOT_TMP_DIR"] = prootTmp.absolutePath
                }
                .start()
        val completed = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
        if (!completed) {
            process.destroyForcibly()
            return CommandResult(-1, logFile.readText() + "\n(timed out after ${timeoutMinutes}m)")
        }
        return CommandResult(process.exitValue(), logFile.readText())
    }
}
