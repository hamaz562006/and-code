package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.RuntimeState
import com.yugahashimoto.andcode.runtime.RuntimeType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PiTargetTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun installPiLayout(rootfs: File) {
        val binDir = File(rootfs, "usr/local/bin").apply { mkdirs() }
        File(binDir, "pi").apply {
            writeText("#!/bin/sh\necho 0.87.1\n")
            setExecutable(true)
        }
        val cli = File(rootfs, "usr/local/lib/pi-coding-agent/dist/bundle/cli.js")
        cli.parentFile?.mkdirs()
        cli.writeText("console.log('pi')\n")
        File(rootfs, "usr/local/lib/pi-coding-agent/node_modules").mkdirs()
        PiInstaller.writeInstalledVersion(rootfs, "0.87.1")
    }

    @Test
    fun `target exposes correct identity and capabilities`() {
        val runtimeDir = tempFolder.newFolder("runtime")
        val runtime = PiRuntime(runtimeDir, { null })
        val target = PiTarget(runtime)

        assertEquals("pi-local", target.id)
        assertEquals("Pi", target.displayName)
        assertEquals(LocalAgent.PI, target.agent)
        assertEquals(BackendKind.LOCAL, target.kind)
        assertEquals(RuntimeType.LOCAL, target.type)
        assertTrue(target.capabilities.providerModelList)
        assertTrue(target.capabilities.toolEvents)
        assertFalse(target.capabilities.permissions)
    }

    @Test
    fun `listProviders returns seeded multi-provider catalog`() =
        runBlocking {
            val runtimeDir = tempFolder.newFolder("runtime-providers")
            val runtime = PiRuntime(runtimeDir, { null })
            val target = PiTarget(runtime)

            val catalog = target.listProviders()
            assertTrue(catalog.all.size >= 4)
            val ids = catalog.all.map { it.id }.toSet()
            assertTrue(ids.contains("anthropic"))
            assertTrue(ids.contains("openai"))
            assertTrue(ids.contains("google"))
        }

    @Test
    fun `providerAuthMethods offers API key for each seed provider`() =
        runBlocking {
            val runtimeDir = tempFolder.newFolder("runtime-auth")
            val runtime = PiRuntime(runtimeDir, { null })
            val target = PiTarget(runtime)

            val methods = target.providerAuthMethods()
            assertTrue(methods.containsKey("anthropic"))
            assertEquals("api", methods.getValue("anthropic").first().type)
        }

    @Test
    fun `connect reports Unavailable when runtime is not installed`() =
        runBlocking {
            val runtimeDir = tempFolder.newFolder("runtime-uninstalled")
            val runtime = PiRuntime(runtimeDir, { null })
            val target = PiTarget(runtime)

            val health = target.connect()
            assertTrue(health.isFailure)
            assertTrue(target.state.value is RuntimeState.Unavailable)
        }

    @Test
    fun `connect reports Connected when runtime binary is present`() =
        runBlocking {
            val runtimeDir = tempFolder.newFolder("runtime-installed")
            val rootfs = File(runtimeDir, "environment/rootfs").apply { mkdirs() }
            installPiLayout(rootfs)

            val installedRuntime =
                LocalRuntimeInstaller.InstalledRuntime(
                    metadata = LocalRuntimeMetadata(version = "test", port = 0, installedAt = 0),
                    commandSuite =
                        EmbeddedCommandSuite.Paths(
                            home = rootfs,
                            tmp = rootfs,
                            nativeLibraryDirectory = rootfs,
                            proot = rootfs,
                            loader = rootfs,
                            loader32 = rootfs,
                        ),
                    rootfs = rootfs,
                    openCode = null,
                    antigravityRootfs = null,
                )

            val runtime = PiRuntime(runtimeDir, { installedRuntime })
            val target = PiTarget(runtime)

            val health = target.connect()
            assertTrue(health.isSuccess)
            val state = target.state.value
            assertTrue(state is RuntimeState.Connected)
            assertEquals("0.87.1", (state as RuntimeState.Connected).version)
        }
}
