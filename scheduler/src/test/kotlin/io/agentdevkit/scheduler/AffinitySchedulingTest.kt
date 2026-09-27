package io.agentdevkit.scheduler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AffinitySchedulingTest {

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun task(
        id: String,
        priority: Int,
        state: TaskState = TaskState.PENDING,
        deps: List<String> = emptyList(),
        writes: Writes = Writes.Known(emptyList()),
    ) = TaskSnapshot(id = id, priority = priority, state = state, dependencyIds = deps, writes = writes)

    private fun fileWrites(vararg paths: String) =
        Writes.Known(paths.map { WriteScope.FileScope(it) })

    private fun fileScope(path: String) = WriteScope.FileScope(path)

    private fun affinityGroup(id: String, vararg scopePaths: String) =
        AffinityGroup(id = id, scopes = scopePaths.map { WriteScope.FileScope(it) as WriteScope }.toSet())

    private fun input(vararg tasks: TaskSnapshot, capacity: Int = 2, groups: List<AffinityGroup> = emptyList()) =
        SchedulerInput(tasks = tasks.toList(), capacity = capacity, affinityGroups = groups)

    private fun batch(result: ScheduleResult): ScheduleResult.Batch {
        assertInstanceOf(ScheduleResult.Batch::class.java, result)
        return result as ScheduleResult.Batch
    }

    private fun skipReasonFor(result: ScheduleResult, taskId: String): SkipReason {
        val b = batch(result)
        return b.skipped.first { it.id == taskId }.reason
    }

    // ─── Test 1: Regression — no affinity groups ──────────────────────────────

    /**
     * Four-task acceptance example from SCHEDULER-BRIEF.md (no affinity groups).
     * Must produce identical output as before; no AffinityPreempted anywhere.
     */
    @Test
    fun `no affinity groups produces identical output to base behaviour`() {
        val result = BatchSelector.select(
            input(
                task("task-a", priority = 90, writes = fileWrites("src/main/Feature.kt")),
                task("task-b", priority = 80, writes = fileWrites("src/main/Feature.kt")),
                task("task-c", priority = 70, writes = fileWrites("src/test/FeatureTest.kt")),
                task("task-d", priority = 60, writes = fileWrites("src/test/FeatureTest.kt")),
                capacity = 2,
            )
        )
        val b = batch(result)
        assertEquals(listOf("task-a", "task-c"), b.selected)
        val skippedIds = b.skipped.map { it.id }.toSet()
        assertEquals(setOf("task-b", "task-d"), skippedIds)
        assertTrue(b.skipped.none { it.reason is SkipReason.AffinityPreempted }) {
            "Expected no AffinityPreempted reasons"
        }
    }

    // ─── Test 2: Basic preemption ─────────────────────────────────────────────

    /**
     * capacity=2:
     *   group-anchor    priority=95, writes=file:src/Validator.kt      (val-cache)
     *   high-unrelated  priority=80, writes=file:src/Feature.kt        (no group)
     *   group-member    priority=70, writes=file:src/ValidatorTest.kt  (val-cache)
     *
     * group-anchor selected first (highest priority). val-cache active.
     * high-unrelated (next by priority) is NOT in the group, but group-member is waiting
     * and conflict-free → high-unrelated preempted, group-member selected.
     */
    @Test
    fun `basic preemption - non-member preempted in favour of waiting group member`() {
        val group = affinityGroup("val-cache", "src/Validator.kt", "src/ValidatorTest.kt")
        val result = BatchSelector.select(
            input(
                task("group-anchor",   priority = 95, writes = fileWrites("src/Validator.kt")),
                task("high-unrelated", priority = 80, writes = fileWrites("src/Feature.kt")),
                task("group-member",   priority = 70, writes = fileWrites("src/ValidatorTest.kt")),
                capacity = 2,
                groups = listOf(group),
            )
        )
        val b = batch(result)
        assertEquals(listOf("group-anchor", "group-member"), b.selected)
        val reason = skipReasonFor(result, "high-unrelated")
        assertInstanceOf(SkipReason.AffinityPreempted::class.java, reason)
        val preempted = reason as SkipReason.AffinityPreempted
        assertEquals("val-cache", preempted.groupId)
        assertEquals("group-member", preempted.competingTaskId)
    }

    // ─── Test 3: No waiting group member → non-member accepted ────────────────

    /**
     * capacity=2:
     *   group-anchor  priority=90, writes=file:src/Validator.kt  (val-cache)
     *   unrelated     priority=80, writes=file:src/Other.kt       (no group)
     *   (no other group members present)
     *
     * No preemption because there is no waiting group member.
     */
    @Test
    fun `no waiting group member - non-member accepted normally`() {
        val group = affinityGroup("val-cache", "src/Validator.kt", "src/ValidatorTest.kt")
        val result = BatchSelector.select(
            input(
                task("group-anchor", priority = 90, writes = fileWrites("src/Validator.kt")),
                task("unrelated",    priority = 80, writes = fileWrites("src/Other.kt")),
                capacity = 2,
                groups = listOf(group),
            )
        )
        val b = batch(result)
        assertEquals(listOf("group-anchor", "unrelated"), b.selected)
        assertTrue(b.skipped.none { it.reason is SkipReason.AffinityPreempted }) {
            "Expected no AffinityPreempted reasons"
        }
    }

    // ─── Test 4: Group member that conflicts is still skipped with conflict reason

    /**
     * capacity=3:
     *   group-anchor              priority=90, writes=file:src/Validator.kt  (val-cache)
     *   high-unrelated            priority=85, writes=file:src/Feature.kt     (no group)
     *   group-member-conflicting  priority=70, writes=file:src/Validator.kt  (val-cache) ← conflicts with anchor
     *
     * high-unrelated should NOT be preempted because the only group member conflicts with anchor.
     * capacity=3 so group-member-conflicting reaches the conflict check (not CapacityExceeded).
     */
    @Test
    fun `conflicting group member does not trigger affinity preemption`() {
        val group = affinityGroup("val-cache", "src/Validator.kt")
        val result = BatchSelector.select(
            input(
                task("group-anchor",             priority = 90, writes = fileWrites("src/Validator.kt")),
                task("high-unrelated",           priority = 85, writes = fileWrites("src/Feature.kt")),
                task("group-member-conflicting", priority = 70, writes = fileWrites("src/Validator.kt")),
                capacity = 3,
                groups = listOf(group),
            )
        )
        val b = batch(result)
        // group-anchor selected; high-unrelated not preempted (no conflict-free group member)
        assertEquals(listOf("group-anchor", "high-unrelated"), b.selected)
        val conflictReason = skipReasonFor(result, "group-member-conflicting")
        assertInstanceOf(SkipReason.SelectedConflict::class.java, conflictReason)
        assertTrue(b.skipped.none { it.reason is SkipReason.AffinityPreempted }) {
            "Expected no AffinityPreempted reasons"
        }
    }

    // ─── Test 5: Affinity does not fire on the very first selected task ────────

    /**
     * capacity=2:
     *   unrelated-high  priority=100, writes=file:src/A.kt         (no group)
     *   group-anchor    priority=50,  writes=file:src/Validator.kt  (val-cache)
     *   group-member    priority=40,  writes=file:src/ValidatorTest.kt (val-cache)
     *
     * On first iteration no active groups → unrelated-high selected normally.
     * Then group-anchor selected (group member itself, capacity=2 → included).
     * group-member → CapacityExceeded.
     */
    @Test
    fun `affinity does not fire on first selected task`() {
        val group = affinityGroup("val-cache", "src/Validator.kt", "src/ValidatorTest.kt")
        val result = BatchSelector.select(
            input(
                task("unrelated-high", priority = 100, writes = fileWrites("src/A.kt")),
                task("group-anchor",   priority = 50,  writes = fileWrites("src/Validator.kt")),
                task("group-member",   priority = 40,  writes = fileWrites("src/ValidatorTest.kt")),
                capacity = 2,
                groups = listOf(group),
            )
        )
        val b = batch(result)
        assertEquals(listOf("unrelated-high", "group-anchor"), b.selected)
        val memberReason = skipReasonFor(result, "group-member")
        assertInstanceOf(SkipReason.CapacityExceeded::class.java, memberReason)
    }

    // ─── Test 6: All group members co-scheduled when capacity allows ───────────

    /**
     * capacity=3, all three tasks in the same group, no conflicts → all selected.
     */
    @Test
    fun `all group members co-scheduled when capacity allows`() {
        val group = affinityGroup("val-cache", "src/A.kt", "src/B.kt", "src/C.kt")
        val result = BatchSelector.select(
            input(
                task("member-a", priority = 90, writes = fileWrites("src/A.kt")),
                task("member-b", priority = 80, writes = fileWrites("src/B.kt")),
                task("member-c", priority = 70, writes = fileWrites("src/C.kt")),
                capacity = 3,
                groups = listOf(group),
            )
        )
        val b = batch(result)
        assertEquals(listOf("member-a", "member-b", "member-c"), b.selected)
        assertTrue(b.skipped.isEmpty())
    }

    // ─── Test 7: Two independent active groups both respected ─────────────────

    /**
     * capacity=3:
     *   group-a-anchor  priority=90, writes=file:src/A.kt   (cache-a)
     *   group-b-anchor  priority=85, writes=file:src/B.kt   (cache-b)
     *   high-unrelated  priority=80, writes=file:src/C.kt   (no group)
     *   group-a-member  priority=70, writes=file:src/A.kt   (cache-a) ← conflicts with anchor
     *   group-b-member  priority=60, writes=file:src/B2.kt  (cache-b)
     *
     * Step 1: group-a-anchor selected. cache-a active.
     * Step 2: group-b-anchor evaluated — not in cache-a; check waiting cache-a members:
     *   group-a-member writes src/A.kt → conflicts with selectedReservations → not eligible.
     *   No eligible cache-a member → group-b-anchor accepted. cache-b now also active.
     * Step 3: high-unrelated evaluated — not in cache-a or cache-b; check remaining:
     *   group-a-member conflicts → skip; group-b-member in cache-b, no conflict → preempted!
     * Step 4: group-a-member → SelectedConflict.
     * Step 5: group-b-member → selected (capacity=3).
     */
    @Test
    fun `two active groups both respected - non-member preempted`() {
        val groupA = affinityGroup("cache-a", "src/A.kt")
        val groupB = affinityGroup("cache-b", "src/B.kt", "src/B2.kt")
        val result = BatchSelector.select(
            input(
                task("group-a-anchor", priority = 90, writes = fileWrites("src/A.kt")),
                task("group-b-anchor", priority = 85, writes = fileWrites("src/B.kt")),
                task("high-unrelated", priority = 80, writes = fileWrites("src/C.kt")),
                task("group-a-member", priority = 70, writes = fileWrites("src/A.kt")),  // conflicts with anchor
                task("group-b-member", priority = 60, writes = fileWrites("src/B2.kt")),
                capacity = 3,
                groups = listOf(groupA, groupB),
            )
        )
        val b = batch(result)
        // Both anchors selected, then group-b-member fills the 3rd slot via affinity
        assertEquals(listOf("group-a-anchor", "group-b-anchor", "group-b-member"), b.selected)

        val preemptedReason = skipReasonFor(result, "high-unrelated")
        assertInstanceOf(SkipReason.AffinityPreempted::class.java, preemptedReason)
        val preempted = preemptedReason as SkipReason.AffinityPreempted
        assertEquals("cache-b", preempted.groupId)
        assertEquals("group-b-member", preempted.competingTaskId)

        val aMemberReason = skipReasonFor(result, "group-a-member")
        assertInstanceOf(SkipReason.SelectedConflict::class.java, aMemberReason)
    }

    // ─── Bug regression: affinity must not fire when capacity is not the limit ──

    /**
     * Three conflict-free tasks, one affinity group containing all three, capacity=3.
     * All three should be selected with no AffinityPreempted reason anywhere.
     * Previously the guard fired on the 2nd slot even though the 3rd task would fit,
     * causing only 2 tasks to be selected and the 3rd to be marked AffinityPreempted.
     */
    @Test
    fun `affinity does not preempt when all tasks fit within capacity`() {
        val group = affinityGroup("cache", "src/A.kt", "src/B.kt", "src/C.kt")
        val result = BatchSelector.select(
            input(
                task("anchor",   priority = 90, writes = fileWrites("src/A.kt")),
                task("unrelated", priority = 85, writes = fileWrites("src/B.kt")),
                task("member",   priority = 70, writes = fileWrites("src/C.kt")),
                capacity = 3,
                groups = listOf(group),
            )
        )
        val b = batch(result)
        assertEquals(listOf("anchor", "unrelated", "member"), b.selected,
            "All three conflict-free tasks must be selected; no capacity wasted by affinity")
        assertTrue(b.skipped.none { it.reason is SkipReason.AffinityPreempted }) {
            "AffinityPreempted must not appear when every task fits: ${b.skipped}"
        }
    }

    /**
     * With capacity=2, only the last slot triggers the preemption guard.
     * The first slot must be filled normally (no active groups yet anyway),
     * and the second slot should be filled by the group member, not the higher-priority
     * non-member — but only because it IS the last slot.
     */
    @Test
    fun `affinity only fires on the last slot`() {
        val group = affinityGroup("cache", "src/Validator.kt", "src/ValidatorTest.kt")
        val result = BatchSelector.select(
            input(
                task("anchor",    priority = 90, writes = fileWrites("src/Validator.kt")),
                task("unrelated", priority = 85, writes = fileWrites("src/Feature.kt")),
                task("member",    priority = 70, writes = fileWrites("src/ValidatorTest.kt")),
                capacity = 2,
                groups = listOf(group),
            )
        )
        val b = batch(result)
        assertEquals(listOf("anchor", "member"), b.selected)
        val reason = skipReasonFor(result, "unrelated")
        assertInstanceOf(SkipReason.AffinityPreempted::class.java, reason)
    }
}
