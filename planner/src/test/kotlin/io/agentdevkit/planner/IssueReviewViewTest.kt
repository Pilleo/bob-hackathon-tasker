package io.agentdevkit.planner

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class IssueReviewViewTest {
    @Test fun `show latest review with blockers and evidence limitations`(@TempDir root: File) {
        val issue = IssueTemplateGenerator.scaffold(IssueRequest("Improve validation", context = "Why", needed = listOf("Fix")), root).file
        issue.appendText("\n**Acceptance criteria:**\n- Reject blank input.\n")
        val review = IssueReview(ReviewVerdict.CHANGES_REQUESTED, "Incomplete", listOf(
            ReviewFinding("F1", "TESTING", "HIGH", true, "Missing regression test", "Name an observable case", listOf("Validator.kt")),
        ), listOf(ReviewQuestion("Which inputs?", "Implementation", "DECISION")))
        IssueReviewStore.save(root, issue, PlanningEvidence(PlanningStatus.PARTIAL, emptyList(), emptyList(),
            listOf(PlanningIssue("CODANNA_UNAVAILABLE", "No impact index")), null), "antigravity", review)
        val view = IssueReviewView.inspect(root, issue)
        assertIs<IssueReviewView.Current>(view)
        val text = IssueReviewView.render(view)
        assertContains(text, "Changes requested")
        assertContains(text, "F1")
        assertContains(text, "Missing regression test")
        assertContains(text, "Which inputs?")
        assertContains(text, "CODANNA_UNAVAILABLE")
        assertContains(text, "review-")
        assertContains(text, "review-issue")
    }

    @Test fun `editing issue makes latest review stale and identifies cause`(@TempDir root: File) {
        val issue = IssueTemplateGenerator.scaffold(IssueRequest("Review me"), root).file
        IssueReviewStore.save(root, issue, PlanningEvidence(PlanningStatus.OK, emptyList(), emptyList(), emptyList(), null),
            "antigravity", IssueReview(ReviewVerdict.ACCEPT, "Looks good", emptyList(), emptyList()))
        issue.appendText("\nNew criteria\n")
        val view = IssueReviewView.inspect(root, issue)
        assertIs<IssueReviewView.Stale>(view)
        assertContains(IssueReviewView.render(view), "issue content changed")
    }

    @Test fun `missing and malformed reviews are distinguished`(@TempDir root: File) {
        val issue = IssueTemplateGenerator.scaffold(IssueRequest("Review me"), root).file
        assertIs<IssueReviewView.Missing>(IssueReviewView.inspect(root, issue))
        val folder = root.resolve("docs/internals/reviews/${issue.nameWithoutExtension}").apply { mkdirs() }
        folder.resolve("review-99999.json").writeText("invalid json")
        assertIs<IssueReviewView.Unreadable>(IssueReviewView.inspect(root, issue))
    }

    @Test fun `source edits invalidate review independently of issue edits`(@TempDir root: File) {
        val issue = IssueTemplateGenerator.scaffold(IssueRequest("Review me"), root).file
        val source = root.resolve("src/Validator.kt").apply { parentFile.mkdirs(); writeText("class Validator") }
        val evidence = PlanningEvidence(PlanningStatus.OK, emptyList(), listOf(FileFingerprint("src/Validator.kt", hash(source.readBytes()))), emptyList(), null)
        IssueReviewStore.save(root, issue, evidence, "antigravity", IssueReview(ReviewVerdict.ACCEPT, "Reviewed", emptyList(), emptyList()))
        source.writeText("class Validator2")
        val view = IssueReviewView.inspect(root, issue)
        assertIs<IssueReviewView.Stale>(view)
        assertContains(IssueReviewView.render(view), "src/Validator.kt")
    }
}
