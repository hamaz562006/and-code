package com.yugahashimoto.andcode.feature.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSetupScreenTest {
    @Test
    fun `minimal install is skipped only when the selected agents are already installed`() {
        assertTrue(shouldStartRuntimeInstall(installComplete = false, selectedDevelopmentTools = false))
        assertFalse(shouldStartRuntimeInstall(installComplete = true, selectedDevelopmentTools = false))
    }

    @Test
    fun `full toolchain option runs even when the selected agents are already installed`() {
        assertTrue(shouldStartRuntimeInstall(installComplete = true, selectedDevelopmentTools = true))
    }

    @Test
    fun `full toolchain is not started again when it is already installed`() {
        assertFalse(
            shouldStartRuntimeInstall(
                installComplete = true,
                selectedDevelopmentTools = true,
                fullDevelopmentToolsInstalled = true,
            ),
        )
    }
}
