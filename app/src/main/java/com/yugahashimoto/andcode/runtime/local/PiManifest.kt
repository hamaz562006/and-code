package com.yugahashimoto.andcode.runtime.local

import java.io.File

data class PiAsset(
    val name: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
)

/** Pinned official earendil-works/pi standalone release metadata. */
object PiManifest {
    const val VERSION = "0.87.1"
    const val BINARY_NAME = "pi"
    const val MIN_FREE_BYTES = 150L * 1024L * 1024L
    private const val BASE = "https://github.com/earendil-works/pi/releases/download/v0.87.1/"

    val arm64 =
        PiAsset(
            name = "pi-linux-arm64.tar.gz",
            url = BASE + "pi-linux-arm64.tar.gz",
            sha256 = "364b4a9f8491450b27a4857d4e3c780dbaf696790821c176a873e860cbbc3b89",
            sizeBytes = 42_217_308L,
        )
    val x64 =
        PiAsset(
            name = "pi-linux-x64.tar.gz",
            url = BASE + "pi-linux-x64.tar.gz",
            sha256 = "80d78dd62d50049a006b981d994c61255bcc10e730b0c278d4ea0a755909764c",
            sizeBytes = 42_120_827L,
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
