package io.agentdevkit.planner

import org.junit.jupiter.api.Test
import kotlin.test.*

class PlanningDocumentReaderTest {
    private fun issue(meta: String = "", body: String = ""): String = """---
schema_version: 2
document_type: issue
id: issue-20260926-120000-validator
title: "Reject blank request names"
severity: MEDIUM
status: open
priority: 100
dependencies: []
$meta---
## Context
Blank names pass validation.
## Acceptance criteria
### AC1 — Reject whitespace-only names
Return InvalidName.
## Changes
### S1 — Validate request names
```yaml
acceptance: [AC1]
targets:
  - file: src/main/kotlin/Validator.kt
    symbol: validateName
    kind: existing
verification: [T1]
```
Reuse validation results.
## Verification
### T1 — Whitespace regression
```yaml
kind: new
file: src/test/kotlin/ValidatorTest.kt
symbol: rejectsWhitespaceName
```
Assert InvalidName.
## Questions
### Q1 — Null input
```yaml
blocks: [S1]
resolve_through: decision
```
**Question:** Does the API accept null?
**Why it matters:** Determines the public type.
**Investigation:** Inspect existing callers.
**Answer:**
$body"""

    @Test fun `v2 document preserves exact step target and actionable question`() {
        val read = assertIs<DocumentRead.Valid>(PlanningDocumentReader.parse(issue()))
        assertEquals(DocumentKind.ISSUE, read.document.kind)
        assertEquals(listOf(TargetRef("src/main/kotlin/Validator.kt", "validateName", TargetKind.EXISTING)), read.document.steps.single().targets)
        assertEquals(listOf("T1"), read.document.steps.single().verification)
        assertEquals(listOf("S1"), read.document.questions.single().blocks)
        assertNull(read.document.questions.single().answer)
    }

    @Test fun `new schema rejects obsolete step writes instead of ignoring them`() {
        val changed = issue().replace("verification: [T1]", "verification: [T1]\nwrites: [\"file:src/main/kotlin/Validator.kt\"]")
        val read = assertIs<DocumentRead.Invalid>(PlanningDocumentReader.parse(changed))
        assertTrue(read.problems.any { it.path == "S1.writes" && it.code == "UNKNOWN_FIELD" })
    }

    @Test fun `duplicate frontmatter key is not silently overwritten`() {
        val read = assertIs<DocumentRead.Invalid>(PlanningDocumentReader.parse(issue("priority: 50\n")))
        assertTrue(read.problems.any { it.code == "YAML_DUPLICATE_KEY" }, read.problems.toString())
    }

    @Test fun `dangling reference is invalid`() {
        val read = assertIs<DocumentRead.Invalid>(PlanningDocumentReader.parse(issue().replace("verification: [T1]", "verification: [T2]")))
        assertTrue(read.problems.any { it.code == "REFERENCE_NOT_FOUND" && it.path == "S1.verification" })
    }

    @Test fun `unsupported explicit version cannot become legacy`() {
        val read = assertIs<DocumentRead.Invalid>(PlanningDocumentReader.parse(issue().replace("schema_version: 2", "schema_version: 3")))
        assertTrue(read.problems.any { it.code == "UNKNOWN_SCHEMA_VERSION" })
    }

    @Test fun `duplicate section IDs are invalid`() {
        val extra = "\n### AC1 — Another criterion\nAnother meaning.\n"
        val read = assertIs<DocumentRead.Invalid>(PlanningDocumentReader.parse(issue().replace("## Changes", extra + "## Changes")))
        assertTrue(read.problems.any { it.code == "SECTION_DUPLICATE" })
    }

    @Test fun `headings inside fenced examples are ignored`() {
        val fenced = "\n```markdown\n### AC1 — Fake\n```\n"
        assertIs<DocumentRead.Valid>(PlanningDocumentReader.parse(issue().replace("## Changes", fenced + "## Changes")))
    }

    @Test fun `legacy issue remains recognizable`() {
        val legacy = "---\ntitle: \"Legacy\"\nseverity: \"LOW\"\nstatus: \"open\"\n---\n**Context:**\nWhy\n**Needed:**\n1. Do work\n"
        assertIs<DocumentRead.Legacy>(PlanningDocumentReader.parse(legacy))
    }

    @Test fun `oversized input and YAML alias fail explicitly`() {
        assertTrue(assertIs<DocumentRead.Invalid>(PlanningDocumentReader.parse("x".repeat(270_000))).problems.any { it.code == "DOCUMENT_TOO_LARGE" })
        assertIs<DocumentRead.Invalid>(PlanningDocumentReader.parse(issue().replace("dependencies: []", "dependencies: &a []\nextra: *a")))
    }

    @Test fun `wrong primitive types and unknown fields fail`() {
        val wrong = assertIs<DocumentRead.Invalid>(PlanningDocumentReader.parse(issue().replace("priority: 100", "priority: many")))
        assertTrue(wrong.problems.any { it.code == "YAML_TYPE" && it.path == "frontmatter.priority" })
        val field = assertIs<DocumentRead.Invalid>(PlanningDocumentReader.parse(issue("mystery: truth\n")))
        assertTrue(field.problems.any { it.code == "UNKNOWN_FIELD" })
    }

    @Test fun `duplicate metadata block and missing test type fail`() {
        val duplicate = assertIs<DocumentRead.Invalid>(PlanningDocumentReader.parse(issue().replace("Reuse validation results.",
            "```yaml\nacceptance: [AC1]\n```\nReuse validation results.")))
        assertTrue(duplicate.problems.any { it.code == "METADATA_DUPLICATE" })
        val missingKind = assertIs<DocumentRead.Invalid>(PlanningDocumentReader.parse(issue().replace("kind: new\nfile:", "file:")))
        assertTrue(missingKind.problems.any { it.code == "TARGET_KIND" })
    }

    @Test fun `question with no blocker and no resolution action fails`() {
        val noBlocks = assertIs<DocumentRead.Invalid>(PlanningDocumentReader.parse(issue().replace("blocks: [S1]", "blocks: []")))
        assertTrue(noBlocks.problems.any { it.code == "QUESTION_BLOCKS" })
        val noAction = assertIs<DocumentRead.Invalid>(PlanningDocumentReader.parse(issue().replace("resolve_through: decision", "resolve_through: investigation")
            .replace("**Investigation:** Inspect existing callers.", "**Investigation:**")))
        assertTrue(noAction.problems.any { it.code == "QUESTION_ACTION" })
    }

    @Test fun `text under unknown section identifier is invalid`() {
        val read = assertIs<DocumentRead.Invalid>(PlanningDocumentReader.parse(issue().replace("### S1 —", "### S0 —")))
        assertTrue(read.problems.any { it.code == "SECTION_ID_INVALID" })
    }
}
