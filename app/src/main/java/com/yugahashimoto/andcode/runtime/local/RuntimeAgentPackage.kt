package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.LocalAgent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Offline install packages for a single [LocalAgent]: binaries and supporting files already present
 * in the shared rootfs, so another device (or a wiped install) can restore without re-downloading.
 *
 * File naming: `{agentId}-{version}-{abi}-{yyyyMMdd-HHmm}.andcode.zip`
 */
object RuntimeAgentPackage {
    const val FORMAT = "andcode-agent-package"
    const val FORMAT_VERSION = 1
    const val MANIFEST_NAME = "manifest.json"
    const val FILE_EXTENSION = "andcode.zip"
    private const val PAYLOAD_PREFIX = "rootfs/"

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

    @Serializable
    data class Manifest(
        @SerialName("format") val format: String = FORMAT,
        @SerialName("formatVersion") val formatVersion: Int = FORMAT_VERSION,
        @SerialName("agentId") val agentId: String,
        @SerialName("agentVersion") val agentVersion: String,
        @SerialName("abi") val abi: String,
        @SerialName("exportedAt") val exportedAt: String,
        @SerialName("paths") val paths: List<String> = emptyList(),
        @SerialName("includeConfig") val includeConfig: Boolean = true,
    )

    data class ExportResult(
        val file: File,
        val suggestedName: String,
        val manifest: Manifest,
    )

    fun suggestedFileName(
        agent: LocalAgent,
        version: String,
        abi: String,
        at: Date = Date(),
    ): String {
        val safeVersion = version.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unknown" }
        val safeAbi = abi.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val stamp =
            SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }.format(at)
        return "${agent.id}-$safeVersion-$safeAbi-$stamp.$FILE_EXTENSION"
    }

    /**
     * Relative paths (files and directories) under [rootfs] to package for [agent].
     *
     * Includes the agent itself **and** the install-time dependencies that were provisioned
     * with it (e.g. Node/npm/ICU for Pi), so import can restore without re-downloading.
     */
    fun collectPaths(
        agent: LocalAgent,
        rootfs: File,
        includeConfig: Boolean = true,
    ): List<String> {
        val paths = linkedSetOf<String>()
        when (agent) {
            LocalAgent.PI -> {
                // Pi CLI tree (includes node_modules when npm deps were installed).
                addIfExists(paths, rootfs, "usr/local/bin/${PiInstaller.PI_BINARY}")
                addIfExists(paths, rootfs, "usr/local/bin/.${PiInstaller.PI_BINARY}-version")
                addIfExists(paths, rootfs, "usr/local/lib/pi-coding-agent")
                // Runtime packages installed with Pi: nodejs, npm, icu-data-full.
                addIfExists(paths, rootfs, "usr/bin/node")
                addIfExists(paths, rootfs, "usr/bin/nodejs")
                addIfExists(paths, rootfs, "usr/bin/npm")
                addIfExists(paths, rootfs, "usr/bin/npx")
                addIfExists(paths, rootfs, "usr/lib/node_modules")
                addPrefixed(paths, rootfs, "usr/lib", "libnode")
                // ICU data (icu-data-full) — required for Node on Alpine.
                addIfExists(paths, rootfs, "usr/share/icu")
                addPrefixed(paths, rootfs, "usr/lib", "libicu")
                if (includeConfig) {
                    addIfExists(paths, rootfs, "root/.pi")
                }
            }
            LocalAgent.CODEX -> {
                CodexInstaller.INSTALLED_BINARIES.forEach { name ->
                    addIfExists(paths, rootfs, "usr/local/bin/$name")
                }
                // Vendor payload directory if present alongside the binaries.
                addIfExists(paths, rootfs, "usr/local/lib/codex")
                addIfExists(paths, rootfs, "usr/local/share/codex")
                if (includeConfig) {
                    addIfExists(paths, rootfs, "root/.codex")
                }
            }
            LocalAgent.OPEN_CODE -> {
                addIfExists(paths, rootfs, "usr/local/bin/opencode")
                addIfExists(paths, rootfs, "usr/local/lib/opencode")
                addIfExists(paths, rootfs, "usr/local/share/opencode")
                if (includeConfig) {
                    addIfExists(paths, rootfs, "root/.local/share/opencode")
                    addIfExists(paths, rootfs, "root/.config/opencode")
                }
            }
            LocalAgent.CLAUDE_CODE -> {
                addIfExists(paths, rootfs, "usr/bin/claude")
                addIfExists(paths, rootfs, "usr/local/bin/claude")
                // Alpine package files for the Claude CLI when installed via apk.
                addIfExists(paths, rootfs, "usr/lib/claude-code")
                addIfExists(paths, rootfs, "usr/share/claude-code")
                if (includeConfig) {
                    addIfExists(paths, rootfs, "root/.claude")
                    addIfExists(paths, rootfs, "root/.config/claude")
                }
            }
            LocalAgent.ANTIGRAVITY -> {
                addIfExists(paths, rootfs, "usr/local/bin/agy")
                addIfExists(paths, rootfs, "usr/local/bin/antigravity")
                addIfExists(paths, rootfs, "usr/local/lib/antigravity")
                addIfExists(paths, rootfs, "usr/local/share/antigravity")
                if (includeConfig) {
                    addIfExists(paths, rootfs, "root/.antigravity")
                    addIfExists(paths, rootfs, "root/.config/antigravity")
                }
            }
            LocalAgent.GROK_BUILD -> {
                addIfExists(paths, rootfs, "usr/local/bin/${GrokBuildInstaller.GROK_BINARY}")
                addIfExists(paths, rootfs, "usr/local/bin/.${GrokBuildInstaller.GROK_BINARY}-version")
                if (includeConfig) {
                    addIfExists(paths, rootfs, "root/.grok")
                }
            }
        }
        return paths.toList()
    }

    private fun addIfExists(
        paths: MutableSet<String>,
        rootfs: File,
        relative: String,
    ) {
        val f = File(rootfs, relative)
        if (f.exists()) paths.add(relative.trim('/'))
    }

    /** Adds every file/dir under [relativeDir] whose name starts with [namePrefix]. */
    private fun addPrefixed(
        paths: MutableSet<String>,
        rootfs: File,
        relativeDir: String,
        namePrefix: String,
    ) {
        val dir = File(rootfs, relativeDir)
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { child ->
            if (child.name.startsWith(namePrefix)) {
                paths.add("${relativeDir.trim('/')}/${child.name}")
            }
        }
    }

    fun export(
        agent: LocalAgent,
        rootfs: File,
        abi: String,
        outputDir: File,
        includeConfig: Boolean = true,
    ): ExportResult {
        require(rootfs.isDirectory) { "Rootfs is not installed" }
        val version =
            when (agent) {
                LocalAgent.PI -> PiInstaller.installedVersion(rootfs) ?: PiInstaller.PI_VERSION
                LocalAgent.GROK_BUILD ->
                    GrokBuildInstaller.installedVersion(rootfs) ?: GrokBuildManifest.VERSION
                else -> "installed"
            }
        val paths = collectPaths(agent, rootfs, includeConfig)
        require(paths.isNotEmpty()) { "Nothing to export for ${agent.id}; is it installed?" }

        val exportedAt =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }.format(Date())
        val manifest =
            Manifest(
                agentId = agent.id,
                agentVersion = version,
                abi = abi,
                exportedAt = exportedAt,
                paths = paths,
                includeConfig = includeConfig,
            )
        val suggested = suggestedFileName(agent, version, abi)
        outputDir.mkdirs()
        val outFile = File(outputDir, suggested)
        if (outFile.exists()) outFile.delete()

        ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile))).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            zip.write(json.encodeToString(Manifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (relative in paths) {
                val source = File(rootfs, relative)
                if (source.isFile) {
                    putFile(zip, source, PAYLOAD_PREFIX + relative)
                } else if (source.isDirectory) {
                    source.walkTopDown().forEach { child ->
                        if (child.isFile) {
                            val rel = child.relativeTo(rootfs).path.replace(File.separatorChar, '/')
                            putFile(zip, child, PAYLOAD_PREFIX + rel)
                        }
                    }
                }
            }
        }
        return ExportResult(outFile, suggested, manifest)
    }

    private fun putFile(
        zip: ZipOutputStream,
        file: File,
        entryName: String,
    ) {
        zip.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    data class ImportResult(
        val manifest: Manifest,
        val agent: LocalAgent,
        val filesWritten: Int,
    )

    /** Reads the package manifest without writing into a rootfs. */
    fun peekManifest(packageFile: File): Manifest? {
        if (!packageFile.isFile) return null
        ZipInputStream(BufferedInputStream(FileInputStream(packageFile))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name == MANIFEST_NAME) {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    return json.decodeFromString(Manifest.serializer(), text)
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    /**
     * Restores an agent package into [rootfs] and returns the manifest.
     * Caller should [LocalRuntimeInstaller.recordAgent] afterward.
     */
    fun import(
        packageFile: File,
        rootfs: File,
        expectedAbi: String? = null,
    ): ImportResult {
        require(packageFile.isFile) { "Package file not found" }
        require(rootfs.isDirectory) { "Rootfs is not installed; set up the Linux environment first" }

        var manifest: Manifest? = null
        var written = 0
        ZipInputStream(BufferedInputStream(FileInputStream(packageFile))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.trimStart('/')
                if (entry.isDirectory) {
                    entry = zip.nextEntry
                    continue
                }
                if (name == MANIFEST_NAME) {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    manifest = json.decodeFromString(Manifest.serializer(), text)
                    entry = zip.nextEntry
                    continue
                }
                if (name.startsWith(PAYLOAD_PREFIX)) {
                    val relative = name.removePrefix(PAYLOAD_PREFIX)
                    if (relative.isBlank() || relative.contains("..")) {
                        entry = zip.nextEntry
                        continue
                    }
                    val dest = File(rootfs, relative)
                    dest.parentFile?.mkdirs()
                    FileOutputStream(dest).use { out -> zip.copyTo(out) }
                    if (
                        relative.startsWith("usr/local/bin/") ||
                        relative.startsWith("usr/bin/") ||
                        relative.endsWith(".so") ||
                        relative.contains("/node_modules/.bin/")
                    ) {
                        dest.setExecutable(true, false)
                    }
                    written++
                }
                entry = zip.nextEntry
            }
        }
        val m = manifest ?: error("Package is missing $MANIFEST_NAME")
        require(m.format == FORMAT) { "Unsupported package format: ${m.format}" }
        require(m.formatVersion <= FORMAT_VERSION) { "Package format version ${m.formatVersion} is newer than this app" }
        if (expectedAbi != null && m.abi != expectedAbi && m.abi != "unknown") {
            // Soft warning: still allow — binaries may be multi-arch scripts (Node/Pi).
        }
        val agent =
            LocalAgent.fromId(m.agentId)
                ?: error("Unknown agent id in package: ${m.agentId}")
        return ImportResult(m, agent, written)
    }
}
