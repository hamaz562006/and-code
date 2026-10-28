package com.yugahashimoto.andcode.runtime.local

object CecliManifest {
    const val BINARY_NAME = "cecli"

    /** Tracks our packaging, not upstream cecli version alone. */
    const val VERSION = "0.1.0"
    const val MARKER_REL = "root/.cecli_andcode_installed"
    const val VERSION_REL = "root/.cecli_andcode_version"
}
