package io.agentdevkit.planner

sealed interface PlanReadiness {
    data class Draft(val reasons: List<DocumentProblem>) : PlanReadiness
    data class NeedsEvidence(val reasons: List<DocumentProblem>) : PlanReadiness
    data object NeedsReview : PlanReadiness
    data class ChangesRequested(val reasons: List<DocumentProblem>) : PlanReadiness
    data object Ready : PlanReadiness
}

object ReadinessEvaluator {
    /** Pass only a current, snapshot-verified review. A verdict alone is not proof of currency. */
    fun evaluate(document: PlanningDocument, evidence: PlanningEvidence, currentReview: IssueReview?): PlanReadiness {
        val content = buildList {
            if (document.title.isBlank()) add(DocumentProblem("TITLE_MISSING", "title", 1, "Title is required"))
            if (document.kind == DocumentKind.ISSUE && document.targetFiles.isEmpty()) add(DocumentProblem("TARGET_FILES_MISSING", "target_files", 2, "Declare target_files before planning"))
            if (document.criteria.isEmpty()) add(DocumentProblem("CRITERIA_MISSING", "Acceptance criteria", 1, "Define an observable criterion"))
            if (document.steps.isEmpty()) add(DocumentProblem("STEPS_MISSING", "Changes", 1, "Describe at least one change"))
            for (step in document.steps) {
                if (step.acceptance.isEmpty()) add(DocumentProblem("CRITERIA_UNLINKED", step.id, 1, "Link a criterion"))
                if (step.targets.isEmpty()) add(DocumentProblem("TARGETS_UNKNOWN", step.id, 1, "Name targets"))
                if (step.verification.isEmpty() && step.verificationNotApplicable.isNullOrBlank()) add(DocumentProblem("VERIFICATION_MISSING", step.id, 1, "Link verification or explain non-applicability"))
            }
            document.criteria.filterNot { criterion -> document.steps.any { criterion.id in it.acceptance } }.forEach {
                add(DocumentProblem("CRITERION_UNUSED", it.id, 1, "Link criterion to a step"))
            }
            document.questions.filter { it.answer.isNullOrBlank() || it.answer.contains("replace this", ignoreCase = true) }.forEach {
                add(DocumentProblem("QUESTION_UNANSWERED", it.id, 1, "Answer the blocking question in this file"))
            }
        }
        if (content.isNotEmpty()) return PlanReadiness.Draft(content)
        val gaps = buildList {
            if (evidence.status != PlanningStatus.OK) add(DocumentProblem("EVIDENCE_INCOMPLETE", "evidence", 1, "Evidence is ${evidence.status}"))
            if (evidence.steps.map { it.stepId }.toSet() != document.steps.map { it.id }.toSet()) add(DocumentProblem("STEP_EVIDENCE_MISSING", "evidence", 1, "Collect evidence for every step"))
            for (step in evidence.steps) {
                if (step.issues.isNotEmpty() || step.targets.any { it.trust != "PARSER_VERIFIED" && it.trust != "PROPOSED" } ||
                    step.verification.any { it.trust != "PARSER_VERIFIED" && it.trust != "PROPOSED" } ||
                    step.impacts.any { it.status != PlanningStatus.OK || it.symbols.size < 2 }) {
                    add(DocumentProblem("STEP_EVIDENCE_INCOMPLETE", step.stepId, 1, "Verify targets, tests and impact"))
                }
                val authorStep = document.steps.firstOrNull { it.id == step.stepId }
                if (authorStep != null && authorStep.verification.isNotEmpty() && step.verification.size != authorStep.verification.size) {
                    add(DocumentProblem("TEST_EVIDENCE_MISSING", step.stepId, 1, "Verify each named test"))
                }
            }
        }
        if (gaps.isNotEmpty()) return PlanReadiness.NeedsEvidence(gaps)
        if (currentReview == null) return PlanReadiness.NeedsReview
        return if (currentReview.verdict != ReviewVerdict.ACCEPT || currentReview.findings.any { it.blocking } || currentReview.questions.isNotEmpty()) {
            PlanReadiness.ChangesRequested(currentReview.findings.filter { it.blocking }.map { DocumentProblem("REVIEW_FINDING", it.id, 1, it.detail) } +
                currentReview.questions.map { DocumentProblem("REVIEW_QUESTION", "Questions", 1, it.question) })
        } else PlanReadiness.Ready
    }
}
