package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Fast Aider install for Alpine/PRoot.
 *
 * Never compiles `tree-sitter-language-pack` (maturin/cargo) — that hung/timed out on-device.
 * Missing imports are filled with **wheels only** (`--only-binary=:all:`); for language-pack
 * modules a tiny pure-Python stub is written so `aider --version` can import.
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

        onProgress(0.04f)
        runShell(
            "apk add --no-cache python3 py3-pip py3-virtualenv py3-setuptools py3-wheel " +
                "py3-pillow py3-numpy git ca-certificates wget || " +
                "apk add --no-cache python3 py3-pip py3-virtualenv git ca-certificates wget",
        )
        onProgress(0.12f)
        runShell("rm -rf /$VENV_REL && python3 -m venv --system-site-packages /$VENV_REL")
        onProgress(0.18f)
        runShell("$VENV_PIP install --upgrade pip setuptools wheel --retries 5 --timeout 60 || true")
        onProgress(0.24f)

        // Drop grep-ast from pure-deps — it pulls tree-sitter-language-pack at runtime.
        runShell(
            "set -e; ok=0; " +
                "for i in 1 2 3 4 5; do " +
                "  if wget -q -O /tmp/aider-pure-deps.txt '$PURE_DEPS_URL'; then ok=1; break; fi; " +
                "  sleep 2; " +
                "done; " +
                "if [ \"\$ok\" != 1 ]; then " +
                "  echo 'Network/DNS failed downloading Aider pure-deps list.' >&2; exit 1; " +
                "fi; " +
                "grep -viE '^(grep-ast|tree-sitter)' /tmp/aider-pure-deps.txt > /tmp/aider-pure-deps-filtered.txt; " +
                "$VENV_PIP install --no-cache-dir --no-deps --retries 5 --timeout 60 " +
                "-r /tmp/aider-pure-deps-filtered.txt",
        )
        onProgress(0.48f)

        runShell(
            "$VENV_PIP install --no-cache-dir --only-binary=:all: --retries 3 --timeout 90 " +
                "Pillow numpy mixpanel posthog pydantic pydantic-core aiohttp httpx openai " +
                "pyyaml regex orjson audioop-lts 2>/dev/null || " +
                "$VENV_PIP install --no-cache-dir --prefer-binary --retries 3 --timeout 90 " +
                "Pillow mixpanel pydantic aiohttp httpx openai audioop-lts || true",
        )
        onProgress(0.58f)

        // Optional tree-sitter core wheel only — never language-pack source builds.
        runShell(
            "$VENV_PIP install --no-cache-dir --only-binary=:all: --retries 2 --timeout 60 " +
                "tree-sitter 2>/dev/null || true",
        )
        onProgress(0.62f)

        // Stub language-pack so imports succeed without compiling Rust.
        runShell(
            "SITE=\$($VENV_PY -c 'import site; print(site.getsitepackages()[0])'); " +
                "mkdir -p \"\$SITE/tree_sitter_language_pack\"; " +
                "printf '%s\\n' " +
                "'# Stub: full language pack is not built on Alpine/musl mobile installs.' " +
                "'def get_language(name):' " +
                "'    raise LookupError(\"tree-sitter language %%r unavailable\" %% (name,))' " +
                "'def get_parser(name):' " +
                "'    raise LookupError(\"tree-sitter parser %%r unavailable\" %% (name,))' " +
                "> \"\$SITE/tree_sitter_language_pack/__init__.py\"",
        )
        onProgress(0.66f)

        runShell(
            "$VENV_PIP install --no-cache-dir --no-deps --retries 5 --timeout 90 " +
                "'git+https://github.com/Aider-AI/aider.git' || " +
                "$VENV_PIP install --no-cache-dir --no-deps --retries 5 --timeout 90 aider-chat",
        )
        onProgress(0.78f)

        runShell(
            "set -e; mkdir -p /usr/local/bin; " +
                "test -x /$VENV_BIN_AIDER; " +
                "ln -sfn /$VENV_BIN_AIDER /usr/local/bin/aider; " +
                "i=0; " +
                "while [ \$i -lt 12 ]; do " +
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
                "    tree_sitter_language_pack|tree_sitter_languages) " +
                "      SITE=\$($VENV_PY -c 'import site; print(site.getsitepackages()[0])'); " +
                "      mkdir -p \"\$SITE/\$mod\"; " +
                "      echo 'def get_language(n): raise LookupError(n)' > \"\$SITE/\$mod/__init__.py\"; " +
                "      echo \"Stubbed \$mod\" >&2; continue ;; " +
                "  esac; " +
                "  echo \"Installing missing module (wheel only): \$pkg\" >&2; " +
                "  $VENV_PIP install --no-cache-dir --only-binary=:all: --retries 2 --timeout 60 \"\$pkg\" || " +
                "  $VENV_PIP install --no-cache-dir --only-binary=:all: --retries 2 --timeout 60 " +
                "\"\$(echo \"\$pkg\" | tr '_' '-')\" || true; " +
                "done; " +
                "if ! /$VENV_BIN_AIDER --version >/tmp/aider-version.out 2>/tmp/aider-version.err; then " +
                "  echo 'aider --version failed after dependency repair:' >&2; " +
                "  cat /tmp/aider-version.err >&2; exit 1; " +
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
        require(isInstalledIn(rootfs)) { "Aider install finished but venv binary/shim not found" }
    }
}
