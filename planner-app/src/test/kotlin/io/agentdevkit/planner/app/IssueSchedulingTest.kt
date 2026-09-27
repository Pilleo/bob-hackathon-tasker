package io.agentdevkit.planner.app

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class IssueSchedulingTest {
    @Test fun `issues created with the same required target file conflict before ACP review`(@TempDir root: File) {
        val a = create(root, "Validate names", "src/NameValidator.kt")
        val b = create(root, "Change validation message", "src/NameValidator.kt")
        val result = ScheduleCommand.run(listOf("--issues", a, "--issues", b, "--capacity", "2"), root)
        assertEquals(0, result.exitCode, result.text)
        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("false", json.getValue("fixture").jsonPrimitive.content)
        assertEquals(1, json.getValue("selected").jsonArray.size)
        assertEquals("SelectedConflict", json.getValue("skipped").jsonArray.single().jsonObject
            .getValue("reason").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test fun `independent target files can be scheduled together without elaboration`(@TempDir root: File) {
        val a = create(root, "Validate names", "src/NameValidator.kt")
        val b = create(root, "Update docs", "docs/names.md")
        val result = ScheduleCommand.run(listOf("--issues", a, "--issues", b, "--capacity", "2"), root)
        assertEquals(0, result.exitCode, result.text)
        assertEquals(2, Json.parseToJsonElement(result.text).jsonObject.getValue("selected").jsonArray.size)
    }

    @Test fun `issue dependency becomes eligible only after referenced issue is resolved`(@TempDir root: File) {
        val prerequisite = create(root, "Validate names", "src/NameValidator.kt")
        val dependent = create(root, "Update message", "src/Message.kt")
        val prerequisiteId = File(prerequisite).nameWithoutExtension
        val dependentFile = File(root, dependent)
        dependentFile.writeText(dependentFile.readText().replace("dependencies: []", "dependencies: [\"$prerequisiteId\"]"))
        val arguments = listOf("--issues", prerequisite, "--issues", dependent, "--capacity", "2")

        val pending = ScheduleCommand.run(arguments, root)
        assertEquals(0, pending.exitCode, pending.text)
        val before = Json.parseToJsonElement(pending.text).jsonObject
        assertEquals(listOf(prerequisiteId), before.getValue("selected").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("DependencyNotSucceeded", before.getValue("skipped").jsonArray.single().jsonObject
            .getValue("reason").jsonObject.getValue("type").jsonPrimitive.content)

        val prerequisiteFile = File(root, prerequisite)
        prerequisiteFile.writeText(prerequisiteFile.readText().replace("status: open", "status: resolved"))
        val after = ScheduleCommand.run(arguments, root)
        assertEquals(0, after.exitCode, after.text)
        assertEquals(listOf(File(dependent).nameWithoutExtension), Json.parseToJsonElement(after.text).jsonObject
            .getValue("selected").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `issue without mandatory target files cannot be scheduled`(@TempDir root: File) {
        val issue = File(root, "docs/internals/backlog/issue-legacy-v2.md").apply {
            parentFile.mkdirs()
            writeText("---\nschema_version: 2\ndocument_type: issue\nid: issue-legacy-v2\ntitle: Old draft\nstatus: open\n---\n## Context\nOld description\n")
        }
        val result = ScheduleCommand.run(listOf("--issues", issue.relativeTo(root).invariantSeparatorsPath), root)
        assertEquals(4, result.exitCode)
        assertContains(result.text, "target_files")
        assertTrue(issue.isFile, "Existing authored issue must not be modified")
    }

    private fun create(root: File, title: String, target: String): String {
        val result = PlannerApplication.run(root, listOf("issue", "new", "--title", title, "--target", target))
        assertEquals(0, result.exitCode, result.text)
        val created = Regex("Created (.+?\\.md)\\.").find(result.text)?.groupValues?.get(1)
        return assertNotNull(created, result.text)
    }
}
