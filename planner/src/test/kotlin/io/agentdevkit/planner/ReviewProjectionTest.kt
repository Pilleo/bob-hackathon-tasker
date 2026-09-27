package io.agentdevkit.planner

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class ReviewProjectionTest {
    private fun issue() = """---
schema_version: 2
document_type: issue
id: issue-20260926-120000-validator
title: Validate input
status: open
---
## Context
Keep authored prose.
## Review notes
My response stays here.
<!-- adk:review:start -->
No review collected yet.
<!-- adk:review:end -->
"""
    private fun review() = StoredReview(issuePath = "docs/internals/backlog/issue-20260926-120000-validator.md", issueSha256 = "unused",
        evidence = PlanningEvidence(PlanningStatus.PARTIAL, emptyList(), emptyList(), listOf(PlanningIssue("CODANNA_UNAVAILABLE", "Index unavailable")), null),
        reviewer = "antigravity", review = IssueReview(ReviewVerdict.CHANGES_REQUESTED, "More detail", listOf(
            ReviewFinding("F1", "requirements", "HIGH", true, "Define invalid input", "Add an acceptance criterion", emptyList())),
            listOf(ReviewQuestion("What about null?", "S1", "decision"))))

    @Test fun `review is readable in file without changing authored hash`(@TempDir root: File) {
        val file = root.resolve("issue.md").apply { writeText(issue()) }
        val before = ReviewProjection.authoredBytes(file.readText())
        val first = ReviewProjection.publish(file, hash(before), hash("No review collected yet.".toByteArray()), review())
        assertIs<ProjectionResult.Written>(first)
        val after = file.readText()
        assertContentEquals(before, ReviewProjection.authoredBytes(after))
        assertContains(after, "F1")
        assertContains(after, "What about null?")
        assertContains(after, "CODANNA_UNAVAILABLE")
        assertContains(after, "My response stays here.")
    }

    @Test fun `first review remains current when issue has no managed block`(@TempDir root: File) {
        val file = root.resolve("issue.md").apply { writeText(issue().substringBefore("<!-- adk:review:start -->").trimEnd()) }
        val authorHash = hash(ReviewProjection.authoredBytes(file.readText()))
        assertIs<ProjectionResult.Written>(ReviewProjection.publish(file, authorHash, null, review()))
        assertEquals(authorHash, hash(ReviewProjection.authoredBytes(file.readText())))
    }

    @Test fun `CRLF issue publishes findings without changing authored fingerprint`(@TempDir root: File) {
        val file = root.resolve("issue.md").apply { writeText(issue().replace("\n", "\r\n")) }
        val authorHash = hash(ReviewProjection.authoredBytes(file.readText()))
        assertIs<ProjectionResult.Written>(ReviewProjection.publish(file, authorHash,
            hash("No review collected yet.".toByteArray()), review()))
        assertEquals(authorHash, hash(ReviewProjection.authoredBytes(file.readText())))
        assertContains(file.readText(), "F1")
        assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(file.readText()), "Keep CRLF consistently")
    }

    @Test fun `edits to authored text or managed text block publication`(@TempDir root: File) {
        val file = root.resolve("issue.md").apply { writeText(issue()) }
        val original = hash(ReviewProjection.authoredBytes(file.readText()))
        file.appendText("\nUser answer: preserve nulls\n")
        assertIs<ProjectionResult.Conflict>(ReviewProjection.publish(file, original, hash("No review collected yet.".toByteArray()), review()))
        assertContains(file.readText(), "User answer: preserve nulls")
        val current = hash(ReviewProjection.authoredBytes(file.readText()))
        file.writeText(file.readText().replace("No review collected yet.", "Edited verdict"))
        assertIs<ProjectionResult.Conflict>(ReviewProjection.publish(file, current, hash("No review collected yet.".toByteArray()), review()))
        assertContains(file.readText(), "Edited verdict")
    }

    @Test fun `malformed markers and markers inside fences cannot hide authored bytes`(@TempDir root: File) {
        val file = root.resolve("issue.md").apply { writeText(issue().replace("<!-- adk:review:end -->", "")) }
        assertFailsWith<IllegalArgumentException> { ReviewProjection.authoredBytes(file.readText()) }
        file.writeText(issue().replace("## Review notes", "```markdown\n<!-- adk:review:start -->\n<!-- adk:review:end -->\n```\n## Review notes"))
        assertContains(ReviewProjection.authoredBytes(file.readText()).toString(Charsets.UTF_8), "```markdown")
    }
}
