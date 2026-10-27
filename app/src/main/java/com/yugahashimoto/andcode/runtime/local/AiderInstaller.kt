package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Full Aider install into the **Debian** rootfs (same proot image Antigravity uses).
 *
 * Do **not** apt-install python3-dev / build-essential here: their postinst scripts
 * (fontconfig, debconf) break under PRoot. Debian rootfs already has python3; we only
 * add python3-venv + python3-pip with --no-install-recommends, then pip install aider-chat.
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
     * @param rootfs Debian bookworm rootfs (antigravity-rootfs), **not** Alpine.
     */
    fun install(
        rootfs: File,
        runShell: (String) -> Unit,
        onProgress: (Float) -> Unit = {},
    ) {
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/resolv.conf").writeText(
            "nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 9.9.9.9\n",
        )
        if (!File(rootfs, "etc/hosts").isFile) {
            File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        }

        onProgress(0.08f)
        // Minimal apt only — mirrors DebianRootfsInstaller flags. Never pull build-essential.
        runShell(
            "export DEBIAN_FRONTEND=noninteractive DEBCONF_NONINTERACTIVE_SEEN=true; " +
                "apt-get update -qq; " +
                "command -v python3 >/dev/null || " +
                "apt-get install -y -qq --no-install-recommends python3; " +
                "python3 -c 'import venv' 2>/dev/null || " +
                "apt-get install -y -qq --no-install-recommends python3-venv; " +
                "python3 -m pip --version >/dev/null 2>&1 || " +
                "apt-get install -y -qq --no-install-recommends python3-pip; " +
                "command -v git >/dev/null || " +
                "apt-get install -y -qq --no-install-recommends git ca-certificates",
        )

        onProgress(0.25f)
        runShell("rm -rf /$VENV_REL && python3 -m venv /$VENV_REL")
        runShell("/$VENV_REL/bin/pip install --upgrade pip setuptools wheel")

        onProgress(0.40f)
        runShell(
            "/$VENV_REL/bin/pip install --retries 5 --timeout 180 aider-chat || " +
                "/$VENV_REL/bin/pip install --retries 5 --timeout 180 " +
                "'git+https://github.com/Aider-AI/aider.git'",
        )

        onProgress(0.85f)
        runShell(
            "set -e; mkdir -p /usr/local/bin; " +
                "test -x /$VENV_BIN_AIDER; " +
                "ln -sfn /$VENV_BIN_AIDER /usr/local/bin/aider; " +
                "/$VENV_BIN_AIDER --version >/tmp/aider-version.out 2>/tmp/aider-version.err; " +
                "command -v aider >/dev/null",
        )

        val versionLine =
            File(rootfs, "tmp/aider-version.out").takeIf { it.isFile }?.readText()?.trim().orEmpty()
        File(rootfs, AiderManifest.MARKER_REL).apply {
            parentFile?.mkdirs()
            writeText("ok\n")
        }
        val ver =
            versionLine.lineSequence().firstOrNull()?.take(80)?.ifBlank { null }
                ?: AiderManifest.VERSION
        File(rootfs, AiderManifest.VERSION_REL).writeText(ver + "\n")
        onProgress(1f)
        require(isInstalledIn(rootfs)) { "Aider install finished but binary/marker missing in Debian rootfs" }
    }
}
