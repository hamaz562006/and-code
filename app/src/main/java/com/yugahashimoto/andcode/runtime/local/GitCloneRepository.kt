package com.yugahashimoto.andcode.runtime.local

import java.io.File
import java.util.concurrent.TimeUnit

data class GitCloneResult(
    val exitCode: Int,
    val serverPath: String,
    val output: String,
)

/**
 * Clones a Git repository into the local runtime's shared workspace so the
 * OpenCode server can open it as a project. The clone runs inside the same
 * PRoot environment as the runtime, binding the host workspace directory to
 * /workspace and exporting the GitHub token for private repositories.
 */
class GitCloneRepository(
    private val runtimeDirectory: File,
    private val installedRuntimeProvider: () -> LocalRuntimeInstaller.InstalledRuntime?,
    private val accessCoordinator: LocalRuntimeAccessCoordinator = LocalRuntimeAccessCoordinator(),
    private val githubToken: () -> String? = { null },
    private val timeoutSeconds: Long = 300L,
) {
    fun clone(
        url: String,
        name: String,
    ): GitCloneResult =
        accessCoordinator.read {
            val sanitizedName = name.trim().trimStart('.', '/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
            if (sanitizedName.isBlank() || sanitizedName.contains("..")) {
                return@read GitCloneResult(1, "", "Invalid repository name: $name")
            }
            val runtime =
                installedRuntimeProvider()
                    ?: return@read GitCloneResult(127, "", "Runtime is not installed")
            val workspace = File(runtimeDirectory, "workspace").apply { mkdirs() }
            val prootTmp = File(runtimeDirectory, "proot-tmp").apply { mkdirs() }
            val target = "/workspace/$sanitizedName"
            val outputFile =
                File.createTempFile(
                    "clone-",
                    ".log",
                    File(runtimeDirectory, "logs").apply { mkdirs() },
                )
            val sanitizedUrl = url.trim().removeSuffix("/")
            val command =
                listOf(
                    runtime.commandSuite.proot.absolutePath,
                    "--kill-on-exit",
                    "--link2symlink",
                    "-0",
                    "-r",
                    runtime.rootfs.absolutePath,
                    "-b",
                    "/dev",
                    "-b",
                    "/proc",
                    "-b",
                    "/sys",
                    "-b",
                    "/system",
                    "-b",
                    "/apex",
                    "-b",
                    "/vendor",
                    "-b",
                    "${workspace.absolutePath}:/workspace",
                    "-w",
                    "/workspace",
                    "/bin/sh",
                    "-lc",
                    "git clone --depth 1 ${shellQuote(sanitizedUrl)} ${shellQuote(target)}",
                )
            try {
                val process =
                    ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.to(outputFile))
                        .apply {
                            environment().clear()
                            environment().putAll(
                                localRuntimeEnvironment(
                                    runtime.commandSuite.environment(),
                                    prootTmp,
                                    githubToken(),
                                ),
                            )
                        }
                        .start()
                val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    process.waitFor(2, TimeUnit.SECONDS)
                    GitCloneResult(124, target, "Clone timed out")
                } else {
                    GitCloneResult(
                        exitCode = process.exitValue(),
                        serverPath = target,
                        output = outputFile.readText().takeLast(4_000),
                    )
                }
            } finally {
                outputFile.delete()
            }
        }

    /**
     * Stages all changes in [workspacePath] (relative to /workspace or absolute under it),
     * commits with [message], and pushes to the configured remote.
     */
    fun commitAndPush(
        workspacePath: String,
        message: String,
    ): GitCloneResult =
        accessCoordinator.read {
            val runtime =
                installedRuntimeProvider()
                    ?: return@read GitCloneResult(127, workspacePath, "Runtime is not installed")
            val workspace = File(runtimeDirectory, "workspace").apply { mkdirs() }
            val prootTmp = File(runtimeDirectory, "proot-tmp").apply { mkdirs() }
            val rel =
                workspacePath
                    .removePrefix("/workspace/")
                    .removePrefix("workspace/")
                    .trim('/')
                    .ifBlank { "." }
            if (rel.contains("..")) {
                return@read GitCloneResult(1, workspacePath, "Invalid workspace path")
            }
            val msg = message.trim().ifBlank { "Update from AndCode" }
            val script =
                "cd ${shellQuote("/workspace/$rel")} && " +
                    "git add -A && " +
                    "git commit -m ${shellQuote(msg)} && " +
                    "git push"
            val outputFile = File.createTempFile("git-push-", ".log", prootTmp)
            val command =
                listOf(
                    runtime.commandSuite.proot.absolutePath,
                    "--kill-on-exit",
                    "--link2symlink",
                    "-0",
                    "-r",
                    runtime.rootfs.absolutePath,
                    "-b",
                    "/dev",
                    "-b",
                    "/proc",
                    "-b",
                    "/sys",
                    "-b",
                    "/system",
                    "-b",
                    "/apex",
                    "-b",
                    "/vendor",
                    "-b",
                    "${workspace.absolutePath}:/workspace",
                    "-w",
                    "/workspace",
                    "/bin/sh",
                    "-lc",
                    script,
                )
            try {
                val process =
                    ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.to(outputFile))
                        .apply {
                            environment().clear()
                            environment().putAll(
                                localRuntimeEnvironment(
                                    runtime.commandSuite.environment(),
                                    prootTmp,
                                    githubToken(),
                                ),
                            )
                        }
                        .start()
                val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    process.waitFor(2, TimeUnit.SECONDS)
                    GitCloneResult(124, workspacePath, "Push timed out")
                } else {
                    GitCloneResult(
                        exitCode = process.exitValue(),
                        serverPath = workspacePath,
                        output = outputFile.readText().takeLast(4_000),
                    )
                }
            } finally {
                outputFile.delete()
            }
        }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
