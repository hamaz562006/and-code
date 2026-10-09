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
                fixTermuxSymlinks(destUsr)

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
                ensureVenvPython(destUsr)
                patchOpenCodeFreeClientHeaders(File(destUsr, "lib/hermes-agent"))

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

    /**
     * Termux debs ship absolute symlinks under /data/data/com.termux/files/usr/…
     * After extract into the app files dir those links are dangling → venv/bin/python
     * Permission denied / Bundled interpreter missing. Retarget every absolute Termux
     * symlink onto our extracted [usr] tree (prefer relative links when possible).
     */
    private fun fixTermuxSymlinks(usr: File) {
        val termuxPrefix = "/data/data/com.termux/files/usr"
        val ourPrefix = usr.absolutePath
        // Walk leaves first so we rewrite deepest links before parents.
        usr.walkBottomUp().forEach { f ->
            val path = f.absolutePath
            val target =
                runCatching {
                    val link = java.nio.file.Files.readSymbolicLink(f.toPath())
                    link.toString()
                }.getOrNull() ?: return@forEach
            val newTarget =
                when {
                    target.startsWith(termuxPrefix) ->
                        ourPrefix + target.removePrefix(termuxPrefix)
                    target.startsWith("/data/data/com.termux/") ->
                        // Rare absolute paths outside usr — map hermes-agent subtree if present.
                        target.replace(
                            "/data/data/com.termux/files/usr",
                            ourPrefix,
                        )
                    else -> return@forEach
                }
            runCatching {
                f.delete()
                val dest = File(newTarget)
                // Prefer relative symlink for portability within the tree.
                val relative =
                    runCatching {
                        f.parentFile!!.toPath().relativize(dest.toPath()).toString()
                    }.getOrNull()
                val linkPath = relative ?: newTarget
                java.nio.file.Files.createSymbolicLink(
                    f.toPath(),
                    java.nio.file.Paths.get(linkPath),
                )
            }
            // Ensure the ultimate target is executable when it is a binary we own.
            runCatching {
                val resolved = File(f.parentFile, java.nio.file.Files.readSymbolicLink(f.toPath()).toString())
                val abs = if (resolved.isAbsolute) resolved else resolved.canonicalFile
                if (abs.isFile) {
                    abs.setExecutable(true, false)
                    abs.setReadable(true, false)
                    android.system.Os.chmod(abs.absolutePath, 0b111_101_101)
                }
            }
        }
        // Final pass: chmod the real bundled python ELF(s).
        File(usr, "lib/hermes-agent/tools").walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            if (f.name.startsWith("python") || f.name == "node" || f.name == "hermes") {
                f.setExecutable(true, false)
                runCatching { android.system.Os.chmod(f.absolutePath, 0b111_101_101) }
            }
        }
        val venvPython = File(usr, "lib/hermes-agent/venv/bin/python")
        if (venvPython.exists()) {
            venvPython.setExecutable(true, false)
            runCatching { android.system.Os.chmod(venvPython.absolutePath, 0b111_101_101) }
        }
    }

    /**
     * Guarantee [usr]/lib/hermes-agent/venv/bin/python] is an executable file that points at
     * the bundled Termux Python ELF. Absolute Termux symlinks and copyRecursively both
     * commonly leave a non-executable or dangling path here.
     */
    fun ensureVenvPythonPublic(usr: File) = ensureVenvPython(usr)

    private fun ensureVenvPython(usr: File) {
        val agent = File(usr, "lib/hermes-agent")
        val realPython =
            sequenceOf(
                File(agent, "tools/python/data/data/com.termux/files/usr/bin/python3.14"),
                File(agent, "tools/python/data/data/com.termux/files/usr/bin/python3"),
                File(agent, "tools/python/data/data/com.termux/files/usr/bin/python"),
            ).firstOrNull { it.isFile && it.length() > 0 }
                ?: agent.walkTopDown().firstOrNull { f ->
                    f.isFile && f.name.startsWith("python3") && f.length() > 1000L &&
                        runCatching {
                            f.inputStream().use { ins ->
                                val b = ByteArray(4)
                                ins.read(b) == 4 && b[0] == 0x7f.toByte() && b[1] == 'E'.code.toByte()
                            }
                        }.getOrDefault(false)
                }
        if (realPython == null) return
        realPython.setReadable(true, false)
        realPython.setExecutable(true, false)
        runCatching { android.system.Os.chmod(realPython.absolutePath, 0b111_101_101) }

        val venvBin = File(agent, "venv/bin").apply { mkdirs() }
        listOf("python", "python3", "python3.14").forEach { name ->
            val link = File(venvBin, name)
            runCatching { if (link.exists()) link.delete() }
            // Copy bytes — more reliable than symlinks on app-private storage.
            realPython.copyTo(link, overwrite = true)
            link.setReadable(true, false)
            link.setExecutable(true, false)
            runCatching { android.system.Os.chmod(link.absolutePath, 0b111_101_101) }
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
        val usr = File(installRoot(runtimeDirectory), "usr")
        require(usr.isDirectory) { "Hermes is not installed" }
        runCatching {
            chmodTreeExecutable(installRoot(runtimeDirectory))
            fixTermuxSymlinks(usr)
            ensureVenvPython(usr)
            patchOpenCodeFreeClientHeaders(agentRoot(runtimeDirectory))
        }
        val agent = agentRoot(runtimeDirectory)
        val python =
            resolveBundledPython(agent)
                ?: return HostResult(127, "Bundled Python missing under ${agent.absolutePath}")
        python.setReadable(true, false)
        python.setExecutable(true, false)
        runCatching { android.system.Os.chmod(python.absolutePath, 0b111_101_101) }

        val hermesHome = File(runtimeDirectory, "hermes-home").apply { mkdirs() }
        val repo = File(agent, "app")
        val site = File(agent, "venv/lib/python3.14/site-packages")
        val pyLib = File(agent, "tools/python/data/data/com.termux/files/usr/lib")
        val nodeLib = File(agent, "tools/node/data/data/com.termux/files/usr/lib")
        val ffmpegLib = File(agent, "tools/ffmpeg/data/data/com.termux/files/usr/lib")
        val runtimeLibs = File(agent, "runtime-libs/lib")
        val ldParts =
            listOf(pyLib, nodeLib, ffmpegLib, runtimeLibs, File(usr, "lib"))
                .filter { it.isDirectory }
                .map { it.absolutePath }

        // Same bootstrap as upstream bin/hermes — but invoke via linker64 so Android
        // app-private storage does not reject the Termux-built ELF (EACCES).
        val bootstrap =
            "import os, site, sys; sys.argv[0]='hermes'; " +
                "site.addsitedir(os.environ['HERMES_SITE']); " +
                "from hermes_cli.main import main; sys.exit(main())"
        val linker = resolveLinker64()
        val command =
            buildList {
                if (linker != null) {
                    add(linker)
                }
                add(python.absolutePath)
                add("-P")
                add("-c")
                add(bootstrap)
                addAll(args)
            }

        val pb =
            ProcessBuilder(command)
                .directory(workingDirectory ?: hermesHome)
                .redirectErrorStream(true)
        val env = pb.environment()
        env["HOME"] = hermesHome.absolutePath
        env["HERMES_HOME"] = hermesHome.absolutePath
        env["PREFIX"] = usr.absolutePath
        env["HERMES_SITE"] = site.absolutePath
        env["HERMES_PYTHON"] = python.absolutePath
        env["HERMES_PYTHON_SRC_ROOT"] = repo.absolutePath
        env["HERMES_RUNTIME_DIR"] = File(agent, "tools").absolutePath
        env["PYTHONPATH"] = listOf(repo.absolutePath, site.absolutePath).joinToString(":")
        env.remove("PYTHONHOME")
        if (ldParts.isNotEmpty()) {
            val existing = env["LD_LIBRARY_PATH"]?.takeIf { it.isNotBlank() }
            env["LD_LIBRARY_PATH"] = (ldParts + listOfNotNull(existing)).joinToString(":")
        }
        env["PATH"] =
            listOf(
                File(usr, "bin").absolutePath,
                File(agent, "bin").absolutePath,
                File(agent, "tools/node/data/data/com.termux/files/usr/bin").absolutePath,
                File(agent, "tools/npm/bin").absolutePath,
                env["PATH"] ?: "/system/bin:/system/xbin",
            ).joinToString(":")
        extraEnv.forEach { (k, v) -> env[k] = v }
        val envFile = File(hermesHome, ".env")
        if (envFile.isFile) {
            envFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) return@forEach
                env[trimmed.substringBefore("=").trim()] = trimmed.substringAfter("=").trim()
            }
        }
        val process = pb.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val completed = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return HostResult(-1, output + "\n(timeout after ${timeoutSeconds}s)")
        }
        return HostResult(process.exitValue(), output)
    }

    /**
     * Free-tier Zen rejects HermesAgent User-Agent for some models (403). Rewrite the bundled
     * opencode-free plugin headers to look like the official OpenCode CLI and attach a session id.
     */
    fun patchOpenCodeFreeClientHeaders(agent: File) {
        val file = File(agent, "app/plugins/model-providers/opencode-free/__init__.py")
        if (!file.isFile) return
        var text = file.readText(Charsets.UTF_8)
        if ("opencode/1.18.0" in text && "X-Session-ID" in text) return
        val lines = text.lines().toMutableList()
        val out = mutableListOf<String>()
        for (line in lines) {
            if ("User-Agent" in line && "HermesAgent" in line) {
                out.add("        \"User-Agent\": \"opencode/1.18.0\",")
                out.add("        \"X-Session-ID\": \"ses_andcodehermes000000000001\",")
            } else {
                out.add(line)
            }
        }
        file.writeText(out.joinToString("\n") + if (text.endsWith("\n")) "\n" else "")
    }

    fun resolveBundledPython(agent: File): File? =
        sequenceOf(
            File(agent, "tools/python/data/data/com.termux/files/usr/bin/python3.14"),
            File(agent, "tools/python/data/data/com.termux/files/usr/bin/python3"),
            File(agent, "tools/python/data/data/com.termux/files/usr/bin/python"),
            File(agent, "venv/bin/python3.14"),
            File(agent, "venv/bin/python"),
        ).firstOrNull { it.isFile && it.length() > 0 }

    fun resolveLinker64(): String? =
        listOf(
            "/system/bin/linker64",
            "/apex/com.android.runtime/bin/linker64",
            "/system/bin/linker",
        ).firstOrNull { File(it).exists() }

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
