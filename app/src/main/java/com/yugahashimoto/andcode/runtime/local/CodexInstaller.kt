package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64

/**
 * Downloads and verifies the Codex native binary into the shared Alpine rootfs, the same one
 * OpenCode and Claude Code already run in.
 *
 * Unlike [ClaudeCodeInstaller] (an Alpine package) or the Antigravity installer (a whole-CLI GitHub
 * release archive), Codex ships as one native binary inside an npm tarball that also bundles a
 * voice runtime, a bundled `bwrap`, and `ripgrep` this app does not need - so only
 * `vendor/<target>/bin/codex` is extracted from the tarball, verified in isolation to run without
 * its sibling resources (see docs/CODEX.md).
 */
object CodexInstaller {
    const val CODEX_BINARY = "codex"
    private const val CODEX_BINARY_PATH = "usr/local/bin/$CODEX_BINARY"

    /** `bin/codex.js`'s `PLATFORM_PACKAGE_BY_TARGET`: the Rust target triple per Android ABI. */
    private val TARGET_TRIPLE_BY_ABI =
        mapOf(
            "arm64-v8a" to "aarch64-unknown-linux-musl",
            "x86_64" to "x86_64-unknown-linux-musl",
        )

    fun isInstalledIn(rootfs: File): Boolean = File(rootfs, CODEX_BINARY_PATH).isFile

    fun binaryPathIn(rootfs: File): File = File(rootfs, CODEX_BINARY_PATH)

    /**
     * Downloads the release for [abi], verifies it against the npm registry's own recorded SHA-512
     * integrity, and installs the extracted binary into [rootfs].
     */
    suspend fun install(
        rootfs: File,
        abi: String,
        runtimeDirectory: File,
        accessCoordinator: LocalRuntimeAccessCoordinator,
        httpClient: OkHttpClient = OkHttpClient(),
        releaseClient: CodexReleaseClient = CodexReleaseClient(httpClient),
    ): String =
        withContext(Dispatchers.IO) {
            val targetTriple = requireNotNull(TARGET_TRIPLE_BY_ABI[abi]) { "Unsupported Android ABI for Codex: $abi" }
            val release = releaseClient.latest(abi)
            val downloadDir = File(runtimeDirectory, "tmp").apply { mkdirs() }
            // A unique filename per invocation: a fixed name would let two concurrent installs
            // (e.g. two callers racing to install Codex) overwrite each other's in-progress download.
            val downloadFile = File.createTempFile("codex-download-", ".tgz", downloadDir)
            try {
                // The network fetch happens outside the lock (slow, and touches nothing shared); only
                // the rootfs write below needs it, the same guarantee every other writer to this shared
                // Alpine rootfs holds (ClaudeCodeInstaller's package install, LocalRuntimeInstaller's
                // environment activation) so a concurrent base-runtime update cannot swap or remove
                // `environment/rootfs` out from under this extraction.
                downloadTo(httpClient, release.tarballUrl, downloadFile)
                verifySha512(downloadFile, release.integrity)

                accessCoordinator.write {
                    val destination = binaryPathIn(rootfs)
                    destination.parentFile?.mkdirs()
                    extractBinary(downloadFile, targetTriple, destination)
                    check(destination.isFile) { "Codex reported a successful download but $CODEX_BINARY_PATH is missing" }
                    destination.setExecutable(true, false)
                    destination.setReadable(true, false)
                }
            } finally {
                downloadFile.delete()
            }
            release.version
        }

    private fun downloadTo(
        httpClient: OkHttpClient,
        url: String,
        destination: File,
    ) {
        val request = Request.Builder().url(url).header("User-Agent", "AndCode").get().build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Codex download failed with HTTP ${response.code}" }
            val body = checkNotNull(response.body) { "Codex download response had no body" }
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
        }
    }

    internal fun verifySha512(
        file: File,
        expectedIntegrity: String,
    ) {
        val expectedBase64 = expectedIntegrity.removePrefix("sha512-")
        val digest = MessageDigest.getInstance("SHA-512")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actualBase64 = Base64.getEncoder().encodeToString(digest.digest())
        check(actualBase64 == expectedBase64) { "SHA-512 mismatch for Codex download: expected $expectedBase64, got $actualBase64" }
    }

    /**
     * Extracts only `vendor/<targetTriple>/bin/codex` from the npm tarball; everything else is
     * skipped.
     *
     * Written to a temporary file first and moved into place only once fully copied: [destination]
     * is the live install path other code checks with [isInstalledIn] (existence only, not
     * integrity), so a copy interrupted mid-write - cancellation, the app killed, disk full - must
     * not leave a truncated binary sitting there and reporting itself installed forever.
     */
    internal fun extractBinary(
        tarball: File,
        targetTriple: String,
        destination: File,
    ) {
        val entryPath = "package/vendor/$targetTriple/bin/$CODEX_BINARY"
        val temporary = File(destination.parentFile, "${destination.name}.download")
        try {
            GzipCompressorInputStream(BufferedInputStream(tarball.inputStream())).use { gzip ->
                TarArchiveInputStream(gzip).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        if (entry.name == entryPath && entry.isFile) {
                            FileOutputStream(temporary).use { output ->
                                tar.copyTo(output)
                                output.fd.sync()
                            }
                            Files.move(
                                temporary.toPath(),
                                destination.toPath(),
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                            return
                        }
                        entry = tar.nextEntry
                    }
                }
            }
            error("Codex tarball does not contain $entryPath")
        } finally {
            temporary.delete()
        }
    }
}
