package com.yugahashimoto.andcode.runtime.local

import com.yugahashimoto.andcode.core.api.OpenCodeFileContent
import com.yugahashimoto.andcode.core.api.OpenCodeFileNode
import com.yugahashimoto.andcode.core.api.OpenCodeSearchMatch
import com.yugahashimoto.andcode.core.api.OpenCodeSearchSubmatch
import com.yugahashimoto.andcode.core.api.OpenCodeSearchText
import com.yugahashimoto.andcode.core.storage.DeviceStorage
import com.yugahashimoto.andcode.feature.workspace.WorkspaceFolders
import java.io.File

/**
 * File access for the Claude Code runtime.
 *
 * OpenCode answers the explorer's file questions over HTTP; Claude Code has no such server. It does
 * not need one: `/workspace` inside the sandbox is a plain directory on the device, so these read it
 * directly. Without this the explorer throws "unsupported" the moment a Claude session is open.
 *
 * [rootfsHostDir] is the Linux rootfs, for workspaces set to a folder outside the `/workspace` mount.
 * Root resolution is shared with the workspace folder picker ([WorkspaceFolders.hostDirectory]) so the
 * explorer can reach exactly what that picker can reach: the whole rootfs, plus device storage.
 */
class ClaudeWorkspaceFiles(
    private val workspaceHostDir: File,
    private val rootfsHostDir: File? = null,
    private val deviceStorage: () -> DeviceStorage.Mounts = { DeviceStorage.Mounts.None },
) {
    fun list(
        directory: String,
        path: String,
    ): List<OpenCodeFileNode> {
        // Canonical throughout: listFiles() returns children of the resolved directory, and a
        // relative path taken against an unresolved root climbs back out through every symlink.
        val root = canonical(resolveRoot(directory)) ?: return emptyList()
        val target = resolve(root, path) ?: return emptyList()
        val children = target.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).orEmpty()
        val nodes =
            children.map { child ->
                OpenCodeFileNode(
                    name = child.name,
                    path = child.relativeTo(root).path,
                    absolute = sandboxPath(directory, child.relativeTo(root).path),
                    type = if (child.isDirectory) "directory" else "file",
                    ignored = child.name.startsWith("."),
                )
            }
        // `/workspace` and device storage are bind mounts, invisible to a plain listing of the
        // rootfs root — without these, browsing up to "/" would be a dead end with no way back in.
        val atGuestRoot = WorkspaceFolders.normalize(directory) == WorkspaceFolders.GUEST_ROOT && (path.isBlank() || path == ".")
        if (!atGuestRoot) return nodes
        val existing = nodes.map { it.name }.toSet()
        val synthetic =
            WorkspaceFolders.syntheticRootNames(deviceStorage())
                .filterNot { it in existing }
                .map { name ->
                    OpenCodeFileNode(
                        name = name,
                        path = name,
                        absolute = "/$name",
                        type = "directory",
                    )
                }
        return (nodes + synthetic).sortedWith(compareBy({ it.type != "directory" }, { it.name.lowercase() }))
    }

    fun read(
        directory: String,
        path: String,
    ): OpenCodeFileContent {
        val root = canonical(resolveRoot(directory))
        val file = root?.let { resolve(it, path) }
        require(file != null && file.isFile) { "File not found: $path" }
        require(file.length() <= MAX_READ_BYTES) { "File is too large to open" }
        val bytes = file.readBytes()
        // A NUL byte in the first block is the usual signal that this is not text.
        val binary = bytes.take(BINARY_SNIFF_BYTES).any { it == 0.toByte() }
        return OpenCodeFileContent(
            type = if (binary) "binary" else "text",
            content = if (binary) "" else String(bytes),
        )
    }

    fun find(
        directory: String,
        query: String,
        includeDirectories: Boolean?,
        limit: Int?,
    ): List<String> {
        val root = canonical(resolveRoot(directory)) ?: return emptyList()
        if (query.isBlank()) return emptyList()
        return walk(root)
            .filter { includeDirectories == true || it.isFile }
            .filter { it.name.contains(query, ignoreCase = true) }
            .map { it.relativeTo(root).path }
            .take(limit ?: DEFAULT_LIMIT)
            .toList()
    }

    fun search(
        directory: String,
        pattern: String,
    ): List<OpenCodeSearchMatch> {
        val root = canonical(resolveRoot(directory)) ?: return emptyList()
        if (pattern.isBlank()) return emptyList()
        val matches = mutableListOf<OpenCodeSearchMatch>()
        for (file in walk(root).filter { it.isFile && it.length() <= MAX_READ_BYTES }) {
            if (matches.size >= DEFAULT_LIMIT) break
            val relative = file.relativeTo(root).path
            runCatching {
                file.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (matches.size >= DEFAULT_LIMIT) return@forEachIndexed
                        val column = line.indexOf(pattern, ignoreCase = true)
                        if (column < 0) return@forEachIndexed
                        matches +=
                            OpenCodeSearchMatch(
                                path = OpenCodeSearchText(relative),
                                lines = OpenCodeSearchText(line.take(MAX_LINE_CHARS)),
                                lineNumber = index + 1,
                                absoluteOffset = column,
                                submatches = listOf(OpenCodeSearchSubmatch(OpenCodeSearchText(pattern), column, column + pattern.length)),
                            )
                    }
                }
            }
        }
        return matches
    }

    /**
     * Lines in a file, or null when it cannot be counted.
     *
     * Untracked files are absent from `git diff`, so their size has to come from the file itself if
     * the changes list is to say the same thing OpenCode's server says about the same repository.
     */
    fun countLines(
        directory: String,
        path: String,
    ): Int? {
        val root = canonical(resolveRoot(directory)) ?: return null
        val file = resolve(root, path)?.takeIf { it.isFile && it.length() <= MAX_READ_BYTES } ?: return null
        return runCatching { file.useLines { lines -> lines.count() } }.getOrNull()
    }

    private fun walk(root: File) =
        root.walkTopDown()
            .onEnter { it.name != ".git" && it.name != "node_modules" }
            .maxDepth(MAX_DEPTH)

    /**
     * Host directory backing [directory].
     *
     * Sessions record sandbox paths such as `/workspace/project`; everything under `/workspace` maps
     * into the app's own workspace directory. A workspace can also be set to a folder the Linux
     * environment already has — `/root/project`, say — and those live in the rootfs instead. Shared
     * with the workspace folder picker ([WorkspaceFolders.hostDirectory]) so the explorer can browse
     * anywhere that picker can: the whole rootfs, plus device storage when it is mounted.
     */
    private fun resolveRoot(directory: String): File? =
        WorkspaceFolders.hostDirectory(rootfsHostDir, workspaceHostDir, directory, deviceStorage())

    private fun sandboxPath(
        directory: String,
        relative: String,
    ): String = directory.trimEnd('/') + "/" + relative

    private fun canonical(file: File?): File? = file?.let { runCatching { it.canonicalFile }.getOrNull() }

    /** Null when [path] escapes [root]; the explorer must not reach outside the workspace. */
    private fun resolve(
        root: File,
        path: String,
    ): File? {
        val candidate = if (path.isBlank() || path == ".") root else File(root, path)
        val resolved = canonical(candidate) ?: return null
        return resolved.takeIf { it == root || it.path.startsWith(root.path + File.separator) }
    }

    private companion object {
        const val MAX_READ_BYTES = 2L * 1024 * 1024
        const val BINARY_SNIFF_BYTES = 1024
        const val DEFAULT_LIMIT = 200
        const val MAX_DEPTH = 12
        const val MAX_LINE_CHARS = 400
    }
}
