package io.agentdevkit.planner

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class ReviewCorrectionTest {
    private val config = PlannerReviewConfiguration(reviewers = mapOf("fixture" to ReviewerConfiguration(listOf("/bin/true"))))
    private val contradiction = """{"verdict":"ACCEPT","summary":"Accepted but needs an answer","findings":[],"questions":[{"question":"Reject null?","blocks":"S1","kind":"decision"}]}"""
    private val clarification = """{"verdict":"NEEDS_CLARIFICATION","summary":"Resolve null behavior","findings":[],"questions":[{"question":"Reject null?","blocks":["S1","S2"],"kind":"decision"}]}"""

    private fun issue(root: File): File = IssueTemplateGenerator.scaffold(IssueRequest("Reject blanks",
        context = "Blank names are accepted.", needed = listOf("Reject them")), root).file.apply {
        appendText("\n**Acceptance criteria:**\n- Blank names fail.\n")
    }

    @Test fun `string array blocks is accepted without weakening contradictory ACCEPT validation`() {
        val review = IssueReview.parse(clarification)
        val validated = assertNotNull(review.review, review.errors.toString())
        assertEquals("S1, S2", validated.questions.single().blocks)
        assertNull(IssueReview.parse(contradiction).review)
        assertNull(IssueReview.parse(clarification.replace("[\"S1\",\"S2\"]", "[1,true]")).review)
    }

    @Test fun `invalid review gets exactly one correction request and persists only validated result`(@TempDir root: File) {
        var calls = 0
        val outcome = IssueReviewService.review(issue(root), root, "fixture", config) { _, _, prompt ->
            calls++
            if (calls == 1) {
                assertContains(prompt, "ACCEPT requires")
                AgentTurn.Completed(contradiction)
            } else {
                assertContains(prompt, "ACCEPT cannot have blocking findings or questions")
                AgentTurn.Completed(clarification)
            }
        }
        assertEquals(2, calls)
        assertTrue(outcome.errors.isEmpty(), outcome.errors.toString())
        assertEquals(ReviewVerdict.NEEDS_CLARIFICATION, outcome.artifact?.review?.verdict)
        assertEquals(1, root.resolve("docs/internals/reviews").walkTopDown().count { it.extension == "json" })
    }

    @Test fun `two invalid replies stop without saving any accepted receipt`(@TempDir root: File) {
        var calls = 0
        val outcome = IssueReviewService.review(issue(root), root, "fixture", config) { _, _, _ ->
            calls++
            AgentTurn.Completed(contradiction)
        }
        assertEquals(2, calls)
        assertNull(outcome.artifact)
        assertTrue(outcome.errors.isNotEmpty())
        assertFalse(root.resolve("docs/internals/reviews").exists())
    }

    @Test fun `human edit after invalid response prevents another paid request`(@TempDir root: File) {
        val file = issue(root)
        var calls = 0
        val outcome = IssueReviewService.review(file, root, "fixture", config) { _, _, _ ->
            calls++
            file.appendText("\nHuman changed the requirement.\n")
            AgentTurn.Completed(contradiction)
        }
        assertEquals(1, calls)
        assertNull(outcome.artifact)
        assertTrue(outcome.errors.any { "changed" in it })
    }
}
