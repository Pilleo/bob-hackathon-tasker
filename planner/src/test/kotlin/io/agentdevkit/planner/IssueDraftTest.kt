package io.agentdevkit.planner

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IssueDraftTest {
    @Test fun `editor prompts are not valid acceptance criteria`(@TempDir root: File) {
        val issue = IssueTemplateGenerator.scaffold(IssueRequest("Improve", context = "Current validation is incomplete", needed = listOf("Reject bad inputs")), root)
        issue.file.appendText("\n**Acceptance criteria:**\n- Describe an observable success case.\n\n**Open questions:**\n- Replace this prompt with a real question, or remove it.\n")
        val result = IssueDraftReader.read(issue.file, root)
        assertTrue(result.errors.any { "Acceptance criteria" in it })
        assertTrue(result.errors.any { "Open questions" in it })
    }
    @Test
    fun `scaffold placeholders cannot be submitted for review`(@TempDir root: File) {
        val issue = IssueTemplateGenerator.scaffold(IssueRequest(title = "Improve validation"), root)
        val result = IssueDraftReader.read(issue.file, root)
        assertTrue(result.errors.any { it.contains("placeholder") }, result.errors.toString())
        assertTrue(result.errors.any { it.contains("Acceptance criteria") }, result.errors.toString())
    }

    @Test
    fun `draft accepts explicit unanswered questions while preserving targets`(@TempDir root: File) {
        val issue = IssueTemplateGenerator.scaffold(
            IssueRequest(
                title = "Improve validation", context = "Current issue validation permits incomplete drafts.",
                needed = listOf("Require acceptance criteria"),
                targetFiles = listOf("src/main/kotlin/Validator.kt"), targetSymbols = listOf("Validator"),
                openQuestions = true,
            ), root,
        )
        issue.file.appendText("\n**Acceptance criteria:**\n- Submitting a placeholder is rejected.\n\n**Open questions:**\n- Which legacy issues need migration?\n")
        val result = IssueDraftReader.read(issue.file, root)
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(listOf("src/main/kotlin/Validator.kt"), result.draft?.targetFiles)
        assertEquals(listOf("Validator"), result.draft?.targetSymbols)
        assertEquals(listOf("Which legacy issues need migration?"), result.draft?.questions)
    }

    @Test
    fun `issue path cannot escape backlog through symlink`(@TempDir root: File) {
        val outside = root.resolve("elsewhere.md").apply { writeText("private") }
        val dir = root.resolve("docs/internals/backlog").apply { mkdirs() }
        val link = dir.resolve("issue-20260926-123456-escape.md")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        val result = IssueDraftReader.read(link, root)
        assertTrue(result.errors.any { it.contains("backlog") })
    }
}
