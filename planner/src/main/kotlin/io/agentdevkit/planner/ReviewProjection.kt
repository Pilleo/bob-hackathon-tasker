package io.agentdevkit.planner

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

sealed interface ProjectionResult {
    data class Written(val file: File) : ProjectionResult
    data class Conflict(val reason: String, val receipt: File) : ProjectionResult
    data class Failed(val reason: String, val receipt: File?) : ProjectionResult
}

object ReviewProjection {
    private const val START = "<!-- adk:review:start -->"
    private const val END = "<!-- adk:review:end -->"
    private data class Region(val start: Int, val contentStart: Int, val contentEnd: Int, val end: Int, val newline: String)

    private fun region(text: String): Region? {
        var fence: Char? = null
        var fenceSize = 0
        var offset = 0
        val begins = mutableListOf<Pair<Int, Int>>()
        val ends = mutableListOf<Pair<Int, Int>>()
        while (offset < text.length) {
            val breakAt = text.indexOf('\n', offset).let { if (it == -1) text.length else it }
            val lineEnd = if (breakAt > offset && text[breakAt - 1] == '\r') breakAt - 1 else breakAt
            val line = text.substring(offset, lineEnd)
            val leading = line.trimStart()
            val marker = leading.takeWhile { it == '`' || it == '~' }
            if (marker.length >= 3 && marker.toSet().size == 1) {
                if (fence == null) { fence = marker[0]; fenceSize = marker.length }
                else if (fence == marker[0] && marker.length >= fenceSize && leading.drop(marker.length).isBlank()) fence = null
            } else if (fence == null) {
                if (line == START) begins += offset to offset + line.length
                if (line == END) ends += offset to offset + line.length
            }
            offset = if (breakAt == text.length) text.length else breakAt + 1
        }
        if (begins.isEmpty() && ends.isEmpty()) return null
        require(begins.size == 1 && ends.size == 1 && begins.single().first < ends.single().first) { "Review markers must form exactly one ordered pair" }
        val begin = begins.single(); val end = ends.single()
        val newline = when {
            text.startsWith("\r\n", begin.second) -> "\r\n"
            text.startsWith("\n", begin.second) -> "\n"
            else -> error("Review block must begin on a new line")
        }
        return Region(begin.first, begin.second + newline.length, end.first, end.second, newline)
    }

    fun authoredBytes(text: String): ByteArray {
        val span = region(text)
        // The first publication may need to add a newline before its marker. Normalize only
        // the terminal newline so that adding the managed block cannot stale its own receipt.
        val authored = if (span == null) text else text.substring(0, span.start) + text.substring(span.end)
        return (authored.trimEnd('\r', '\n') + "\n").toByteArray(Charsets.UTF_8)
    }

    fun blockHash(text: String): String? = region(text)?.let { hash(text.substring(it.contentStart, it.contentEnd).trimEnd('\r', '\n').toByteArray(Charsets.UTF_8)) }

    fun publish(issue: File, expectedAuthorHash: String, expectedBlockHash: String?, artifact: StoredReview): ProjectionResult {
        fun conflict(reason: String) = ProjectionResult.Conflict(reason, artifact.file)
        val before = try { issue.readText() } catch (error: Exception) { return ProjectionResult.Failed(error.message.orEmpty(), artifact.file) }
        val region = try { region(before) } catch (error: IllegalArgumentException) { return conflict(error.message.orEmpty()) }
        if (hash(authoredBytes(before)) != expectedAuthorHash) return conflict("Authored content changed before review publication")
        if (blockHash(before) != expectedBlockHash) return conflict("Managed review text changed before publication")
        val summary = buildString {
            appendLine("Review snapshot (source and author hashes must still be checked): ${artifact.review.verdict}")
            appendLine("Reviewer: ${artifact.reviewer}; Evidence: ${artifact.evidence.status}")
            appendLine("Author hash: $expectedAuthorHash")
            appendLine(safe(artifact.review.summary))
            for (issue in artifact.evidence.issues) appendLine("Evidence gap ${safe(issue.code)}: ${safe(issue.message)}")
            for (finding in artifact.review.findings) appendLine("${safe(finding.id)} [${safe(finding.severity)}] ${safe(finding.detail)} — Next: ${safe(finding.resolution)}")
            for ((index, question) in artifact.review.questions.withIndex()) appendLine("Q${index + 1}: ${safe(question.question)} (blocks: ${safe(question.blocks)})")
        }.trimEnd()
        val newline = region?.newline ?: if ("\r\n" in before) "\r\n" else "\n"
        val rendered = summary.replace("\n", newline)
        val updated = if (region == null) before.trimEnd() + "$newline$newline$START$newline$rendered$newline$END$newline"
        else before.substring(0, region.contentStart) + rendered + newline + before.substring(region.contentEnd)
        val directory = issue.parentFile?.toPath() ?: return ProjectionResult.Failed("Issue has no parent directory", artifact.file)
        return try {
            val temporary = Files.createTempFile(directory, ".review-", ".tmp")
            try {
                Files.writeString(temporary, updated)
                if (issue.readText() != before) return conflict("Issue changed while rendering review")
                Files.move(temporary, issue.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                ProjectionResult.Written(issue)
            } finally { Files.deleteIfExists(temporary) }
        } catch (error: Exception) { ProjectionResult.Failed(error.message.orEmpty(), artifact.file) }
    }

    private fun safe(text: String): String = text.replace("<!-- adk:", "&lt;!-- adk:")
        .map { if (it == '\n' || it == '\t' || !it.isISOControl()) it else ' ' }.joinToString("").take(2000)
}
