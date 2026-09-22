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
 * Downloads and verifies the Codex native binaries into the shared Alpine rootfs, the same one
 * OpenCode and Claude Code already run in.
 *
 * Unlike [ClaudeCodeInstaller] (an Alpine package) or the Antigravity installer (a whole-CLI GitHub
 * release archive), Codex ships as native binaries inside an npm tarball that also bundles a
 * voice runtime, a bundled `bwrap`, and `ripgrep` this app does not need - so only the
 * `vendor/<target>/bin/` binaries in [INSTALLED_BINARIES] are extracted (see docs/CODEX.md).
 */
object CodexInstaller {
    const val CODEX_BINARY = "codex"
    private const val BIN_DIR = "usr/local/bin"

    /**
     * What is installed from `vendor/<target>/bin/`: the CLI, and the host it runs model tool calls
     * in. Codex's "code mode" executes every tool call - image generation, MCP tools - by spawning
     * `codex-code-mode-host` from the directory `codex` itself lives in; without it each call fails
     * with "failed to spawn code-mode host" (seen on a device, 0.155.1).
     */
    internal val INSTALLED_BINARIES = listOf(CODEX_BINARY, "codex-code-mode-host")

    /** `bin/codex.js`'s `PLATFORM_PACKAGE_BY_TARGET`: the Rust target triple per Android ABI. */
    private val TARGET_TRIPLE_BY_ABI =
        mapOf(
            "arm64-v8a" to "aarch64-unknown-linux-musl",
            "x86_64" to "x86_64-unknown-linux-musl",
        )

    /** Both binaries: an install that predates the code-mode host counts as not installed, so it is redone. */
    fun isInstalledIn(rootfs: File): Boolean = INSTALLED_BINARIES.all { File(rootfs, "$BIN_DIR/$it").isFile }

    /**
     * Downloads the release for [abi], verifies it against the npm registry's own recorded SHA-512
     * integrity, and installs the extracted [INSTALLED_BINARIES] into [rootfs].
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
                    val binDir = File(rootfs, BIN_DIR)
                    extractBinaries(downloadFile, targetTriple, binDir)
                    INSTALLED_BINARIES.forEach { name ->
                        val installed = File(binDir, name)
                        check(installed.isFile) { "Codex reported a successful download but $BIN_DIR/$name is missing" }
                        installed.setExecutable(true, false)
                        installed.setReadable(true, false)
                    }
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
     * Extracts the named files from `vendor/<targetTriple>/bin/` into [binDir]; everything else in the
     * tarball (voice runtime, bundled `bwrap`, `ripgrep`) is skipped.
     *
     * Each file is written to a temporary name and moved into place only once fully copied: these are
     * the live install paths [isInstalledIn] checks (existence only, not integrity), so a copy
     * interrupted mid-write - cancellation, the app killed, disk full - must not leave a truncated
     * binary reporting itself installed forever. Fails when any of [names] is missing.
     */
    internal fun extractBinaries(
        tarball: File,
        targetTriple: String,
        binDir: File,
        names: List<String> = INSTALLED_BINARIES,
    ) {
        val prefix = "package/vendor/$targetTriple/bin/"
        val pending = names.toMutableSet()
        binDir.mkdirs()
        GzipCompressorInputStream(BufferedInputStream(tarball.inputStream())).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                var entry = tar.nextEntry
                while (entry != null && pending.isNotEmpty()) {
                    val name = entry.name.removePrefix(prefix)
                    if (entry.isFile && entry.name.startsWith(prefix) && name in pending) {
                        val destination = File(binDir, name)
                        val temporary = File(binDir, "$name.download")
                        try {
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
                        } finally {
                            temporary.delete()
                        }
                        pending -= name
                    }
                    entry = tar.nextEntry
                }
            }
        }
        check(pending.isEmpty()) { "Codex tarball does not contain ${pending.joinToString { "$prefix$it" }}" }
    }
}
