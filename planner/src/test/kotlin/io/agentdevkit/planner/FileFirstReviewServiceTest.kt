package io.agentdevkit.planner

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class FileFirstReviewServiceTest {
    private fun issue(root: File): File = root.resolve("docs/internals/backlog/issue-20260926-120000-validator.md").apply {
        parentFile.mkdirs()
        writeText("""---
schema_version: 2
document_type: issue
id: issue-20260926-120000-validator
title: Validate blank names
status: open
priority: 100
dependencies: []
---
## Context
Names are not checked.
## Acceptance criteria
### AC1 — Reject blank
Return InvalidName.
## Changes
### S1 — Validate names
```yaml
acceptance: [AC1]
targets:
  - file: src/Validator.kt
    symbol: validate
    kind: existing
verification: [T1]
```
Change behavior.
## Verification
### T1 — Reject blank name
```yaml
kind: new
file: src/ValidatorTest.kt
symbol: rejectsBlank
```
Check result.
## Questions
### Q1 — Null name
```yaml
blocks: [S1]
resolve_through: decision
```
**Question:** What about null?
**Why it matters:** Changes public API.
**Answer:** Null is not allowed.
## Review notes
Author note.
<!-- adk:review:start -->
No review collected yet.
<!-- adk:review:end -->
""")
    }

    @Test fun `review findings are visible in the issue and remain current after projection`(@TempDir root: File) {
        val file = issue(root)
        root.resolve("src/Validator.kt").apply { parentFile.mkdirs(); writeText("fun validate() = true") }
        val configuration = PlannerReviewConfiguration(reviewers = mapOf("antigravity" to ReviewerConfiguration(listOf("/bin/true"))))
        val result = IssueReviewService.review(file, root, "antigravity", configuration,
            tools = object : PlanningTools {
                override fun source(root: File, path: String, symbol: String?) = SourceEvidence(PlanningStatus.OK, "PARSER_VERIFIED")
                override fun impact(root: File, symbol: String) = ImpactEvidence(PlanningStatus.OK, listOf(symbol))
                override fun source(root: File, target: TargetRef) = SourceEvidence(PlanningStatus.OK, "PARSER_VERIFIED")
                override fun impact(root: File, target: TargetRef) = ImpactEvidence(PlanningStatus.OK, listOf(target.symbol!!, "caller"))
            }, dispatch = { _, _, _ -> AgentTurn.Completed("""{"verdict":"CHANGES_REQUESTED","summary":"Clarify null input","findings":[{"id":"F1","category":"requirements","severity":"HIGH","blocking":true,"detail":"State null behavior","resolution":"Add criterion","evidence":[]}],"questions":[]}""") })
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertIs<ProjectionResult.Written>(result.projection)
        assertContains(file.readText(), "State null behavior")
        assertContains(file.readText(), "Author note.")
        assertTrue(result.artifact!!.isCurrent(root))
        assertIs<IssueReviewView.Current>(IssueReviewView.inspect(root, file))
        file.writeText(file.readText().replace("Null is not allowed.", "Null is allowed."))
        assertIs<IssueReviewView.Stale>(IssueReviewView.inspect(root, file))
    }

    @Test fun `reviewer receives bounded source test and related-symbol evidence for each step`(@TempDir root: File) {
        val file = issue(root)
        root.resolve("src/Validator.kt").apply { parentFile.mkdirs(); writeText("fun validate() = true") }
        val configuration = PlannerReviewConfiguration(reviewers = mapOf("fixture" to ReviewerConfiguration(listOf("/bin/true"))))
        var observed = ""
        val result = IssueReviewService.review(file, root, "fixture", configuration,
            tools = object : PlanningTools {
                override fun revision(root: File) = "revision-1"
                override fun source(root: File, path: String, symbol: String?) = error("Unexpected unqualified lookup")
                override fun impact(root: File, symbol: String) = error("Unexpected unqualified impact")
                override fun source(root: File, target: TargetRef) = SourceEvidence(PlanningStatus.OK, "PARSER_VERIFIED",
                    "fun validate() = true", provider = "ast-grep")
                override fun impact(root: File, target: TargetRef) = ImpactEvidence(PlanningStatus.OK,
                    listOf("validate", "ValidatorController.submit"), provider = "codanna")
            }, dispatch = { _, _, prompt ->
                observed = prompt
                AgentTurn.Completed("""{"verdict":"CHANGES_REQUESTED","summary":"Check callers","findings":[],"questions":[]}""")
            })
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertContains(observed, "Step S1")
        assertContains(observed, "fun validate() = true")
        assertContains(observed, "src/ValidatorTest.kt")
        assertContains(observed, "ValidatorController.submit")
        assertContains(observed, "codanna")
    }
}
