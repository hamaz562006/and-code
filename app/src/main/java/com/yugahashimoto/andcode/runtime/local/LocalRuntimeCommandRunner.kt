package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.storage.DeviceStorage
import java.io.File
import java.util.concurrent.TimeUnit

class LocalRuntimeCommandRunner(
    private val runtimeDirectory: File,
    private val installedRuntimeProvider: () -> LocalRuntimeInstaller.InstalledRuntime?,
    private val accessCoordinator: LocalRuntimeAccessCoordinator = LocalRuntimeAccessCoordinator(),
    private val timeoutSeconds: Long = 15L,
    private val maxOutputCharacters: Int = 4_000,
    private val messages: LocalRuntimeMessages = LocalRuntimeMessages,
) {
    init {
        require(timeoutSeconds > 0)
        require(maxOutputCharacters > 0)
    }

    fun run(definition: LocalRuntimeToolDefinition): LocalRuntimeCommandResult = runShell(definition.command)

    @Synchronized
    fun runShell(
        commandText: String,
        timeoutSeconds: Long = this.timeoutSeconds,
    ): LocalRuntimeCommandResult =
        accessCoordinator.read {
            require(timeoutSeconds > 0L)
            val runtime =
                installedRuntimeProvider()
                    ?: return@read LocalRuntimeCommandResult(127, messages.notInstalled)
            // Grok Build is a Termux/Android (bionic) binary — Alpine proot cannot satisfy its
            // system media / linkerconfig dependencies. Run simple `grok …` invocations on host.
            val hostGrokArgs = hostGrokArgsOrNull(commandText)
            if (hostGrokArgs != null) {
                return@read GrokBuildInstaller.runOnHost(runtime.rootfs, hostGrokArgs, timeoutSeconds)
            }
            val hostHermesArgs = hostHermesArgsOrNull(commandText)
            if (hostHermesArgs != null) {
                val result =
                    HermesInstaller.runOnHost(
                        runtimeDirectory,
                        hostHermesArgs,
                        timeoutSeconds,
                    )
                return@read LocalRuntimeCommandResult(result.exitCode, result.output)
            }
            val prootTmp = File(runtimeDirectory, "proot-tmp").apply { mkdirs() }
            val outputFile = File.createTempFile("diagnostic-", ".log", File(runtimeDirectory, "logs").apply { mkdirs() })
            try {
                val command =
                    buildList {
                        add(runtime.commandSuite.proot.absolutePath)
                        add("--kill-on-exit")
                        add("--link2symlink")
                        add("-0")
                        add("-r")
                        add(runtime.rootfs.absolutePath)
                        ProotHostBinds.appendBindArgs(this)
                        // So a shell command can reach the device's files once the user allows it.
                        addAll(DeviceStorage.bindArguments())
                        add("-w")
                        add("/root")
                        add("/bin/sh")
                        add("-lc")
                        add(commandText)
                    }
                val process =
                    ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.to(outputFile))
                        .apply {
                            environment().clear()
                            environment().putAll(localRuntimeEnvironment(runtime.commandSuite.environment(), prootTmp))
                        }
                        .start()
                val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    process.waitFor(2, TimeUnit.SECONDS)
                    LocalRuntimeCommandResult(124, messages.commandTimedOut)
                } else {
                    LocalRuntimeCommandResult(
                        exitCode = process.exitValue(),
                        output = outputFile.readText().takeLast(maxOutputCharacters),
                    )
                }
            } finally {
                outputFile.delete()
            }
        }

    /**
     * Returns argv after `grok` when [commandText] is a plain grok invocation (no shell metacharacters).
     * Complex pipelines stay in proot and will fail for this binary by design.
     */
    private fun hostGrokArgsOrNull(commandText: String): List<String>? {
        val trimmed = commandText.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it in charArrayOf('|', ';', '&', '>', '<', '`') }) return null
        if ("&&" in trimmed || "||" in trimmed) return null
        val parts = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val head = parts.first()
        if (head != "grok" && head != "/usr/local/bin/grok") return null
        return parts.drop(1)
    }

    /** Hermes is Termux/bionic — run plain `hermes …` on the host, not Alpine proot. */
    private fun hostHermesArgsOrNull(commandText: String): List<String>? {
        val trimmed = commandText.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it in charArrayOf('|', ';', '&', '>', '<', '`') }) return null
        if ("&&" in trimmed || "||" in trimmed) return null
        val parts = trimmed.split(Regex("\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val head = parts.first()
        if (head != "hermes" && !head.endsWith("/hermes")) return null
        return parts.drop(1)
    }
}
