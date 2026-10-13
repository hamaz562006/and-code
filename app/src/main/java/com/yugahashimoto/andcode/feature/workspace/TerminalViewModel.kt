package com.yugahashimoto.andcode.feature.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker
import com.yugahashimoto.andcode.runtime.local.LocalRuntimeCommandRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TerminalLineType { INPUT, OUTPUT, ERROR, SYSTEM }

data class TerminalLine(
    val text: String,
    val type: TerminalLineType,
)

data class TerminalUiState(
    val lines: List<TerminalLine> = emptyList(),
    val isRunning: Boolean = false,
    val currentInput: String = "",
    val workingDirectory: String = "/root",
)

class TerminalViewModel(
    private val commandRunner: LocalRuntimeCommandRunner,
    /**
     * A shell command run here is real work on the runtime's proot process just like a chat turn,
     * but the terminal never touches
     * [com.yugahashimoto.andcode.data.repository.RuntimeActivityRepository] - the only place that
     * already tracks that as work - so without a lease the device could suspend mid-command.
     */
    private val runtimeWork: RuntimeWorkTracker,
    /** Active agent label for the banner, e.g. "Pi" or "OpenCode". */
    private val agentLabel: () -> String = { "OpenCode" },
    /** True when the selected agent is Hermes (host CLI, not Alpine proot). */
    private val isHermesHost: () -> Boolean = { false },
    /** App runtime dir used for Hermes host execution (`filesDir/runtime`). */
    private val hermesRuntimeDirectory: java.io.File? = null,
) : ViewModel() {
    private fun systemBanner(): TerminalLine {
        val label = agentLabel()
        val env =
            if (label.equals("Hermes", ignoreCase = true)) {
                "host CLI · 127.0.0.1:8642"
            } else {
                "PRoot Alpine Linux"
            }
        return TerminalLine("$label Terminal - $env", TerminalLineType.SYSTEM)
    }

    private val _state =
        MutableStateFlow(
            TerminalUiState(
                lines =
                    listOf(
                        systemBanner(),
                    ),
            ),
        )
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    fun executeCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return

        _state.update { s ->
            s.copy(
                lines = appendLine(s.lines, TerminalLine("${s.workingDirectory} $ $trimmed", TerminalLineType.INPUT)),
                currentInput = "",
                isRunning = true,
            )
        }

        viewModelScope.launch {
            val result =
                runtimeWork.withLease(TERMINAL_LEASE_TAG) {
                    withContext(Dispatchers.IO) {
                        if (isHermesHost()) {
                            val dir =
                                hermesRuntimeDirectory
                                    ?: error("Hermes runtime directory missing")
                            if (!com.yugahashimoto.andcode.runtime.local.HermesInstaller.isInstalledIn(dir)) {
                                com.yugahashimoto.andcode.runtime.local.LocalRuntimeCommandResult(
                                    127,
                                    "Hermes is not installed on this device",
                                )
                            } else {
                                val parts =
                                    trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
                                val argv =
                                    when {
                                        parts.isEmpty() -> listOf("--help")
                                        parts.first() == "hermes" -> parts.drop(1).ifEmpty { listOf("--help") }
                                        else -> parts
                                    }
                                val result =
                                    com.yugahashimoto.andcode.runtime.local.HermesInstaller.runOnHost(
                                        dir,
                                        argv,
                                        timeoutSeconds = 60L,
                                    )
                                com.yugahashimoto.andcode.runtime.local.LocalRuntimeCommandResult(
                                    result.exitCode,
                                    result.output.ifBlank {
                                        if (result.exitCode == 0) "" else "exit ${result.exitCode}"
                                    },
                                )
                            }
                        } else {
                            val fullCommand =
                                if (_state.value.workingDirectory != "/root") {
                                    "cd ${_state.value.workingDirectory} && $trimmed"
                                } else {
                                    trimmed
                                }
                            commandRunner.runShell(fullCommand, timeoutSeconds = 30L)
                        }
                    }
                }

            _state.update { s ->
                val newLines = s.lines.toMutableList()
                if (result.output.isNotBlank()) {
                    result.output.lines().forEach { line ->
                        val type = if (result.exitCode != 0) TerminalLineType.ERROR else TerminalLineType.OUTPUT
                        newLines.add(TerminalLine(line, type))
                    }
                }
                if (result.exitCode != 0 && result.output.isBlank()) {
                    newLines.add(TerminalLine("exit code: ${result.exitCode}", TerminalLineType.ERROR))
                }
                s.copy(
                    lines = trimScrollback(newLines),
                    isRunning = false,
                    workingDirectory = resolveWorkingDirectory(s.workingDirectory, trimmed),
                )
            }
        }
    }

    fun updateInput(text: String) {
        _state.update { it.copy(currentInput = text) }
    }

    fun clear() {
        _state.update {
            it.copy(
                lines =
                    listOf(
                        systemBanner(),
                    ),
            )
        }
    }

    private fun resolveWorkingDirectory(
        current: String,
        command: String,
    ): String {
        val cdPattern = Regex("""^cd\s+(.*)$""")
        val match = cdPattern.find(command.trim()) ?: return current
        val target = match.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("'")
        return when {
            target.startsWith("/") -> target
            target == "~" -> "/root"
            target == ".." -> current.substringBeforeLast("/", "").ifEmpty { "/" }.ifEmpty { "/" }
            target == "." -> current
            else -> if (current == "/") "/$target" else "$current/$target"
        }
    }

    private fun appendLine(
        lines: List<TerminalLine>,
        line: TerminalLine,
    ): List<TerminalLine> {
        return trimScrollback(lines + line)
    }

    private fun trimScrollback(lines: List<TerminalLine>): List<TerminalLine> {
        return if (lines.size > MAX_SCROLLBACK) lines.takeLast(MAX_SCROLLBACK) else lines
    }

    private companion object {
        const val MAX_SCROLLBACK = 500
        const val TERMINAL_LEASE_TAG = "terminal"
    }
}
