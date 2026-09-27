package io.agentdevkit.planner

sealed interface DocumentRead {
    data class Valid(val document: PlanningDocument) : DocumentRead
    data class Legacy(val draft: IssueDraft) : DocumentRead
    data class Invalid(val problems: List<DocumentProblem>) : DocumentRead
}

data class DocumentProblem(val code: String, val path: String, val line: Int, val message: String)
enum class DocumentKind { ISSUE, EXECUTION_PLAN }
enum class TargetKind { EXISTING, NEW }
data class TargetRef(val file: String, val symbol: String?, val kind: TargetKind)
data class Criterion(val id: String, val title: String, val body: String)
data class PlanStep(val id: String, val title: String, val body: String,
    val acceptance: List<String>, val targets: List<TargetRef>, val verification: List<String>,
    val verificationNotApplicable: String? = null)
data class VerificationRef(val id: String, val title: String, val body: String, val target: TargetRef?)
enum class QuestionResolution { INVESTIGATION, EXPERIMENT, DECISION }
data class PlanQuestion(val id: String, val question: String, val why: String,
    val blocks: List<String>, val resolveThrough: QuestionResolution,
    val investigation: String?, val answer: String?)
data class PlanningDocument(val kind: DocumentKind, val id: String, val title: String,
    val authoredText: String, val criteria: List<Criterion>, val steps: List<PlanStep>,
    val verification: List<VerificationRef>, val questions: List<PlanQuestion>,
    val priority: Int, val dependencies: List<String>, val baseRevision: String? = null,
    val targetFiles: List<String> = emptyList(), val status: String = "open")
