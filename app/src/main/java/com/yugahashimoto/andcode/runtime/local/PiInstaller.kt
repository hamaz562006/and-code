package com.yugahashimoto.andcode.runtime.local

import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PiInstaller(
    private val runtimeDirectory: File,
    private val abi: String,
    private val downloader: VerifiedRuntimeDownloader = VerifiedRuntimeDownloader(),
) {
    suspend fun installInto(
        rootfs: File,
        onProgress: (Float) -> Unit = {},
    ): File =
        withContext(Dispatchers.IO) {
            require(runtimeDirectory.usableSpace >= PiManifest.MIN_FREE_BYTES) {
                "Pi needs at least 180 MB free space (available ${runtimeDirectory.usableSpace} bytes)"
            }
            val asset = PiManifest.assetFor(abi)
            val cache = File(runtimeDirectory, "cache").apply { mkdirs() }
            val archive = File(cache, asset.name)
            downloader.download(asset.url, archive, asset.sha256, asset.sizeBytes) { progress ->
                progress?.let { onProgress(it * 0.75f) }
            }
            val extraction = File(runtimeDirectory, "pi-extract-${System.nanoTime()}").apply { mkdirs() }
            try {
                archive.inputStream().use { RuntimeArchive.extractTarGz(it, extraction) }
                val sourceDir =
                    extraction.walkTopDown()
                        .firstOrNull { it.isDirectory && File(it, PiManifest.BINARY_NAME).isFile }
                        ?: error("Official Pi archive did not contain a pi binary")
                val destinationDir = File(rootfs, INSTALL_DIR)
                destinationDir.parentFile?.mkdirs()
                val candidate = File(destinationDir.parentFile, "pi.new-${System.nanoTime()}")
                val backup = File(destinationDir.parentFile, "pi.rollback")
                val destinationBin = File(rootfs, BINARY_PATH.removePrefix("/"))
                runCatching {
                    candidate.deleteRecursively()
                    sourceDir.copyRecursively(candidate, overwrite = true)
                    val candidateBinary = File(candidate, PiManifest.BINARY_NAME)
                    require(candidateBinary.setExecutable(true, false) || candidateBinary.canExecute()) {
                        "Unable to mark pi executable"
                    }
                    backup.deleteRecursively()
                    if (destinationDir.exists()) {
                        require(destinationDir.renameTo(backup)) { "Unable to stage the previous Pi install" }
                    }
                    require(candidate.renameTo(destinationDir)) { "Unable to activate the verified Pi install" }
                    installLauncher(rootfs)
                    backup.deleteRecursively()
                }.onFailure { error ->
                    candidate.deleteRecursively()
                    if (!destinationDir.exists() && backup.exists()) backup.renameTo(destinationDir)
                    if (!destinationBin.exists() && destinationDir.exists()) installLauncher(rootfs)
                    throw error
                }
                writeInstalledVersion(rootfs, PiManifest.VERSION)
                onProgress(1f)
                archive.delete()
                destinationBin
            } finally {
                extraction.deleteRecursively()
            }
        }

    companion object {
        const val BINARY_PATH = "/usr/local/bin/pi"
        private const val INSTALL_DIR = "usr/local/lib/and-code/pi"
        private const val VERSION_MARKER = "usr/local/share/and-code/pi-version"

        fun isInstalled(rootfs: File): Boolean = File(rootfs, BINARY_PATH.removePrefix("/")).isFile

        fun installedVersion(rootfs: File): String? =
            runCatching { File(rootfs, VERSION_MARKER).readText().trim().takeIf(String::isNotEmpty) }.getOrNull()

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

        fun ensureLauncher(rootfs: File) {
            if (File(rootfs, INSTALL_DIR).isDirectory) installLauncher(rootfs)
        }

        private fun installLauncher(rootfs: File) {
            val bin = File(rootfs, BINARY_PATH.removePrefix("/"))
            bin.parentFile?.mkdirs()
            bin.delete()
            runCatching {
                Os.symlink("../lib/and-code/pi/pi", bin.absolutePath)
            }.getOrElse {
                File(rootfs, "$INSTALL_DIR/${PiManifest.BINARY_NAME}").copyTo(bin, overwrite = true)
                require(bin.setExecutable(true, false) || bin.canExecute()) { "Unable to install pi launcher" }
            }
        }
    }
}
