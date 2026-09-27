package io.agentdevkit

import io.agentdevkit.planner.BacklogValidator
import io.agentdevkit.planner.ExecutionPlan
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class ExecutionPlanTest {
    @Test fun `check-backlog validates superpowers plans even without a document type marker`() {
        val root = Files.createTempDirectory("adk-plans").toFile()
        val plan = root.resolve("docs/superpowers/plans/cell.md")
        plan.parentFile.mkdirs()
        plan.writeText("""# Cell Implementation Plan

**Goal:** Do the thing.

### Task 1
- [ ] **Step 1: Add failing test**
""")
        val errors = BacklogValidator.validateBacklog(root)
        assertTrue(errors.any { it.contains("frontmatter") || it.contains("document_type") }, errors.toString())
    }

    @Test fun `superpowers shaped plans pass with goal TDD and revision frontmatter`() {
        val file = Files.createTempFile("superpowers-plan", ".md").toFile()
        file.writeText("""---
document_type: execution_plan
base_revision: abcdef1
---
# Cell Implementation Plan

**Goal:** Do the thing.

### Task 1
- [ ] **Step 1: Add failing TDD test**
""")
        assertTrue(ExecutionPlan.validate(file).isEmpty(), ExecutionPlan.validate(file).toString())
    }

    @Test fun `check-backlog validates execution plans under superpowers plans`() {
        val root = Files.createTempDirectory("adk-plans").toFile()
        val plan = root.resolve("docs/superpowers/plans/cell.md")
        plan.parentFile.mkdirs()
        plan.writeText("""---
document_type: execution_plan
base_revision: not-a-revision
---
## Goal
x
""")
        val errors = BacklogValidator.validateBacklog(root)
        assertTrue(errors.any { it.contains("base_revision") }, errors.toString())
    }

    @Test fun `rejects duplicate issue identifiers`() {
        val root = Files.createTempDirectory("adk-dup-id").toFile()
        val dir = root.resolve("docs/internals/backlog")
        dir.mkdirs()
        val body = """---
title: "Dup"
severity: "LOW"
status: "open"
priority: low
id: issue-dup
---
**Context:**
x
**Needed:**
1. x
"""
        dir.resolve("issue-20260906-010101-one.md").writeText(body)
        dir.resolve("issue-20260906-010102-two.md").writeText(body)
        val errors = BacklogValidator.validateBacklog(root)
        assertTrue(errors.any { it.contains("Duplicate issue id") }, errors.toString())
    }

    @Test fun `k03-empty-case rejects empty compact TDD entries`() {
        val file = compactPlan("-\n- [ ]\n- [k03-empty-case]")
        assertTrue(ExecutionPlan.validate(file).any { it.contains("TDD cases") })
    }

    @Test fun `k03-no-reason rejects bare not applicable`() {
        val file = compactPlan("TDD: not applicable")
        assertTrue(ExecutionPlan.validate(file).any { it.contains("TDD cases") })
    }

    @Test fun `k03-valid-cases accepts behavior and named checklist entries`() {
        val file = compactPlan("- verify validation\n- [k03-valid-cases] behavior is described")
        assertTrue(ExecutionPlan.validate(file).isEmpty(), ExecutionPlan.validate(file).toString())
    }

    @Test fun `k03-valid-exemption accepts explained not applicable`() {
        val file = compactPlan("TDD: not applicable — documentation-only change")
        assertTrue(ExecutionPlan.validate(file).isEmpty(), ExecutionPlan.validate(file).toString())
    }

    @Test fun `k03-legacy keeps valid superpowers TDD text accepted`() {
        val file = Files.createTempFile("legacy-plan", ".md").toFile()
        file.writeText("""---
 document_type: execution_plan
 base_revision: abcdef1
 ---
 **Goal:** Do the thing.
 ### Task 1
 - [ ] **Step 1: Add failing TDD test**
 """.replace("\n ", "\n"))
        assertTrue(ExecutionPlan.validate(file).isEmpty(), ExecutionPlan.validate(file).toString())
    }

    private fun compactPlan(tdd: String) = Files.createTempFile("compact-plan", ".md").toFile().also {
        it.writeText("""---
 document_type: execution_plan
 base_revision: abcdef1
 ---
 ## Goal
 x
 ## Acceptance criteria
 x
 ## Non-goals
 x
 ## Risks
 x
 ## Target claims
 x
 ## TDD cases
 $tdd
 ## Unresolved decisions
 none
 """.replace("\n ", "\n"))
    }

    @Test fun `requires TDD choice in approved plan`() {
        val file = Files.createTempFile("plan", ".md").toFile()
        file.writeText("""---
document_type: execution_plan
base_revision: abcdef1
---
## Goal
x
## Acceptance criteria
x
## Non-goals
x
## Risks
x
## Target claims
x
## TDD cases
- regression stays covered
## Unresolved decisions
none
""")
        assertTrue(ExecutionPlan.validate(file).isEmpty())
    }
}
