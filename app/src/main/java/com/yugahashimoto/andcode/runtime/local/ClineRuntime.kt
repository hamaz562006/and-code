package com.yugahashimoto.andcode.runtime.local

import java.io.File

/** Host-side Cline Termux port (not Alpine/Debian proot). */
class ClineRuntime(
    private val runtimeDirectory: File,
) {
    fun isInstalled(): Boolean = ClineInstaller.isInstalledIn(runtimeDirectory)

    fun installedVersion(): String? = ClineInstaller.installedVersion(runtimeDirectory)

    fun runVersion(): LocalRuntimeCommandResult =
        ClineInstaller.runOnHost(runtimeDirectory, listOf("--version"))
}
