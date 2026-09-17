package com.yugahashimoto.andcode.runtime.local

import java.io.File

data class PiAsset(
    val name: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

/** Pinned official earendil-works/pi release metadata. */
object PiManifest {
    const val VERSION = "0.85.1"
    const val BINARY_NAME = "pi"
    const val MIN_FREE_BYTES = 180L * 1024L * 1024L
    private const val BASE = "https://github.com/earendil-works/pi/releases/download/v$VERSION/"

    val arm64 =
        PiAsset(
            name = "pi-linux-arm64.tar.gz",
            url = BASE + "pi-linux-arm64.tar.gz",
            sha256 = "042d20ae885ee4f3b102815f3280b962c377b2e9fb44de4037908cc530eae4d4",
            sizeBytes = 42_628_180L,
        )

    val x64 =
        PiAsset(
            name = "pi-linux-x64.tar.gz",
            url = BASE + "pi-linux-x64.tar.gz",
            sha256 = "494e498f47d74d21f40b3386f6a5e921a3d49531a169cab55bbdaca0ea1fe25a",
            sizeBytes = 42_560_927L,
        )

    fun assetFor(abi: String): PiAsset =
        when (abi) {
            "arm64-v8a", "aarch64" -> arm64
            "x86_64", "amd64" -> x64
            else -> error("Pi official Linux release does not support ABI $abi")
        }

    fun verifyArchive(
        file: File,
        abi: String,
    ) {
        RuntimeArchive.verifySha256(file, assetFor(abi).sha256)
    }
}
