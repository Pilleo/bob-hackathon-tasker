package io.agentdevkit.planner

import java.io.File
import java.nio.file.Files

sealed interface IssueReviewView {
    data class Current(val artifact: StoredReview) : IssueReviewView
    data class Stale(val artifact: StoredReview, val reasons: List<String>) : IssueReviewView
    data object Missing : IssueReviewView
    data class Unreadable(val reason: String) : IssueReviewView

    companion object {
        fun inspect(root: File, issue: File, revision: (File) -> String? = { null }): IssueReviewView {
            val backlog = File(root, "docs/internals/backlog").canonicalFile
            val canonical = boundary({ return Unreadable("Cannot resolve issue: ${it.message}") }) { issue.canonicalFile }
            if (canonical.parentFile != backlog || !canonical.name.startsWith("issue-") || canonical.extension != "md" || !canonical.isFile) {
                return Unreadable("Choose an existing issue in docs/internals/backlog")
            }
            val directory = File(root, "docs/internals/reviews/${canonical.nameWithoutExtension}")
            if (!directory.exists()) return Missing
            if (Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory || directory.canonicalFile.parentFile != File(root, "docs/internals/reviews").canonicalFile) {
                return Unreadable("Review directory is invalid")
            }
            val files = directory.listFiles()?.filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.name.startsWith("review-") && it.extension == "json" }.orEmpty()
            val latest = files.maxByOrNull { it.name } ?: return Missing
            return boundary({ Unreadable("Cannot load latest review ${latest.name}: ${it.message}") }) {
                if (latest.length() > 1_048_576) return@boundary Unreadable("Latest review exceeds 1 MiB")
                val artifact = IssueReviewStore.load(latest)
                if (artifact.schemaVersion !in setOf(1, 2) || artifact.issuePath != canonical.relativeTo(root.canonicalFile).invariantSeparatorsPath) {
                    return@boundary Unreadable("Latest review refers to another issue or unsupported schema")
                }
                val reasons = buildList {
                    if (artifact.schemaVersion == 2) {
                        if (hash(ReviewProjection.authoredBytes(canonical.readText())) != artifact.authorSha256) add("issue content changed")
                    } else if (hash(canonical.readBytes()) != artifact.issueSha256) add("issue content changed")
                    artifact.evidence.files.filterNot { it.matches(root) }.forEach { add("source changed: ${it.path}") }
                    if (artifact.evidence.repositoryRevision != revision(root)) add("repository revision changed")
                }
                if (reasons.isEmpty()) Current(artifact) else Stale(artifact, reasons)
            }
        }

        fun render(view: IssueReviewView): String = when (view) {
            Missing -> "No review yet. Complete the issue, then run: adk review-issue <issue-file>"
            is Unreadable -> "Review unavailable: ${view.reason}"
            is Current -> report(view.artifact, emptyList())
            is Stale -> report(view.artifact, view.reasons)
        }

        private fun report(artifact: StoredReview, stale: List<String>): String = buildString {
            val review = artifact.review
            val verdict = when (review.verdict) {
                ReviewVerdict.ACCEPT -> "Accepted by reviewer"
                ReviewVerdict.CHANGES_REQUESTED -> "Changes requested"
                ReviewVerdict.NEEDS_CLARIFICATION -> "Needs clarification"
            }
            appendLine("Review: $verdict${if (stale.isEmpty()) "" else " (STALE)"}")
            appendLine("Reviewer: ${artifact.reviewer}")
            appendLine("Evidence: ${artifact.evidence.status}")
            appendLine(review.summary)
            if (stale.isNotEmpty()) { appendLine("Stale because:"); stale.forEach { appendLine("  - $it") } }
            if (artifact.evidence.issues.isNotEmpty()) {
                appendLine("Evidence limitations:")
                artifact.evidence.issues.forEach { appendLine("  ${it.code}: ${it.message}") }
            }
            val blocking = review.findings.filter { it.blocking }
            val other = review.findings.filterNot { it.blocking }
            if (blocking.isNotEmpty()) {
                appendLine("Blocking findings:")
                blocking.forEach { appendLine("  ${it.id} [${it.severity}]: ${it.detail}\n    Next: ${it.resolution}") }
            }
            if (other.isNotEmpty()) { appendLine("Other findings:"); other.forEach { appendLine("  ${it.id}: ${it.detail}") } }
            if (review.questions.isNotEmpty()) {
                appendLine("Open questions:")
                review.questions.forEachIndexed { i, it -> appendLine("  Q${i + 1}: ${it.question} (blocks: ${it.blocks})") }
            }
            appendLine("Saved review: ${artifact.file.path}")
            append("Next: ${if (stale.isNotEmpty() || review.verdict != ReviewVerdict.ACCEPT) "Edit the issue, then run adk review-issue ${artifact.issuePath}" else "Review the findings and verify before implementation."}")
        }
    }
}
