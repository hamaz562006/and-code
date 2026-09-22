package com.yugahashimoto.andcode.runtime.local

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.util.Base64

/**
 * The tarball layout matched here (`package/vendor/<target>/bin/codex`) is the real layout of
 * `@openai/codex-linux-{x64,arm64}` on npm, confirmed by downloading and inspecting the actual
 * package - see docs/CODEX.md. The tarball fixtures below are synthetic (a real one is 100+ MB),
 * built with the same layout to exercise the extraction and verification logic in isolation.
 */
class CodexInstallerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun buildTarGz(entries: Map<String, ByteArray>) =
        tempFolder.newFile("fixture.tgz").also { file ->
            GzipCompressorOutputStream(file.outputStream()).use { gzip ->
                TarArchiveOutputStream(gzip).use { tar ->
                    entries.forEach { (name, content) ->
                        val entry = TarArchiveEntry(name)
                        entry.size = content.size.toLong()
                        tar.putArchiveEntry(entry)
                        tar.write(content)
                        tar.closeArchiveEntry()
                    }
                }
            }
        }

    @Test
    fun `extracts only the target platform's binary, ignoring sibling vendor resources`() {
        val binaryBytes = "#!/bin/sh\necho fake-codex".toByteArray()
        val tarball =
            buildTarGz(
                mapOf(
                    "package/vendor/x86_64-unknown-linux-musl/bin/codex" to binaryBytes,
                    "package/vendor/x86_64-unknown-linux-musl/codex-resources/bwrap" to "not codex".toByteArray(),
                    "package/vendor/aarch64-unknown-linux-musl/bin/codex" to "wrong arch".toByteArray(),
                ),
            )
        val binDir = tempFolder.newFolder("extracted")

        CodexInstaller.extractBinaries(tarball, "x86_64-unknown-linux-musl", binDir, names = listOf("codex"))

        assertArrayEquals(binaryBytes, java.io.File(binDir, "codex").readBytes())
    }

    /**
     * `bin/codex-code-mode-host` sits next to `bin/codex` in the real 0.155.1 tarball, and Codex runs
     * every model tool call - image generation included - through it: installed without it, each call
     * failed with "failed to spawn code-mode host /usr/local/bin/codex-code-mode-host".
     */
    @Test
    fun `installs the code-mode host next to codex`() {
        val codex = "codex".toByteArray()
        val host = "code-mode host".toByteArray()
        val tarball =
            buildTarGz(
                mapOf(
                    "package/vendor/aarch64-unknown-linux-musl/bin/codex" to codex,
                    "package/vendor/aarch64-unknown-linux-musl/bin/codex-code-mode-host" to host,
                    "package/vendor/aarch64-unknown-linux-musl/codex-resources/voice/bin/codex-voice-host" to "voice".toByteArray(),
                ),
            )
        val binDir = tempFolder.newFolder("bin")

        CodexInstaller.extractBinaries(tarball, "aarch64-unknown-linux-musl", binDir)

        assertArrayEquals(codex, java.io.File(binDir, "codex").readBytes())
        assertArrayEquals(host, java.io.File(binDir, "codex-code-mode-host").readBytes())
        assertTrue(!java.io.File(binDir, "codex-voice-host").exists())
    }

    /**
     * A tarball with `codex` but no host fails the install rather than leaving a half install that
     * reports success. `codex` itself may already have been replaced by then; the install still reads
     * as not installed, so it is simply redone.
     */
    @Test
    fun `a tarball without the code-mode host fails and names it`() {
        val tarball = buildTarGz(mapOf("package/vendor/aarch64-unknown-linux-musl/bin/codex" to "codex".toByteArray()))
        val rootfs = tempFolder.newFolder("rootfs-partial")
        val binDir = java.io.File(rootfs, "usr/local/bin")

        val error =
            assertThrows(IllegalStateException::class.java) {
                CodexInstaller.extractBinaries(tarball, "aarch64-unknown-linux-musl", binDir)
            }

        assertTrue(error.message.orEmpty().contains("bin/codex-code-mode-host"))
        assertTrue(!CodexInstaller.isInstalledIn(rootfs))
    }

    @Test
    fun `an install missing the code-mode host is not installed`() {
        val rootfs = tempFolder.newFolder("rootfs")
        java.io.File(rootfs, "usr/local/bin").mkdirs()
        java.io.File(rootfs, "usr/local/bin/codex").writeText("codex")

        assertTrue(!CodexInstaller.isInstalledIn(rootfs))

        java.io.File(rootfs, "usr/local/bin/codex-code-mode-host").writeText("host")
        assertTrue(CodexInstaller.isInstalledIn(rootfs))
    }

    @Test
    fun `a tarball with no matching entry fails loudly instead of installing nothing`() {
        val tarball = buildTarGz(mapOf("package/vendor/aarch64-unknown-linux-musl/bin/codex" to "arm binary".toByteArray()))
        val binDir = tempFolder.newFolder("extracted")

        assertThrows(IllegalStateException::class.java) {
            CodexInstaller.extractBinaries(tarball, "x86_64-unknown-linux-musl", binDir)
        }
    }

    @Test
    fun `accepts a download whose SHA-512 matches the recorded integrity string`() {
        val content = "codex binary bytes".toByteArray()
        val file = tempFolder.newFile("download").apply { writeBytes(content) }
        val digest = MessageDigest.getInstance("SHA-512").digest(content)
        val integrity = "sha512-" + Base64.getEncoder().encodeToString(digest)

        CodexInstaller.verifySha512(file, integrity)
    }

    @Test
    fun `rejects a download whose bytes do not match the recorded integrity string`() {
        val file = tempFolder.newFile("download").apply { writeBytes("actual bytes".toByteArray()) }
        val wrongDigest = MessageDigest.getInstance("SHA-512").digest("different bytes".toByteArray())
        val integrity = "sha512-" + Base64.getEncoder().encodeToString(wrongDigest)

        val error = assertThrows(IllegalStateException::class.java) { CodexInstaller.verifySha512(file, integrity) }
        assertTrue(error.message.orEmpty().contains("mismatch"))
    }
}
