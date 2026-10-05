package com.yugahashimoto.andcode.runtime.local

/**
 * Pinned release of the community Termux/Android aarch64 build of xAI Grok Build
 * (https://github.com/Duro02/grok-build-termux).
 *
 * Auth for headless use is API-key only (`XAI_API_KEY` from console.x.ai) — no browser login.
 *
 * The upstream binary is dynamically linked against NDK `libc++_shared.so` with
 * `RUNPATH=$ORIGIN/../lib`, so the installer also places Termux's libc++ next to it.
 */
object GrokBuildManifest {
    const val BINARY_NAME = "grok"
    const val VERSION = "1.0.45"
    const val TAG = "termux-v$VERSION"
    const val REPO = "Duro02/grok-build-termux"

    /** Only arm64 is published by the Termux port today. */
    private const val ARCHIVE_NAME = "grok-termux-aarch64-$VERSION.tar.gz"

    /** Termux libc++ package providing libc++_shared.so for aarch64. */
    const val LIBCPP_DEB_URL =
        "https://packages.termux.dev/apt/termux-main/pool/main/libc/libc++/libc++_30_aarch64.deb"
    const val LIBCPP_DEB_SHA256 = "53d0b84a7ba7459024257cb94d5b136fe13ef858567f65a8064b35950799f2ca"
    const val LIBCPP_SONAME = "libc++_shared.so"

    fun archiveUrl(): String = "https://github.com/$REPO/releases/download/$TAG/$ARCHIVE_NAME"

    fun sha256Url(): String = "${archiveUrl()}.sha256"
}
