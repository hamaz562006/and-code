package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Aider install for Alpine/PRoot, following the **skoll43/aider-chat-termux-wheels** order:
 *
 * 1. System packages (numpy/pillow via apk when available)
 * 2. venv --system-site-packages
 * 3. Stub `tree_sitter_language_pack` (Termux uses a prebuilt wheel; we cannot build it on musl)
 * 4. pure-deps-pinned.txt with **--no-deps** (never resolve into language-pack)
 * 5. aider application with **--no-deps**
 * 6. audioop-lts (Python 3.13+)
 * 7. `aider --version` must succeed
 *
 * Termux android_24 wheels are **not** used — they do not load on Alpine musl Python.
 * @see https://github.com/skoll43/aider-chat-termux-wheels
 * @see https://github.com/infinityempire/aider-termux
 */
object AiderInstaller {
    const val VENV_REL = "usr/local/lib/aider-venv"
    private const val VENV_BIN_AIDER = "$VENV_REL/bin/aider"
    private const val SHIM_REL = "usr/local/bin/aider"
    private const val VENV_PIP = "/$VENV_REL/bin/pip"
    private const val VENV_PY = "/$VENV_REL/bin/python"
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

        // --- skoll43 step 1 equivalent: system packages ---
        onProgress(0.05f)
        runShell(
            "apk add --no-cache python3 py3-pip py3-virtualenv py3-setuptools py3-wheel " +
                "py3-pillow py3-numpy git ca-certificates wget curl || " +
                "apk add --no-cache python3 py3-pip py3-virtualenv py3-setuptools py3-wheel " +
                "git ca-certificates wget curl",
        )

        // --- skoll43 step 2: venv with system-site-packages ---
        onProgress(0.12f)
        runShell("rm -rf /$VENV_REL && python3 -m venv --system-site-packages /$VENV_REL")
        runShell("$VENV_PIP install --upgrade pip setuptools wheel --retries 5 --timeout 60 || true")

        // --- stub language-pack BEFORE any pure-dep that imports it ---
        onProgress(0.18f)
        runShell(stubLanguagePackShell())

        // --- skoll43 step 5: pure-deps with --no-deps (never resolve) ---
        onProgress(0.22f)
        runShell(
            "set -e; ok=0; " +
                "for i in 1 2 3 4 5; do " +
                "  if wget -q -O /tmp/aider-pure-deps.txt '$PURE_DEPS_URL'; then ok=1; break; fi; " +
                "  sleep 2; " +
                "done; " +
                "if [ \"\$ok\" != 1 ]; then " +
                "  echo 'Failed to download pure-deps-pinned.txt (network/DNS).' >&2; exit 1; " +
                "fi; " +
                // Keep grep-ast (pure py) but never let pip resolve its deps.
                "$VENV_PIP install --no-cache-dir --no-deps --retries 5 --timeout 120 " +
                "-r /tmp/aider-pure-deps.txt",
        )

        // Binary extensions — pin pydantic-core to the version pure-deps pydantic expects
        // (skoll43 wheel bundle uses 2.41.5; unpinned pip was pulling 2.50.1 → SystemError).
        onProgress(0.50f)
        runShell(
            "set -e; " +
                "$VENV_PIP install --no-cache-dir --only-binary=:all: --retries 3 --timeout 90 " +
                "'pydantic-core==2.41.5' 2>/dev/null || " +
                "$VENV_PIP install --no-cache-dir --only-binary=:all: --force-reinstall --retries 3 --timeout 90 " +
                "pydantic pydantic-core; " +
                "$VENV_PIP install --no-cache-dir --only-binary=:all: --retries 2 --timeout 60 " +
                "jiter orjson pyyaml regex aiohttp yarl multidict frozenlist propcache " +
                "tree-sitter 2>/dev/null || true",
        )

        // Pillow/numpy if apk did not provide them
        runShell(
            "$VENV_PIP install --no-cache-dir --only-binary=:all: --retries 2 --timeout 60 " +
                "Pillow numpy 2>/dev/null || true",
        )

        // --- skoll43 step 4: aider app, --no-deps ---
        onProgress(0.62f)
        runShell(
            "$VENV_PIP install --no-cache-dir --no-deps --retries 5 --timeout 120 " +
                "'git+https://github.com/Aider-AI/aider.git' || " +
                "$VENV_PIP install --no-cache-dir --no-deps --retries 5 --timeout 120 aider-chat",
        )

        // --- skoll43 step 6: audioop-lts ---
        onProgress(0.75f)
        runShell(
            "$VENV_PIP install --no-cache-dir --no-deps --retries 3 --timeout 60 audioop-lts || " +
                "$VENV_PIP install --no-cache-dir --prefer-binary --retries 3 --timeout 60 audioop-lts",
        )

        // Re-assert stub (pip must never replace it with a source build).
        runShell(stubLanguagePackShell())

        // --- skoll43 step 7: verify; fill missing pure modules with --no-deps only ---
        onProgress(0.82f)
        runShell(
            "set -e; mkdir -p /usr/local/bin; " +
                "test -x /$VENV_BIN_AIDER; " +
                "ln -sfn /$VENV_BIN_AIDER /usr/local/bin/aider; " +
                "i=0; " +
                "while [ \$i -lt 16 ]; do " +
                "  i=\$((i+1)); " +
                "  if /$VENV_BIN_AIDER --version >/tmp/aider-version.out 2>/tmp/aider-version.err; then break; fi; " +
                "  err=\$(cat /tmp/aider-version.err 2>/dev/null || true); " +
                "  mod=\$(printf '%s\\n' \"\$err\" | sed -n \"s/.*No module named '\\([^']*\\)'.*/\\1/p\" | head -n1); " +
                "  if [ -z \"\$mod\" ]; then " +
                "    mod=\$(printf '%s\\n' \"\$err\" | sed -n \"s/.*No module named \\\"\\([^\\\"]*\\)\\\".*/\\1/p\" | head -n1); " +
                "  fi; " +
                "  if [ -z \"\$mod\" ]; then echo \"\$err\" >&2; exit 1; fi; " +
                "  pkg=\"\$mod\"; " +
                "  case \"\$mod\" in " +
                "    PIL) pkg=Pillow ;; " +
                "    audioop) pkg=audioop-lts ;; " +
                "    cv2) pkg=opencv-python-headless ;; " +
                "    tree_sitter) pkg=tree-sitter ;; " +
                "    pydantic_core) pkg='pydantic-core==2.41.5' ;; " +
                "    tree_sitter_language_pack|tree_sitter_languages) " +
                "      SITE=\$($VENV_PY -c 'import site; print(site.getsitepackages()[0])'); " +
                "      mkdir -p \"\$SITE/\$mod\"; " +
                "      printf 'def get_language(n):\\n    raise LookupError(n)\\ndef get_parser(n):\\n    raise LookupError(n)\\n' " +
                "        > \"\$SITE/\$mod/__init__.py\"; " +
                "      echo \"stubbed \$mod\" >&2; continue ;; " +
                "  esac; " +
                "  echo \"Installing missing pure/wheel module: \$pkg\" >&2; " +
                "  if [ \"\$mod\" = pydantic_core ]; then " +
                "    $VENV_PIP install --no-cache-dir --only-binary=:all: --retries 3 --timeout 90 'pydantic-core==2.41.5' || " +
                "    $VENV_PIP install --no-cache-dir --only-binary=:all: --force-reinstall --retries 3 --timeout 90 pydantic pydantic-core || true; " +
                "  else " +
                "    $VENV_PIP install --no-cache-dir --no-deps --retries 3 --timeout 90 \"\$pkg\" || " +
                "    $VENV_PIP install --no-cache-dir --only-binary=:all: --retries 2 --timeout 60 \"\$pkg\" || " +
                "    $VENV_PIP install --no-cache-dir --no-deps --retries 2 --timeout 60 " +
                "\"\$(echo \"\$pkg\" | tr '_' '-')\" || true; " +
                "  fi; " +
                "done; " +
                "if ! /$VENV_BIN_AIDER --version >/tmp/aider-version.out 2>/tmp/aider-version.err; then " +
                "  echo 'aider --version failed:' >&2; cat /tmp/aider-version.err >&2; exit 1; " +
                "fi; " +
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
        require(isInstalledIn(rootfs)) { "Aider install finished but marker/shim missing" }
    }

    /** Pure-Python stand-in for the Termux prebuilt language-pack wheel. */
    private fun stubLanguagePackShell(): String =
        "SITE=\$($VENV_PY -c 'import site; print(site.getsitepackages()[0])'); " +
            "mkdir -p \"\$SITE/tree_sitter_language_pack\"; " +
            "printf '%s\\n' " +
            "'# Alpine/musl stub — Termux uses a prebuilt wheel (skoll43).' " +
            "'def get_language(name):' " +
            "'    raise LookupError(\"tree-sitter language unavailable: %%s\" %% (name,))' " +
            "'def get_parser(name):' " +
            "'    raise LookupError(\"tree-sitter parser unavailable: %%s\" %% (name,))' " +
            "> \"\$SITE/tree_sitter_language_pack/__init__.py\""
}
