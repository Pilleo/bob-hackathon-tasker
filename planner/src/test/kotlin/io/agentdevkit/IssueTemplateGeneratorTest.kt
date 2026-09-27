package io.agentdevkit

import io.agentdevkit.planner.BacklogValidator
import io.agentdevkit.planner.IssueRequest
import io.agentdevkit.planner.IssueTemplateGenerator
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class IssueTemplateGeneratorTest {

    @Test
    fun `scaffolds structured markdown issue file with valid yaml frontmatter`(@TempDir tempDir: File) {
        val request = IssueRequest(
            title = "Implement Automated Memory Pruning",
            severity = "HIGH",
            priority = "high",
            component = "memory",
            targetFiles = listOf("src/main/kotlin/io/agentdevkit/memory/MemoryAuditor.kt"),
            targetSymbols = listOf("MemoryAuditor"),
            context = "Stale negative memories cause hallucinations.",
            needed = listOf(
                "Scan memories against Codanna",
                "Tombstone deleted symbols",
            ),
        )

        val result = IssueTemplateGenerator.scaffold(request, tempDir)
        assertTrue(result.file.exists())
        assertEquals(result.file.name.removeSuffix(".md"), result.id)
        assertTrue(result.markdown.contains("severity: \"HIGH\""))
        assertTrue(result.markdown.contains("status: \"open\""))
        assertTrue(result.markdown.contains("1. Scan memories against Codanna"))
        assertTrue(result.markdown.contains("2. Tombstone deleted symbols"))
    }

    @Test
    fun `slugify cleans special characters and spaces`() {
        val slug = IssueTemplateGenerator.slugify("Add EINTR progressive backoff to Socket (Part 1)!")
        assertEquals("add-eintr-progressive-backoff-to-socket-part-1", slug)
    }

    @Test
    fun `rapid issue creation produces distinct embedded identifiers`(@TempDir tempDir: File) {
        val first = IssueTemplateGenerator.scaffold(IssueRequest(title = "First"), tempDir)
        val second = IssueTemplateGenerator.scaffold(IssueRequest(title = "Second"), tempDir)
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `k01-nonlatin uses compatible fallback and preserves title`(@TempDir tempDir: File) {
        val title = "Привет мир"
        val result = IssueTemplateGenerator.scaffold(IssueRequest(title = title), tempDir)

        assertTrue(result.file.name.startsWith("issue-"))
        assertTrue(result.file.name.matches(Regex("issue-.+-[a-z0-9_-]+\\.md")))
        assertTrue(result.markdown.contains("title: \"$title\""))
        assertTrue(result.markdown.contains("# 🟡 [Severity: MEDIUM]: $title"))
    }

    @Test
    fun `k01-symbols uses literal issue fallback`(@TempDir tempDir: File) {
        val result = IssueTemplateGenerator.scaffold(IssueRequest(title = "😀?!"), tempDir)

        assertEquals("issue", IssueTemplateGenerator.slugify("😀?!"))
        assertTrue(result.file.name.endsWith("-issue.md"))
    }

    @Test
    fun `k01-compatible preserves ascii normalization and cap`() {
        assertEquals("add-eintr-progressive-backoff-to-socket-part-1", IssueTemplateGenerator.slugify("Add EINTR progressive backoff to Socket (Part 1)!"))
        assertEquals(50, IssueTemplateGenerator.slugify("a".repeat(60)).length)
    }

    @Test
    fun `k01-identity agrees with repeated generated artifacts`(@TempDir tempDir: File) {
        val first = IssueTemplateGenerator.scaffold(IssueRequest(title = "Same title"), tempDir)
        val second = IssueTemplateGenerator.scaffold(IssueRequest(title = "Same title"), tempDir)

        assertNotEquals(first.file.name, second.file.name)
        assertNotEquals(first.id, second.id)
        assertTrue(first.markdown.contains("<!-- id: ${first.id}  file: ${first.file.name} -->"))
        assertTrue(second.markdown.contains("<!-- id: ${second.id}  file: ${second.file.name} -->"))
        assertFalse(BacklogValidator.validateBacklog(tempDir).any { it.startsWith("issue-") && it.contains("Invalid filename") })
    }

    @Test
    fun `k02-title-roundtrip`(@TempDir tempDir: File) {
        val title = "Quote \" slash \\ tab\t newline\n snowman ☃"
        val result = IssueTemplateGenerator.scaffold(IssueRequest(title = title), tempDir)
        val scalar = result.markdown.substringAfter("title: ").substringBefore("\n")
        assertEquals(title, Json.decodeFromString<String>(scalar))
    }

    @Test
    fun `k02-target-roundtrip`(@TempDir tempDir: File) {
        val value = "Quote \" slash \\ tab\t newline\n snowman ☃"
        val result = IssueTemplateGenerator.scaffold(IssueRequest(title = "Targets", component = value, targetModules = listOf(value), targetFiles = listOf(value), targetSymbols = listOf(value)), tempDir)
        val lines = result.markdown.lines()
        assertEquals(value, Json.decodeFromString(lines.first { it.startsWith("component: ") }.substringAfter("component: ")))
        val listScalars = lines.filter { it.startsWith("  - ") }.map { it.substringAfter("  - ") }
        assertEquals(listOf(value, value, value), listScalars.map { Json.decodeFromString<String>(it) })
    }

    @Test
    fun `k02-frontmatter-boundary`(@TempDir tempDir: File) {
        val value = "safe\n---\ninjected: true"
        val result = IssueTemplateGenerator.scaffold(IssueRequest(title = value, component = value), tempDir)
        val frontmatter = result.markdown.substringAfter("---\n").substringBefore("\n---")
        assertFalse(frontmatter.lines().any { it.startsWith("injected:") })
        assertTrue(result.markdown.contains("severity: \"MEDIUM\""))
    }

    @Test
    fun `k02-compatible`(@TempDir tempDir: File) {
        val request = IssueRequest(title = "Ordinary issue", context = "Context", needed = listOf("Step"))
        val result = IssueTemplateGenerator.scaffold(request, tempDir)
        assertTrue(result.markdown.contains("title: \"Ordinary issue\""))
        assertTrue(result.markdown.contains("# 🟡 [Severity: MEDIUM]: Ordinary issue"))
        assertTrue(result.markdown.contains("**Context:**\nContext"))
        assertTrue(result.markdown.contains("1. Step"))
        assertTrue(BacklogValidator.validateBacklog(tempDir).isEmpty())
    }
}
