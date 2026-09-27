package io.agentdevkit.planner.app

import io.agentdevkit.planner.DocumentKind
import io.agentdevkit.planner.PlanningDocument
import io.agentdevkit.planner.PlanStep
import io.agentdevkit.planner.TargetRef
import io.agentdevkit.planner.TargetKind
import io.agentdevkit.scheduler.TaskState
import io.agentdevkit.scheduler.WriteScope
import io.agentdevkit.scheduler.Writes
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PlanToSchedulerBridgeTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun minimalDoc(
        id: String = "plan-1",
        priority: Int = 5,
        dependencies: List<String> = emptyList(),
        steps: List<PlanStep> = emptyList(),
        targetFiles: List<String> = listOf("src/Foo.kt"),
    ) = PlanningDocument(
        kind = DocumentKind.EXECUTION_PLAN,
        id = id,
        title = "Test",
        authoredText = "",
        criteria = emptyList(),
        steps = steps,
        verification = emptyList(),
        questions = emptyList(),
        priority = priority,
        dependencies = dependencies,
        targetFiles = targetFiles,
    )

    private fun step(file: String? = null) = PlanStep(
        id = "s1",
        title = "Step",
        body = "",
        acceptance = emptyList(),
        targets = if (file == null) emptyList() else listOf(TargetRef(file, null, TargetKind.NEW)),
        verification = emptyList(),
    )

    // ── translate: priority and dependencyIds ─────────────────────────────

    @Test
    fun `priority and dependencyIds are mapped from document`() {
        val doc = minimalDoc(id = "my-task", priority = 10, dependencies = listOf("dep-a", "dep-b"))
        val snapshot = PlanToSchedulerBridge.buildTaskSnapshot(doc, TaskState.PENDING)
        assertEquals("my-task", snapshot.id)
        assertEquals(10, snapshot.priority)
        assertEquals(listOf("dep-a", "dep-b"), snapshot.dependencyIds)
    }

    @Test
    fun `state is passed through correctly`() {
        val doc = minimalDoc()
        val snapshot = PlanToSchedulerBridge.buildTaskSnapshot(doc, TaskState.RUNNING)
        assertEquals(TaskState.RUNNING, snapshot.state)
    }

    // ── writes union logic ────────────────────────────────────────────────

    @Test
    fun `declared target files and proposed step files reserve file scopes`() {
        val doc = minimalDoc(targetFiles = listOf("src/Foo.kt", "src/bar/Generated.kt"), steps = listOf(
            step("src/Foo.kt"),
            step("src/bar/Generated.kt"),
        ))
        val snapshot = PlanToSchedulerBridge.buildTaskSnapshot(doc, TaskState.PENDING)
        val known = assertIs<Writes.Known>(snapshot.writes)
        assertEquals(
            listOf(WriteScope.FileScope("src/Foo.kt"), WriteScope.FileScope("src/bar/Generated.kt")),
            known.scopes,
        )
    }

    @Test
    fun `missing mandatory target files produces Writes Unknown`() {
        val doc = minimalDoc(targetFiles = emptyList(), steps = listOf(step("src/A.kt"), step()))
        val snapshot = PlanToSchedulerBridge.buildTaskSnapshot(doc, TaskState.PENDING)
        assertIs<Writes.Unknown>(snapshot.writes)
    }

    @Test
    fun `steps with no additional files still reserve target files`() {
        val doc = minimalDoc(steps = listOf(step(), step()))
        val snapshot = PlanToSchedulerBridge.buildTaskSnapshot(doc, TaskState.PENDING)
        val known = assertIs<Writes.Known>(snapshot.writes)
        assertEquals(listOf(WriteScope.FileScope("src/Foo.kt")), known.scopes)
    }

    @Test
    fun `no elaboration steps still reserves mandatory target files`() {
        val doc = minimalDoc(steps = emptyList())
        val snapshot = PlanToSchedulerBridge.buildTaskSnapshot(doc, TaskState.PENDING)
        assertIs<Writes.Known>(snapshot.writes)
        assertEquals(listOf(WriteScope.FileScope("src/Foo.kt")), (snapshot.writes as Writes.Known).scopes)
    }

    // ── prefix parsing ────────────────────────────────────────────────────

    @Test
    fun `file prefix produces FileScope`() {
        val scope = PlanToSchedulerBridge.parseWriteScope("file:src/Foo.kt")
        assertEquals(WriteScope.FileScope("src/Foo.kt"), scope)
    }

    @Test
    fun `dir prefix produces DirScope`() {
        val scope = PlanToSchedulerBridge.parseWriteScope("dir:src/bar")
        assertEquals(WriteScope.DirScope("src/bar"), scope)
    }

    @Test
    fun `resource prefix produces ResourceScope`() {
        val scope = PlanToSchedulerBridge.parseWriteScope("resource:db-connection")
        assertEquals(WriteScope.ResourceScope("db-connection"), scope)
    }

    @Test
    fun `unknown prefix throws IllegalArgumentException with raw string`() {
        val ex = assertThrows<IllegalArgumentException> {
            PlanToSchedulerBridge.parseWriteScope("unknown:something")
        }
        assert(ex.message!!.contains("unknown:something"))
    }

    // ── path normalisation ────────────────────────────────────────────────

    @Test
    fun `file path with dot-dot segments is normalised`() {
        val scope = PlanToSchedulerBridge.parseWriteScope("file:src/../src/Foo.kt")
        assertEquals(WriteScope.FileScope("src/Foo.kt"), scope)
    }

    @Test
    fun `file path with single dot segments is normalised`() {
        val scope = PlanToSchedulerBridge.parseWriteScope("file:src/./bar/Baz.kt")
        assertEquals(WriteScope.FileScope("src/bar/Baz.kt"), scope)
    }

    @Test
    fun `dir path with trailing slash is stripped`() {
        val scope = PlanToSchedulerBridge.parseWriteScope("dir:src/api/")
        assertEquals(WriteScope.DirScope("src/api"), scope)
    }

    @Test
    fun `resource id is not normalised`() {
        val scope = PlanToSchedulerBridge.parseWriteScope("resource:some/../opaque/id")
        assertEquals(WriteScope.ResourceScope("some/../opaque/id"), scope)
    }

    // ── root-dir rejection ────────────────────────────────────────────────────

    @Test
    fun `dir dot is rejected with an explicit error`() {
        // dir:. normalises to "" on all JVMs via Paths.get(".").normalize().
        // A DirScope("") would silently cover nothing; we must reject it explicitly.
        val ex = assertThrows<IllegalArgumentException> {
            PlanToSchedulerBridge.parseWriteScope("dir:.")
        }
        assert(ex.message!!.contains("repository root")) { "message should explain the root issue: ${ex.message}" }
    }

    @Test
    fun `dir path that dot-dots to root is rejected`() {
        val ex = assertThrows<IllegalArgumentException> {
            PlanToSchedulerBridge.parseWriteScope("dir:src/..")
        }
        assert(ex.message!!.contains("repository root")) { "message should explain the root issue: ${ex.message}" }
    }
}
