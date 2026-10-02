package com.yugahashimoto.andcode.runtime

import com.yugahashimoto.andcode.R

/**
 * Independently selectable optional package groups for the shared Alpine sandbox.
 */
enum class DevelopmentToolGroup(
    val id: String,
    val displayNameRes: Int,
    val descriptionRes: Int,
    val approxSizeLabel: String,
    val packages: List<String>,
) {
    EDITORS(
        id = "editors",
        displayNameRes = R.string.development_tool_group_editors,
        descriptionRes = R.string.development_tool_group_editors_desc,
        approxSizeLabel = "~20 MB",
        packages =
            listOf(
                "tree",
                "file",
                "less",
                "nano",
                "vim",
                "zip",
                "unzip",
                "sqlite",
                "util-linux",
                "gcompat",
                "patch",
                "pkgconf",
            ),
    ),
    ANDROID_JAVA(
        id = "android-java",
        displayNameRes = R.string.development_tool_group_android_java,
        descriptionRes = R.string.development_tool_group_android_java_desc,
        approxSizeLabel = "~250 MB",
        packages = listOf("openjdk17", "gradle"),
    ),
    NODE(
        id = "node",
        displayNameRes = R.string.development_tool_group_node,
        descriptionRes = R.string.development_tool_group_node_desc,
        approxSizeLabel = "~60 MB",
        packages = listOf("nodejs", "npm", "icu-data-full"),
    ),
    PYTHON(
        id = "python",
        displayNameRes = R.string.development_tool_group_python,
        descriptionRes = R.string.development_tool_group_python_desc,
        approxSizeLabel = "~8 MB",
        packages = listOf("py3-pip"),
    ),
    NATIVE(
        id = "native",
        displayNameRes = R.string.development_tool_group_native,
        descriptionRes = R.string.development_tool_group_native_desc,
        approxSizeLabel = "~100 MB",
        packages = listOf("make", "cmake", "gcc", "g++", "musl-dev"),
    ),
    GO(
        id = "go",
        displayNameRes = R.string.development_tool_group_go,
        descriptionRes = R.string.development_tool_group_go_desc,
        approxSizeLabel = "~120 MB",
        packages = listOf("go"),
    ),
    GITHUB_CLI(
        id = "github-cli",
        displayNameRes = R.string.development_tool_group_github_cli,
        descriptionRes = R.string.development_tool_group_github_cli_desc,
        approxSizeLabel = "~15 MB",
        packages = listOf("github-cli"),
    ),
    ;

    companion object {
        fun fromId(id: String): DevelopmentToolGroup? = entries.firstOrNull { it.id == id }

        fun packagesFor(groups: Collection<DevelopmentToolGroup>): List<String> =
            groups.flatMap { it.packages }.distinct()

        val ALL: Set<DevelopmentToolGroup>
            get() = entries.toSet()
    }
}
