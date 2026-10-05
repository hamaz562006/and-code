package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Downloads the Duro02 Termux aarch64 Grok Build archive into the shared rootfs.
 *
 * Upstream binary needs:
 * - Android linker (`/system/bin/linker64` → APEX) — proot binds `/system` + `/apex`
 * - NDK `libc++_shared.so` with `RUNPATH=$ORIGIN/../lib` — we install Termux's copy at
 *   `usr/local/lib/libc++_shared.so` so `$ORIGIN/../lib` resolves when the ELF lives at
 *   `usr/local/lib/grok-build/grok`
 *
 * Headless auth is [XAI_API_KEY] (see [GrokBuildRuntime.setApiKey]) — no browser login.
 */
object GrokBuildInstaller {
    const val GROK_BINARY = GrokBuildManifest.BINARY_NAME
    const val GROK_VERSION = GrokBuildManifest.VERSION
    private const val BIN_DIR = "usr/local/bin"
    private const val LIB_DIR = "usr/local/lib/grok-build"
    private const val LOCAL_LIB = "usr/local/lib"

    fun binaryPath(rootfs: File): File = File(rootfs, "$LIB_DIR/$GROK_BINARY")

    fun pathEntry(rootfs: File): File = File(rootfs, "$BIN_DIR/$GROK_BINARY")

    fun isInstalledIn(rootfs: File): Boolean {
        val real = binaryPath(rootfs)
        val libCpp = File(rootfs, "$LOCAL_LIB/${GrokBuildManifest.LIBCPP_SONAME}")
        if (real.isFile && real.length() > 1_000_000L && libCpp.isFile) return true
        val legacy = pathEntry(rootfs)
        return legacy.isFile && legacy.length() > 1_000_000L
    }

    /**
     * Runs the Grok binary as a **host** Android process (not under Alpine proot).
     *
     * The Duro02 build is `aarch64-linux-android` and pulls system media/OpenSLES symbols;
     * under proot those resolve through broken stub chains (`libmediastub.so`, linkerconfig).
     * Termux runs the same way — native process + [LD_LIBRARY_PATH] for [LIBCPP_SONAME].
     */
    fun runOnHost(
        rootfs: File,
        args: List<String>,
        timeoutSeconds: Long = 20L,
    ): LocalRuntimeCommandResult {
        val binary =
            binaryPath(rootfs).takeIf { it.isFile }
                ?: pathEntry(rootfs).takeIf { it.isFile && it.length() > 1_000_000L }
                ?: return LocalRuntimeCommandResult(127, "grok: not installed")
        val libDir = File(rootfs, LOCAL_LIB)
        val command = listOf(binary.absolutePath) + args
        return try {
            val process =
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .apply {
                        environment()["LD_LIBRARY_PATH"] =
                            listOfNotNull(
                                libDir.absolutePath.takeIf { libDir.isDirectory },
                                environment()["LD_LIBRARY_PATH"]?.takeIf { it.isNotBlank() },
                            ).joinToString(":")
                        environment()["ANDROID_ROOT"] = "/system"
                        environment()["ANDROID_DATA"] = "/data"
                        environment()["HOME"] = File(rootfs, "root").absolutePath
                        environment()["PREFIX"] = File(rootfs, "usr/local").absolutePath
                    }
                    .start()
            val completed = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                LocalRuntimeCommandResult(124, "grok: timed out")
            } else {
                val output =
                    process.inputStream.bufferedReader().use { it.readText() }.takeLast(4_000)
                LocalRuntimeCommandResult(process.exitValue(), output)
            }
        } catch (error: Exception) {
            LocalRuntimeCommandResult(1, error.message ?: "grok: host launch failed")
        }
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
                onProgress(fraction * 0.55f)
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
            onProgress(0.58f)

            val libcppDeb = File(cache, "libcpp_30_aarch64.deb")
            download(httpClient, GrokBuildManifest.LIBCPP_DEB_URL, libcppDeb) { fraction ->
                onProgress(0.58f + fraction * 0.15f)
            }
            val libcppSha = sha256Hex(libcppDeb)
            require(libcppSha == GrokBuildManifest.LIBCPP_DEB_SHA256) {
                "libc++ deb SHA-256 mismatch (expected ${GrokBuildManifest.LIBCPP_DEB_SHA256}, got $libcppSha)"
            }
            onProgress(0.75f)

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

                // RUNPATH=$ORIGIN/../lib → usr/local/lib when binary is usr/local/lib/grok-build/grok
                val libDir = File(rootfs, LOCAL_LIB).apply { mkdirs() }
                extractLibcxxShared(libcppDeb, File(libDir, GrokBuildManifest.LIBCPP_SONAME))

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
     * Extract [GrokBuildManifest.LIBCPP_SONAME] from a Termux `.deb` into [destination].
     */
    private fun extractLibcxxShared(
        deb: File,
        destination: File,
    ) {
        val tmp = File(destination.absolutePath + ".part")
        if (tmp.exists()) tmp.delete()
        ArArchiveInputStream(BufferedInputStream(FileInputStream(deb))).use { ar ->
            var entry = ar.nextEntry
            var dataMember: File? = null
            val staging = File(destination.parentFile, "deb-staging-${System.nanoTime()}").apply { mkdirs() }
            try {
                while (entry != null) {
                    val name = entry.name.trimStart('/')
                    if (name.startsWith("data.tar")) {
                        dataMember = File(staging, name)
                        FileOutputStream(dataMember).use { ar.copyTo(it) }
                    }
                    entry = ar.nextEntry
                }
                val data = dataMember ?: error("libc++ deb missing data.tar.* member")
                val found =
                    when {
                        data.name.endsWith(".xz") ->
                            extractNamedFromTar(
                                TarArchiveInputStream(XZCompressorInputStream(BufferedInputStream(FileInputStream(data)))),
                                GrokBuildManifest.LIBCPP_SONAME,
                                tmp,
                            )
                        data.name.endsWith(".gz") ->
                            extractNamedFromTar(
                                TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(FileInputStream(data)))),
                                GrokBuildManifest.LIBCPP_SONAME,
                                tmp,
                            )
                        else ->
                            extractNamedFromTar(
                                TarArchiveInputStream(BufferedInputStream(FileInputStream(data))),
                                GrokBuildManifest.LIBCPP_SONAME,
                                tmp,
                            )
                    }
                require(found) { "libc++ deb did not contain ${GrokBuildManifest.LIBCPP_SONAME}" }
            } finally {
                staging.deleteRecursively()
            }
        }
        if (destination.exists()) destination.delete()
        require(tmp.renameTo(destination)) { "Unable to finalize ${GrokBuildManifest.LIBCPP_SONAME}" }
        destination.setReadable(true, false)
    }

    private fun extractNamedFromTar(
        tar: TarArchiveInputStream,
        fileName: String,
        out: File,
    ): Boolean {
        tar.use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.substringAfterLast('/') == fileName) {
                    FileOutputStream(out).use { input.copyTo(it) }
                    return true
                }
                entry = input.nextEntry
            }
        }
        return false
    }

    private fun writeWrapper(
        rootfs: File,
        realBinary: File,
    ) {
        val wrapper = pathEntry(rootfs)
        wrapper.parentFile?.mkdirs()
        val guestReal = "/$LIB_DIR/$GROK_BINARY"
        val script =
            buildString {
                appendLine("#!/bin/sh")
                appendLine("export ANDROID_ROOT=\"\${ANDROID_ROOT:-/system}\"")
                appendLine("export ANDROID_DATA=\"\${ANDROID_DATA:-/data}\"")
                appendLine("export PREFIX=\"\${PREFIX:-/usr/local}\"")
                // $ORIGIN/../lib is usr/local/lib — keep it first for libc++_shared.so
                appendLine(
                    "export LD_LIBRARY_PATH=\"/usr/local/lib:/apex/com.android.runtime/lib64:/apex/com.android.i18n/lib64:/apex/com.android.art/lib64:/system/lib64:/system/lib:/vendor/lib64\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}\"",
                )
                appendLine("REAL=\"$guestReal\"")
                appendLine("if [ ! -f \"\$REAL\" ]; then")
                appendLine("  echo \"grok: binary missing at \$REAL\" >&2")
                appendLine("  exit 127")
                appendLine("fi")
                appendLine("if [ ! -f /usr/local/lib/libc++_shared.so ]; then")
                appendLine("  echo \"grok: libc++_shared.so missing under /usr/local/lib\" >&2")
                appendLine("  exit 127")
                appendLine("fi")
                appendLine("LINKER=\"\"")
                appendLine("for c in \\")
                appendLine("  /apex/com.android.runtime/bin/linker64 \\")
                appendLine("  /system/bin/linker64 \\")
                appendLine("  /system/bin/linker")
                appendLine("do")
                appendLine("  if [ -e \"\$c\" ]; then LINKER=\"\$c\"; break; fi")
                appendLine("done")
                appendLine("if [ -n \"\$LINKER\" ]; then")
                appendLine("  exec \"\$LINKER\" \"\$REAL\" \"\$@\"")
                appendLine("fi")
                appendLine("echo \"grok: Android linker not found\" >&2")
                appendLine("exit 127")
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
        require(tmp.renameTo(destination)) { "Unable to finalize download $url" }
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
