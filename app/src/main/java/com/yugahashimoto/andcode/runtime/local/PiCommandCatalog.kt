package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeCommand

/**
 * Slash commands advertised by the Pi coding agent (interactive mode).
 * Source: @mariozechner/pi-coding-agent README → Interactive Mode → Commands.
 *
 * Without this catalog [PiTarget] falls through to [OpenCodeBackend.commands]'s default
 * `unsupported`, so the composer only shows the six app-level entries from
 * [com.yugahashimoto.andcode.feature.chat.SlashCommandRegistry].
 */
object PiCommandCatalog {
    val commands: List<OpenCodeCommand> =
        listOf(
            OpenCodeCommand(name = "settings", description = "Thinking level, theme, message delivery, transport"),
            OpenCodeCommand(name = "model", description = "Switch models"),
            OpenCodeCommand(name = "scoped-models", description = "Enable/disable models for Ctrl+P cycling"),
            OpenCodeCommand(name = "login", description = "OAuth authentication"),
            OpenCodeCommand(name = "resume", description = "Pick from previous sessions"),
            OpenCodeCommand(name = "new", description = "Start a new session"),
            OpenCodeCommand(name = "name", description = "Set session display name"),
            OpenCodeCommand(name = "session", description = "Show session info (file, ID, messages, tokens, cost)"),
            OpenCodeCommand(name = "tree", description = "Jump to any point in the session and continue from there"),
            OpenCodeCommand(name = "fork", description = "Create a new session from a previous user message"),
            OpenCodeCommand(name = "clone", description = "Duplicate the current active branch into a new session"),
            OpenCodeCommand(name = "compact", description = "Manually compact context, optional custom instructions"),
            OpenCodeCommand(name = "copy", description = "Copy last assistant message to clipboard"),
            OpenCodeCommand(name = "export", description = "Export session to HTML file"),
            OpenCodeCommand(name = "share", description = "Upload as private GitHub gist with shareable HTML link"),
            OpenCodeCommand(name = "reload", description = "Reload keybindings, extensions, skills, prompts, and context files"),
            OpenCodeCommand(name = "hotkeys", description = "Show all keyboard shortcuts"),
            OpenCodeCommand(name = "changelog", description = "Display version history"),
            OpenCodeCommand(name = "quit", description = "Quit pi"),
        )
}
