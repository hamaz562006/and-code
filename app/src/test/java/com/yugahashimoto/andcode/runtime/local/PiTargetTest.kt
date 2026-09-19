package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.LocalAgent
import com.yugahashimoto.andcode.runtime.RuntimeType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertTrue(target.capabilities.toolEvents)
    }

    @Test
    fun `creates and lists sessions`() = runBlocking {
        val runtimeDir = tempFolder.newFolder("runtime")
        val runtime = PiRuntime(runtimeDir, { null })
        val target = PiTarget(runtime)

        val session = target.createSession(title = "My Test Chat", directory = "/workspace/project")
        assertNotNull(session.id)
        assertEquals("My Test Chat", session.title)
        assertEquals("/workspace/project", session.directory)

        val sessions = target.listSessions()
        assertEquals(1, sessions.size)
        assertEquals(session.id, sessions[0].id)
        assertEquals("My Test Chat", sessions[0].title)

        // Rename session
        val renamed = target.renameSession(session.id, "Updated Name")
        assertEquals("Updated Name", renamed.title)
        val sessionsAfterRename = target.listSessions()
        assertEquals("Updated Name", sessionsAfterRename[0].title)

        // Delete session
        val deleted = target.deleteSession(session.id)
        assertTrue(deleted)
        val sessionsAfterDelete = target.listSessions()
        assertTrue(sessionsAfterDelete.isEmpty())
    }

    @Test
    fun `lists providers catalog with Pi default`() = runBlocking {
        val runtimeDir = tempFolder.newFolder("runtime")
        val runtime = PiRuntime(runtimeDir, { null })
        val target = PiTarget(runtime)

        val catalog = target.listProviders()
        assertTrue(catalog.connected.contains(PiModels.PROVIDER_ID))
        assertEquals(1, catalog.all.size)
        assertEquals("pi", catalog.all[0].id)
    }
}
