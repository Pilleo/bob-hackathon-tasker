package io.agentdevkit.planner

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IssueReviewTest {
    @Test
    fun `review parses one fenced JSON response`() {
        val parsed = IssueReview.parse("""```json
{"verdict":"NEEDS_CLARIFICATION","summary":"Missing boundary","findings":[],"questions":[]}
```""")
        assertEquals(ReviewVerdict.NEEDS_CLARIFICATION, parsed.review?.verdict, parsed.errors.toString())
    }
    @Test
    fun `single evidence citation can be a string`() {
        val parsed = IssueReview.parse("""{"verdict":"CHANGES_REQUESTED","summary":"Missing checks","findings":[{"id":"F1","category":"TESTING","severity":"MEDIUM","blocking":true,"detail":"No test","resolution":"Add test","evidence":"Validator.kt"}],"questions":[]}""")
        assertEquals(listOf("Validator.kt"), parsed.review?.findings?.single()?.evidence, parsed.errors.toString())
    }
    @Test
    fun `question blocking flag is preserved as an explicit blocking explanation`() {
        val parsed = IssueReview.parse("""{"verdict":"NEEDS_CLARIFICATION","summary":"Define invalid","findings":[],"questions":[{"question":"Which inputs are invalid?","blocks":true,"kind":"requirements"}]}""")
        assertEquals("Answer required before implementation", parsed.review?.questions?.single()?.blocks, parsed.errors.toString())
    }
    @Test
    fun `accepted review with blocking question is invalid`() {
        val parsed = IssueReview.parse("""{"verdict":"ACCEPT","summary":"looks good","findings":[],"questions":[{"question":"Which API is supported?","blocks":"Implementation","kind":"DECISION"}]}""")
        assertTrue(parsed.errors.any { it.contains("blocking") })
    }

    @Test
    fun `review artifact keeps issue content hash and becomes stale after edit`(@TempDir root: File) {
        val issue = root.resolve("docs/internals/backlog/issue-20260926-123456-review.md").apply {
            parentFile.mkdirs()
            writeText("first draft")
        }
        val review = IssueReview.parse("""{"verdict":"CHANGES_REQUESTED","summary":"Add a test","findings":[{"id":"F1","category":"TESTING","severity":"MEDIUM","blocking":true,"detail":"Missing regression test","resolution":"Name observable behavior","evidence":[]}],"questions":[]}""").review!!
        val artifact = IssueReviewStore.save(root, issue, PlanningEvidence(PlanningStatus.PARTIAL,
            emptyList(), emptyList(), emptyList(), null), "antigravity", review)
        assertTrue(artifact.isCurrent(root))
        assertEquals("F1", IssueReviewStore.load(artifact.file).review.findings.single().id)
        issue.writeText("second draft")
        assertFalse(artifact.isCurrent(root))
    }

    @Test
    fun `source fingerprint changes invalidate review`(@TempDir root: File) {
        val issue = root.resolve("docs/internals/backlog/issue-20260926-123456-source.md").apply {
            parentFile.mkdirs()
            writeText("draft")
        }
        val source = root.resolve("src/Validator.kt").apply { parentFile.mkdirs(); writeText("fun validate() = true") }
        val evidence = PlanningEvidence(PlanningStatus.OK, emptyList(),
            listOf(FileFingerprint("src/Validator.kt", hash(source.readBytes()))), emptyList(), null)
        val review = IssueReview(ReviewVerdict.ACCEPT, "Reviewed", emptyList(), emptyList())
        val artifact = IssueReviewStore.save(root, issue, evidence, "antigravity", review)
        source.writeText("fun validate() = false")
        assertFalse(artifact.isCurrent(root))
    }
}
