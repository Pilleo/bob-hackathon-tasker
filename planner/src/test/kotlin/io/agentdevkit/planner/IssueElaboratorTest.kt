package io.agentdevkit.planner

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class IssueElaboratorTest {
    @Test fun `unanswered human decision remains unanswered after agent revises generated steps`(@TempDir root: File) {
        val issue = root.resolve("issue.md")
        issue.writeText("---\nschema_version: 2\ndocument_type: issue\nid: issue-1\ntitle: Reject blanks\n---\n\n## Context\nReject blank names.\n")
        fun decision(target: String, answer: String) = generated(target) + """

## Questions
### Q1 — Null handling
```yaml
blocks: [S1]
resolve_through: decision
```
**Question:** Reject null?
**Why it matters:** Changes public behavior.
**Answer:** $answer
"""
        assertTrue(IssueElaborator.apply(issue, decision("OldValidator.kt", "")).success)
        val revised = IssueElaborator.apply(issue, decision("NewValidator.kt", "Yes, reject null."))
        assertTrue(revised.success, revised.message)
        val document = assertIs<DocumentRead.Valid>(PlanningDocumentReader.read(issue, root)).document
        assertNull(document.questions.single().answer, "Only a human may resolve an unanswered decision")
        assertEquals("src/NewValidator.kt", document.steps.single().targets.single().file)
        val fresh = root.resolve("fresh.md")
        fresh.writeText("---\nschema_version: 2\ndocument_type: issue\nid: issue-1\ntitle: Reject blanks\n---\n\n## Context\nReject blank names.\n")
        assertTrue(IssueElaborator.apply(fresh, decision("NewValidator.kt", "Agent guessed yes.")).success)
        assertNull(assertIs<DocumentRead.Valid>(PlanningDocumentReader.read(fresh, root)).document.questions.single().answer,
            "The first agent response cannot settle a new human decision")
    }

    @Test fun `agent cannot remove rename reclassify or rewrite an existing unresolved decision`(@TempDir root: File) {
        val issue = root.resolve("issue.md")
        issue.writeText("---\nschema_version: 2\ndocument_type: issue\nid: issue-1\ntitle: Reject blanks\n---\n\n## Context\nReject blank names.\n")
        val question = """

## Questions
### Q1 — Null handling
```yaml
blocks: [S1]
resolve_through: decision
```
**Question:** Reject null?
**Why it matters:** Changes public behavior.
**Answer:**
"""
        assertTrue(IssueElaborator.apply(issue, generated("OldValidator.kt") + question).success)
        val original = issue.readText()
        val responses = listOf(
            generated("NewValidator.kt"),
            generated("NewValidator.kt") + question.replace("Q1 —", "Q2 —"),
            generated("NewValidator.kt") + question.replace("resolve_through: decision", "resolve_through: investigation")
                .replace("**Answer:**", "**Investigation:** Inspect callers.\n**Answer:** Agent says yes."),
            generated("NewValidator.kt") + question.replace("Reject null?", "Allow null?"),
            generated("NewValidator.kt") + question.replace("blocks: [S1]", "blocks: [S1, S1]"),
        )
        for (response in responses) {
            issue.writeText(original)
            val outcome = IssueElaborator.apply(issue, response)
            assertFalse(outcome.success, "Agent changed an existing unresolved decision: $outcome")
            assertEquals(original, issue.readText(), "Rejected revision modified the issue")
        }
    }

    @Test fun `agent can record result of an actionable investigation`(@TempDir root: File) {
        val issue = root.resolve("issue.md")
        issue.writeText("---\nschema_version: 2\ndocument_type: issue\nid: issue-1\ntitle: Reject blanks\n---\n\n## Context\nReject blank names.\n")
        fun investigation(answer: String) = generated("Validator.kt") + """

## Questions
### Q1 — Find null behavior
```yaml
blocks: [S1]
resolve_through: investigation
```
**Question:** How is null handled?
**Why it matters:** Changes validation.
**Investigation:** Inspect validator callers.
**Answer:** $answer
"""
        assertTrue(IssueElaborator.apply(issue, investigation("")).success)
        val revised = IssueElaborator.apply(issue, investigation("Callers reject null."))
        assertTrue(revised.success, revised.message)
        assertEquals("Callers reject null.", assertIs<DocumentRead.Valid>(PlanningDocumentReader.read(issue, root))
            .document.questions.single().answer)
    }

    @Test fun `answered question merge preserves CRLF frontmatter and body`(@TempDir root: File) {
        val issue = root.resolve("issue.md")
        issue.writeText("---\nschema_version: 2\ndocument_type: issue\nid: issue-1\ntitle: Reject blanks\n---\n\n## Context\nReject blank names.\n"
            .replace("\n", "\r\n"))
        fun question(target: String) = (generated(target) + """

## Questions
### Q1 — Null handling
```yaml
blocks: [S1]
resolve_through: decision
```
**Question:** Reject null?
**Why it matters:** Defines validation.
**Answer:**
""").replace("\n", "\r\n")
        val first = IssueElaborator.apply(issue, question("OldValidator.kt"))
        assertTrue(first.success, first.message)
        issue.writeText(issue.readText().replace("**Answer:**", "**Answer:** Yes, reject null."))
        val revised = IssueElaborator.apply(issue, question("NewValidator.kt"))
        assertTrue(revised.success, revised.message)
        val saved = issue.readText()
        assertEquals("Yes, reject null.", assertIs<DocumentRead.Valid>(PlanningDocumentReader.read(issue, root))
            .document.questions.single().answer)
        assertFalse(Regex("(?<!\\r)\\n").containsMatchIn(saved), "Document line endings changed during answer merge")
    }

    private fun generated(target: String) = """---
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
### S1 — Update validator
```yaml
acceptance: [AC1]
targets:
  - file: src/$target
    kind: new
verification: [T1]
```
Change validation.

## Verification
### T1 — Check blanks
```yaml
kind: new
file: src/ValidatorTest.kt
symbol: rejectsBlank
```
Check blank name.
"""

    @Test fun `agent may correct its own unchanged generated target`(@TempDir root: File) {
        val issue = root.resolve("issue.md")
        issue.writeText("---\nschema_version: 2\ndocument_type: issue\nid: issue-1\ntitle: Reject blanks\n---\n\n## Context\nReject blank names.\n")
        val first = IssueElaborator.apply(issue, generated("OldValidator.kt"))
        assertTrue(first.success, first.message)
        val corrected = IssueElaborator.apply(issue, generated("NewValidator.kt"))
        assertTrue(corrected.success, corrected.message)
        assertEquals("src/NewValidator.kt", assertIs<DocumentRead.Valid>(PlanningDocumentReader.read(issue, root))
            .document.steps.single().targets.single().file)
        assertContains(issue.readText(), "Reject blank names.")
    }

    @Test fun `human edit to generated step prevents agent overwrite`(@TempDir root: File) {
        val issue = root.resolve("issue.md")
        issue.writeText("---\nschema_version: 2\ndocument_type: issue\nid: issue-1\ntitle: Reject blanks\n---\n\n## Context\nReject blank names.\n")
        assertTrue(IssueElaborator.apply(issue, generated("OldValidator.kt")).success)
        val edited = issue.readText().replace("OldValidator.kt", "HumanValidator.kt")
        issue.writeText(edited)
        val result = IssueElaborator.apply(issue, generated("NewValidator.kt"))
        assertFalse(result.success)
        assertEquals(edited, issue.readText())
        assertNotNull(IssueElaborator.draftProblem(edited), "Refuse a paid agent turn for a conflicted generated section")
    }

    @Test fun `published review does not change generated ownership or prevent correction`(@TempDir root: File) {
        val issue = root.resolve("issue.md")
        issue.writeText("---\nschema_version: 2\ndocument_type: issue\nid: issue-1\ntitle: Reject blanks\n---\n\n## Context\nReject blank names.\n")
        assertTrue(IssueElaborator.apply(issue, generated("OldValidator.kt")).success)
        val authorHash = hash(ReviewProjection.authoredBytes(issue.readText()))
        val artifact = StoredReview(issuePath = "issue.md", issueSha256 = "unused",
            evidence = PlanningEvidence(PlanningStatus.PARTIAL, emptyList(), emptyList(), emptyList(), null),
            reviewer = "fixture", review = IssueReview(ReviewVerdict.CHANGES_REQUESTED, "Wrong target", emptyList(), emptyList()))
        assertIs<ProjectionResult.Written>(ReviewProjection.publish(issue, authorHash, null, artifact))
        assertNull(IssueElaborator.draftProblem(issue.readText()))
        val corrected = IssueElaborator.apply(issue, generated("NewValidator.kt"))
        assertTrue(corrected.success, corrected.message)
        assertEquals("src/NewValidator.kt", assertIs<DocumentRead.Valid>(PlanningDocumentReader.read(issue, root))
            .document.steps.single().targets.single().file)
    }

    @Test fun `human answer to generated question remains owned while agent corrects its steps`(@TempDir root: File) {
        val issue = root.resolve("issue.md")
        issue.writeText("---\nschema_version: 2\ndocument_type: issue\nid: issue-1\ntitle: Reject blanks\n---\n\n## Context\nReject blank names.\n")
        fun withQuestion(target: String) = generated(target) + """

## Questions
### Q1 — Null names
```yaml
blocks: [S1]
resolve_through: decision
```
**Question:** Should null be rejected?
**Why it matters:** Changes validation.
**Answer:**
"""
        assertTrue(IssueElaborator.apply(issue, withQuestion("OldValidator.kt")).success)
        issue.writeText(issue.readText().replace("**Answer:**", "**Answer:** Yes, reject null."))
        assertNull(IssueElaborator.draftProblem(issue.readText()), "Answer is human-owned, not a corrupted generated section")
        val corrected = IssueElaborator.apply(issue, withQuestion("NewValidator.kt"))
        assertTrue(corrected.success, corrected.message)
        val parsed = assertIs<DocumentRead.Valid>(PlanningDocumentReader.read(issue, root)).document
        assertEquals("Yes, reject null.", parsed.questions.single().answer)
        assertEquals("src/NewValidator.kt", parsed.steps.single().targets.single().file)
        val answeredIssue = issue.readText()
        assertFalse(IssueElaborator.apply(issue, generated("ThirdValidator.kt")).success, "The agent must not drop an answered question")
        assertFalse(IssueElaborator.apply(issue, withQuestion("ThirdValidator.kt")
            .replace("Should null be rejected?", "Should null be accepted?")).success, "The agent must not change what was answered")
        assertEquals(answeredIssue, issue.readText())
    }

    @Test fun `multiple human answers survive when agent omits answer lines`(@TempDir root: File) {
        val issue = root.resolve("issue.md")
        issue.writeText("---\nschema_version: 2\ndocument_type: issue\nid: issue-1\ntitle: Reject blanks\n---\n\n## Context\nReject blank names.\n")
        fun questions(target: String) = generated(target) + """

## Questions
### Q1 — Null handling
```yaml
blocks: [S1]
resolve_through: decision
```
**Question:** Reject null?
**Why it matters:** Validation semantics.
**Answer:**
### Q2 - Error format
```yaml
blocks: [S1]
resolve_through: decision
```
**Question:** Which error?
**Why it matters:** Public API shape.
**Answer:**
"""
        assertTrue(IssueElaborator.apply(issue, questions("OldValidator.kt")).success)
        issue.writeText(issue.readText().replaceFirst("**Answer:**\n", "**Answer:** Reject null.\n")
            .replaceFirst("**Answer:**\n", "**Answer:** Use InvalidName.\n"))
        val response = questions("NewValidator.kt").replace("**Answer:**\n", "")
        val result = IssueElaborator.apply(issue, response)
        assertTrue(result.success, result.message)
        val answers = assertIs<DocumentRead.Valid>(PlanningDocumentReader.read(issue, root)).document.questions.map { it.answer }
        assertEquals(listOf("Reject null.", "Use InvalidName."), answers)
    }

    @Test fun `agent plan keeps the human description and optional targets`(@TempDir root: File) {
        val issue = root.resolve("docs/internals/backlog/issue.md")
        issue.parentFile.mkdirs()
        issue.writeText("""---
schema_version: 2
document_type: issue
id: issue-1
title: "Reject blanks"
status: open
priority: 0
dependencies: []
---

## Context
Blank names are accepted. They should be rejected.

## Targets
src/main/kotlin/Validator.kt
""")
        val prompt = IssueElaborator.prompt(issue.readText())
        assertContains(prompt, "Blank names are accepted")
        assertContains(prompt, "src/main/kotlin/Validator.kt")
        assertContains(prompt, "id: issue-1")
        assertContains(prompt, "title: \"Reject blanks\"")
        assertContains(prompt, "## Context")
        assertContains(prompt, "verification: [T1]")
        assertContains(prompt, "### Q1 —")
        assertContains(prompt, "blocks: [S1]")
        assertContains(prompt, "resolve_through: decision")
        assertContains(prompt, "**Question:**")
        assertContains(prompt, "**Why it matters:**")
        assertContains(prompt, "**Answer:**")
        val filled = """---
schema_version: 2
document_type: issue
id: issue-1
title: "Reject blanks"
status: open
priority: 0
dependencies: []
---

## Context
Blank names are accepted. They should be rejected.

## Acceptance criteria
### AC1 — Blank names fail
A blank name is rejected.

## Changes
### S1 — Reject blank names
```yaml
acceptance: [AC1]
targets:
  - file: src/main/kotlin/Validator.kt
    symbol: validate
    kind: existing
verification: [T1]
```
Check the name before accepting it.

## Verification
### T1 — Blank input
```yaml
kind: new
file: src/test/kotlin/ValidatorTest.kt
symbol: rejectsBlank
```
A blank name fails.

## Targets
src/main/kotlin/Validator.kt
"""
        val result = IssueElaborator.apply(issue, filled)
        assertTrue(result.success, result.message)
        val saved = assertIs<DocumentRead.Valid>(PlanningDocumentReader.parse(issue.readText()))
        assertEquals(listOf("src/main/kotlin/Validator.kt"), saved.document.steps.flatMap { it.targets }.map { it.file })
        assertEquals("src/test/kotlin/ValidatorTest.kt", saved.document.verification.single().target?.file)
        assertContains(saved.document.authoredText, "Blank names are accepted")
    }

    @Test fun `elaboration rejects changed human draft without overwriting it`(@TempDir root: File) {
        val issue = root.resolve("issue.md")
        issue.writeText("---\nschema_version: 2\ndocument_type: issue\nid: issue-1\ntitle: Example\n---\n\n## Context\nOriginal request\n")
        val original = issue.readText()
        val generated = original + "\n## Acceptance criteria\n### AC1 — Outcome\nObservable\n\n## Changes\n### S1 — Work\n"
        issue.writeText(original.replace("Original request", "Updated request"))
        val result = IssueElaborator.apply(issue, generated, expectedText = original)
        assertFalse(result.success)
        assertContains(result.message, "changed")
        assertContains(issue.readText(), "Updated request")
    }

    @Test fun `re-elaboration cannot rewrite existing human decisions criteria or notes`(@TempDir root: File) {
        val issue = root.resolve("issue.md")
        val original = """---
schema_version: 2
document_type: issue
id: issue-1
title: Reject blanks
status: open
priority: 20
dependencies: [issue-prerequisite]
---
## Context
Reject empty names.
## Targets
src/Validator.kt
## Acceptance criteria
### AC1 — Blank names fail
Reject blank input with InvalidName.
## Changes
### S1 — Validate names
```yaml
acceptance: [AC1]
targets:
  - file: src/Validator.kt
    kind: new
verification: [T1]
```
Change validation.
## Verification
### T1 — Invalid name
```yaml
kind: new
file: src/ValidatorTest.kt
symbol: rejectsBlank
```
Test invalid name.
## Questions
### Q1 — Null behavior
```yaml
blocks: [S1]
resolve_through: decision
```
**Question:** What about null?
**Why it matters:** Changes validation.
**Answer:** Reject null too.
## Review notes
Keep the public API stable.
"""
        val revisions = listOf(
            original.replace("priority: 20", "priority: 0"),
            original.replace("Reject blank input with InvalidName.", "Return any error."),
            original.replace("Keep the public API stable.", "No review notes."),
            original.replace("src/Validator.kt\n## Acceptance", "src/Other.kt\n## Acceptance"),
        )
        for (revision in revisions) {
            issue.writeText(original)
            val result = IssueElaborator.apply(issue, revision)
            assertFalse(result.success, "Unsafe revision was accepted: $revision")
            assertEquals(original, issue.readText())
        }
        issue.writeText(original)
        val answerAttempt = IssueElaborator.apply(issue, original.replace("**Answer:** Reject null too.", "**Answer:** Accept null."))
        assertTrue(answerAttempt.success, answerAttempt.message)
        assertContains(issue.readText(), "**Answer:** Reject null too.")
    }
}
