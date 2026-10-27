package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeCommand

/** Slash commands exposed for the Cline host agent (independent of Aider/OpenCode catalogs). */
object ClineCommandCatalog {
    val commands: List<OpenCodeCommand> =
        listOf(
            OpenCodeCommand(name = "help", description = "Show Cline help"),
            OpenCodeCommand(name = "model", description = "Select model"),
            OpenCodeCommand(name = "settings", description = "Open settings"),
        )
}
