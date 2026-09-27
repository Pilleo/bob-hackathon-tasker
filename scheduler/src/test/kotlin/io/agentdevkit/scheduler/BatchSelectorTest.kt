package io.agentdevkit.scheduler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BatchSelectorTest {

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun task(
        id: String,
        priority: Int,
        state: TaskState,
        deps: List<String> = emptyList(),
        writes: Writes = Writes.Known(emptyList()),
    ) = TaskSnapshot(id = id, priority = priority, state = state, dependencyIds = deps, writes = writes)

    private fun fileWrites(vararg paths: String) =
        Writes.Known(paths.map { WriteScope.FileScope(it) })

    private fun dirWrites(vararg paths: String) =
        Writes.Known(paths.map { WriteScope.DirScope(it) })

    private fun resourceWrites(vararg ids: String) =
        Writes.Known(ids.map { WriteScope.ResourceScope(it) })

    private fun input(vararg tasks: TaskSnapshot, capacity: Int = 2) =
        SchedulerInput(tasks = tasks.toList(), capacity = capacity)

    private fun assertBatch(result: ScheduleResult, selected: List<String>, skippedIds: Set<String> = emptySet()) {
        assertInstanceOf(ScheduleResult.Batch::class.java, result)
        val batch = result as ScheduleResult.Batch
        assertEquals(selected, batch.selected, "selected list mismatch")
        assertEquals(skippedIds, batch.skipped.map { it.id }.toSet(), "skipped ids mismatch")
    }

    private fun assertInvalid(result: ScheduleResult, messageContains: String? = null) {
        assertInstanceOf(ScheduleResult.InvalidSnapshot::class.java, result)
        if (messageContains != null) {
            val diag = (result as ScheduleResult.InvalidSnapshot).diagnostics.joinToString()
            assertTrue(diag.contains(messageContains, ignoreCase = true),
                "Expected diagnostics to contain '$messageContains' but was: $diag")
        }
    }

    private fun skipReason(result: ScheduleResult, taskId: String): SkipReason {
        val batch = result as ScheduleResult.Batch
        return batch.skipped.first { it.id == taskId }.reason
    }

    // ─── Literal four-task acceptance example ─────────────────────────────────

    @Test
    fun `four-task acceptance example`() {
        // capacity 2
        // validation/100/pending/none/file:src/Validator.kt
        // message/90/pending/none/file:src/Validator.kt  (conflicts with validation)
        // docs/80/pending/none/file:docs/validation.md
        // tests/70/pending/[validation]/file:src/ValidatorTest.kt
        val inp = input(
            task("validation", 100, TaskState.PENDING, writes = fileWrites("src/Validator.kt")),
            task("message",    90,  TaskState.PENDING, writes = fileWrites("src/Validator.kt")),
            task("docs",       80,  TaskState.PENDING, writes = fileWrites("docs/validation.md")),
            task("tests",      70,  TaskState.PENDING, deps = listOf("validation"),
                writes = fileWrites("src/ValidatorTest.kt")),
            capacity = 2,
        )

        val result = BatchSelector.select(inp)
        assertInstanceOf(ScheduleResult.Batch::class.java, result)
        val batch = result as ScheduleResult.Batch

        // selected must be [validation, docs] in that order
        assertEquals(listOf("validation", "docs"), batch.selected)

        // message → SelectedConflict with validation
        val messageReason = skipReason(result, "message")
        assertInstanceOf(SkipReason.SelectedConflict::class.java, messageReason)
        val messageConflict = messageReason as SkipReason.SelectedConflict
        assertEquals("validation", messageConflict.taskId)
        assertEquals(WriteScope.FileScope("src/Validator.kt"), messageConflict.scope)

        // tests → DependencyNotSucceeded
        val testsReason = skipReason(result, "tests")
        assertInstanceOf(SkipReason.DependencyNotSucceeded::class.java, testsReason)
        val testsDep = testsReason as SkipReason.DependencyNotSucceeded
        assertEquals(listOf(Pair("validation", TaskState.PENDING)), testsDep.unsatisfied)
    }

    // ─── Priority ordering and deterministic ID tie-break ─────────────────────

    @Test
    fun `higher priority task is selected first`() {
        val result = BatchSelector.select(input(
            task("low",  10, TaskState.PENDING, writes = fileWrites("a.kt")),
            task("high", 99, TaskState.PENDING, writes = fileWrites("b.kt")),
            capacity = 1,
        ))
        val batch = result as ScheduleResult.Batch
        assertEquals(listOf("high"), batch.selected)
        val skipReason = batch.skipped.first { it.id == "low" }.reason
        assertInstanceOf(SkipReason.CapacityExceeded::class.java, skipReason)
    }

    @Test
    fun `same priority tasks are ordered by ascending id`() {
        val result = BatchSelector.select(input(
            task("beta",  50, TaskState.PENDING, writes = fileWrites("b.kt")),
            task("alpha", 50, TaskState.PENDING, writes = fileWrites("a.kt")),
            capacity = 1,
        ))
        val batch = result as ScheduleResult.Batch
        assertEquals(listOf("alpha"), batch.selected)
    }

    // ─── Unknown writes on pending ─────────────────────────────────────────────

    @Test
    fun `pending task with Unknown writes is skipped with UnknownWrites`() {
        val result = BatchSelector.select(input(
            task("unknown-task", 50, TaskState.PENDING, writes = Writes.Unknown),
            capacity = 2,
        ))
        val batch = result as ScheduleResult.Batch
        assertTrue(batch.selected.isEmpty())
        val reason = batch.skipped.first { it.id == "unknown-task" }.reason
        assertInstanceOf(SkipReason.UnknownWrites::class.java, reason)
    }

    // ─── Known-empty writes is allowed ────────────────────────────────────────

    @Test
    fun `task with Known empty writes can be selected`() {
        val result = BatchSelector.select(input(
            task("no-writes", 50, TaskState.PENDING, writes = Writes.Known(emptyList())),
            capacity = 1,
        ))
        val batch = result as ScheduleResult.Batch
        assertEquals(listOf("no-writes"), batch.selected)
    }

    // ─── Running task reservations ────────────────────────────────────────────

    @Test
    fun `running task with Known writes blocks conflicting pending task`() {
        val result = BatchSelector.select(input(
            task("runner",  50, TaskState.RUNNING,  writes = fileWrites("src/Foo.kt")),
            task("blocker", 50, TaskState.PENDING,  writes = fileWrites("src/Foo.kt")),
            capacity = 2,
        ))
        val batch = result as ScheduleResult.Batch
        assertTrue(batch.selected.isEmpty())
        val reason = batch.skipped.first { it.id == "blocker" }.reason
        assertInstanceOf(SkipReason.RunningConflict::class.java, reason)
        val rc = reason as SkipReason.RunningConflict
        assertEquals("runner", rc.taskId)
        assertEquals(WriteScope.FileScope("src/Foo.kt"), rc.scope)
    }

    @Test
    fun `running task does not block non-conflicting pending task`() {
        val result = BatchSelector.select(input(
            task("runner", 50, TaskState.RUNNING, writes = fileWrites("src/Foo.kt")),
            task("ok",     50, TaskState.PENDING, writes = fileWrites("src/Bar.kt")),
            capacity = 2,
        ))
        val batch = result as ScheduleResult.Batch
        assertEquals(listOf("ok"), batch.selected)
    }

    // ─── Unknown writes on RUNNING task ───────────────────────────────────────

    @Test
    fun `running task with Unknown writes blocks ALL new selections`() {
        val result = BatchSelector.select(input(
            task("runner",  50, TaskState.RUNNING, writes = Writes.Unknown),
            task("pending", 50, TaskState.PENDING, writes = fileWrites("src/Bar.kt")),
            capacity = 2,
        ))
        val batch = result as ScheduleResult.Batch
        assertTrue(batch.selected.isEmpty())
        val reason = batch.skipped.first { it.id == "pending" }.reason
        assertInstanceOf(SkipReason.UnknownRunningWrites::class.java, reason)
        val urw = reason as SkipReason.UnknownRunningWrites
        assertEquals("runner", urw.taskId)
    }

    // ─── Dependency resolution ────────────────────────────────────────────────

    @Test
    fun `task with SUCCEEDED dependency is eligible`() {
        val result = BatchSelector.select(input(
            task("dep",  50, TaskState.SUCCEEDED),
            task("task", 50, TaskState.PENDING, deps = listOf("dep"), writes = fileWrites("a.kt")),
            capacity = 2,
        ))
        val batch = result as ScheduleResult.Batch
        assertEquals(listOf("task"), batch.selected)
    }

    @Test
    fun `task with PENDING dependency is not eligible`() {
        val result = BatchSelector.select(input(
            task("dep",  50, TaskState.PENDING,  writes = fileWrites("x.kt")),
            task("task", 50, TaskState.PENDING,  deps = listOf("dep"), writes = fileWrites("a.kt")),
            capacity = 2,
        ))
        val batch = result as ScheduleResult.Batch
        val reason = skipReason(result, "task")
        assertInstanceOf(SkipReason.DependencyNotSucceeded::class.java, reason)
        val dns = reason as SkipReason.DependencyNotSucceeded
        assertEquals(listOf(Pair("dep", TaskState.PENDING)), dns.unsatisfied)
    }

    @Test
    fun `task with FAILED dependency is not eligible`() {
        val result = BatchSelector.select(input(
            task("dep",  50, TaskState.FAILED),
            task("task", 50, TaskState.PENDING, deps = listOf("dep"), writes = fileWrites("a.kt")),
            capacity = 2,
        ))
        val reason = skipReason(result, "task") as SkipReason.DependencyNotSucceeded
        assertEquals(listOf(Pair("dep", TaskState.FAILED)), reason.unsatisfied)
    }

    @Test
    fun `task with BLOCKED dependency is not eligible`() {
        val result = BatchSelector.select(input(
            task("dep",  50, TaskState.BLOCKED),
            task("task", 50, TaskState.PENDING, deps = listOf("dep"), writes = fileWrites("a.kt")),
            capacity = 2,
        ))
        val reason = skipReason(result, "task") as SkipReason.DependencyNotSucceeded
        assertEquals(listOf(Pair("dep", TaskState.BLOCKED)), reason.unsatisfied)
    }

    @Test
    fun `dependency selected in this batch does NOT release its dependent`() {
        // "second" depends on "first"; both are PENDING
        // "first" is selected in this batch, but "second" must still get DependencyNotSucceeded
        val result = BatchSelector.select(input(
            task("first",  100, TaskState.PENDING, writes = fileWrites("a.kt")),
            task("second", 90,  TaskState.PENDING, deps = listOf("first"), writes = fileWrites("b.kt")),
            capacity = 2,
        ))
        val batch = result as ScheduleResult.Batch
        assertEquals(listOf("first"), batch.selected)
        val reason = skipReason(result, "second")
        assertInstanceOf(SkipReason.DependencyNotSucceeded::class.java, reason)
    }

    // ─── DependencyNotSucceeded reports ALL unsatisfied deps ──────────────────

    @Test
    fun `DependencyNotSucceeded reports all unsatisfied deps when multiple exist`() {
        val result = BatchSelector.select(input(
            task("depA", 50, TaskState.FAILED),
            task("depB", 50, TaskState.BLOCKED),
            task("depC", 50, TaskState.SUCCEEDED),
            task("task", 50, TaskState.PENDING,
                deps = listOf("depA", "depB", "depC"), writes = fileWrites("a.kt")),
            capacity = 4,
        ))
        val reason = skipReason(result, "task") as SkipReason.DependencyNotSucceeded
        // depC is SUCCEEDED so it should NOT appear; depA and depB should appear
        val unsatisfiedIds = reason.unsatisfied.map { it.first }.toSet()
        assertEquals(setOf("depA", "depB"), unsatisfiedIds)
        val states = reason.unsatisfied.associate { it.first to it.second }
        assertEquals(TaskState.FAILED, states["depA"])
        assertEquals(TaskState.BLOCKED, states["depB"])
    }

    // ─── Capacity exceeded ─────────────────────────────────────────────────────

    @Test
    fun `tasks beyond capacity get CapacityExceeded reason`() {
        val result = BatchSelector.select(input(
            task("t1", 100, TaskState.PENDING, writes = fileWrites("a.kt")),
            task("t2", 90,  TaskState.PENDING, writes = fileWrites("b.kt")),
            task("t3", 80,  TaskState.PENDING, writes = fileWrites("c.kt")),
            capacity = 2,
        ))
        val batch = result as ScheduleResult.Batch
        assertEquals(listOf("t1", "t2"), batch.selected)
        val reason = batch.skipped.first { it.id == "t3" }.reason
        assertInstanceOf(SkipReason.CapacityExceeded::class.java, reason)
    }

    // ─── Invalid: capacity <= 0 ────────────────────────────────────────────────

    @Test
    fun `capacity of 0 returns InvalidSnapshot`() {
        val result = BatchSelector.select(input(capacity = 0))
        assertInvalid(result, "capacity")
    }

    @Test
    fun `negative capacity returns InvalidSnapshot`() {
        val result = BatchSelector.select(input(capacity = -5))
        assertInvalid(result, "capacity")
    }

    // ─── Invalid: duplicate IDs ────────────────────────────────────────────────

    @Test
    fun `duplicate task IDs returns InvalidSnapshot`() {
        val result = BatchSelector.select(input(
            task("dup", 50, TaskState.PENDING),
            task("dup", 60, TaskState.PENDING),
            capacity = 2,
        ))
        assertInvalid(result, "dup")
    }

    // ─── Invalid: unknown dependency reference ─────────────────────────────────

    @Test
    fun `unknown dependency ID returns InvalidSnapshot`() {
        val result = BatchSelector.select(input(
            task("task", 50, TaskState.PENDING, deps = listOf("does-not-exist")),
            capacity = 2,
        ))
        assertInvalid(result, "does-not-exist")
    }

    // ─── Invalid: cycle in dependency graph ────────────────────────────────────

    @Test
    fun `direct cycle returns InvalidSnapshot`() {
        val result = BatchSelector.select(input(
            task("A", 50, TaskState.PENDING, deps = listOf("B")),
            task("B", 50, TaskState.PENDING, deps = listOf("A")),
            capacity = 2,
        ))
        assertInvalid(result, "cycle")
    }

    @Test
    fun `indirect cycle returns InvalidSnapshot`() {
        val result = BatchSelector.select(input(
            task("A", 50, TaskState.PENDING, deps = listOf("C")),
            task("B", 50, TaskState.PENDING, deps = listOf("A")),
            task("C", 50, TaskState.PENDING, deps = listOf("B")),
            capacity = 3,
        ))
        assertInvalid(result, "cycle")
    }

    // ─── Invalid: two RUNNING tasks with conflicting Known writes ─────────────

    @Test
    fun `two running tasks with conflicting Known writes returns InvalidSnapshot`() {
        val result = BatchSelector.select(input(
            task("runnerA", 50, TaskState.RUNNING, writes = fileWrites("src/Foo.kt")),
            task("runnerB", 50, TaskState.RUNNING, writes = fileWrites("src/Foo.kt")),
            capacity = 2,
        ))
        assertInvalid(result)
    }

    @Test
    fun `two running tasks with dir-file conflict returns InvalidSnapshot`() {
        val result = BatchSelector.select(input(
            task("runnerA", 50, TaskState.RUNNING, writes = dirWrites("src/api")),
            task("runnerB", 50, TaskState.RUNNING, writes = fileWrites("src/api/A.kt")),
            capacity = 2,
        ))
        assertInvalid(result)
    }

    // ─── Input collections are not mutated ────────────────────────────────────

    @Test
    fun `input task list is not mutated`() {
        val taskList = mutableListOf(
            task("t1", 50, TaskState.PENDING, writes = fileWrites("a.kt")),
            task("t2", 40, TaskState.PENDING, writes = fileWrites("b.kt")),
        )
        val originalCopy = taskList.toList()
        BatchSelector.select(SchedulerInput(tasks = taskList, capacity = 2))
        assertEquals(originalCopy, taskList)
    }

    // ─── Selected conflict between two pending tasks ───────────────────────────

    @Test
    fun `second pending task conflicting with first selected gets SelectedConflict`() {
        val result = BatchSelector.select(input(
            task("first",  100, TaskState.PENDING, writes = fileWrites("src/Foo.kt")),
            task("second",  90, TaskState.PENDING, writes = fileWrites("src/Foo.kt")),
            capacity = 2,
        ))
        val batch = result as ScheduleResult.Batch
        assertEquals(listOf("first"), batch.selected)
        val reason = skipReason(result, "second")
        assertInstanceOf(SkipReason.SelectedConflict::class.java, reason)
        val sc = reason as SkipReason.SelectedConflict
        assertEquals("first", sc.taskId)
    }

    // ─── Dir-based conflict detection ─────────────────────────────────────────

    @Test
    fun `pending task with dir write conflicts with running file write in same dir`() {
        val result = BatchSelector.select(input(
            task("runner",  50, TaskState.RUNNING, writes = fileWrites("src/api/A.kt")),
            task("pending", 50, TaskState.PENDING, writes = dirWrites("src/api")),
            capacity = 2,
        ))
        val batch = result as ScheduleResult.Batch
        assertTrue(batch.selected.isEmpty())
        val reason = batch.skipped.first { it.id == "pending" }.reason
        assertInstanceOf(SkipReason.RunningConflict::class.java, reason)
    }

    // ─── Empty input ──────────────────────────────────────────────────────────

    @Test
    fun `empty task list returns empty batch`() {
        val result = BatchSelector.select(SchedulerInput(tasks = emptyList(), capacity = 2))
        val batch = result as ScheduleResult.Batch
        assertTrue(batch.selected.isEmpty())
        assertTrue(batch.skipped.isEmpty())
    }

    // ─── Non-pending states are not selected ──────────────────────────────────

    @Test
    fun `SUCCEEDED tasks are not selected or skipped`() {
        val result = BatchSelector.select(input(
            task("done", 100, TaskState.SUCCEEDED),
            capacity = 2,
        ))
        val batch = result as ScheduleResult.Batch
        assertTrue(batch.selected.isEmpty())
        assertTrue(batch.skipped.isEmpty())
    }

    @Test
    fun `FAILED tasks are not selected or skipped`() {
        val result = BatchSelector.select(input(
            task("failed", 100, TaskState.FAILED),
            capacity = 2,
        ))
        val batch = result as ScheduleResult.Batch
        assertTrue(batch.selected.isEmpty())
        assertTrue(batch.skipped.isEmpty())
    }

    @Test
    fun `BLOCKED tasks are not selected or skipped`() {
        val result = BatchSelector.select(input(
            task("blocked", 100, TaskState.BLOCKED),
            capacity = 2,
        ))
        val batch = result as ScheduleResult.Batch
        assertTrue(batch.selected.isEmpty())
        assertTrue(batch.skipped.isEmpty())
    }
}
