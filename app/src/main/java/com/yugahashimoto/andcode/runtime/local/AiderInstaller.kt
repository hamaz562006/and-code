package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Full Aider install into the **Debian** rootfs (same proot image Antigravity uses).
 *
 * Debian bookworm needs the `python3-venv` (or `python3.11-venv`) package for ensurepip.
 * Avoid python3-dev / build-essential — their postinst scripts break under PRoot.
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
        // Always install venv package — `import venv` can succeed without ensurepip.
        runShell(
            "export DEBIAN_FRONTEND=noninteractive DEBCONF_NONINTERACTIVE_SEEN=true; " +
                "apt-get update -qq; " +
                "apt-get install -y -qq --no-install-recommends " +
                "python3 python3-pip python3-venv python3.11-venv git ca-certificates || " +
                "apt-get install -y -qq --no-install-recommends " +
                "python3 python3-pip python3-venv git ca-certificates",
        )

        onProgress(0.25f)
        // Prefer normal venv; if ensurepip still missing, create without pip and bootstrap.
        runShell(
            "set -e; rm -rf /$VENV_REL; " +
                "if python3 -m venv /$VENV_REL; then " +
                "  true; " +
                "else " +
                "  python3 -m venv --without-pip /$VENV_REL; " +
                "  wget -q -O /tmp/get-pip.py https://bootstrap.pypa.io/get-pip.py; " +
                "  /$VENV_REL/bin/python /tmp/get-pip.py; " +
                "fi; " +
                "test -x /$VENV_REL/bin/pip",
        )
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
