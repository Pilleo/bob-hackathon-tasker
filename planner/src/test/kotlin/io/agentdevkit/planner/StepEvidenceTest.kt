package io.agentdevkit.planner

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class StepEvidenceTest {
    private fun document(steps: List<PlanStep>) = PlanningDocument(DocumentKind.ISSUE, "issue-1", "Improve", "author text",
        listOf(Criterion("AC1", "Behavior", "Outcome")), steps,
        listOf(VerificationRef("T1", "Regression", "Observation", TargetRef("src/test/kotlin/ValidatorTest.kt", "rejects", TargetKind.NEW))),
        emptyList(), 1, emptyList())
    private fun step(id: String, file: String, kind: TargetKind = TargetKind.EXISTING) = PlanStep(id, "Change", "Do work",
        listOf("AC1"), listOf(TargetRef(file, "validate", kind)), listOf("T1"))

    @Test fun `only exact file symbol pairs are inspected and shared lookups are cached`(@TempDir root: File) {
        val a = root.resolve("a/A.kt").apply { parentFile.mkdirs(); writeText("fun validate() = true") }
        val b = root.resolve("b/B.kt").apply { parentFile.mkdirs(); writeText("fun validate() = false") }
        val inspected = mutableListOf<TargetRef>()
        val tools = object : PlanningTools {
            override fun source(root: File, path: String, symbol: String?) = error("Legacy cross-product lookup must not run")
            override fun impact(root: File, symbol: String) = error("Unqualified impact lookup must not run")
            override fun source(root: File, target: TargetRef): SourceEvidence {
                inspected += target
                return SourceEvidence(PlanningStatus.OK, "PARSER_VERIFIED", target.file, provider = "ast-grep")
            }
            override fun impact(root: File, target: TargetRef) = ImpactEvidence(PlanningStatus.OK, listOf(target.symbol!!, "caller"), provider = "codanna")
            override fun revision(root: File) = "abcdef1"
        }
        val result = PlanningEvidenceCollector.collect(document(listOf(step("S1", "a/A.kt"), step("S2", "b/B.kt"), step("S3", "a/A.kt"))), root, tools)
        assertEquals(listOf("a/A.kt", "b/B.kt"), inspected.map { it.file })
        assertEquals(listOf("S1", "S2", "S3"), result.steps.map { it.stepId })
        assertEquals("a/A.kt", result.steps[2].targets.single().file)
        assertTrue(a.isFile && b.isFile)
    }

    @Test fun `proposed targets do not masquerade as failed existing lookups`(@TempDir root: File) {
        val result = PlanningEvidenceCollector.collect(document(listOf(step("S1", "src/New.kt", TargetKind.NEW))), root)
        assertEquals("PROPOSED", result.steps.single().targets.single().trust)
        assertFalse(result.steps.single().issues.any { it.code == "AST_FAILED" })
        assertTrue(result.files.all { it.matches(root) })
    }

    @Test fun `already existing file claimed as new is not proposed evidence`(@TempDir root: File) {
        root.resolve("src/New.kt").apply { parentFile.mkdirs(); writeText("fun validate() = true") }
        val evidence = PlanningEvidenceCollector.collect(document(listOf(step("S1", "src/New.kt", TargetKind.NEW))), root)
        assertEquals(PlanningStatus.PARTIAL, evidence.status)
        assertTrue(evidence.steps.single().issues.any { it.code == "TARGET_KIND_MISMATCH" })
    }

    @Test fun `missing providers remain explicit gaps attached to step`(@TempDir root: File) {
        root.resolve("src/A.kt").apply { parentFile.mkdirs(); writeText("fun validate() = true") }
        val result = PlanningEvidenceCollector.collect(document(listOf(step("S1", "src/A.kt"))), root)
        assertEquals(PlanningStatus.PARTIAL, result.status)
        assertTrue(result.steps.single().issues.any { it.code == "AST_UNAVAILABLE" })
        assertTrue(result.steps.single().issues.any { it.code == "IMPACT_UNQUALIFIED" })
        assertFalse(result.steps.single().verification.single().trust == "PARSER_VERIFIED")
    }
}
