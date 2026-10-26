package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Installs [Aider](https://github.com/Aider-AI/aider) into the shared Alpine/PRoot rootfs.
 *
 * Uses a venv (PEP 668). Heavy native deps (`tree-sitter*`) are installed in separate steps with
 * progress updates so setup UI does not sit frozen on one percentage for a long compile.
 * Builds use a single job (`MAX_JOBS=1`) to avoid OOM/hangs on mobile RAM.
 */
object AiderInstaller {
    const val VENV_REL = "usr/local/lib/aider-venv"
    private const val VENV_BIN_AIDER = "$VENV_REL/bin/aider"
    private const val SHIM_REL = "usr/local/bin/aider"
    private const val VENV_PIP = "/$VENV_REL/bin/pip"

    fun isInstalledIn(rootfs: File): Boolean {
        val shim = File(rootfs, SHIM_REL)
        val venvAider = File(rootfs, VENV_BIN_AIDER)
        return (shim.isFile || venvAider.isFile) && File(rootfs, AiderManifest.MARKER_REL).isFile
    }

    fun installedVersion(rootfs: File): String? {
        val f = File(rootfs, AiderManifest.VERSION_REL)
        return f.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun install(
        rootfs: File,
        runShell: (String) -> Unit,
        onProgress: (Float) -> Unit = {},
    ) {
        onProgress(0.04f)
        // Toolchain only — no cargo/rust (those made installs hang for hours).
        runShell(
            "apk add --no-cache " +
                "python3 py3-pip py3-virtualenv py3-setuptools py3-wheel git " +
                "build-base python3-dev libffi-dev openssl-dev cmake",
        )
        onProgress(0.12f)
        runShell("rm -rf /$VENV_REL && python3 -m venv /$VENV_REL")
        onProgress(0.18f)
        runShell("$VENV_PIP install --upgrade pip setuptools wheel")
        onProgress(0.24f)

        // Single-job native builds; prefer wheels when they exist for this platform.
        val buildEnv =
            "export MAX_JOBS=1 MAKEFLAGS=-j1 CMAKE_BUILD_PARALLEL_LEVEL=1 " +
                "CC=cc CXX=c++ PATH=/usr/bin:/usr/local/bin:\$PATH; "

        // tree-sitter core first (smaller than language-pack).
        onProgress(0.30f)
        runShell(
            buildEnv +
                "$VENV_PIP install --no-cache-dir --prefer-binary 'tree-sitter>=0.21.0'",
        )
        onProgress(0.45f)
        // language-pack is the slow compile on aarch64/musl — isolated so progress moves.
        runShell(
            buildEnv +
                "$VENV_PIP install --no-cache-dir --prefer-binary 'tree-sitter-language-pack' || " +
                "$VENV_PIP install --no-cache-dir 'tree-sitter-language-pack'",
        )
        onProgress(0.70f)
        runShell(
            buildEnv +
                "$VENV_PIP install --no-cache-dir --prefer-binary 'aider-chat' || " +
                "$VENV_PIP install --no-cache-dir 'aider-chat' || " +
                "$VENV_PIP install --no-cache-dir 'git+https://github.com/Aider-AI/aider.git'",
        )
        onProgress(0.90f)
        runShell(
            "set -e; mkdir -p /usr/local/bin; " +
                "test -x /$VENV_BIN_AIDER; " +
                "ln -sfn /$VENV_BIN_AIDER /usr/local/bin/aider; " +
                "command -v aider >/dev/null",
        )
        File(rootfs, AiderManifest.MARKER_REL).apply {
            parentFile?.mkdirs()
            writeText("ok\n")
        }
        File(rootfs, AiderManifest.VERSION_REL).writeText(AiderManifest.VERSION + "\n")
        onProgress(1f)
        require(isInstalledIn(rootfs)) { "Aider install finished but venv binary/shim not found" }
    }
}
