package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.LocalAgent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PiRegressionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `Pi installer only reports installed when its binary exists`() {
        val rootfs = temporaryFolder.newFolder("rootfs")

        assertFalse(PiInstaller.isInstalledIn(rootfs))

        val binary = rootfs.resolve("usr/local/bin/pi")
        binary.parentFile.mkdirs()
        binary.writeText("test")

        assertTrue(PiInstaller.isInstalledIn(rootfs))
    }

    @Test
    fun `runtime metadata preserves Pi and ignores unknown future agents`() {
        val metadata =
            Json.decodeFromString<LocalRuntimeMetadata>(
                """{"version":"1.18.3","port":4097,"installedAt":123,"components":["opencode","pi","future-agent"]}""",
            )

        assertTrue(metadata.has(LocalAgent.PI))
        assertEquals(
            setOf(LocalAgent.OPEN_CODE, LocalAgent.PI),
            metadata.installedAgents(),
        )
    }

    @Test
    fun `Pi target exposes shared provider model and tool event capabilities`() {
        val runtime =
            PiRuntime(
                runtimeDirectory = temporaryFolder.root,
                installedRuntimeProvider = { null },
            )
        val target = PiTarget(runtime)

        assertEquals(LocalAgent.PI.targetId, target.id)
        assertTrue(target.capabilities.providerModelList)
        assertTrue(target.capabilities.toolEvents)
        assertTrue(target.capabilities.resume)
        assertTrue(target.capabilities.abortsBeforeInterrupt)
    }
}
