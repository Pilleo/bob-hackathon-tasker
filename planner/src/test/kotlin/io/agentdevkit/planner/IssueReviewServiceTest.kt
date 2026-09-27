package io.agentdevkit.planner

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IssueReviewServiceTest {
    @Test
    fun `placeholder issue does not dispatch reviewer`(@TempDir root: File) {
        val issue = IssueTemplateGenerator.scaffold(IssueRequest(title = "Placeholder"), root)
        var dispatched = false
        val result = IssueReviewService.review(issue.file, root, "antigravity", null) { _, _, _ ->
            dispatched = true
            AgentTurn.Failed("should not run")
        }
        assertTrue(result.errors.any { it.contains("placeholder") })
        assertEquals(false, dispatched)
    }

    @Test
    fun `substantive issue saves review from configured agent`(@TempDir root: File) {
        val issue = IssueTemplateGenerator.scaffold(IssueRequest(title = "Improve quality", context = "Tests are insufficient.",
            needed = listOf("Add failing regression")), root)
        issue.file.appendText("\n**Acceptance criteria:**\n- An invalid issue is rejected.\n")
        val config = PlannerReviewConfiguration(reviewers = mapOf("antigravity" to ReviewerConfiguration(
            listOf("bwrap"), ReadOnlyLaunch.BUBBLEWRAP)))
        val result = IssueReviewService.review(issue.file, root, "antigravity", config) { _, _, prompt ->
            assertTrue(prompt.contains("Acceptance criteria"))
            AgentTurn.Completed("""{"verdict":"CHANGES_REQUESTED","summary":"Missing test","findings":[],"questions":[]}""")
        }
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(result.artifact?.file?.isFile == true)
        assertEquals(ReviewVerdict.CHANGES_REQUESTED, result.artifact.review.verdict)
    }
}
