package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Fast Aider install for Alpine/PRoot.
 *
 * Pure-python pins come from skoll43's pure-deps list (`--no-deps` so tree-sitter-language-pack
 * never compiles). After `aider` is present, a short loop installs any still-missing import
 * (Pillow, mixpanel, …) until `aider --version` succeeds — that is the only install success
 * criterion.
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
        onProgress(0.48f)

        // Known binary / runtime imports (not always in pure-deps).
        runShell(
            "$VENV_PIP install --no-cache-dir --prefer-binary --retries 3 --timeout 90 " +
                "Pillow numpy mixpanel posthog pydantic pydantic-core aiohttp httpx openai " +
                "pyyaml regex orjson 2>/dev/null || " +
                "$VENV_PIP install --no-cache-dir --prefer-binary --retries 3 --timeout 90 " +
                "Pillow mixpanel pydantic aiohttp httpx openai || true",
        )
        onProgress(0.60f)

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
                // Repair missing imports until aider actually starts (max 12 modules).
                "i=0; " +
                "while [ \$i -lt 12 ]; do " +
                "  i=\$((i+1)); " +
                "  if /$VENV_BIN_AIDER --version >/tmp/aider-version.out 2>/tmp/aider-version.err; then " +
                "    break; " +
                "  fi; " +
                "  err=\$(cat /tmp/aider-version.err 2>/dev/null || true); " +
                "  mod=\$(printf '%s\\n' \"\$err\" | sed -n \"s/.*No module named '\\([^']*\\)'.*/\\1/p\" | head -n1); " +
                "  if [ -z \"\$mod\" ]; then " +
                "    mod=\$(printf '%s\\n' \"\$err\" | sed -n \"s/.*ModuleNotFoundError: No module named \\\"\\([^\\\"]*\\)\\\".*/\\1/p\" | head -n1); " +
                "  fi; " +
                "  if [ -z \"\$mod\" ]; then " +
                "    echo \"\$err\" >&2; exit 1; " +
                "  fi; " +
                "  pkg=\"\$mod\"; " +
                "  case \"\$mod\" in PIL) pkg=Pillow ;; cv2) pkg=opencv-python-headless ;; esac; " +
                "  echo \"Installing missing module: \$pkg\" >&2; " +
                "  $VENV_PIP install --no-cache-dir --prefer-binary --retries 3 --timeout 90 \"\$pkg\" || " +
                "  $VENV_PIP install --no-cache-dir --prefer-binary --retries 3 --timeout 90 \"\$(echo \"\$pkg\" | tr '[:upper:]' '[:lower:]')\" || true; " +
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
