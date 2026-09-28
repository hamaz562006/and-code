package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PiSandboxLauncherTest {
    @Test
    fun `command launches pi binary in proot with workspace bind`() {
        val rootfs = File("/tmp/mock-rootfs")
        val runtime =
            LocalRuntimeInstaller.InstalledRuntime(
                metadata = LocalRuntimeMetadata(version = "test", port = 0, installedAt = 0),
                commandSuite =
                    EmbeddedCommandSuite.Paths(
                        home = rootfs,
                        tmp = rootfs,
                        nativeLibraryDirectory = rootfs,
                        proot = File("/tmp/mock-proot"),
                        loader = rootfs,
                        loader32 = rootfs,
                    ),
                rootfs = rootfs,
                openCode = null,
                antigravityRootfs = null,
            )

        val command = PiSandboxLauncher.command(runtime, "/host/workspace", listOf("--mode", "rpc"))
        assertTrue(command.first() == "/tmp/mock-proot")
        assertTrue(command.contains(PiSandboxLauncher.PI_BINARY))
        assertTrue(command.contains("/host/workspace:/workspace"))
        assertTrue(command.contains("--mode"))
        assertTrue(command.contains("rpc"))
    }

    @Test
    fun `environment contains expected path and home variables`() {
        val rootfs = File("/tmp/mock-rootfs")
        val runtime =
            LocalRuntimeInstaller.InstalledRuntime(
                metadata = LocalRuntimeMetadata(version = "test", port = 0, installedAt = 0),
                commandSuite =
                    EmbeddedCommandSuite.Paths(
                        home = rootfs,
                        tmp = rootfs,
                        nativeLibraryDirectory = rootfs,
                        proot = File("/tmp/mock-proot"),
                        loader = rootfs,
                        loader32 = rootfs,
                    ),
                rootfs = rootfs,
                openCode = null,
                antigravityRootfs = null,
            )

        val env = PiSandboxLauncher.environment(runtime, File("/tmp/mock-tmp"))
        assertEquals("/root", env["HOME"])
        assertEquals("xterm-256color", env["TERM"])
        assertTrue(env["PATH"]?.contains("/usr/local/bin") == true)
        assertEquals("/etc/ssl/certs/ca-certificates.crt", env["SSL_CERT_FILE"])
    }
}
