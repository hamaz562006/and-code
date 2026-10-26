package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Installs [Aider](https://github.com/paul-gauthier/aider) into the shared Alpine/PRoot rootfs
 * following the Termux-oriented flow from infinityempire/aider-termux (python + pip + aider).
 *
 * Independent of OpenCode — only uses the shared rootfs and [LocalRuntimeCommandRunner] shell.
 */
object AiderInstaller {
    fun isInstalledIn(rootfs: File): Boolean {
        val candidates =
            listOf(
                File(rootfs, "usr/bin/aider"),
                File(rootfs, "usr/local/bin/aider"),
                File(rootfs, "root/.local/bin/aider"),
            )
        if (candidates.any { it.isFile }) return true
        // Fallback: marker + python entrypoint script under site-packages bin
        val marker = File(rootfs, AiderManifest.MARKER_REL)
        if (!marker.isFile) return false
        return File(rootfs, "usr/bin/aider").isFile ||
            File(rootfs, "usr/local/bin/aider").isFile ||
            candidates.any { it.isFile }
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
        // Alpine packages for python tooling
        runShell(
            "apk add --no-cache python3 py3-pip py3-setuptools py3-wheel git build-base linux-headers || " +
                "apk add --no-cache python3 py3-pip git",
        )
        onProgress(0.25f)
        runShell("python3 -m pip install --upgrade pip setuptools wheel || true")
        onProgress(0.40f)
        // Prefer PyPI package; fall back to git like aider-termux install.sh
        runShell(
            "python3 -m pip install --no-cache-dir 'aider-chat' || " +
                "python3 -m pip install --no-cache-dir 'git+https://github.com/paul-gauthier/aider.git'",
        )
        onProgress(0.85f)
        // Ensure a stable shim on PATH
        val localBin = File(rootfs, "usr/local/bin")
        localBin.mkdirs()
        val shim = File(localBin, AiderManifest.BINARY_NAME)
        if (!File(rootfs, "usr/bin/aider").isFile && !shim.isFile) {
            // try find site-packages scripts
            runShell(
                "AID=\$(command -v aider || true); " +
                    "if [ -n \"\$AID\" ]; then ln -sf \"\$AID\" /usr/local/bin/aider; fi",
            )
        }
        File(rootfs, AiderManifest.MARKER_REL).apply {
            parentFile?.mkdirs()
            writeText("ok\n")
        }
        File(rootfs, AiderManifest.VERSION_REL).writeText(AiderManifest.VERSION + "\n")
        onProgress(1f)
        require(isInstalledIn(rootfs)) { "Aider install finished but binary/marker not found" }
    }
}
