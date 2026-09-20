package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.storage.DeviceStorage
import java.io.File

/**
 * Builds the PRoot invocation that runs Codex inside the same Alpine sandbox OpenCode and Claude
 * Code already share.
 *
 * Codex's native CLI targets `*-unknown-linux-musl`, the same libc this rootfs is built on, so
 * unlike Antigravity's glibc build it needs no separate rootfs and no `gcompat` shim - see
 * docs/CODEX.md. `app-server` talks JSON-RPC over plain stdio, not a TUI, so - also unlike
 * Antigravity - this needs no PTY.
 */
object CodexSandboxLauncher {
    const val CODEX_BINARY = "/usr/local/bin/codex"

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
            // Empty until the user grants all-files access, so the sandbox is unchanged without it.
            addAll(DeviceStorage.bindArguments())
            add("-w")
            add("/workspace")
            add(CODEX_BINARY)
            // Must precede the subcommand: `codex -c key=value app-server ...`, not the reverse -
            // verified against the real binary, which otherwise treats it as an app-server option.
            //
            // Codex's own command-execution sandbox (bundled bubblewrap) needs unprivileged Linux
            // user namespaces, which PRoot does not provide underneath - the outer PRoot jail is this
            // app's actual containment boundary, matching how Claude Code and Antigravity run here.
            // `danger-full-access` only turns off Codex's *own*, redundant, and (inside PRoot)
            // non-functional inner sandbox; commands still cannot leave the PRoot rootfs.
            add("-c")
            add("sandbox_mode=\"danger-full-access\"")
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
                "CODEX_HOME" to "/root/.codex",
                "TERM" to "xterm-256color",
                "SSL_CERT_FILE" to "/etc/ssl/certs/ca-certificates.crt",
                "SSL_CERT_DIR" to "/etc/ssl/certs",
            ) +
            githubToken.orEmpty().takeIf { it.isNotBlank() }?.let { mapOf("GH_TOKEN" to it) }.orEmpty()
}
