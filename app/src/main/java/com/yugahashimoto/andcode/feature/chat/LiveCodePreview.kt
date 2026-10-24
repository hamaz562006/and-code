package com.yugahashimoto.andcode.feature.chat

/**
 * Detects fenced code blocks in a streaming assistant message for live preview (feature 4).
 */
object LiveCodePreview {
    data class Block(
        val language: String,
        val code: String,
        val complete: Boolean,
    )

    private val fenceStart = Regex("^```([\\w.+-]*)\\s*$")

    fun extractBlocks(text: String): List<Block> {
        val lines = text.split('\n')
        val blocks = mutableListOf<Block>()
        var i = 0
        while (i < lines.size) {
            val start = fenceStart.matchEntire(lines[i].trim())
            if (start != null) {
                val lang = start.groupValues[1].ifBlank { "text" }
                val body = mutableListOf<String>()
                i++
                var complete = false
                while (i < lines.size) {
                    if (lines[i].trim() == "```") {
                        complete = true
                        break
                    }
                    body.add(lines[i])
                    i++
                }
                blocks.add(Block(lang, body.joinToString("\n"), complete))
            }
            i++
        }
        return blocks
    }

    fun summary(text: String): String? {
        val blocks = extractBlocks(text)
        if (blocks.isEmpty()) return null
        return blocks.joinToString(" · ") { b ->
            val state = if (b.complete) "ready" else "generating"
            "${b.language} (${b.code.lines().size} lines, $state)"
        }
    }
}
