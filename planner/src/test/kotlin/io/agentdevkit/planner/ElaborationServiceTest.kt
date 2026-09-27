package io.agentdevkit.planner

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class ElaborationServiceTest {
    private fun proposal(target: String, extra: String = "") = """---
schema_version: 2
document_type: issue
id: issue-1
title: Reject blanks
---

## Context
Reject blank names.

## Acceptance criteria
### AC1 — Reject blanks
Blank names fail.

## Changes
### S1 — Validate
```yaml
acceptance: [AC1]
targets:
  - file: src/$target
    symbol: validate
    kind: existing
verification: [T1]
```
Update validation. $extra

## Verification
### T1 — Blank input
```yaml
kind: new
file: src/ValidatorTest.kt
symbol: rejectsBlank
```
FINAL_VERIFICATION_SENTINEL
"""

    @Test fun `wrong file receives one corrective turn even with no verified source`(@TempDir root: File) {
        val issue = root.resolve("issue.md")
        issue.writeText("---\nschema_version: 2\ndocument_type: issue\nid: issue-1\ntitle: Reject blanks\n---\n\n## Context\nReject blank names.\n")
        root.resolve("src/Valid.kt").apply { parentFile.mkdirs(); writeText("fun validate() = true") }
        val tools = object : PlanningTools {
            override fun source(root: File, path: String, symbol: String?) = SourceEvidence(PlanningStatus.OK, "PARSER_VERIFIED")
            override fun impact(root: File, symbol: String) = ImpactEvidence(PlanningStatus.OK, listOf(symbol, "caller"))
            override fun source(root: File, target: TargetRef) = source(root, target.file, target.symbol)
            override fun impact(root: File, target: TargetRef) = impact(root, target.symbol.orEmpty())
            override fun revision(root: File) = "abc"
        }
        val prompts = mutableListOf<String>()
        val outcome = ElaborationService.elaborate(issue, root, ReviewerConfiguration(listOf("/bin/true")), tools) { _, _, prompt ->
            prompts += prompt
            AgentTurn.Completed(proposal(if (prompts.size == 1) "Missing.kt" else "Valid.kt"))
        }
        assertTrue(outcome.success, outcome.message)
        assertEquals(2, prompts.size)
        assertContains(prompts.last(), "EXISTING_TARGET_MISSING")
        assertEquals("src/Valid.kt", assertIs<DocumentRead.Valid>(PlanningDocumentReader.read(issue, root))
            .document.steps.single().targets.single().file)
    }

    @Test fun `refinement prompt contains the complete proposal after long step prose`(@TempDir root: File) {
        val issue = root.resolve("issue.md")
        issue.writeText("---\nschema_version: 2\ndocument_type: issue\nid: issue-1\ntitle: Reject blanks\n---\n\n## Context\nReject blank names.\n")
        root.resolve("src/Valid.kt").apply { parentFile.mkdirs(); writeText("fun validate() = true") }
        val tools = object : PlanningTools {
            override fun source(root: File, path: String, symbol: String?) = SourceEvidence(PlanningStatus.OK, "PARSER_VERIFIED", "fun validate() = true")
            override fun impact(root: File, symbol: String) = ImpactEvidence(PlanningStatus.OK, listOf(symbol, "caller"))
            override fun source(root: File, target: TargetRef) = source(root, target.file, target.symbol)
            override fun impact(root: File, target: TargetRef) = impact(root, target.symbol.orEmpty())
            override fun revision(root: File) = "abc"
        }
        var calls = 0
        val complete = proposal("Valid.kt", "x".repeat(25_000))
        val outcome = ElaborationService.elaborate(issue, root, ReviewerConfiguration(listOf("/bin/true")), tools) { _, _, prompt ->
            calls++
            if (calls == 2) assertContains(prompt, "FINAL_VERIFICATION_SENTINEL")
            AgentTurn.Completed(complete)
        }
        assertTrue(outcome.success, outcome.message)
        assertEquals(2, calls)
        assertContains(issue.readText(), "FINAL_VERIFICATION_SENTINEL")
    }
    @Test fun `untouched human scaffold refuses elaboration before agent dispatch`(@TempDir root: File) {
        val issue = IssueTemplateGenerator.scaffoldStructured("Reject blanks", root, listOf("src/Validator.kt")).file
        val initial = issue.readText()
        var requests = 0
        val outcome = ElaborationService.elaborate(issue, root, ReviewerConfiguration(listOf("/bin/true"))) { _, _, _ ->
            requests++
            AgentTurn.Failed("agent charged for request")
        }
        assertFalse(outcome.success)
        assertContains(outcome.message, "description")
        assertEquals(0, requests)
        assertEquals(initial, issue.readText())
    }

    @Test fun `agent cannot overwrite a human edit made during elaboration`(@TempDir root: File) {
        val issue = root.resolve("issue.md")
        issue.writeText("---\nschema_version: 2\ndocument_type: issue\nid: issue-1\ntitle: Test\n---\n\n## Context\nDo the thing\n")
        val original = issue.readText()
        val outcome = ElaborationService.elaborate(issue, root, ReviewerConfiguration(listOf("/bin/true"))) { _, _, _ ->
            issue.writeText(original.replace("Do the thing", "A human changed this"))
            AgentTurn.Completed(original)
        }
        assertFalse(outcome.success)
        assertContains(outcome.message, "changed")
        assertContains(issue.readText(), "A human changed this")
    }

    @Test fun `invalid path is refused without agent invocation`(@TempDir root: File) {
        val issue = root.parentFile.resolve("elsewhere.md")
        var invoked = false
        val outcome = ElaborationService.elaborate(issue, root, ReviewerConfiguration(listOf("/bin/true"))) { _, _, _ ->
            invoked = true
            AgentTurn.Failed("Unexpected")
        }
        assertFalse(outcome.success)
        assertFalse(invoked)
    }

    @Test fun `candidate discovery and exact impact refine proposed plan before publication`(@TempDir root: File) {
        val issue = root.resolve("issue.md")
        issue.writeText("---\nschema_version: 2\ndocument_type: issue\nid: issue-1\ntitle: Reject blanks\n---\n\n## Context\nReject blank names.\n")
        root.resolve("src/Validator.kt").apply { parentFile.mkdirs(); writeText("class Validator { fun validate() = true }") }
        val initialPlan = """---
schema_version: 2
document_type: issue
id: issue-1
title: Reject blanks
---

## Context
Reject blank names.

## Acceptance criteria
### AC1 — Blank names fail
Reject empty names.

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
Reject empty input.

## Verification
### T1 — Empty name
```yaml
kind: new
file: src/ValidatorTest.kt
symbol: rejectsBlank
```
Check result.
"""
        var collections = 0
        val tools = object : PlanningTools {
            override fun freshCollection(): PlanningTools { collections++; return this }
            override fun source(root: File, path: String, symbol: String?) = SourceEvidence(PlanningStatus.OK, "PARSER_VERIFIED", "fun validate() = true")
            override fun impact(root: File, symbol: String) = ImpactEvidence(PlanningStatus.OK, listOf(symbol, "submit"))
            override fun source(root: File, target: TargetRef) = source(root, target.file, target.symbol)
            override fun impact(root: File, target: TargetRef) = ImpactEvidence(PlanningStatus.OK, listOf("validate", "ValidatorController.submit"), provider = "codanna")
            override fun revision(root: File) = "revision-1"
            override fun candidates(root: File, description: String): CandidateSearch {
                assertContains(description, "Reject blank names")
                return CandidateSearch(PlanningStatus.OK, listOf(PlanningCandidate(
                    TargetRef("src/Validator.kt", "validate", TargetKind.EXISTING), "fun validate() = true")))
            }
        }
        val prompts = mutableListOf<String>()
        val outcome = ElaborationService.elaborate(issue, root, ReviewerConfiguration(listOf("/bin/true")), tools) { _, _, prompt ->
            prompts += prompt
            if (prompts.size == 1) AgentTurn.Completed(initialPlan)
            else AgentTurn.Completed(initialPlan.replace("Reject empty input.", "Reject empty input. Handle ValidatorController.submit too."))
        }
        assertTrue(outcome.success, outcome.message)
        assertEquals(2, prompts.size)
        assertEquals(2, collections, "Candidate lookup and proposed-target verification need separate budgets")
        assertContains(prompts.first(), "src/Validator.kt::validate")
        assertContains(prompts.last(), "ValidatorController.submit")
        assertContains(issue.readText(), "Handle ValidatorController.submit too.")
    }
}
