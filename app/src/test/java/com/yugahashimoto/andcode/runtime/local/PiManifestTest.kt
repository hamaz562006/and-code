package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PiManifestTest {
    @Test
    fun `selects pinned official assets by ABI`() {
        assertEquals("pi-linux-arm64.tar.gz", PiManifest.assetFor("arm64-v8a").name)
        assertEquals("pi-linux-arm64.tar.gz", PiManifest.assetFor("aarch64").name)
        assertEquals("pi-linux-x64.tar.gz", PiManifest.assetFor("x86_64").name)
        assertEquals("pi-linux-x64.tar.gz", PiManifest.assetFor("amd64").name)
        assertEquals("364b4a9f8491450b27a4857d4e3c780dbaf696790821c176a873e860cbbc3b89", PiManifest.arm64.sha256)
        assertEquals("80d78dd62d50049a006b981d994c61255bcc10e730b0c278d4ea0a755909764c", PiManifest.x64.sha256)
        assertEquals("0.87.1", PiManifest.VERSION)
        assertEquals("pi", PiManifest.BINARY_NAME)
    }

    @Test
    fun `rejects unsupported ABI`() {
        assertThrows(IllegalStateException::class.java) { PiManifest.assetFor("armeabi-v7a") }
        assertThrows(IllegalStateException::class.java) { PiManifest.assetFor("x86") }
        assertThrows(IllegalStateException::class.java) { PiManifest.assetFor("mips") }
    }
}
