package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Host install of Cline Termux port (aarch64), same class of agent as Hermes / Grok Build.
 *
 * Layout under [runtimeDirectory]/[ClineManifest.INSTALL_DIR]:
 *   opt/cline-termux/<version>/index.js
 *   opt/cline-termux/current → symlink/dir
 *   opt/bun-android-ffi/<version>/bun
 *   bin/cline launcher
 */
object ClineInstaller {
    private const val VERSION_MARKER = ".cline-version"

    fun installRoot(runtimeDirectory: File): File = File(runtimeDirectory, ClineManifest.INSTALL_DIR)

    fun launcherFile(runtimeDirectory: File): File = File(installRoot(runtimeDirectory), "bin/${ClineManifest.BINARY_NAME}")

    fun clineHome(runtimeDirectory: File): File = File(installRoot(runtimeDirectory), "opt/cline-termux/current")

    fun bunBinary(runtimeDirectory: File): File = File(installRoot(runtimeDirectory), "opt/bun-android-ffi/current/bun")

    fun isInstalledIn(runtimeDirectory: File): Boolean {
        val home = clineHome(runtimeDirectory)
        return launcherFile(runtimeDirectory).isFile &&
            File(home, "index.js").isFile &&
            bunBinary(runtimeDirectory).isFile
    }

    fun installedVersion(runtimeDirectory: File): String? =
        runCatching {
            File(installRoot(runtimeDirectory), VERSION_MARKER)
                .takeIf { it.isFile }
                ?.readText()
                ?.trim()
                ?.ifBlank { null }
        }.getOrNull()
            ?: if (isInstalledIn(runtimeDirectory)) ClineManifest.VERSION else null

    suspend fun install(
        runtimeDirectory: File,
        httpClient: OkHttpClient = defaultClient(),
        onProgress: (Float) -> Unit = {},
    ): String =
        withContext(Dispatchers.IO) {
            val root = installRoot(runtimeDirectory).apply { mkdirs() }
            val cache = File(runtimeDirectory, "cache").apply { mkdirs() }
            val clineArchive = File(cache, ClineManifest.ARCHIVE_NAME)
            val bunArchive = File(cache, ClineManifest.BUN_FFI_ARCHIVE)

            onProgress(0.02f)
            download(httpClient, ClineManifest.ARCHIVE_URL, clineArchive) { f ->
                onProgress(0.02f + f * 0.40f)
            }
            RuntimeArchive.verifySha256(clineArchive, ClineManifest.ARCHIVE_SHA256)

            onProgress(0.45f)
            download(httpClient, ClineManifest.BUN_FFI_URL, bunArchive) { f ->
                onProgress(0.45f + f * 0.35f)
            }

            val staging = File(runtimeDirectory, "staging-cline").apply {
                deleteRecursively()
                mkdirs()
            }
            try {
                onProgress(0.82f)
                // Bun FFI
                val bunExtract = File(staging, "bun").apply { mkdirs() }
                FileInputStream(bunArchive).use { RuntimeArchive.extractTarGz(it, bunExtract) }
                val bunSource =
                    bunExtract.walkTopDown().maxDepth(3).firstOrNull {
                        it.isDirectory && it.name.startsWith("bun-android-ffi-aarch64")
                    } ?: bunExtract.listFiles()?.firstOrNull { it.isDirectory } ?: bunExtract
                require(File(bunSource, "bun").isFile) { "Bun FFI archive missing bun binary" }
                val bunDest =
                    File(root, "opt/bun-android-ffi/${ClineManifest.BUN_FFI_VERSION}").apply {
                        deleteRecursively()
                        mkdirs()
                    }
                bunSource.copyRecursively(bunDest, overwrite = true)
                File(bunDest, "bun").setExecutable(true, false)
                val bunCurrent = File(root, "opt/bun-android-ffi/current")
                bunCurrent.deleteRecursively()
                // Directory junction via rename copy for Android (no reliable symlink)
                bunDest.copyRecursively(bunCurrent, overwrite = true)
                File(bunCurrent, "bun").setExecutable(true, false)

                onProgress(0.90f)
                // Cline tree
                val clineExtract = File(staging, "cline").apply { mkdirs() }
                FileInputStream(clineArchive).use { RuntimeArchive.extractTarGz(it, clineExtract) }
                val clineSource =
                    clineExtract.walkTopDown().maxDepth(4).firstOrNull {
                        it.isFile && it.name == "index.js"
                    }?.parentFile
                        ?: error("Cline archive missing index.js")
                val clineDest =
                    File(root, "opt/cline-termux/${ClineManifest.VERSION}").apply {
                        deleteRecursively()
                        mkdirs()
                    }
                clineSource.copyRecursively(clineDest, overwrite = true)
                val clineCurrent = File(root, "opt/cline-termux/current")
                clineCurrent.deleteRecursively()
                clineDest.copyRecursively(clineCurrent, overwrite = true)

                onProgress(0.96f)
                val binDir = File(root, "bin").apply { mkdirs() }
                val launcher = File(binDir, ClineManifest.BINARY_NAME)
                val homePath = clineCurrent.absolutePath
                val bunPath = File(bunCurrent, "bun").absolutePath
                launcher.writeText(
                    "#!/system/bin/sh\n" +
                        "# CLINE_ANDCODE_LAUNCHER=1\n" +
                        "set -e\n" +
                        "CLINE_TERMUX_HOME=\"\${CLINE_TERMUX_HOME:-$homePath}\"\n" +
                        "BUN_BIN=\"\${CLINE_TERMUX_BUN:-$bunPath}\"\n" +
                        "if [ ! -f \"\$CLINE_TERMUX_HOME/index.js\" ]; then\n" +
                        "  echo \"Error: Cline runtime missing at \$CLINE_TERMUX_HOME\" >&2\n" +
                        "  exit 1\n" +
                        "fi\n" +
                        "if [ ! -x \"\$BUN_BIN\" ]; then\n" +
                        "  echo \"Error: Bun FFI missing at \$BUN_BIN\" >&2\n" +
                        "  exit 1\n" +
                        "fi\n" +
                        "export CLINE_NO_AUTO_UPDATE=\"\${CLINE_NO_AUTO_UPDATE:-1}\"\n" +
                        "export CLINE_TERMUX_HOME\n" +
                        "export CLINE_TERMUX_BUN=\"\$BUN_BIN\"\n" +
                        "exec \"\$BUN_BIN\" \"\$CLINE_TERMUX_HOME/index.js\" \"\$@\"\n",
                )
                launcher.setExecutable(true, false)

                File(root, VERSION_MARKER).writeText(ClineManifest.VERSION + "\n")
                require(isInstalledIn(runtimeDirectory)) { "Cline install finished but layout incomplete" }
                onProgress(1f)
                ClineManifest.VERSION
            } finally {
                staging.deleteRecursively()
            }
        }

    fun runOnHost(
        runtimeDirectory: File,
        args: List<String>,
        timeoutSeconds: Long = 30L,
    ): LocalRuntimeCommandResult {
        val launcher = launcherFile(runtimeDirectory)
        if (!launcher.isFile) {
            return LocalRuntimeCommandResult(127, "Cline is not installed")
        }
        val cmd = listOf("/system/bin/sh", launcher.absolutePath) + args
        val process =
            ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .apply {
                    environment()["HOME"] = File(runtimeDirectory, "home").apply { mkdirs() }.absolutePath
                    environment()["CLINE_NO_AUTO_UPDATE"] = "1"
                }
                .start()
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return LocalRuntimeCommandResult(124, "Cline command timed out")
        }
        val out = process.inputStream.bufferedReader().readText().takeLast(4000)
        return LocalRuntimeCommandResult(process.exitValue(), out)
    }

    private fun download(
        client: OkHttpClient,
        url: String,
        dest: File,
        onProgress: (Float) -> Unit,
    ) {
        if (dest.isFile && dest.length() > 1_000_000L) {
            onProgress(1f)
            return
        }
        val tmp = File(dest.absolutePath + ".part")
        tmp.delete()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Download failed HTTP ${response.code} for $url" }
            val body = response.body ?: error("Empty body for $url")
            val total = body.contentLength().takeIf { it > 0 } ?: -1L
            body.byteStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var readTotal = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        readTotal += n
                        if (total > 0) onProgress((readTotal.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        require(tmp.renameTo(dest) || (dest.delete() && tmp.renameTo(dest))) {
            "Unable to finalize download $dest"
        }
        onProgress(1f)
    }

    private fun defaultClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .build()
}
