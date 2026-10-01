package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * Installs the official Pi coding agent as the npm-published JS CLI under Alpine/musl.
 *
 * The GitHub "standalone" Linux archives are glibc-linked and do not run under this app's PRoot
 * Alpine rootfs (gcompat's ld-linux stub rejects being used as an explicit interpreter). The npm
 * package ships a Node entrypoint ([NPM_CLI_REL]) that runs on Alpine's native Node package.
 */
object PiInstaller {
    const val PI_BINARY = PiManifest.BINARY_NAME
    const val PI_VERSION = PiManifest.VERSION

    private const val BIN_DIR = "usr/local/bin"
    private const val LIB_DIR = "usr/local/lib/pi-coding-agent"
    private const val NPM_CLI_REL = "dist/bundle/cli.js"
    const val NPM_PACKAGE = "@earendil-works/pi-coding-agent"

    fun isInstalledIn(rootfs: File): Boolean {
        val binary = File(rootfs, "$BIN_DIR/$PI_BINARY")
        val cli = File(rootfs, "$LIB_DIR/$NPM_CLI_REL")
        val deps = File(rootfs, "$LIB_DIR/node_modules")
        return binary.isFile && cli.isFile && deps.isDirectory
    }

    fun installedVersion(rootfs: File): String? =
        runCatching {
            File(rootfs, "$BIN_DIR/.$PI_BINARY-version").takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null }
        }.getOrNull() ?: if (isInstalledIn(rootfs)) PI_VERSION else null

    internal fun writeInstalledVersion(
        rootfs: File,
        version: String,
    ) {
        File(rootfs, "$BIN_DIR/.$PI_BINARY-version").apply {
            parentFile?.mkdirs()
            writeText("$version\n")
        }
    }

    /**
     * Downloads the npm tarball on the Android host, extracts it into the rootfs, and writes a
     * `/usr/local/bin/pi` shim that invokes Alpine's `node` on the bundled CLI.
     *
     * [LocalRuntimeInstaller] must install the `nodejs` package into [rootfs] before this runs.
     * [onProgress] reports 0f..1f for this install step (download ~0–0.7, extract ~0.7–1).
     */
    suspend fun install(
        rootfs: File,
        abi: String,
        runtimeDirectory: File,
        accessCoordinator: LocalRuntimeAccessCoordinator,
        httpClient: OkHttpClient = OkHttpClient(),
        version: String = PiManifest.VERSION,
        onProgress: (Float) -> Unit = {},
    ): String =
        withContext(Dispatchers.IO) {
            // abi is unused: the npm package is pure JS. Kept for call-site symmetry with Codex.
            @Suppress("UNUSED_PARAMETER")
            val ignoredAbi = abi
            val cache = File(runtimeDirectory, "cache").apply { mkdirs() }
            val archive = File(cache, "pi-coding-agent-$version.tgz")
            val tarball =
                "https://registry.npmjs.org/$NPM_PACKAGE/-/pi-coding-agent-$version.tgz"
            onProgress(0.02f)
            if (!archive.isFile || archive.length() < 1_000_000L) {
                download(httpClient, tarball, archive) { fraction ->
                    // Map download 0..1 → overall 0.02..0.70
                    onProgress(0.02f + fraction.coerceIn(0f, 1f) * 0.68f)
                }
            }
            onProgress(0.72f)
            accessCoordinator.write {
                val extraction = File(runtimeDirectory, "pi-npm-extract-${System.nanoTime()}").apply { mkdirs() }
                try {
                    archive.inputStream().use { RuntimeArchive.extractTarGz(it, extraction) }
                    onProgress(0.85f)
                    // npm packs as package/...
                    val packageRoot =
                        File(extraction, "package").takeIf { it.isDirectory }
                            ?: extraction.walkTopDown().firstOrNull {
                                it.isDirectory && File(it, NPM_CLI_REL).isFile
                            }
                            ?: error("pi-coding-agent tarball missing $NPM_CLI_REL")
                    val libDir = File(rootfs, LIB_DIR)
                    libDir.deleteRecursively()
                    libDir.parentFile?.mkdirs()
                    packageRoot.copyRecursively(libDir, overwrite = true)
                    onProgress(0.93f)
                    val cli = File(libDir, NPM_CLI_REL)
                    require(cli.isFile) { "Extracted Pi package is missing $NPM_CLI_REL" }

                    val destination = File(rootfs, "$BIN_DIR/$PI_BINARY")
                    destination.parentFile?.mkdirs()
                    // Drop any previous glibc binary / broken gcompat wrapper.
                    File(destination.parentFile, "$PI_BINARY.real").delete()
                    destination.writeText(
                        "#!/bin/sh\n" +
                            "exec node /usr/local/lib/pi-coding-agent/$NPM_CLI_REL \"\$@\"\n",
                    )
                    require(destination.setExecutable(true, false) || destination.canExecute()) {
                        "Unable to mark pi shim executable"
                    }
                    writeInstalledVersion(rootfs, version)
                    onProgress(1f)
                } finally {
                    extraction.deleteRecursively()
                }
            }
            version
        }

    private fun download(
        httpClient: OkHttpClient,
        url: String,
        destination: File,
        onProgress: (Float) -> Unit = {},
    ) {
        destination.parentFile?.mkdirs()
        val tmp = File(destination.parentFile, "${destination.name}.partial")
        tmp.delete()
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Failed to download Pi npm package: HTTP ${response.code}" }
            val body = response.body ?: error("Empty body downloading Pi npm package")
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
                            // Throttle UI updates to whole-percent steps.
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
        require(tmp.renameTo(destination)) { "Unable to finalize Pi npm package download" }
        onProgress(1f)
    }
}
