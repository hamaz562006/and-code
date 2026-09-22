package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Test

/** `codex-cli 0.155.1` is the real `codex --version` output, read on an Android emulator. */
class CodexVersionTest {
    @Test
    fun `drops the product name from the version line`() {
        assertEquals("0.155.1", parseVersionLine("codex-cli 0.155.1"))
    }

    @Test
    fun `tolerates surrounding whitespace`() {
        assertEquals("0.155.1", parseVersionLine("  codex-cli 0.155.1 \n"))
    }

    @Test
    fun `leaves an already bare version untouched`() {
        assertEquals("0.155.1", parseVersionLine("0.155.1"))
    }
}
