package com.yugahashimoto.andcode.runtime

import com.yugahashimoto.andcode.data.connection.ConnectionProfile
import com.yugahashimoto.andcode.runtime.local.AntigravityRuntime
import com.yugahashimoto.andcode.runtime.local.AntigravityTarget
import com.yugahashimoto.andcode.runtime.local.ClaudeCodeRuntime
import com.yugahashimoto.andcode.runtime.local.ClaudeCodeTarget
import com.yugahashimoto.andcode.runtime.remote.RemoteRuntimeTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Which backends the chat's diff dialog can actually fetch a diff from
 * ([RuntimeCapabilities.diffCapable]).
 *
 * OpenCode (local and remote) and Claude Code are git-backed and answer real diffs; Antigravity has
 * no diff surface at all, so it must stay `false` rather than let the chat call a method that only
 * ever returns nothing.
 */
class DiffCapableTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `remote OpenCode is diff-capable`() {
        val target = RemoteRuntimeTarget(ConnectionProfile(name = "test", baseUrl = "https://example.test"))

        assertTrue(target.capabilities.diffCapable)
    }

    @Test
    fun `Claude Code is diff-capable`() {
        val target = ClaudeCodeTarget(ClaudeCodeRuntime(folder.root, { null }))

        assertTrue(target.capabilities.diffCapable)
    }

    @Test
    fun `Antigravity is not diff-capable`() {
        val target = AntigravityTarget(AntigravityRuntime(folder.root, { null }))

        assertFalse(target.capabilities.diffCapable)
    }

    /**
     * Antigravity's own non-git-repo fallback (see [AntigravityTarget.vcsInfo]) must read the same
     * way Claude Code's does, or the Explorer's Changes tab and the chat's diff view - both of which
     * recognize "not a git repository" and fall back to a file-list-only view - would show a raw,
     * unrecognized error for Antigravity instead.
     */
    @Test
    fun `Antigravity's non-git fallback matches Claude Code's own`() =
        runBlocking {
            val antigravity = AntigravityTarget(AntigravityRuntime(folder.root, { null }))

            val error = runCatching { antigravity.vcsInfo("/workspace/project") }.exceptionOrNull()

            assertEquals(true, error?.message?.let { "git" in it.lowercase() && "not" in it.lowercase() })
            assertEquals(emptyList<Any>(), antigravity.vcsStatus("/workspace/project"))
            assertEquals(emptyList<Any>(), antigravity.vcsDiff("/workspace/project"))
            assertEquals(emptyList<Any>(), antigravity.sessionDiff("s1", "/workspace/project", null))
        }
}
