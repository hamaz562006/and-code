package com.yugahashimoto.andcode.runtime.local

/**
 * Pinned [IChouChiang/cline-termux] release (Android aarch64 / Termux port).
 *
 * Closest AndCode agents: Hermes and Grok Build — host-side Termux/bionic binaries,
 * not Alpine or Debian proot.
 *
 * https://github.com/IChouChiang/cline-termux
 */
object ClineManifest {
    const val VERSION = "3.0.74-termux.1"
    const val BINARY_NAME = "cline"
    const val INSTALL_DIR = "cline"

    const val ARCHIVE_NAME = "cline-termux-aarch64-v$VERSION.tar.gz"
    const val ARCHIVE_URL =
        "https://github.com/IChouChiang/cline-termux/releases/download/v$VERSION/$ARCHIVE_NAME"
    const val ARCHIVE_SHA256 = "c7905f1c8bd0706f6db51b9e2b2477e8e53e66b34760ab0128a06f9830789380"

    const val BUN_FFI_VERSION = "1.4.0-canary.1-55f6c899f"
    const val BUN_FFI_ARCHIVE = "bun-android-ffi-aarch64-v$BUN_FFI_VERSION.tar.gz"
    const val BUN_FFI_URL =
        "https://github.com/IChouChiang/bun-android-ffi/releases/download/v$BUN_FFI_VERSION/$BUN_FFI_ARCHIVE"
}
