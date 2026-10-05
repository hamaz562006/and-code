package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Downloads the Duro02 Termux aarch64 Grok Build archive into the shared rootfs.
 *
 * The upstream binary is an Android (bionic) PIE linked against `/system/bin/linker64`.
 * Alpine/musl cannot load it as a normal guest binary, so we:
 * 1. Install the real ELF under [LIB_DIR]
 * 2. Put a small shell wrapper on `PATH` that sets Android library paths and execs it
 *
 * Headless auth is [XAI_API_KEY] (see [GrokBuildRuntime.setApiKey]) — no browser login.
 */
object GrokBuildInstaller {
    const val GROK_BINARY = GrokBuildManifest.BINARY_NAME
    const val GROK_VERSION = GrokBuildManifest.VERSION
    private const val BIN_DIR = "usr/local/bin"
    private const val LIB_DIR = "usr/local/lib/grok-build"

    /** Real Android ELF (not the wrapper). */
    fun binaryPath(rootfs: File): File = File(rootfs, "$LIB_DIR/$GROK_BINARY")

    /** PATH entry — shell wrapper when present, else legacy direct binary. */
    fun pathEntry(rootfs: File): File = File(rootfs, "$BIN_DIR/$GROK_BINARY")

    fun isInstalledIn(rootfs: File): Boolean {
        val real = binaryPath(rootfs)
        if (real.isFile && real.length() > 1_000_000L) return true
        // Legacy installs placed the ELF directly on PATH.
        val legacy = pathEntry(rootfs)
        return legacy.isFile && legacy.length() > 1_000_000L && !legacy.readText().startsWith("#!")
    }

    fun installedVersion(rootfs: File): String? =
        File(rootfs, "$BIN_DIR/.$GROK_BINARY-version").takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null }

    fun writeInstalledVersion(
        rootfs: File,
        version: String,
    ) {
        File(rootfs, "$BIN_DIR/.$GROK_BINARY-version").apply {
            parentFile?.mkdirs()
            writeText(version.trim())
        }
    }

    suspend fun install(
        rootfs: File,
        runtimeDirectory: File,
        httpClient: OkHttpClient,
        onProgress: (Float) -> Unit = {},
    ): String =
        withContext(Dispatchers.IO) {
            val version = GROK_VERSION
            val cache = File(runtimeDirectory, "cache").apply { mkdirs() }
            val archive = File(cache, "grok-termux-aarch64-$version.tar.gz")
            download(httpClient, GrokBuildManifest.archiveUrl(), archive) { fraction ->
                onProgress(fraction * 0.85f)
            }
            val expectedSha =
                runCatching {
                    httpClient
                        .newCall(Request.Builder().url(GrokBuildManifest.sha256Url()).get().build())
                        .execute()
                        .use { resp ->
                            if (!resp.isSuccessful) return@use null
                            resp.body?.string()?.trim()?.substringBefore(' ')?.lowercase()
                        }
                }.getOrNull()
            if (expectedSha != null) {
                val actual = sha256Hex(archive)
                require(actual == expectedSha) {
                    "Grok Build archive SHA-256 mismatch (expected $expectedSha, got $actual)"
                }
            }
            onProgress(0.88f)
            val extraction = File(runtimeDirectory, "grok-extract-${System.nanoTime()}").apply { mkdirs() }
            try {
                extractTarGz(archive, extraction)
                val source =
                    extraction.walkTopDown().firstOrNull { it.isFile && it.name == GROK_BINARY }
                        ?: error("Grok Build archive did not contain a '$GROK_BINARY' binary")
                require(source.length() > 1_000_000L) {
                    "Grok Build binary looks incomplete (${source.length()} bytes)"
                }

                val realDestination = binaryPath(rootfs)
                realDestination.parentFile?.mkdirs()
                source.copyTo(realDestination, overwrite = true)
                realDestination.setExecutable(true, false)
                realDestination.setReadable(true, false)

                writeWrapper(rootfs, realDestination)
                writeInstalledVersion(rootfs, version)
                File(rootfs, "root/.grok").mkdirs()
                onProgress(1f)
            } finally {
                extraction.deleteRecursively()
            }
            version
        }

    /**
     * Shell wrapper so `grok` on PATH runs the Android ELF with the host linker and system libs
     * (proot already binds `/system`).
     */
    private fun writeWrapper(
        rootfs: File,
        realBinary: File,
    ) {
        val wrapper = pathEntry(rootfs)
        wrapper.parentFile?.mkdirs()
        // Guest path for the real binary (not the host absolute path).
        val guestReal = "/$LIB_DIR/$GROK_BINARY"
        val script =
            buildString {
                appendLine("#!/bin/sh")
                appendLine("# Grok Build is an Android (bionic) binary; needs /system linker + libs.")
                appendLine("export ANDROID_ROOT=\"\${ANDROID_ROOT:-/system}\"")
                appendLine("export ANDROID_DATA=\"\${ANDROID_DATA:-/data}\"")
                appendLine(
                    "export LD_LIBRARY_PATH=\"/system/lib64:/system/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}\"",
                )
                appendLine("exec \"$guestReal\" \"\$@\"")
            }
        wrapper.writeText(script)
        wrapper.setExecutable(true, false)
        wrapper.setReadable(true, false)
        realBinary.setExecutable(true, false)
    }

    private fun download(
        httpClient: OkHttpClient,
        url: String,
        destination: File,
        onProgress: (Float) -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        val tmp = File(destination.absolutePath + ".part")
        if (tmp.exists()) tmp.delete()
        httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            require(response.isSuccessful) { "Download failed HTTP ${response.code} for $url" }
            val body = response.body ?: error("Empty body for $url")
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var readTotal = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        readTotal += n
                        if (total != null) {
                            onProgress((readTotal.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }
        if (destination.exists()) destination.delete()
        require(tmp.renameTo(destination)) { "Unable to finalize Grok Build download" }
        onProgress(1f)
    }

    private fun extractTarGz(
        archive: File,
        destination: File,
    ) {
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val out = File(destination, entry.name)
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { tar.copyTo(it) }
                }
                entry = tar.nextEntry
            }
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { b -> "%02x".format(b) }
    }
}
