package io.agentdevkit.planner.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduleCommandTest {

    @TempDir
    lateinit var tmp: File

    private fun fixture(content: String): String {
        val f = File(tmp, "fixture.json")
        f.writeText(content)
        return f.absolutePath
    }

    // ── exit codes ───────────────────────────────────────────────────────────

    @Test
    fun `valid batch produces exit code 0`() {
        val path = fixture(
            """[{"id":"a","priority":10,"state":"PENDING","dependencyIds":[],"target_files":["src/A.kt"]}]"""
        )
        val result = ScheduleCommand.run(listOf("--fixture", path, "--capacity", "2"))
        assertEquals(0, result.exitCode, "A usable batch should exit 0")
        assertTrue(result.text.contains("\"type\""), "Output should contain type field: ${result.text}")
        assertTrue(result.text.contains("\"batch\""), "Output should be a batch: ${result.text}")
    }

    @Test
    fun `InvalidSnapshot produces exit code 1`() {
        // Duplicate task IDs → InvalidSnapshot
        val path = fixture(
            """[
              {"id":"dup","priority":10,"state":"PENDING","dependencyIds":[],"target_files":["src/A.kt"]},
              {"id":"dup","priority":20,"state":"PENDING","dependencyIds":[],"target_files":["src/B.kt"]}
            ]"""
        )
        val result = ScheduleCommand.run(listOf("--fixture", path))
        assertEquals(1, result.exitCode, "InvalidSnapshot must exit 1, not 0")
        assertTrue(result.text.contains("\"invalidSnapshot\""), "Output should be invalidSnapshot: ${result.text}")
    }

    @Test
    fun `missing fixture file produces exit code 4`() {
        val result = ScheduleCommand.run(listOf("--fixture", "/nonexistent/path/fixture.json"))
        assertEquals(4, result.exitCode, "I/O errors should exit 4")
    }

    @Test
    fun `missing --fixture flag produces exit code 4`() {
        val result = ScheduleCommand.run(emptyList())
        assertEquals(4, result.exitCode)
    }

    @Test
    fun `fixture task must declare target_files`() {
        val path = fixture("""[{"id":"a","priority":10,"state":"PENDING","dependencyIds":[]}]""")
        val result = ScheduleCommand.run(listOf("--fixture", path))
        assertEquals(4, result.exitCode)
        assertTrue(result.text.contains("target_files"), result.text)
    }

    // ── backward compatibility: flat array fixture ───────────────────────────

    @Test
    fun `flat array fixture still produces exit code 0 and a batch`() {
        val path = fixture(
            """[
              {"id":"validation","priority":100,"state":"PENDING","dependencyIds":[],"target_files":["src/Validator.kt"]},
              {"id":"docs","priority":80,"state":"PENDING","dependencyIds":[],"target_files":["docs/validation.md"]}
            ]"""
        )
        val result = ScheduleCommand.run(listOf("--fixture", path, "--capacity", "2"))
        assertEquals(0, result.exitCode)
        assertTrue(result.text.contains("\"validation\""))
    }
}
