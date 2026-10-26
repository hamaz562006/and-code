package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Installs [Aider](https://github.com/Aider-AI/aider) into the shared Alpine/PRoot rootfs.
 *
 * Alpine marks system Python as PEP 668 "externally managed", so `pip install` into the system
 * site-packages fails. We install into a dedicated virtualenv under [VENV_REL] and put a shim on
 * PATH at `/usr/local/bin/aider` — same idea as the Termux installer, adapted for apk/Alpine.
 */
object AiderInstaller {
    const val VENV_REL = "usr/local/lib/aider-venv"
    private const val VENV_BIN_AIDER = "$VENV_REL/bin/aider"
    private const val SHIM_REL = "usr/local/bin/aider"

    fun isInstalledIn(rootfs: File): Boolean {
        val shim = File(rootfs, SHIM_REL)
        val venvAider = File(rootfs, VENV_BIN_AIDER)
        return (shim.isFile || venvAider.isFile) && File(rootfs, AiderManifest.MARKER_REL).isFile
    }

    fun installedVersion(rootfs: File): String? {
        val f = File(rootfs, AiderManifest.VERSION_REL)
        return f.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * @param runShell executes a command inside the guest rootfs (already proot-wrapped by caller).
     */
    fun install(
        rootfs: File,
        runShell: (String) -> Unit,
        onProgress: (Float) -> Unit = {},
    ) {
        onProgress(0.05f)
        // Python + venv support via apk (no system pip into dist-packages).
        runShell(
            "apk add --no-cache python3 py3-pip py3-virtualenv py3-setuptools py3-wheel git || " +
                "apk add --no-cache python3 py3-pip py3-virtualenv git",
        )
        onProgress(0.20f)
        // Fresh venv so retries do not fight a half-installed tree.
        runShell(
            "rm -rf /$VENV_REL && " +
                "python3 -m venv /$VENV_REL",
        )
        onProgress(0.35f)
        runShell(
            "/$VENV_REL/bin/pip install --upgrade pip setuptools wheel",
        )
        onProgress(0.50f)
        // Prefer PyPI; fall back to upstream git (Aider-AI org).
        runShell(
            "/$VENV_REL/bin/pip install --no-cache-dir 'aider-chat' || " +
                "/$VENV_REL/bin/pip install --no-cache-dir " +
                "'git+https://github.com/Aider-AI/aider.git'",
        )
        onProgress(0.85f)
        runShell(
            "set -e; " +
                "mkdir -p /usr/local/bin; " +
                "test -x /$VENV_BIN_AIDER; " +
                "ln -sfn /$VENV_BIN_AIDER /usr/local/bin/aider; " +
                "command -v aider >/dev/null; " +
                "aider --version >/dev/null 2>&1 || true",
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
