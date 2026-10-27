package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Aider for Alpine/PRoot.
 *
 * Language-pack stub must be **valid Python** (grep-ast imports get_language at import time).
 * No %% / broken format strings — that caused SyntaxError on device.
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

        onProgress(0.05f)
        runShell(
            "apk add --no-cache python3 py3-pip py3-virtualenv py3-setuptools py3-wheel " +
                "py3-yaml py3-pillow py3-numpy py3-cffi py3-regex git ca-certificates wget curl || " +
                "apk add --no-cache python3 py3-pip py3-virtualenv py3-setuptools py3-wheel " +
                "git ca-certificates wget curl",
        )

        onProgress(0.12f)
        runShell("rm -rf /$VENV_REL && python3 -m venv --system-site-packages /$VENV_REL")
        runShell("$VENV_PIP install --upgrade pip setuptools wheel --retries 5 --timeout 60 || true")

        onProgress(0.18f)
        runShell(stubLanguagePackShell())

        onProgress(0.22f)
        runShell(
            "set -e; ok=0; " +
                "for i in 1 2 3 4 5; do " +
                "  if wget -q -O /tmp/aider-pure-deps.txt '$PURE_DEPS_URL'; then ok=1; break; fi; " +
                "  sleep 2; " +
                "done; " +
                "if [ \"\$ok\" != 1 ]; then echo 'Failed to download pure-deps-pinned.txt' >&2; exit 1; fi; " +
                "$VENV_PIP install --no-cache-dir --no-deps --retries 5 --timeout 120 -r /tmp/aider-pure-deps.txt",
        )

        onProgress(0.48f)
        runShell(
            "$VENV_PIP install --no-cache-dir --only-binary=:all: --retries 3 --timeout 90 " +
                "'pydantic-core==2.41.5' 2>/dev/null || " +
                "$VENV_PIP install --no-cache-dir --only-binary=:all: --force-reinstall --retries 3 --timeout 90 " +
                "pydantic pydantic-core || true",
        )
        runShell(
            "$VENV_PIP install --no-cache-dir --only-binary=:all: --retries 2 --timeout 60 " +
                "PyYAML jiter orjson regex aiohttp yarl multidict frozenlist propcache cffi " +
                "psutil tree-sitter 2>/dev/null || " +
                "$VENV_PIP install --no-cache-dir --only-binary=:all: --retries 2 --timeout 60 PyYAML psutil 2>/dev/null || true",
        )

        onProgress(0.60f)
        runShell(
            "$VENV_PIP install --no-cache-dir --no-deps --retries 5 --timeout 120 " +
                "'git+https://github.com/Aider-AI/aider.git' || " +
                "$VENV_PIP install --no-cache-dir --no-deps --retries 5 --timeout 120 aider-chat",
        )

        onProgress(0.72f)
        runShell(
            "$VENV_PIP install --no-cache-dir --no-deps --retries 3 --timeout 60 audioop-lts || " +
                "$VENV_PIP install --no-cache-dir --prefer-binary --retries 3 --timeout 60 audioop-lts || true",
        )
        runShell(stubLanguagePackShell())

        onProgress(0.80f)
        runShell(
            "set -e; mkdir -p /usr/local/bin; " +
                "test -x /$VENV_BIN_AIDER; " +
                "ln -sfn /$VENV_BIN_AIDER /usr/local/bin/aider; " +
                "i=0; " +
                "while [ \$i -lt 20 ]; do " +
                "  i=\$((i+1)); " +
                "  if /$VENV_BIN_AIDER --version >/tmp/aider-version.out 2>/tmp/aider-version.err; then break; fi; " +
                "  err=\$(cat /tmp/aider-version.err 2>/dev/null || true); " +
                "  mod=\$(printf '%s\\n' \"\$err\" | sed -n \"s/.*No module named '\\([^']*\\)'.*/\\1/p\" | head -n1); " +
                "  if [ -z \"\$mod\" ]; then " +
                "    mod=\$(printf '%s\\n' \"\$err\" | sed -n \"s/.*No module named \\\"\\([^\\\"]*\\)\\\".*/\\1/p\" | head -n1); " +
                "  fi; " +
                "  if [ -z \"\$mod\" ]; then " +
                "    if echo \"\$err\" | grep -q SyntaxError; then " +
                "      " + stubLanguagePackShell() + "; " +
                "      continue; " +
                "    fi; " +
                "    echo \"\$err\" >&2; exit 1; " +
                "  fi; " +
                "  pkg=\"\$mod\"; " +
                "  case \"\$mod\" in " +
                "    yaml) pkg=PyYAML ;; " +
                "    PIL|Pillow) pkg=Pillow ;; " +
                "    audioop) pkg=audioop-lts ;; " +
                "    cv2) pkg=opencv-python-headless ;; " +
                "    tree_sitter) pkg=tree-sitter ;; " +
                "    pydantic_core) pkg='pydantic-core==2.41.5' ;; " +
                "    tree_sitter_language_pack|tree_sitter_languages) " +
                "      " + stubLanguagePackShell() + "; " +
                "      echo stubbed_\$mod >&2; continue ;; " +
                "  esac; " +
                "  echo \"Installing: \$pkg (for import \$mod)\" >&2; " +
                "  if [ \"\$mod\" = yaml ]; then " +
                "    apk add --no-cache py3-yaml 2>/dev/null || true; " +
                "    $VENV_PIP install --no-cache-dir --only-binary=:all: --retries 3 --timeout 60 PyYAML || " +
                "    $VENV_PIP install --no-cache-dir --prefer-binary --retries 3 --timeout 60 PyYAML || true; " +
                "  elif [ \"\$mod\" = pydantic_core ]; then " +
                "    $VENV_PIP install --no-cache-dir --only-binary=:all: --retries 3 --timeout 90 'pydantic-core==2.41.5' || " +
                "    $VENV_PIP install --no-cache-dir --only-binary=:all: --force-reinstall pydantic pydantic-core || true; " +
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

    /**
     * Writes a minimal valid module so `from tree_sitter_language_pack import get_language`
     * succeeds. Repo-map AST features stay unavailable on Alpine without a real wheel.
     */
    private fun stubLanguagePackShell(): String =
        "SITE=\$($VENV_PY -c 'import site; print(site.getsitepackages()[0])'); " +
            "mkdir -p \"\$SITE/tree_sitter_language_pack\"; " +
            "printf '%s\\n' " +
            "'def get_language(name):' " +
            "'    raise LookupError(\"tree-sitter language unavailable\")' " +
            "'def get_parser(name):' " +
            "'    raise LookupError(\"tree-sitter parser unavailable\")' " +
            "> \"\$SITE/tree_sitter_language_pack/__init__.py\""
}
