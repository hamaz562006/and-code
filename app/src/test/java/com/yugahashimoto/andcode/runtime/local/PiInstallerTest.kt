package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PiInstallerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun installLayout(rootfs: File) {
        val binDir = File(rootfs, "usr/local/bin").apply { mkdirs() }
        File(binDir, "pi").apply {
            writeText("#!/bin/sh\nexec node /usr/local/lib/pi-coding-agent/dist/bundle/cli.js \"\$@\"\n")
            setExecutable(true)
        }
        val cli = File(rootfs, "usr/local/lib/pi-coding-agent/dist/bundle/cli.js")
        cli.parentFile?.mkdirs()
        cli.writeText("console.log('pi')\n")
        File(rootfs, "usr/local/lib/pi-coding-agent/node_modules").mkdirs()
    }

    @Test
    fun `isInstalledIn returns true only when shim and cli exist`() {
        val rootfs = tempFolder.newFolder("rootfs")
        assertFalse(PiInstaller.isInstalledIn(rootfs))

        installLayout(rootfs)
        assertTrue(PiInstaller.isInstalledIn(rootfs))
    }

    @Test
    fun `installedVersion reads marker or falls back to manifest version if installed`() {
        val rootfs = tempFolder.newFolder("rootfs2")
        assertNull(PiInstaller.installedVersion(rootfs))

        installLayout(rootfs)
        assertEquals(PiManifest.VERSION, PiInstaller.installedVersion(rootfs))

        PiInstaller.writeInstalledVersion(rootfs, "0.87.1-custom")
        assertEquals("0.87.1-custom", PiInstaller.installedVersion(rootfs))
    }
}
