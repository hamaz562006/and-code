package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Installs Pi the same way Codex is installed: download archives on the Android host and extract
 * them into the shared rootfs. Never runs `npm install` inside proot — that path was killed with
 * exit 137 (SIGKILL) even on devices with several GB free, because npm's full dependency resolve
 * (AWS SDK, etc.) spikes memory far beyond what the coding-agent RPC path needs.
 *
 * The published `dist/bundle` is nearly self-contained; only a few packages remain external
 * (`@earendil-works/chord`, `typebox`, `undici`). Those are extracted next to the agent package.
 */
object PiInstaller {
    const val PI_VERSION = "0.87.1"

    /** Canonical path we expose to the rest of the app. */
    private const val PI_BINARY = "/usr/local/bin/pi"

    private const val MODULES_DIR = "usr/local/lib/node_modules"
    private const val PI_MODULE = "@earendil-works/pi-coding-agent"
    private const val CLI_RELATIVE = "dist/bundle/cli.js"

    /**
     * Host-side downloads only. Each entry is registry name → exact version.
     * Keep this list minimal: the bundle already inlines almost everything.
     */
    private val PACKAGES =
        listOf(
            NpmPackage(PI_MODULE, PI_VERSION),
            NpmPackage("@earendil-works/chord", PI_VERSION),
            NpmPackage("typebox", "1.3.27"),
            NpmPackage("undici", "8.10.2"),
        )

    fun isInstalledIn(rootfs: File): Boolean {
        val bin = File(rootfs, PI_BINARY.removePrefix("/"))
        val cli = File(rootfs, "$MODULES_DIR/$PI_MODULE/$CLI_RELATIVE")
        return bin.isFile && cli.isFile
    }

    suspend fun install(
        runtime: LocalRuntimeInstaller.InstalledRuntime,
        runtimeDirectory: File,
        accessCoordinator: LocalRuntimeAccessCoordinator,
    ): String =
        withContext(Dispatchers.IO) {
            accessCoordinator.write {
                val cache = File(runtimeDirectory, "cache/npm").apply { mkdirs() }
                val modules = File(runtime.rootfs, MODULES_DIR)
                modules.mkdirs()

                for (pkg in PACKAGES) {
                    val tarball = File(cache, pkg.fileName)
                    downloadTarball(pkg.tarballUrl, tarball)
                    extractNpmPackage(tarball, File(modules, pkg.modulePath))
                }

                val cli = File(modules, "$PI_MODULE/$CLI_RELATIVE")
                require(cli.isFile) {
                    "Pi package extracted without $CLI_RELATIVE"
                }

                // Wrapper so PATH lookups find `pi` without depending on npm's bin linker.
                val bin = File(runtime.rootfs, PI_BINARY.removePrefix("/"))
                bin.parentFile?.mkdirs()
                bin.writeText(
                    """
                    #!/usr/bin/env node
                    import("file:///$MODULES_DIR/$PI_MODULE/$CLI_RELATIVE");
                    """.trimIndent() + "\n",
                )
                bin.setExecutable(true, false)
                bin.setReadable(true, false)

                require(isInstalledIn(runtime.rootfs)) {
                    "Pi installation completed without installing $PI_BINARY"
                }
                PI_VERSION
            }
        }

    private data class NpmPackage(
        val name: String,
        val version: String,
    ) {
        val fileName: String
            get() {
                val base = name.substringAfterLast("/")
                return "$base-$version.tgz"
            }

        /** Path under node_modules (scoped packages keep the @scope/ directory). */
        val modulePath: String
            get() = name

        val tarballUrl: String
            get() {
                val base = name.substringAfterLast("/")
                return if (name.startsWith("@")) {
                    "https://registry.npmjs.org/$name/-/$base-$version.tgz"
                } else {
                    "https://registry.npmjs.org/$name/-/$name-$version.tgz"
                }
            }
    }

    private fun downloadTarball(
        url: String,
        destination: File,
    ) {
        if (destination.isFile && destination.length() > 1_000L) return
        destination.parentFile?.mkdirs()
        val tmp = File(destination.parentFile, "${destination.name}.part")
        tmp.delete()
        val connection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 60_000
                readTimeout = 300_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "and-code-pi-installer/$PI_VERSION")
            }
        try {
            connection.inputStream.use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            require(tmp.length() > 1_000L) {
                "Downloaded package is too small (${tmp.length()} bytes): $url"
            }
            if (!tmp.renameTo(destination)) {
                tmp.copyTo(destination, overwrite = true)
                tmp.delete()
            }
        } finally {
            connection.disconnect()
        }
    }

    /** npm packs put contents under a top-level `package/` directory — strip it. */
    private fun extractNpmPackage(
        tarball: File,
        destination: File,
    ) {
        val staging = File(destination.parentFile, ".extract-${destination.name.replace('/', '_')}")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            tarball.inputStream().use { input ->
                RuntimeArchive.extractTarGz(input, staging)
            }
            val packageDir = File(staging, "package")
            require(packageDir.isDirectory) {
                "npm tarball ${tarball.name} has no package/ directory"
            }
            if (destination.exists()) destination.deleteRecursively()
            destination.parentFile?.mkdirs()
            require(packageDir.renameTo(destination) || packageDir.copyRecursively(destination, overwrite = true)) {
                "Failed to place ${destination.path}"
            }
        } finally {
            staging.deleteRecursively()
        }
    }
}
