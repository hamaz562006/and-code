package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeCommand

/** Common Aider slash-style commands exposed in the composer. */
object AiderCommandCatalog {
    val commands: List<OpenCodeCommand> =
        listOf(
            OpenCodeCommand(name = "add", description = "Add files to the chat"),
            OpenCodeCommand(name = "drop", description = "Remove files from the chat"),
            OpenCodeCommand(name = "ls", description = "List files currently in the chat"),
            OpenCodeCommand(name = "diff", description = "Show diff of changes since the chat began"),
            OpenCodeCommand(name = "undo", description = "Undo the last git commit by aider"),
            OpenCodeCommand(name = "commit", description = "Commit changes with a message"),
            OpenCodeCommand(name = "model", description = "Switch the model"),
            OpenCodeCommand(name = "models", description = "Search available models"),
            OpenCodeCommand(name = "help", description = "Show help"),
            OpenCodeCommand(name = "exit", description = "Exit the chat"),
            OpenCodeCommand(name = "clear", description = "Clear the chat history"),
            OpenCodeCommand(name = "tokens", description = "Report token usage"),
        )
}
