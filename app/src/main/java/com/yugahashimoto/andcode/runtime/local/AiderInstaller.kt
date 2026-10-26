package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Installs Aider into the Alpine/PRoot rootfs **without compiling native code on-device**.
 *
 * Termux guides (infinityempire/aider-termux, skoll43/aider-chat-termux-wheels) rely on
 * Termux apt + android_24 wheels — those wheels do **not** load inside this app's Alpine musl
 * Python. Compiling `tree-sitter-language-pack` on-phone hung for hours.
 *
 * Strategy (fast, Alpine-compatible):
 * 1. venv (PEP 668)
 * 2. pure-python deps (skoll43 pure-deps-pinned.txt)
 * 3. binary-preferring wheels from PyPI where musllinux exists (pydantic, aiohttp, …)
 * 4. `aider-chat` with `--no-deps` from git main
 * 5. optional `tree-sitter` wheel only — never build language-pack from source
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
        // No build-base / cargo — we must not compile tree-sitter on-device.
        runShell(
            "apk add --no-cache python3 py3-pip py3-virtualenv py3-setuptools py3-wheel git ca-certificates",
        )
        onProgress(0.12f)
        runShell("rm -rf /$VENV_REL && python3 -m venv /$VENV_REL")
        onProgress(0.18f)
        runShell("$VENV_PIP install --upgrade pip setuptools wheel")
        onProgress(0.25f)

        // Pure-python pins from the Termux wheels project (no binary builds).
        runShell(
            "wget -q -O /tmp/aider-pure-deps.txt '$PURE_DEPS_URL' && " +
                "$VENV_PIP install --no-cache-dir -r /tmp/aider-pure-deps.txt",
        )
        onProgress(0.55f)

        // Binary deps that usually have musllinux wheels on PyPI — never force source builds.
        runShell(
            "$VENV_PIP install --no-cache-dir --prefer-binary --only-binary=:all: " +
                "pydantic-core jiter orjson pyyaml regex aiohttp yarl multidict frozenlist " +
                "2>/dev/null || " +
                "$VENV_PIP install --no-cache-dir --prefer-binary " +
                "pydantic pydantic-core aiohttp pyyaml",
        )
        onProgress(0.70f)

        // Optional tree-sitter wheel only (skip if no musl wheel — aider still runs, weaker repo-map).
        runShell(
            "$VENV_PIP install --no-cache-dir --prefer-binary --only-binary=:all: tree-sitter " +
                "2>/dev/null || true",
        )
        onProgress(0.78f)

        // Aider itself (pure Python) — no dependency resolver pulling language-pack source builds.
        runShell(
            "$VENV_PIP install --no-cache-dir --no-deps " +
                "'git+https://github.com/Aider-AI/aider.git' || " +
                "$VENV_PIP install --no-cache-dir --no-deps 'aider-chat'",
        )
        onProgress(0.92f)

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
