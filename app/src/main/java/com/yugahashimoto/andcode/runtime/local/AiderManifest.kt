package com.yugahashimoto.andcode.runtime.local

object AiderManifest {
    const val BINARY_NAME = "aider"
    /** Tracks our packaging, not upstream aider version alone. */
    const val VERSION = "1.0.0-termux"
    const val MARKER_REL = "root/.aider_andcode_installed"
    const val VERSION_REL = "root/.aider_andcode_version"
}
