package io.agentdevkit.planner

import java.io.File

data class IssueReviewOutcome(val artifact: StoredReview? = null, val errors: List<String> = emptyList(), val projection: ProjectionResult? = null)

object IssueReviewService {
    fun review(
        issue: File,
        root: File,
        reviewerName: String,
        configuration: PlannerReviewConfiguration?,
        tools: PlanningTools = UnavailablePlanningTools,
        dispatch: PlanningAgent = UnavailablePlanningAgent,
    ): IssueReviewOutcome = boundary({ IssueReviewOutcome(errors = listOf("Review failed: ${it.message}")) }) {
        val parsed = IssueDraftReader.read(issue, root)
        if (parsed.errors.isNotEmpty()) return@boundary IssueReviewOutcome(errors = parsed.errors)
        val draft = parsed.draft ?: return@boundary IssueReviewOutcome(errors = listOf("Missing issue draft"))
        val structured = PlanningDocumentReader.read(issue, root)
        val v2 = (structured as? DocumentRead.Valid)?.document
        val reviewer = configuration?.reviewers?.get(reviewerName)
            ?: return@boundary IssueReviewOutcome(errors = listOf("Reviewer '$reviewerName' is not configured in .agent-devkit.json"))
        val issueFingerprint = hash(draft.content.toByteArray(Charsets.UTF_8))
        val authorFingerprint = if (v2 != null) hash(ReviewProjection.authoredBytes(draft.content)) else null
        val blockFingerprint = if (v2 != null) ReviewProjection.blockHash(draft.content) else null
        val evidenceTools = tools.freshCollection()
        val evidence = if (v2 != null) PlanningEvidenceCollector.collect(v2, root, evidenceTools) else PlanningEvidenceCollector.collect(draft, root, evidenceTools)
        if (evidence.status in setOf(PlanningStatus.INVALID, PlanningStatus.FAILED)) return@boundary IssueReviewOutcome(errors = evidence.issues.map { it.message })
        val prompt = buildString {
            appendLine("Review this initial issue as a read-only independent reviewer. Inspect gaps, weak assumptions, missed dependencies, acceptance criteria, testing, and open questions. Treat evidence gaps as unknowns, not clean results.")
            appendLine(ReviewResponseContract.instructions)
            if (draft.content.length > 24_000) return@boundary IssueReviewOutcome(errors = listOf("Issue exceeds review prompt budget; shorten authored text before review"))
            appendLine("Issue:\n${draft.content}")
            appendLine("Evidence status: ${evidence.status}; revision: ${evidence.repositoryRevision}")
            var remaining = 60_000
            var clipped = false
            fun evidenceLine(value: String) {
                if (remaining <= 0) {
                    if (!clipped) appendLine("Further evidence omitted because the reviewer prompt budget was reached")
                    clipped = true
                    return
                }
                val bounded = value.take(minOf(remaining, 1_200))
                appendLine(bounded)
                remaining -= bounded.length + 1
                if (bounded.length < value.length) appendLine("Evidence excerpt abbreviated")
            }
            evidence.issues.forEach { evidenceLine("Evidence limitation ${it.code}: ${it.message.take(400)}") }
            if (v2 == null) {
                evidence.targets.forEach { evidenceLine("Target ${it.file} ${it.symbol.orEmpty()} trust=${it.trust}: ${it.excerpt.take(800)}") }
                evidence.impacts.forEach { (symbol, impact) -> evidenceLine("Impact $symbol (${impact.provider}, ${impact.status}): ${impact.symbols.take(50).joinToString()}") }
            } else evidence.steps.forEach { step ->
                evidenceLine("Step ${step.stepId}:")
                step.issues.forEach { evidenceLine("  Gap ${it.code}: ${it.message.take(400)}") }
                step.targets.forEach { evidenceLine("  Source ${it.file}::${it.symbol.orEmpty()} trust=${it.trust}: ${it.excerpt.take(800)}") }
                step.verification.forEach { evidenceLine("  Test ${it.file}::${it.symbol.orEmpty()} trust=${it.trust}: ${it.excerpt.take(800)}") }
                val authors = v2.steps.firstOrNull { it.id == step.stepId }?.targets?.filter { it.kind == TargetKind.EXISTING && it.symbol != null }.orEmpty()
                step.impacts.forEachIndexed { index, impact ->
                    val target = authors.getOrNull(index)
                    evidenceLine("  Related symbols ${target?.file.orEmpty()}::${target?.symbol.orEmpty()} (${impact.provider}, ${impact.status}): ${impact.symbols.take(50).joinToString()}")
                    impact.issues.forEach { evidenceLine("  Impact limitation ${it.code}: ${it.message.take(400)}") }
                }
            }
        }
        if (prompt.toByteArray(Charsets.UTF_8).size > 128_000) return@boundary IssueReviewOutcome(errors = listOf("Review prompt exceeds 128000 bytes"))
        val result = dispatch.execute(reviewer, root, prompt)
        if (result is AgentTurn.Failed) return@boundary IssueReviewOutcome(errors = listOf(result.message))
        val responseText = (result as AgentTurn.Completed).text
        var parsedReview = IssueReview.parse(responseText)
        if (parsedReview.review == null) {
            // Never spend a correction turn on a stale issue or changed evidence.
            val retryTools = tools.freshCollection()
            if (hash(issue.readBytes()) != issueFingerprint || !evidence.files.all { it.matches(root) } ||
                evidence.repositoryRevision != retryTools.revision(root)) {
                return@boundary IssueReviewOutcome(errors = listOf("Issue or evidence changed during review"))
            }
            val correction = buildString {
                appendLine(prompt)
                appendLine("Your previous response failed validation. Correct the response once using the contract above. Preserve genuine blockers; do not erase them to force ACCEPT.")
                appendLine("Validation errors: ${parsedReview.errors.joinToString().take(2_000)}")
                appendLine("Previous response:\n$responseText")
            }
            if (correction.toByteArray(Charsets.UTF_8).size > 128_000) return@boundary IssueReviewOutcome(
                errors = parsedReview.errors + "Review correction exceeds prompt budget; no retry was sent")
            when (val corrected = dispatch.execute(reviewer, root, correction)) {
                is AgentTurn.Failed -> return@boundary IssueReviewOutcome(errors = listOf(corrected.message))
                is AgentTurn.Completed -> parsedReview = IssueReview.parse(corrected.text)
            }
        }
        val review = parsedReview.review ?: return@boundary IssueReviewOutcome(errors = parsedReview.errors)
        val verificationTools = tools.freshCollection()
        if ((if (v2 == null) hash(issue.readBytes()) != issueFingerprint else hash(ReviewProjection.authoredBytes(issue.readText())) != authorFingerprint) || !evidence.files.all { it.matches(root) } ||
            evidence.repositoryRevision != verificationTools.revision(root)) {
            return@boundary IssueReviewOutcome(errors = listOf("Issue or evidence changed during review"))
        }
        val artifact = IssueReviewStore.save(root, issue, evidence, reviewerName, review,
            revision = verificationTools::revision, expectedIssueSha256 = issueFingerprint,
            schemaVersion = if (v2 == null) 1 else 2, expectedAuthorSha256 = authorFingerprint, expectedBlockSha256 = blockFingerprint)
        val projection = if (v2 == null) null else ReviewProjection.publish(issue, authorFingerprint!!, blockFingerprint, artifact)
        IssueReviewOutcome(artifact = artifact, errors = if (projection is ProjectionResult.Conflict) listOf(projection.reason)
            else if (projection is ProjectionResult.Failed) listOf(projection.reason) else emptyList(), projection = projection)
    }
}
