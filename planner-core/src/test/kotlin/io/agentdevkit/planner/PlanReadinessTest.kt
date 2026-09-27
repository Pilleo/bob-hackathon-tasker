package io.agentdevkit.planner

import org.junit.jupiter.api.Test
import kotlin.test.*

class PlanReadinessTest {
    private fun doc(answer: String? = "Resolved", tests: List<String> = listOf("T1")) =
        PlanningDocument(DocumentKind.ISSUE, "issue-1", "Improve", "author",
            listOf(Criterion("AC1", "Outcome", "Reject blank input")),
            listOf(PlanStep("S1", "Change", "Validate", listOf("AC1"), listOf(TargetRef("src/A.kt", "validate", TargetKind.EXISTING)), tests)),
            listOf(VerificationRef("T1", "Test", "Assert invalid result", TargetRef("src/ATest.kt", "rejects", TargetKind.EXISTING))),
            listOf(PlanQuestion("Q1", "What is blank?", "Changes validation semantics", listOf("S1"), QuestionResolution.DECISION, null, answer)), 5, emptyList(),
            targetFiles = listOf("src/A.kt"))
    private fun evidence(status: PlanningStatus = PlanningStatus.OK, trust: String = "PARSER_VERIFIED") = PlanningEvidence(status,
        emptyList(), emptyList(), emptyList(), "abc", steps = listOf(StepEvidence("S1",
            listOf(TargetEvidence("src/A.kt", "validate", trust)), listOf(TargetEvidence("src/ATest.kt", "rejects", trust)),
            listOf(ImpactEvidence(PlanningStatus.OK, listOf("validate", "caller"))), emptyList())))
    private val review = IssueReview(ReviewVerdict.ACCEPT, "Accepted", emptyList(), emptyList())

    @Test fun `ready requires complete links evidence question answer write scope and current acceptance`() {
        assertIs<PlanReadiness.Ready>(ReadinessEvaluator.evaluate(doc(), evidence(), review))
        assertIs<PlanReadiness.Draft>(ReadinessEvaluator.evaluate(doc(answer = null), evidence(), review))
        assertIs<PlanReadiness.Draft>(ReadinessEvaluator.evaluate(doc().copy(targetFiles = emptyList()), evidence(), review))
        assertIs<PlanReadiness.Draft>(ReadinessEvaluator.evaluate(doc(tests = emptyList()), evidence(), review))
        assertIs<PlanReadiness.NeedsEvidence>(ReadinessEvaluator.evaluate(doc(), evidence(PlanningStatus.PARTIAL), review))
        assertIs<PlanReadiness.NeedsEvidence>(ReadinessEvaluator.evaluate(doc(), evidence(trust = "UNAVAILABLE"), review))
        assertIs<PlanReadiness.NeedsReview>(ReadinessEvaluator.evaluate(doc(), evidence(), null))
        assertIs<PlanReadiness.ChangesRequested>(ReadinessEvaluator.evaluate(doc(), evidence(), review.copy(verdict = ReviewVerdict.CHANGES_REQUESTED)))
    }

    @Test fun `acceptance verdict cannot override missing verification evidence`() {
        val inadequate = evidence().copy(steps = listOf(evidence().steps.single().copy(verification = emptyList())))
        assertIs<PlanReadiness.NeedsEvidence>(ReadinessEvaluator.evaluate(doc(), inadequate, review))
    }
}
