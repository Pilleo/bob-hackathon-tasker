package io.agentdevkit.planner

import java.io.File
import java.io.IOException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class PlanningFailuresTest {
    private fun draft() = IssueDraft("issue-1", "Validate", "Context", listOf("step"), listOf("criterion"),
        listOf("src/Validator.kt"), listOf("validate"), emptyList(), "draft")

    @Test fun `unconfigured tools return incomplete evidence`(@TempDir root: File) {
        root.resolve("src/Validator.kt").apply { parentFile.mkdirs(); writeText("fun validate() = true") }
        val result = PlanningEvidenceCollector.collect(draft(), root)
        assertEquals(PlanningStatus.PARTIAL, result.status)
        assertTrue(result.issues.any { it.code == "AST_UNAVAILABLE" })
        assertTrue(result.issues.any { it.code == "CODANNA_UNAVAILABLE" })
    }

    @Test fun `provider exceptions do not escape collection`(@TempDir root: File) {
        root.resolve("src/Validator.kt").apply { parentFile.mkdirs(); writeText("fun validate() = true") }
        val tools = object : PlanningTools {
            override fun source(root: File, path: String, symbol: String?): SourceEvidence = throw IOException("ast-grep missing")
            override fun impact(root: File, symbol: String): ImpactEvidence = throw IllegalStateException("codanna invalid JSON")
            override fun revision(root: File): String? = throw IOException("git missing")
        }
        val result = PlanningEvidenceCollector.collect(draft(), root, tools)
        assertEquals(PlanningStatus.PARTIAL, result.status)
        assertTrue(result.issues.map { it.code }.containsAll(listOf("AST_FAILED", "CODANNA_FAILED", "REVISION_UNAVAILABLE")))
    }

    @Test fun `impact result and provenance reach planning evidence`(@TempDir root: File) {
        root.resolve("src/Validator.kt").apply { parentFile.mkdirs(); writeText("fun validate() = true") }
        val tools = object : PlanningTools {
            override fun source(root: File, path: String, symbol: String?) = SourceEvidence(PlanningStatus.OK, "PARSER_VERIFIED", "fun validate() = true", provider = "ast-grep")
            override fun impact(root: File, symbol: String) = ImpactEvidence(PlanningStatus.OK, listOf(symbol, "caller"), provider = "codanna")
        }
        val result = PlanningEvidenceCollector.collect(draft(), root, tools)
        assertEquals(listOf("validate", "caller"), result.impacts["validate"]?.symbols)
        assertEquals("codanna", result.impacts["validate"]?.provider)
    }

    @Test fun `missing draft is explicit failure`(@TempDir root: File) {
        val result = IssueReviewService.review(root.resolve("missing.md"), root, "antigravity", null)
        assertNull(result.artifact)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test fun `reviewer exception is reported and no artifact is written`(@TempDir root: File) {
        val issue = IssueTemplateGenerator.scaffold(IssueRequest(title = "Improve", context = "Why", needed = listOf("Fix")), root)
        issue.file.appendText("\n**Acceptance criteria:**\n- Reject empty input.\n")
        val config = PlannerReviewConfiguration(reviewers = mapOf("antigravity" to ReviewerConfiguration(listOf("missing"))))
        val result = IssueReviewService.review(issue.file, root, "antigravity", config) { _, _, _ -> throw IOException("no ACP") }
        assertNull(result.artifact)
        assertTrue(result.errors.any { "no ACP" in it })
        assertFalse(root.resolve("docs/internals/reviews").exists())
    }

    @Test fun `issue deleted during review returns explicit failure`(@TempDir root: File) {
        val issue = IssueTemplateGenerator.scaffold(IssueRequest(title = "Improve", context = "Why", needed = listOf("Fix")), root)
        issue.file.appendText("\n**Acceptance criteria:**\n- Reject empty input.\n")
        val config = PlannerReviewConfiguration(reviewers = mapOf("antigravity" to ReviewerConfiguration(listOf("missing"))))
        val result = IssueReviewService.review(issue.file, root, "antigravity", config) { _, _, _ ->
            assertTrue(issue.file.delete())
            AgentTurn.Completed("""{"verdict":"ACCEPT","summary":"Reviewed","findings":[],"questions":[]}""")
        }
        assertNull(result.artifact)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test fun `proposed file created later invalidates fingerprint`(@TempDir root: File) {
        val evidence = PlanningEvidenceCollector.collect(draft(), root)
        assertTrue(evidence.files.single().matches(root))
        root.resolve("src/Validator.kt").apply { parentFile.mkdirs(); writeText("fun validate() = true") }
        assertFalse(evidence.files.single().matches(root))
    }

    @Test fun `storage failure is explicit and does not return a review artifact`(@TempDir root: File) {
        val issue = IssueTemplateGenerator.scaffold(IssueRequest(title = "Improve", context = "Why", needed = listOf("Fix")), root)
        issue.file.appendText("\n**Acceptance criteria:**\n- Reject empty input.\n")
        root.resolve("docs/internals/reviews").writeText("not a directory")
        val config = PlannerReviewConfiguration(reviewers = mapOf("antigravity" to ReviewerConfiguration(listOf("missing"))))
        val result = IssueReviewService.review(issue.file, root, "antigravity", config) { _, _, _ ->
            AgentTurn.Completed("""{"verdict":"ACCEPT","summary":"Reviewed","findings":[],"questions":[]}""")
        }
        assertNull(result.artifact)
        assertTrue(result.errors.isNotEmpty())
        assertEquals("not a directory", root.resolve("docs/internals/reviews").readText())
    }
}
