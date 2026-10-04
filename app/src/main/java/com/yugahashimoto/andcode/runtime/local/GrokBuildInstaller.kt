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
 * Downloads the Duro02 Termux aarch64 Grok Build archive into the shared Alpine rootfs.
 *
 * Headless auth is [XAI_API_KEY] (see GrokBuildRuntime.setApiKey) — no browser login.
 */
object GrokBuildInstaller {
    const val GROK_BINARY = GrokBuildManifest.BINARY_NAME
    const val GROK_VERSION = GrokBuildManifest.VERSION
    private const val BIN_DIR = "usr/local/bin"

    fun isInstalledIn(rootfs: File): Boolean = File(rootfs, "$BIN_DIR/$GROK_BINARY").isFile

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
                val destination = File(rootfs, "$BIN_DIR/$GROK_BINARY")
                destination.parentFile?.mkdirs()
                source.copyTo(destination, overwrite = true)
                destination.setExecutable(true, false)
                destination.setReadable(true, false)
                writeInstalledVersion(rootfs, version)
                // Ensure config dir exists for API-key auth.
                File(rootfs, "root/.grok").mkdirs()
                onProgress(1f)
            } finally {
                extraction.deleteRecursively()
            }
            version
        }

    private fun download(
        httpClient: OkHttpClient,
        url: String,
        destination: File,
        onProgress: (Float) -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        val tmp = File(destination.parentFile, "${destination.name}.partial")
        tmp.delete()
        val request = Request.Builder().url(url).header("User-Agent", "AndCode").get().build()
        httpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Failed to download Grok Build: HTTP ${response.code}" }
            val body = response.body ?: error("Empty body downloading Grok Build")
            val contentLength = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var readTotal = 0L
                    var lastReported = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        readTotal += n
                        if (contentLength > 0L) {
                            val pct = ((readTotal * 100) / contentLength).toInt().coerceIn(0, 100)
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress(pct / 100f)
                            }
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
                    val name = entry.name.substringAfterLast('/')
                    if (name == GROK_BINARY || name.isNotBlank()) {
                        val out = File(destination, entry.name)
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { tar.copyTo(it) }
                    }
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
