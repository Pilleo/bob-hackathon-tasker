package io.agentdevkit.planner.app

import io.agentdevkit.planner.*
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class PlannerApplicationTest {
    @Test fun `new issue accepts repeated target file hints without inventing changes`(@TempDir root: File) {
        root.resolve("src/main/kotlin/sample/Main.kt").apply { parentFile.mkdirs(); writeText("class NameValidator") }
        val result = PlannerApplication.run(root, listOf("issue", "new", "--title", "Reject blanks",
            "--target", "src/main/kotlin/sample/Main.kt", "--target", "src/test/kotlin/sample/NameValidatorTest.kt"))
        assertEquals(0, result.exitCode, result.text)
        val file = root.resolve("docs/internals/backlog").listFiles().orEmpty().single()
        val text = file.readText()
        assertContains(text, "## Targets\nsrc/main/kotlin/sample/Main.kt\nsrc/test/kotlin/sample/NameValidatorTest.kt")
        assertContains(text, "target_files:\n  - \"src/main/kotlin/sample/Main.kt\"\n  - \"src/test/kotlin/sample/NameValidatorTest.kt\"")
        assertFalse(text.contains("Optional. One repository-relative file"))
        assertFalse(text.contains("writes:"), "File hints are not scheduler write claims")
        assertIs<DocumentRead.Valid>(PlanningDocumentReader.read(file, root))
    }

    @Test fun `new issue requires target files before creating a draft`(@TempDir root: File) {
        val result = PlannerApplication.run(root, listOf("issue", "new", "--title", "Reject blanks"))
        assertEquals(4, result.exitCode, result.text)
        assertTrue(root.resolve("docs/internals/backlog").listFiles().orEmpty().isEmpty())
    }

    @Test fun `invalid target hints are rejected before creating any issue`(@TempDir root: File) {
        root.resolve("src").mkdir()
        for (target in listOf("../outside.kt", "/tmp/outside.kt", "src", "src/Bad\nInjected.kt")) {
            val result = PlannerApplication.run(root, listOf("issue", "new", "--title", "Reject blanks", "--target", target))
            assertEquals(4, result.exitCode, "Expected invalid target '$target': ${result.text}")
            assertTrue(root.resolve("docs/internals/backlog").listFiles().orEmpty().isEmpty(), "Invalid target created an issue")
        }
    }

    @Test fun `standalone starter creates a short issue without ADK`(@TempDir root: File) {
        val result = PlannerApplication.run(root, listOf("issue", "new", "--title", "Reject blanks", "--target", "src/Validator.kt"))
        assertEquals(0, result.exitCode, result.text)
        val issue = root.resolve("docs/internals/backlog").listFiles().orEmpty().single()
        assertContains(issue.readText(), "## Context")
        assertFalse(issue.readText().contains("### AC1"))
    }

    @Test fun `explicit ACP profile is saved without Antigravity assumptions`(@TempDir root: File) {
        val agent = root.resolve("agent").apply { writeText("fixture"); setExecutable(true) }
        val result = PlannerApplication.run(root, listOf("planner", "setup", "--name", "sample", "--agent", agent.absolutePath,
            "--arg", "--stdio", "--arg", "--profile=testing"))
        assertEquals(0, result.exitCode, result.text)
        val config = root.resolve(".planner.json").readText()
        assertContains(config, "sample")
        assertContains(config, agent.absolutePath)
        assertContains(config, "--stdio")
        assertContains(config, "--profile=testing")
        assertFalse(config.contains("--uid="))
    }

    @Test fun `reconfiguring named agent retains limits model and other profiles`(@TempDir root: File) {
        root.resolve(".planner.json").writeText("""{
          "defaultReviewer": "sample",
          "reviewers": {
            "sample": {"command":["/old"], "readOnly":"BUBBLEWRAP", "timeoutMs":90000,
                       "maxOutputBytes":8192, "model":"custom-model"},
            "other": {"command":["/bin/true"], "readOnly":"BUBBLEWRAP"}
          }
        }""")
        val agent = root.resolve("updated-agent").apply { writeText("fixture"); setExecutable(true) }
        val result = PlannerApplication.run(root, listOf("planner", "setup", "--name", "sample", "--agent", agent.absolutePath))
        assertEquals(0, result.exitCode, result.text)
        val saved = root.resolve(".planner.json").readText()
        assertContains(saved, "\"timeoutMs\": 90000")
        assertContains(saved, "\"maxOutputBytes\": 8192")
        assertContains(saved, "custom-model")
        assertContains(saved, "other")
        assertContains(saved, agent.absolutePath)
    }

    @Test fun `unconfigured agent fails explicitly without changing issue`(@TempDir root: File) {
        val created = PlannerApplication.run(root, listOf("issue", "new", "--title", "Reject blanks", "--target", "src/Validator.kt"))
        assertEquals(0, created.exitCode)
        val issue = root.resolve("docs/internals/backlog").listFiles().orEmpty().single()
        val before = issue.readText()
        val result = PlannerApplication.run(root, listOf("issue", "elaborate", issue.relativeTo(root).invariantSeparatorsPath))
        assertNotEquals(0, result.exitCode)
        assertContains(result.text, "agent")
        assertEquals(before, issue.readText())
    }

    @Test fun `fixture agent elaborates and reviews a human description in the standalone app`(@TempDir root: File) {
        val executable = root.resolve("agent").apply { writeText("fixture"); setExecutable(true) }
        assertEquals(0, PlannerApplication.run(root, listOf("planner", "setup", "--name", "fixture", "--agent", executable.absolutePath)).exitCode)
        assertEquals(0, PlannerApplication.run(root, listOf("issue", "new", "--title", "Reject blanks", "--target", "src/Validator.kt")).exitCode)
        val issue = root.resolve("docs/internals/backlog").listFiles().orEmpty().single()
        val input = issue.readText().replace("Write what should change and why. A short description is enough.", "Reject whitespace-only names.")
        issue.writeText(input)
        val agent = PlanningAgent { _, _, prompt ->
            if ("Review this initial issue" in prompt) {
                AgentTurn.Completed("""{"verdict":"CHANGES_REQUESTED","summary":"Review example","findings":[],"questions":[]}""")
            } else {
                val source = prompt.substringAfter("Issue document:\n", missingDelimiterValue = "")
                assertContains(source, "Reject whitespace-only names.")
                val plan = source.substringBefore("## Targets") + """## Acceptance criteria
### AC1 — Reject blanks
Blank names are rejected.
## Changes
### S1 — Validate names
```yaml
acceptance: [AC1]
targets:
  - file: src/Validator.kt
    kind: new
verification: [T1]
```
Check input.
## Verification
### T1 — Blank name
```yaml
kind: new
file: src/ValidatorTest.kt
symbol: rejectsBlank
```
Observe rejection.
"""
                AgentTurn.Completed(plan + "\n## Targets" + source.substringAfter("## Targets"))
            }
        }
        val path = issue.relativeTo(root).invariantSeparatorsPath
        val elaboration = PlannerApplication.run(root, listOf("issue", "elaborate", path), agent)
        assertEquals(0, elaboration.exitCode, elaboration.text)
        assertEquals(1, assertIs<DocumentRead.Valid>(PlanningDocumentReader.read(issue, root)).document.steps.size)
        val reviewed = PlannerApplication.run(root, listOf("review-issue", path), agent)
        assertEquals(0, reviewed.exitCode, reviewed.text)
        assertContains(issue.readText(), "Review example")
    }
}
