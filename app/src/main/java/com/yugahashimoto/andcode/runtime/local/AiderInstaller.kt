package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Installs [Aider](https://github.com/Aider-AI/aider) into the shared Alpine/PRoot rootfs.
 *
 * Alpine marks system Python as PEP 668 "externally managed", so pip goes into a dedicated venv
 * under [VENV_REL]. Dependencies such as `tree-sitter` often lack musl wheels and need a C
 * compiler (`build-base`) to build from source — without it pip fails with `No such file or
 * directory: 'cc'`.
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
        // Python + venv + C toolchain for native wheels (tree-sitter et al. on musl/aarch64).
        runShell(
            "apk add --no-cache " +
                "python3 py3-pip py3-virtualenv py3-setuptools py3-wheel git " +
                "build-base python3-dev libffi-dev openssl-dev cargo rust || " +
                "apk add --no-cache python3 py3-pip py3-virtualenv git build-base python3-dev",
        )
        onProgress(0.18f)
        runShell(
            "rm -rf /$VENV_REL && " +
                "python3 -m venv /$VENV_REL",
        )
        onProgress(0.28f)
        runShell(
            "/$VENV_REL/bin/pip install --upgrade pip setuptools wheel",
        )
        onProgress(0.38f)
        // Prefer binary wheels when available; still allow source builds now that cc exists.
        runShell(
            "export CC=cc CXX=c++ " +
                "PATH=/usr/bin:/usr/local/bin:\$PATH; " +
                "/$VENV_REL/bin/pip install --no-cache-dir 'aider-chat' || " +
                "/$VENV_REL/bin/pip install --no-cache-dir " +
                "'git+https://github.com/Aider-AI/aider.git'",
        )
        onProgress(0.88f)
        runShell(
            "set -e; " +
                "mkdir -p /usr/local/bin; " +
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
