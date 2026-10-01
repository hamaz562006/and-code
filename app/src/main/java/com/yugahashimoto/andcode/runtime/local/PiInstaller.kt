package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * Downloads and installs the Pi coding agent into the shared Alpine rootfs,
 * backed by the official earendil-works/pi standalone release archive.
 */
object PiInstaller {
    const val PI_VERSION = PiManifest.VERSION
    const val PI_BINARY = PiManifest.BINARY_NAME
    private const val BIN_DIR = "usr/local/bin"
    private const val VERSION_MARKER = "usr/local/share/and-code/pi-version"

    fun isInstalledIn(rootfs: File): Boolean {
        val binary = File(rootfs, "$BIN_DIR/$PI_BINARY")
        return binary.isFile && (binary.canExecute() || binary.canRead())
    }

    fun installedVersion(rootfs: File): String? =
        runCatching {
            File(rootfs, VERSION_MARKER).readText().trim().takeIf(String::isNotEmpty)
        }.getOrNull() ?: if (isInstalledIn(rootfs)) PI_VERSION else null

    internal fun writeInstalledVersion(
        rootfs: File,
        version: String,
    ) {
        runCatching {
            File(rootfs, VERSION_MARKER).apply {
                parentFile?.mkdirs()
                writeText("$version\n")
            }
        }
    }

    suspend fun install(
        rootfs: File,
        abi: String,
        runtimeDirectory: File,
        accessCoordinator: LocalRuntimeAccessCoordinator,
        httpClient: OkHttpClient = OkHttpClient(),
        downloader: VerifiedRuntimeDownloader = VerifiedRuntimeDownloader(httpClient),
        onProgress: (Float) -> Unit = {},
    ): String =
        withContext(Dispatchers.IO) {
            require(runtimeDirectory.usableSpace >= PiManifest.MIN_FREE_BYTES) {
                "Pi needs at least 150 MB free space (available ${runtimeDirectory.usableSpace} bytes)"
            }
            val asset = PiManifest.assetFor(abi)
            val cache = File(runtimeDirectory, "cache").apply { mkdirs() }
            val archive = File(cache, "pi-${PiManifest.VERSION}-${asset.name}")
            downloader.download(asset.url, archive, asset.sha256, asset.sizeBytes) { progress ->
                progress?.let { onProgress(it * 0.75f) }
            }
            accessCoordinator.write {
                val extraction = File(runtimeDirectory, "pi-extract-${System.nanoTime()}").apply { mkdirs() }
                try {
                    archive.inputStream().use { RuntimeArchive.extractTarGz(it, extraction) }
                    val source =
                        extraction.walkTopDown().firstOrNull { it.isFile && (it.name == PI_BINARY || it.name == PiManifest.BINARY_NAME) }
                            ?: error("Official Pi standalone release archive did not contain a pi binary")
                    val destination = File(rootfs, "$BIN_DIR/$PI_BINARY")
                    destination.parentFile?.mkdirs()
                    val candidate = File(destination.parentFile, "$PI_BINARY.new-${System.nanoTime()}")
                    val backup = File(destination.parentFile, "$PI_BINARY.rollback")
                    runCatching {
                        source.copyTo(candidate, overwrite = true)
                        require(candidate.setExecutable(true, false) || candidate.canExecute()) {
                            "Unable to mark pi executable"
                        }
                        candidate.setReadable(true, false)
                        backup.delete()
                        if (destination.exists()) {
                            require(destination.renameTo(backup)) { "Unable to stage previous pi binary" }
                        }
                        require(candidate.renameTo(destination)) { "Unable to activate verified pi binary" }
                        backup.delete()
                    }.onFailure { error ->
                        candidate.delete()
                        if (!destination.exists() && backup.exists()) backup.renameTo(destination)
                        throw error
                    }
                    val realBinary = File(destination.parentFile, "$PI_BINARY.real")
                    if (realBinary.exists()) realBinary.delete()
                    require(destination.renameTo(realBinary)) { "Unable to stage pi.real" }
                    destination.writeText(
                        "#!/bin/sh\n" +
                            "if [ -x /lib/ld-linux-aarch64.so.1 ]; then\n" +
                            "  exec /lib/ld-linux-aarch64.so.1 /usr/local/bin/pi.real \"\$@\"\n" +
                            "elif [ -x /lib/ld-linux-x86-64.so.2 ]; then\n" +
                            "  exec /lib/ld-linux-x86-64.so.2 /usr/local/bin/pi.real \"\$@\"\n" +
                            "fi\n" +
                            "exec /usr/local/bin/pi.real \"\$@\"\n",
                    )
                    require(destination.setExecutable(true, false) || destination.canExecute()) {
                        "Unable to mark pi wrapper executable"
                    }
                    writeInstalledVersion(rootfs, PiManifest.VERSION)
                    onProgress(1f)
                    archive.delete()
                } finally {
                    extraction.deleteRecursively()
                }
            }
            PiManifest.VERSION
        }
}
