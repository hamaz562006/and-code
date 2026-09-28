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

    @Test
    fun `isInstalledIn returns true only when pi binary exists`() {
        val rootfs = tempFolder.newFolder("rootfs")
        assertFalse(PiInstaller.isInstalledIn(rootfs))

        val binDir = File(rootfs, "usr/local/bin").apply { mkdirs() }
        val piBinary = File(binDir, "pi").apply {
            writeText("#!/bin/sh\necho 0.87.1")
            setExecutable(true)
        }

        assertTrue(PiInstaller.isInstalledIn(rootfs))
    }

    @Test
    fun `installedVersion reads marker or falls back to manifest version if installed`() {
        val rootfs = tempFolder.newFolder("rootfs2")
        assertNull(PiInstaller.installedVersion(rootfs))

        val binDir = File(rootfs, "usr/local/bin").apply { mkdirs() }
        val piBinary = File(binDir, "pi").apply {
            writeText("#!/bin/sh\necho 0.87.1")
            setExecutable(true)
        }

        assertEquals(PiManifest.VERSION, PiInstaller.installedVersion(rootfs))

        PiInstaller.writeInstalledVersion(rootfs, "0.87.1-custom")
        assertEquals("0.87.1-custom", PiInstaller.installedVersion(rootfs))
    }
}
