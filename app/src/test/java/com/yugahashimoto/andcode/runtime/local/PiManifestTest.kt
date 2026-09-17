package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PiManifestTest {
    @Test
    fun `maps Android ABIs to official Linux release assets`() {
        assertEquals("pi-linux-arm64.tar.gz", PiManifest.assetFor("arm64-v8a").name)
        assertEquals("pi-linux-arm64.tar.gz", PiManifest.assetFor("aarch64").name)
        assertEquals("pi-linux-x64.tar.gz", PiManifest.assetFor("x86_64").name)
        assertEquals("pi-linux-x64.tar.gz", PiManifest.assetFor("amd64").name)
    }

    @Test
    fun `rejects unsupported ABI`() {
        assertThrows(IllegalStateException::class.java) { PiManifest.assetFor("armeabi-v7a") }
    }

    @Test
    fun `pins official release checksums`() {
        assertEquals(
            "042d20ae885ee4f3b102815f3280b962c377b2e9fb44de4037908cc530eae4d4",
            PiManifest.arm64.sha256,
        )
        assertEquals(
            "494e498f47d74d21f40b3386f6a5e921a3d49531a169cab55bbdaca0ea1fe25a",
            PiManifest.x64.sha256,
        )
    }
}
