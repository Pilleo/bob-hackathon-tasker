package io.agentdevkit.planner

import java.io.File
import java.nio.file.Files
import java.util.Collections

private fun <T> List<T>.frozen(): List<T> = Collections.unmodifiableList(ArrayList(this))

private fun PlanningDocument.frozen(): PlanningDocument = copy(
    criteria = criteria.frozen(),
    targetFiles = targetFiles.frozen(),
    steps = steps.map { step -> step.copy(
        acceptance = step.acceptance.frozen(),
        targets = step.targets.frozen(),
        verification = step.verification.frozen(),
    ) }.frozen(),
    verification = verification.frozen(),
    questions = questions.map { it.copy(blocks = it.blocks.frozen()) }.frozen(),
    dependencies = dependencies.frozen(),
)

/** Created only after repository evidence and a current persisted review have been checked. */
class VerifiedPlan internal constructor(
    document: PlanningDocument,
    private val issue: File,
    private val authorHash: String,
    private val fullHash: String,
    private val review: StoredReview,
    private val tools: PlanningTools,
) {
    val document: PlanningDocument = document.frozen()
    private val receiptHash = hash(review.file.readBytes())

    fun isCurrent(root: File): Boolean = boundary({ false }) {
        val verificationTools = tools.freshCollection()
        val current = IssueReviewView.inspect(root, issue, verificationTools::revision) as? IssueReviewView.Current
        issue.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) &&
            hash(issue.readBytes()) == fullHash &&
            hash(ReviewProjection.authoredBytes(issue.readText())) == authorHash &&
            review.isCurrent(root, verificationTools::revision) &&
            current?.artifact?.file?.canonicalFile == review.file.canonicalFile &&
            hash(current.artifact.file.readBytes()) == receiptHash
    }
}

sealed interface PlanVerification {
    data class Ready(val plan: VerifiedPlan) : PlanVerification
    data class Rejected(val reasons: List<String>) : PlanVerification

    companion object {
        fun verify(issue: File, root: File, tools: PlanningTools): PlanVerification = boundary({ Rejected(listOf(it.message.orEmpty())) }) {
            val target = issue.canonicalFile
            if (!target.toPath().startsWith(root.canonicalFile.toPath()) || !target.isFile ||
                Files.isSymbolicLink(issue.toPath()) || target.length() > 262_144) {
                return@boundary Rejected(listOf("Issue must be a bounded repository file"))
            }
            val originalBytes = target.readBytes()
            if (originalBytes.size > 262_144) return@boundary Rejected(listOf("Issue exceeds 256 KiB"))
            val fullHash = hash(originalBytes)
            val originalText = originalBytes.toString(Charsets.UTF_8)
            val originalAuthorHash = hash(ReviewProjection.authoredBytes(originalText))
            val parsed = PlanningDocumentReader.parse(originalText)
            if (parsed !is DocumentRead.Valid) return@boundary Rejected(
                (parsed as? DocumentRead.Invalid)?.problems?.map { it.message } ?: listOf("A schema-v2 issue is required"))
            val evidence = PlanningEvidenceCollector.collect(parsed.document, root, tools.freshCollection())
            val viewTools = tools.freshCollection()
            val view = IssueReviewView.inspect(root, issue, viewTools::revision)
            val review = (view as? IssueReviewView.Current)?.artifact
            if (hash(target.readBytes()) != fullHash) return@boundary Rejected(listOf("Issue changed while verifying the plan"))
            if (review != null && review.authorSha256 != originalAuthorHash) return@boundary Rejected(listOf("Review refers to another issue snapshot"))
            val readiness = ReadinessEvaluator.evaluate(parsed.document, evidence, review?.review)
            if (readiness !is PlanReadiness.Ready || review?.schemaVersion != 2) {
                return@boundary Rejected(when (readiness) {
                    is PlanReadiness.Draft -> readiness.reasons.map { it.message }
                    is PlanReadiness.NeedsEvidence -> readiness.reasons.map { it.message }
                    is PlanReadiness.ChangesRequested -> readiness.reasons.map { it.message }
                    PlanReadiness.NeedsReview -> listOf("A current review is required")
                    PlanReadiness.Ready -> listOf("A schema-v2 review is required")
                })
            }
            val plan = VerifiedPlan(parsed.document, issue, originalAuthorHash, fullHash, review, tools)
            if (plan.isCurrent(root)) Ready(plan) else Rejected(listOf("Issue, evidence or review changed while verifying"))
        }
    }
}
