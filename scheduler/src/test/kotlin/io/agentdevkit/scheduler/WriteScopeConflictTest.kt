package io.agentdevkit.scheduler

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WriteScopeConflictTest {

    // ─── File vs File ──────────────────────────────────────────────────────────

    @Test
    fun `file vs file - same path conflicts`() {
        val a = WriteScope.FileScope("src/Foo.kt")
        val b = WriteScope.FileScope("src/Foo.kt")
        assertTrue(a.conflictsWith(b))
    }

    @Test
    fun `file vs file - different path does not conflict`() {
        val a = WriteScope.FileScope("src/Foo.kt")
        val b = WriteScope.FileScope("src/Bar.kt")
        assertFalse(a.conflictsWith(b))
    }

    // ─── Dir vs File ───────────────────────────────────────────────────────────

    @Test
    fun `dir vs file - direct child conflicts`() {
        val d = WriteScope.DirScope("src/api")
        val f = WriteScope.FileScope("src/api/A.kt")
        assertTrue(d.conflictsWith(f))
    }

    @Test
    fun `dir vs file - nested descendant conflicts`() {
        val d = WriteScope.DirScope("src/api")
        val f = WriteScope.FileScope("src/api/sub/A.kt")
        assertTrue(d.conflictsWith(f))
    }

    @Test
    fun `dir vs file - same-string-prefix but different segment does not conflict`() {
        val d = WriteScope.DirScope("src/api")
        val f = WriteScope.FileScope("src/apiculture/B.kt")
        assertFalse(d.conflictsWith(f))
    }

    @Test
    fun `dir vs file - similar prefix with digit suffix does not conflict`() {
        val d = WriteScope.DirScope("src/api")
        val f = WriteScope.FileScope("src/api2/B.kt")
        assertFalse(d.conflictsWith(f))
    }

    @Test
    fun `dir vs file - file path equals dir path conflicts (exact match)`() {
        // edge case: file at exactly the dir path
        val d = WriteScope.DirScope("src/api")
        val f = WriteScope.FileScope("src/api")
        assertTrue(d.conflictsWith(f))
    }

    @Test
    fun `dir vs file - unrelated path does not conflict`() {
        val d = WriteScope.DirScope("src/api")
        val f = WriteScope.FileScope("src/other/B.kt")
        assertFalse(d.conflictsWith(f))
    }

    // ─── Dir vs Dir ────────────────────────────────────────────────────────────

    @Test
    fun `dir vs dir - same path conflicts`() {
        val a = WriteScope.DirScope("src/api")
        val b = WriteScope.DirScope("src/api")
        assertTrue(a.conflictsWith(b))
    }

    @Test
    fun `dir vs dir - one is parent of other conflicts`() {
        val parent = WriteScope.DirScope("src")
        val child = WriteScope.DirScope("src/api")
        assertTrue(parent.conflictsWith(child))
    }

    @Test
    fun `dir vs dir - child is prefix of parent conflicts (reversed)`() {
        val child = WriteScope.DirScope("src/api")
        val parent = WriteScope.DirScope("src")
        assertTrue(child.conflictsWith(parent))
    }

    @Test
    fun `dir vs dir - sibling dirs do not conflict`() {
        val a = WriteScope.DirScope("src/api")
        val b = WriteScope.DirScope("src/service")
        assertFalse(a.conflictsWith(b))
    }

    @Test
    fun `dir vs dir - string-prefix but not segment boundary does not conflict`() {
        val a = WriteScope.DirScope("src/api")
        val b = WriteScope.DirScope("src/apiculture")
        assertFalse(a.conflictsWith(b))
    }

    // ─── Resource vs Resource ──────────────────────────────────────────────────

    @Test
    fun `resource vs resource - same id conflicts`() {
        val a = WriteScope.ResourceScope("db://users")
        val b = WriteScope.ResourceScope("db://users")
        assertTrue(a.conflictsWith(b))
    }

    @Test
    fun `resource vs resource - different id does not conflict`() {
        val a = WriteScope.ResourceScope("db://users")
        val b = WriteScope.ResourceScope("db://orders")
        assertFalse(a.conflictsWith(b))
    }

    // ─── Cross-type (no conflict) ──────────────────────────────────────────────

    @Test
    fun `file vs resource - no conflict`() {
        val f = WriteScope.FileScope("src/Foo.kt")
        val r = WriteScope.ResourceScope("db://foo")
        assertFalse(f.conflictsWith(r))
    }

    @Test
    fun `dir vs resource - no conflict`() {
        val d = WriteScope.DirScope("src/api")
        val r = WriteScope.ResourceScope("db://foo")
        assertFalse(d.conflictsWith(r))
    }

    // ─── Symmetry ──────────────────────────────────────────────────────────────

    @Test
    fun `symmetry - file vs file same path`() {
        val a = WriteScope.FileScope("src/Foo.kt")
        val b = WriteScope.FileScope("src/Foo.kt")
        assertTrue(a.conflictsWith(b) == b.conflictsWith(a))
    }

    @Test
    fun `symmetry - file vs file different path`() {
        val a = WriteScope.FileScope("src/Foo.kt")
        val b = WriteScope.FileScope("src/Bar.kt")
        assertTrue(a.conflictsWith(b) == b.conflictsWith(a))
    }

    @Test
    fun `symmetry - dir vs file conflicts`() {
        val d = WriteScope.DirScope("src/api")
        val f = WriteScope.FileScope("src/api/A.kt")
        assertTrue(d.conflictsWith(f) == f.conflictsWith(d))
    }

    @Test
    fun `symmetry - dir vs file no conflict (segment boundary)`() {
        val d = WriteScope.DirScope("src/api")
        val f = WriteScope.FileScope("src/apiculture/B.kt")
        assertTrue(d.conflictsWith(f) == f.conflictsWith(d))
    }

    @Test
    fun `symmetry - dir vs dir parent-child`() {
        val parent = WriteScope.DirScope("src")
        val child = WriteScope.DirScope("src/api")
        assertTrue(parent.conflictsWith(child) == child.conflictsWith(parent))
    }

    @Test
    fun `symmetry - resource vs resource same id`() {
        val a = WriteScope.ResourceScope("db://users")
        val b = WriteScope.ResourceScope("db://users")
        assertTrue(a.conflictsWith(b) == b.conflictsWith(a))
    }

    @Test
    fun `symmetry - file vs resource`() {
        val f = WriteScope.FileScope("src/Foo.kt")
        val r = WriteScope.ResourceScope("db://foo")
        assertTrue(f.conflictsWith(r) == r.conflictsWith(f))
    }

    @Test
    fun `symmetry - dir vs resource`() {
        val d = WriteScope.DirScope("src/api")
        val r = WriteScope.ResourceScope("db://foo")
        assertTrue(d.conflictsWith(r) == r.conflictsWith(d))
    }
}
