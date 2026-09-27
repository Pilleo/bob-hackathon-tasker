package io.agentdevkit.planner

data class MarkdownBlock(val level: Int, val heading: String, val line: Int, val body: String)

object MarkdownSections {
    fun scan(text: String): List<MarkdownBlock> {
        val lines = text.lines()
        val starts = mutableListOf<Triple<Int, Int, String>>()
        var fence: Char? = null
        var length = 0
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trimStart()
            val delimiter = trimmed.takeWhile { it == '`' || it == '~' }
            if (delimiter.length >= 3 && delimiter.toSet().size == 1) {
                if (fence == null) { fence = delimiter[0]; length = delimiter.length }
                else if (fence == delimiter[0] && delimiter.length >= length && trimmed.drop(delimiter.length).isBlank()) fence = null
                continue
            }
            if (fence != null) continue
            val match = Regex("^(#{2,3})[ \\t]+(.+?)[ \\t]*#*[ \\t]*$").matchEntire(line) ?: continue
            starts += Triple(index, match.groupValues[1].length, match.groupValues[2].trim())
        }
        return starts.mapIndexed { index, (line, level, heading) ->
            val end = starts.drop(index + 1).firstOrNull { it.second <= level }?.first ?: lines.size
            MarkdownBlock(level, heading, line + 1, lines.subList(line + 1, end).joinToString("\n").trim('\n'))
        }
    }
}
