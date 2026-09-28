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
    fun `listAgents returns single Pi primary agent`() =
        runBlocking {
            val runtimeDir = tempFolder.newFolder("runtime-agents")
            val runtime = PiRuntime(runtimeDir, { null })
            val target = PiTarget(runtime)

            val agents = target.listAgents()
            assertEquals(1, agents.size)
            assertEquals("pi", agents.first().name)
            assertEquals("Pi", agents.first().description)
            assertEquals("primary", agents.first().mode)
        }

    @Test
    fun `listProviders returns Pi catalog`() =
        runBlocking {
            val runtimeDir = tempFolder.newFolder("runtime-providers")
            val runtime = PiRuntime(runtimeDir, { null })
            val target = PiTarget(runtime)

            val catalog = target.listProviders()
            assertEquals(1, catalog.all.size)
            assertEquals("pi", catalog.all.first().id)
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
            val binDir = File(rootfs, "usr/local/bin").apply { mkdirs() }
            val piBinary =
                File(binDir, "pi").apply {
                    writeText("#!/bin/sh\necho 0.87.1")
                    setExecutable(true)
                }
            PiInstaller.writeInstalledVersion(rootfs, "0.87.1")

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
