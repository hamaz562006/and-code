package com.yugahashimoto.andcode.runtime.local

/**
 * Pinned Hermes Agent Termux APT package (aarch64 / arm64-v8a only).
 *
 * Official install path: signed Termux repo → `pkg install hermes-agent`.
 * Docs: https://hermes-agent.nousresearch.com/docs/getting-started/termux
 *
 * The package is Android/bionic (not Alpine/musl), so AndCode installs it on the
 * host under the app files dir and runs `hermes` outside PRoot — same constraint
 * as other Termux-targeted CLIs.
 */
object HermesManifest {
    // Termux APT package versioning (0.27.1-canary.*) differs from the GitHub app tag (v0.21.x).
    // The installed CLI reports the app version via `hermes --version`; the constants below pin the
    // signed canary .deb from hermes-assets.nousresearch.com (stable channel is currently 404).

    const val VERSION = "0.27.1-canary.20260917131241"
    const val PACKAGE_VERSION = "0.27.1~canary.20260917131241-1"
    const val BINARY_NAME = "hermes"
    const val CHANNEL = "canary"
    const val MIN_FREE_BYTES = 400L * 1024L * 1024L

    private const val BASE =
        "https://hermes-assets.nousresearch.com/releases/termux/canary/pool/h/"

    const val DEB_NAME = "hermes-agent_${PACKAGE_VERSION}_aarch64.deb"
    const val DEB_URL = BASE + DEB_NAME
    const val DEB_SHA256 = "324397ce52887ce24f124e0396fde3974062d20451b0254613f9c3c5f4064fb4"
    const val DEB_SIZE_BYTES = 161_855_904L

    /** OpenAI-compatible API server (hermes gateway). Not OpenCode :4097. */
    const val API_PORT = 8642
    const val API_HOST = "127.0.0.1"
    const val API_KEY = "andcode-hermes-local"
    fun apiBaseUrl(): String = "http://$API_HOST:$API_PORT/"

    /** Relative install root under the app runtime directory. */
    const val INSTALL_DIR = "hermes"
}
