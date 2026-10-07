package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Host paths that must be visible inside proot for Android (bionic) binaries.
 *
 * [Duro02/grok-build-termux](https://github.com/Duro02/grok-build-termux) ships an
 * `aarch64-linux-android` binary. Modern Android resolves `/system/bin/linker64` into
 * `/apex/...`. `/linkerconfig` is intentionally omitted: proot cannot bind it on many
 * devices (Permission denied) and the noise broke every diagnostics line.
 */
object ProotHostBinds {
    private val HOST_PATHS =
        listOf(
            "/dev",
            "/proc",
            "/sys",
            "/system",
            "/system_ext",
            "/product",
            "/apex",
            "/vendor",
        )

    /**
     * Appends `-b <path>` for each host path that exists on this device.
     * Call after `-r <rootfs>` and before `-w` / the guest command.
     */
    fun appendBindArgs(command: MutableList<String>) {
        for (path in HOST_PATHS) {
            if (File(path).exists()) {
                command.add("-b")
                command.add(path)
            }
        }
    }
}
