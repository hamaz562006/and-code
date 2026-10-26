package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Fast Aider install for Alpine/PRoot.
 *
 * Pure-deps are installed with `--no-deps` so pip never pulls `tree-sitter-language-pack`.
 * Binary-ish runtime modules that Aider imports at startup (Pillow → `PIL`) are installed
 * explicitly afterward; without them `aider --version` crashes and the agent is unusable even
 * though the shim exists.
 */
object AiderInstaller {
    const val VENV_REL = "usr/local/lib/aider-venv"
    private const val VENV_BIN_AIDER = "$VENV_REL/bin/aider"
    private const val SHIM_REL = "usr/local/bin/aider"
    private const val VENV_PIP = "/$VENV_REL/bin/pip"
    private const val PURE_DEPS_URL =
        "https://raw.githubusercontent.com/skoll43/aider-chat-termux-wheels/main/pure-deps-pinned.txt"

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

        onProgress(0.04f)
        // py3-pillow provides PIL to the system; venv uses --system-site-packages so imports work.
        runShell(
            "apk add --no-cache python3 py3-pip py3-virtualenv py3-setuptools py3-wheel " +
                "py3-pillow py3-numpy git ca-certificates wget || " +
                "apk add --no-cache python3 py3-pip py3-virtualenv git ca-certificates wget",
        )
        onProgress(0.12f)
        runShell(
            "rm -rf /$VENV_REL && " +
                "python3 -m venv --system-site-packages /$VENV_REL",
        )
        onProgress(0.18f)
        runShell(
            "$VENV_PIP install --upgrade pip setuptools wheel --retries 5 --timeout 60 || true",
        )
        onProgress(0.24f)

        runShell(
            "set -e; ok=0; " +
                "for i in 1 2 3 4 5; do " +
                "  if wget -q -O /tmp/aider-pure-deps.txt '$PURE_DEPS_URL'; then ok=1; break; fi; " +
                "  sleep 2; " +
                "done; " +
                "if [ \"\$ok\" != 1 ]; then " +
                "  echo 'Network/DNS failed downloading Aider pure-deps list.' >&2; exit 1; " +
                "fi; " +
                "$VENV_PIP install --no-cache-dir --no-deps --retries 5 --timeout 60 " +
                "-r /tmp/aider-pure-deps.txt",
        )
        onProgress(0.50f)

        // Critical imports: Pillow (PIL), numpy. Prefer wheels; fall back to apk already done.
        runShell(
            "$VENV_PIP install --no-cache-dir --only-binary=:all: --retries 3 --timeout 60 " +
                "Pillow numpy 2>/dev/null || " +
                "$VENV_PIP install --no-cache-dir --prefer-binary --retries 3 --timeout 90 " +
                "Pillow || true",
        )
        onProgress(0.58f)

        runShell(
            "$VENV_PIP install --no-cache-dir --only-binary=:all: --retries 3 --timeout 60 " +
                "pydantic-core jiter orjson pyyaml regex aiohttp yarl multidict frozenlist " +
                "2>/dev/null || " +
                "$VENV_PIP install --no-cache-dir --only-binary=:all: --retries 3 --timeout 60 " +
                "pydantic-core aiohttp pyyaml 2>/dev/null || true",
        )
        onProgress(0.68f)

        runShell(
            "$VENV_PIP install --no-cache-dir --only-binary=:all: tree-sitter 2>/dev/null || true",
        )
        onProgress(0.74f)

        runShell(
            "$VENV_PIP install --no-cache-dir --no-deps --retries 5 --timeout 90 " +
                "'git+https://github.com/Aider-AI/aider.git' || " +
                "$VENV_PIP install --no-cache-dir --no-deps --retries 5 --timeout 90 aider-chat",
        )
        onProgress(0.88f)

        runShell(
            "set -e; mkdir -p /usr/local/bin; " +
                "test -x /$VENV_BIN_AIDER; " +
                "ln -sfn /$VENV_BIN_AIDER /usr/local/bin/aider; " +
                // Must actually import (catches missing PIL etc. before we mark installed).
                "/$VENV_BIN_AIDER --version >/tmp/aider-version.out 2>/tmp/aider-version.err || " +
                "{ echo 'aider --version failed:' >&2; cat /tmp/aider-version.err >&2; exit 1; }; " +
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
        require(isInstalledIn(rootfs)) { "Aider install finished but venv binary/shim not found" }
    }
}
