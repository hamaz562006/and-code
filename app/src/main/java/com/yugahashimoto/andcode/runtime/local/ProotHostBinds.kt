package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Host paths that must be visible inside proot for Android (bionic) binaries.
 *
 * [Duro02/grok-build-termux](https://github.com/Duro02/grok-build-termux) ships an
 * `aarch64-linux-android` binary whose INTERP is `/system/bin/linker64`. On modern Android that
 * path is a symlink into `/apex/com.android.runtime/...`. Binding only `/system` leaves the
 * symlink target missing, so exec reports "not found".
 */
object ProotHostBinds {
    /** Core mounts every local proot session already used, plus APEX/vendor for bionic. */
    private val HOST_PATHS =
        listOf(
            "/dev",
            "/proc",
            "/sys",
            "/system",
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
