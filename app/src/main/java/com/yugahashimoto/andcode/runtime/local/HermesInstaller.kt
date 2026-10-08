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
 * Deb layout (canary aarch64):
 *   data/data/data/com.termux/files/usr/lib/hermes-agent/{bin,app,venv,tools,...}
 *
 * After install:
 *   `{runtimeDirectory}/hermes/usr/lib/hermes-agent/bin/hermes`
 *   `{runtimeDirectory}/hermes/usr/bin/hermes` (thin launcher)
 */
object HermesInstaller {
    private const val VERSION_MARKER = ".hermes-version"

    fun installRoot(runtimeDirectory: File): File = File(runtimeDirectory, HermesManifest.INSTALL_DIR)

    /** Bundled agent tree (venv, app, tools). */
    fun agentRoot(runtimeDirectory: File): File = File(installRoot(runtimeDirectory), "usr/lib/hermes-agent")

    /** Real CLI entry (shell wrapper around bundled Python). */
    fun binaryFile(runtimeDirectory: File): File = File(agentRoot(runtimeDirectory), "bin/${HermesManifest.BINARY_NAME}")

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
                onProgress(0.88f)
                val usr = findUsrTree(staging)
                val destUsr = File(root, "usr")
                destUsr.deleteRecursively()
                usr.copyRecursively(destUsr)
                onProgress(0.94f)

                // Termux shebangs point at /data/data/com.termux/files/usr/bin/sh — rewrite for host.
                val agentBin = File(destUsr, "lib/hermes-agent/bin")
                agentBin.listFiles()?.forEach { f ->
                    if (f.isFile) rewriteShebangToSystemSh(f)
                }
                // Scripts hardcode Termux PREFIX; retarget to this extract so the bundled
                // Python/Node under lib/hermes-agent resolve (fixes "Bundled interpreter missing").
                rewriteTermuxPrefixPaths(destUsr)

                // Thin launcher on PATH: export PREFIX then exec real hermes.
                val pathBin = File(destUsr, "bin").apply { mkdirs() }
                val launcher = File(pathBin, HermesManifest.BINARY_NAME)
                val realBin = File(destUsr, "lib/hermes-agent/bin/${HermesManifest.BINARY_NAME}")
                val prefix = destUsr.absolutePath
                launcher.writeText(
                    "#!/system/bin/sh\n" +
                        "export PREFIX=\"$prefix\"\n" +
                        "export PATH=\"$prefix/bin:$prefix/lib/hermes-agent/bin:\$PATH\"\n" +
                        "exec \"${realBin.absolutePath}\" \"\$@\"\n",
                )
                launcher.setExecutable(true, false)
                realBin.setExecutable(true, false)

                // Make the whole extract tree traversable/executable. Termux debs often
                // unpack without +x on venv/bin/python → "Permission denied".
                chmodTreeExecutable(destUsr)

                File(root, VERSION_MARKER).writeText(HermesManifest.VERSION + "\n")
                require(binaryFile(runtimeDirectory).isFile) {
                    "Hermes binary missing after extract at ${binaryFile(runtimeDirectory)}"
                }
                onProgress(1f)
                HermesManifest.VERSION
            } finally {
                staging.deleteRecursively()
            }
        }

    /**
     * Deb packages nest Termux paths under one or more `data/` prefixes
     * (`data/data/com.termux/...` or `data/data/data/com.termux/...`).
     */
    private fun findUsrTree(staging: File): File {
        val candidates =
            listOf(
                File(staging, "data/data/data/com.termux/files/usr"),
                File(staging, "data/data/com.termux/files/usr"),
                File(staging, "data/com.termux/files/usr"),
                File(staging, "usr"),
            )
        candidates.firstOrNull { it.isDirectory }?.let { return it }
        val walked =
            staging
                .walkTopDown()
                .maxDepth(10)
                .firstOrNull { dir ->
                    dir.isDirectory &&
                        dir.name == "usr" &&
                        dir.parentFile?.name == "files" &&
                        dir.parentFile?.parentFile?.name == "com.termux"
                }
        return walked ?: error("Hermes package did not contain a Termux usr/ tree under $staging")
    }

    /**
     * Replace hardcoded Termux prefix paths inside text scripts so the bundled
     * interpreter under our extract tree is found when PREFIX is not Termux.
     */
    private fun rewriteTermuxPrefixPaths(usr: File) {
        val termuxPrefix = "/data/data/com.termux/files/usr"
        val ourPrefix = usr.absolutePath
        val textExt =
            setOf(
                "",
                "sh",
                "bash",
                "py",
                "cfg",
                "ini",
                "toml",
                "yaml",
                "yml",
                "json",
                "txt",
                "env",
                "pth",
            )
        usr.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            if (f.length() > 2_000_000L) return@forEach
            val ext = f.extension.lowercase()
            if (ext !in textExt && !f.name.startsWith("hermes") && f.name != "activate") return@forEach
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: return@forEach
            // Skip ELF binaries
            if (bytes.size >= 4 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte()) return@forEach
            val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() ?: return@forEach
            if (termuxPrefix !in text) return@forEach
            f.writeText(text.replace(termuxPrefix, ourPrefix))
        }
    }


    /** Recursively set 0755 on directories and common executables under [root]. */
    private fun chmodTreeExecutable(root: File) {
        root.walkTopDown().forEach { f ->
            if (f.isDirectory) {
                f.setExecutable(true, false)
                f.setReadable(true, false)
                runCatching { android.system.Os.chmod(f.absolutePath, 0b111_101_101) }
                return@forEach
            }
            if (!f.isFile) return@forEach
            f.setReadable(true, false)
            val name = f.name
            val parent = f.parentFile?.name.orEmpty()
            val needsExec =
                parent == "bin" ||
                    parent == "sbin" ||
                    name == "hermes" ||
                    name.startsWith("python") ||
                    name.startsWith("node") ||
                    name.endsWith(".so") ||
                    !name.contains(".")
            if (needsExec) {
                f.setExecutable(true, false)
                runCatching { android.system.Os.chmod(f.absolutePath, 0b111_101_101) }
            }
        }
    }

    private fun rewriteShebangToSystemSh(file: File) {
        val text =
            runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return
        if (!text.startsWith("#!")) return
        val nl = text.indexOf('\n')
        if (nl < 0) return
        val body = text.substring(nl + 1)
        file.writeText("#!/system/bin/sh\n$body")
        file.setExecutable(true, false)
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
        // Repair exec bits on already-extracted installs (venv/bin/python often lost +x).
        runCatching { chmodTreeExecutable(installRoot(runtimeDirectory)) }
        val agent = agentRoot(runtimeDirectory)
        val hermesHome = File(runtimeDirectory, "hermes-home").apply { mkdirs() }
        // Always launch via system sh — wrappers may not have the exec bit on some FS.
        val pb =
            ProcessBuilder(listOf("/system/bin/sh", binary.absolutePath) + args)
                .directory(workingDirectory ?: hermesHome)
                .redirectErrorStream(true)
        val env = pb.environment()
        env["HOME"] = hermesHome.absolutePath
        env["HERMES_HOME"] = hermesHome.absolutePath
        env["PREFIX"] = File(installRoot(runtimeDirectory), "usr").absolutePath
        env["PATH"] =
            File(installRoot(runtimeDirectory), "usr/bin").absolutePath +
            ":" +
            File(agent, "bin").absolutePath +
            ":" +
            (env["PATH"] ?: "")
        extraEnv.forEach { (k, v) -> env[k] = v }
        // Load HERMES_HOME/.env into the process (API keys written by Settings).
        val envFile = File(hermesHome, ".env")
        if (envFile.isFile) {
            envFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) return@forEach
                val key = trimmed.substringBefore("=").trim()
                val value = trimmed.substringAfter("=").trim()
                if (key.isNotEmpty()) env[key] = value
            }
        }
        val process = pb.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return HostResult(-1, output + "\n(timeout after ${timeoutSeconds}s)")
        }
        return HostResult(process.exitValue(), output)
    }

    data class HostResult(
        val exitCode: Int,
        val output: String,
    )

    private fun download(
        client: OkHttpClient,
        url: String,
        dest: File,
        onProgress: (Float) -> Unit,
    ) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        if (tmp.exists()) tmp.delete()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Download failed HTTP ${response.code} for $url" }
            val body = response.body ?: error("Empty body")
            val total = body.contentLength().takeIf { it > 0 } ?: HermesManifest.DEB_SIZE_BYTES
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
