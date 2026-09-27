package io.agentdevkit.planner

import java.io.File
import java.nio.file.Files

object ElaborationService {
    private val actionable = setOf("EXISTING_TARGET_MISSING", "TARGET_SYMBOL_NOT_FOUND", "TARGET_KIND_MISMATCH",
        "TARGET_PATH_INVALID", "TARGET_OUTSIDE_REPOSITORY", "SYMBOL_INVALID", "TARGET_AMBIGUOUS")

    fun elaborate(issue: File, root: File, profile: ReviewerConfiguration,
        tools: PlanningTools = UnavailablePlanningTools,
        agent: PlanningAgent = UnavailablePlanningAgent): Elaboration = boundary({ Elaboration(false, "Elaboration failed: ${it.message}") }) {
        val rootPath = root.canonicalFile.toPath()
        val issuePath = issue.canonicalFile.toPath()
        if (!issuePath.startsWith(rootPath) || Files.isSymbolicLink(issue.toPath()) || !issue.isFile || issue.length() > 262_144) {
            return@boundary Elaboration(false, "Issue must be a bounded repository file")
        }
        val original = issue.readText()
        if (PlanningDocumentReader.parse(original) !is DocumentRead.Valid) return@boundary Elaboration(false, "Issue is not a schema-v2 document")
        IssueElaborator.draftProblem(original)?.let { return@boundary Elaboration(false, it) }
        val context = MarkdownSections.scan(original).firstOrNull { it.level == 2 && it.heading == "Context" }?.body.orEmpty()
        val candidates = tools.freshCollection().candidates(root, context)
        val prompt = buildString {
            appendLine("Repository candidate evidence: ${candidates.status}. Candidates are hints, not mandatory edits.")
            candidates.candidates.take(5).forEach { appendLine("Candidate ${it.target.file}::${it.target.symbol.orEmpty()} parser-verified excerpt: ${it.excerpt.take(400)}") }
            candidates.issues.take(10).forEach { appendLine("Candidate limitation ${it.code}: ${it.message.take(300)}") }
            append(IssueElaborator.prompt(original))
        }
        if (prompt.toByteArray().size > 128_000) return@boundary Elaboration(false, "Issue exceeds the agent prompt budget")
        when (val result = agent.execute(profile, root, prompt)) {
            is AgentTurn.Completed -> {
                val proposed = IssueElaborator.inspect(result.text) as? DocumentRead.Valid
                    ?: return@boundary IssueElaborator.apply(issue, result.text, expectedText = original)
                val evidence = PlanningEvidenceCollector.collect(proposed.document, root, tools.freshCollection())
                val hasSource = evidence.steps.any { step -> step.targets.any { it.trust == "PARSER_VERIFIED" } || step.impacts.any { it.status == PlanningStatus.OK } }
                val needsCorrection = evidence.steps.any { step -> step.issues.any { it.code in actionable } }
                if (!hasSource && !needsCorrection) return@boundary IssueElaborator.apply(issue, result.text, expectedText = original)
                if (result.text.toByteArray(Charsets.UTF_8).size > 64_000) return@boundary Elaboration(false,
                    "Agent plan exceeds the evidence-refinement prompt budget; shorten its generated text before retrying")
                val refinement = buildRefinement(result.text, evidence)
                when (val updated = agent.execute(profile, root, refinement)) {
                    is AgentTurn.Completed -> IssueElaborator.apply(issue, updated.text, expectedText = original)
                    is AgentTurn.Failed -> Elaboration(false, "Evidence-informed refinement failed: ${updated.message}")
                }
            }
            is AgentTurn.Failed -> Elaboration(false, result.message)
        }
    }

    private fun buildRefinement(proposal: String, evidence: PlanningEvidence): String = buildString {
        appendLine("Refine this schema-v2 issue using verified source and actionable gaps. Preserve the original frontmatter and human-authored sections. Return only the complete revised Markdown document.")
        appendLine("Current complete proposal:\n$proposal")
        appendLine("Evidence status: ${evidence.status}; revision: ${evidence.repositoryRevision}")
        var remaining = minOf(36_000, 120_000 - toString().toByteArray(Charsets.UTF_8).size - 1_000)
        var omitted = false
        fun evidenceLine(value: String) {
            val line = value.take(1_500)
            val size = (line + "\n").toByteArray(Charsets.UTF_8).size
            if (size > remaining) { omitted = true; return }
            appendLine(line)
            remaining -= size
        }
        evidence.steps.take(100).forEach { step ->
            evidenceLine("Step ${step.stepId}:")
            step.issues.take(10).forEach { evidenceLine("  Gap ${it.code}: ${it.message.take(200)}") }
            step.targets.take(16).forEach { evidenceLine("  Source ${it.file}::${it.symbol.orEmpty()} trust=${it.trust}: ${it.excerpt.take(500)}") }
            step.verification.take(16).forEach { evidenceLine("  Test ${it.file}::${it.symbol.orEmpty()} trust=${it.trust}: ${it.excerpt.take(250)}") }
            step.impacts.take(16).forEach { evidenceLine("  Related symbols (${it.provider}, ${it.status}): ${it.symbols.take(30).joinToString()}") }
        }
        if (omitted) appendLine("Further evidence omitted due to the bounded refinement budget; the proposal above is complete")
    }
}
