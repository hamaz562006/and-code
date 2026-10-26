package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Fast Aider install for Alpine/PRoot (not Termux).
 *
 * [infinityempire/aider-termux](https://github.com/infinityempire/aider-termux) works on Termux
 * because its `pip install aider` can use Android wheels / a more permissive Python. Inside this
 * app the guest is **Alpine musl** — the same command pulls `tree-sitter-language-pack` and tries
 * to compile it with cargo/maturin (hours / failure).
 *
 * We follow the pure-deps list from skoll43/aider-chat-termux-wheels but install **every** package
 * with `--no-deps` so pip never resolves into native build deps. Optional binary wheels only via
 * `--only-binary=:all:`.
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
        onProgress(0.05f)
        runShell(
            "apk add --no-cache python3 py3-pip py3-virtualenv py3-setuptools py3-wheel git " +
                "ca-certificates wget",
        )
        onProgress(0.12f)
        runShell("rm -rf /$VENV_REL && python3 -m venv /$VENV_REL")
        onProgress(0.18f)
        runShell("$VENV_PIP install --upgrade pip setuptools wheel")
        onProgress(0.24f)

        // Critical: --no-deps so grep-ast etc. do NOT pull tree-sitter-language-pack (cargo build).
        runShell(
            "wget -q -O /tmp/aider-pure-deps.txt '$PURE_DEPS_URL' && " +
                "$VENV_PIP install --no-cache-dir --no-deps -r /tmp/aider-pure-deps.txt",
        )
        onProgress(0.55f)

        // Musllinux wheels only — if missing, skip (do not fall back to source).
        runShell(
            "$VENV_PIP install --no-cache-dir --only-binary=:all: " +
                "pydantic-core jiter orjson pyyaml regex aiohttp yarl multidict " +
                "frozenlist propcache " +
                "2>/dev/null || " +
                "$VENV_PIP install --no-cache-dir --only-binary=:all: pydantic-core aiohttp pyyaml " +
                "2>/dev/null || true",
        )
        onProgress(0.68f)

        // Optional tree-sitter wheel only — never language-pack, never source.
        runShell(
            "$VENV_PIP install --no-cache-dir --only-binary=:all: tree-sitter 2>/dev/null || true",
        )
        onProgress(0.75f)

        // Aider application code only.
        runShell(
            "$VENV_PIP install --no-cache-dir --no-deps " +
                "'git+https://github.com/Aider-AI/aider.git' || " +
                "$VENV_PIP install --no-cache-dir --no-deps aider-chat",
        )
        onProgress(0.90f)

        runShell(
            "set -e; mkdir -p /usr/local/bin; " +
                "test -x /$VENV_BIN_AIDER; " +
                "ln -sfn /$VENV_BIN_AIDER /usr/local/bin/aider; " +
                "/usr/local/bin/aider --help >/dev/null 2>&1 || " +
                "/$VENV_BIN_AIDER --help >/dev/null 2>&1 || true; " +
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
