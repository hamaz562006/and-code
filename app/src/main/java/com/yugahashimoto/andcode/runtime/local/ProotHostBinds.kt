package com.yugahashimoto.andcode.runtime.local

import java.io.File

/**
 * Host paths that must be visible inside proot for Android (bionic) binaries.
 *
 * [Duro02/grok-build-termux](https://github.com/Duro02/grok-build-termux) ships an
 * `aarch64-linux-android` binary. On modern Android:
 * - `/system/bin/linker64` → symlink into `/apex/com.android.runtime/...`
 * - dynamic libs such as `libandroidicu.so` live under other APEX packages
 * - the linker reads namespace config from `/linkerconfig`
 *
 * Binding only Alpine guest paths leaves those host locations missing.
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
            "/linkerconfig",
        )

    /**
     * Library search path for bionic binaries run under proot (Termux-style).
     * Applied in [localRuntimeEnvironment] and the Grok wrapper.
     */
    val BIONIC_LD_LIBRARY_PATH: String =
        listOf(
            "/apex/com.android.runtime/lib64",
            "/apex/com.android.i18n/lib64",
            "/apex/com.android.art/lib64",
            "/apex/com.android.os.statsd/lib64",
            "/system/lib64",
            "/system/lib",
            "/system_ext/lib64",
            "/system_ext/lib",
            "/vendor/lib64",
            "/vendor/lib",
            "/product/lib64",
            "/product/lib",
        ).joinToString(":")

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
