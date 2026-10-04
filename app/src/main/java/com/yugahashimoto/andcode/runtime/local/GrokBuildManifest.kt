package com.yugahashimoto.andcode.runtime.local

/**
 * Pinned release of the community Termux/Android aarch64 build of xAI Grok Build
 * (https://github.com/Duro02/grok-build-termux).
 *
 * Auth for headless use is API-key only (`XAI_API_KEY` from console.x.ai) — no browser login.
 */
object GrokBuildManifest {
    const val BINARY_NAME = "grok"
    const val VERSION = "1.0.45"
    const val TAG = "termux-v$VERSION"
    const val REPO = "Duro02/grok-build-termux"

    /** Only arm64 is published by the Termux port today. */
    private const val ARCHIVE_NAME = "grok-termux-aarch64-$VERSION.tar.gz"

    fun archiveUrl(): String = "https://github.com/$REPO/releases/download/$TAG/$ARCHIVE_NAME"

    fun sha256Url(): String = "${archiveUrl()}.sha256"
}
