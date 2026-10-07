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
 * Installs the official Hermes Agent Termux `.deb` onto the Android host.
 *
 * Layout after install:
 *   `{runtimeDirectory}/hermes/usr/bin/hermes`
 *   `{runtimeDirectory}/hermes/usr/lib/hermes-agent/...`
 *
 * The package is Android/bionic. Chat uses `hermes chat -q` / `hermes -z` on the host.
 */
object HermesInstaller {
    private const val VERSION_MARKER = ".hermes-version"

    fun installRoot(runtimeDirectory: File): File = File(runtimeDirectory, HermesManifest.INSTALL_DIR)

    fun binaryFile(runtimeDirectory: File): File = File(installRoot(runtimeDirectory), "usr/bin/${HermesManifest.BINARY_NAME}")

    fun isInstalledIn(runtimeDirectory: File): Boolean = binaryFile(runtimeDirectory).isFile

    fun installedVersion(runtimeDirectory: File): String? =
        runCatching {
            File(installRoot(runtimeDirectory), VERSION_MARKER)
                .takeIf { it.isFile }
                ?.readText()
                ?.trim()
                ?.ifBlank { null }
        }.getOrNull()
            ?: if (isInstalledIn(runtimeDirectory)) HermesManifest.VERSION else null

    suspend fun install(
        runtimeDirectory: File,
        httpClient: OkHttpClient = defaultClient(),
        onProgress: (Float) -> Unit = {},
    ): String =
        withContext(Dispatchers.IO) {
            val root = installRoot(runtimeDirectory).apply { mkdirs() }
            val cache = File(runtimeDirectory, "cache").apply { mkdirs() }
            val deb = File(cache, HermesManifest.DEB_NAME)
            onProgress(0.02f)
            if (!deb.isFile || deb.length() < HermesManifest.DEB_SIZE_BYTES / 2) {
                download(httpClient, HermesManifest.DEB_URL, deb) { f ->
                    onProgress(0.02f + f.coerceIn(0f, 1f) * 0.70f)
                }
            }
            RuntimeArchive.verifySha256(deb, HermesManifest.DEB_SHA256)
            onProgress(0.75f)
            val staging =
                File(runtimeDirectory, "hermes.staging").apply {
                    deleteRecursively()
                    mkdirs()
                }
            try {
                FileInputStream(deb).use { input ->
                    RuntimeArchive.extractDebianPackage(input, staging)
                }
                onProgress(0.90f)
                val termuxUsr = File(staging, "data/data/com.termux/files/usr")
                val plainUsr = File(staging, "usr")
                val usr =
                    when {
                        termuxUsr.isDirectory -> termuxUsr
                        plainUsr.isDirectory -> plainUsr
                        else -> error("Hermes package did not contain a usr/ tree")
                    }
                val destUsr = File(root, "usr")
                destUsr.deleteRecursively()
                usr.copyRecursively(destUsr)
                File(root, VERSION_MARKER).writeText(HermesManifest.VERSION + "\n")
                listOf("bin", "lib").forEach { sub ->
                    File(destUsr, sub).walkTopDown().forEach { f ->
                        if (f.isFile || f.isDirectory) f.setExecutable(true, false)
                    }
                }
                require(binaryFile(runtimeDirectory).isFile) {
                    "Hermes binary missing after extract at ${binaryFile(runtimeDirectory)}"
                }
                onProgress(1f)
                HermesManifest.VERSION
            } finally {
                staging.deleteRecursively()
            }
        }

    fun runOnHost(
        runtimeDirectory: File,
        args: List<String>,
        timeoutSeconds: Long = 300L,
        workingDirectory: File? = null,
        extraEnv: Map<String, String> = emptyMap(),
    ): HostResult {
        val binary = binaryFile(runtimeDirectory)
        require(binary.isFile) { "Hermes is not installed" }
        val hermesHome = File(runtimeDirectory, "hermes-home").apply { mkdirs() }
        val pb =
            ProcessBuilder(listOf(binary.absolutePath) + args)
                .directory(workingDirectory ?: hermesHome)
                .redirectErrorStream(true)
        val env = pb.environment()
        env["HOME"] = hermesHome.absolutePath
        env["HERMES_HOME"] = hermesHome.absolutePath
        env["PREFIX"] = File(installRoot(runtimeDirectory), "usr").absolutePath
        env["PATH"] =
            File(installRoot(runtimeDirectory), "usr/bin").absolutePath +
            ":" + (env["PATH"] ?: "")
        extraEnv.forEach { (k, v) -> env[k] = v }
        val process = pb.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return HostResult(-1, output + "\n(timeout after ${timeoutSeconds}s)")
        }
        return HostResult(process.exitValue(), output)
    }

    data class HostResult(val exitCode: Int, val output: String)

    private fun download(
        client: OkHttpClient,
        url: String,
        dest: File,
        onProgress: (Float) -> Unit,
    ) {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { response ->
            require(response.isSuccessful) { "Download failed HTTP ${response.code}" }
            val body = response.body ?: error("Empty body")
            val total = body.contentLength().takeIf { it > 0 } ?: HermesManifest.DEB_SIZE_BYTES
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".part")
            body.byteStream().use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        onProgress((read.toDouble() / total).toFloat().coerceIn(0f, 1f))
                    }
                }
            }
            if (dest.exists()) dest.delete()
            require(tmp.renameTo(dest)) { "Failed to finalize download" }
        }
    }

    private fun defaultClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
}
