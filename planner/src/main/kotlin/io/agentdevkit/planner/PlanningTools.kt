package io.agentdevkit.planner

import java.io.File

/** Ports owned by the planner. Host adapters supply tools; absence is explicit evidence. */
interface PlanningTools {
    fun source(root: File, path: String, symbol: String?): SourceEvidence
    fun impact(root: File, symbol: String): ImpactEvidence
    fun source(root: File, target: TargetRef): SourceEvidence = source(root, target.file, target.symbol)
    fun impact(root: File, target: TargetRef): ImpactEvidence = ImpactEvidence(PlanningStatus.UNAVAILABLE,
        issues = listOf(PlanningIssue("IMPACT_UNQUALIFIED", "No path-qualified impact provider for ${target.file}::${target.symbol}")))
    fun revision(root: File): String? = null
    /** Create a new bounded evidence pass; time spent in an agent turn must not consume its budget. */
    fun freshCollection(): PlanningTools = this
    fun candidates(root: File, description: String): CandidateSearch = CandidateSearch(PlanningStatus.UNAVAILABLE,
        issues = listOf(PlanningIssue("DISCOVERY_UNAVAILABLE", "No repository candidate search configured")))
}

data class PlanningCandidate(val target: TargetRef, val excerpt: String)

data class CandidateSearch(val status: PlanningStatus, val candidates: List<PlanningCandidate> = emptyList(),
    val issues: List<PlanningIssue> = emptyList())

object UnavailablePlanningTools : PlanningTools {
    override fun source(root: File, path: String, symbol: String?) = SourceEvidence(
        PlanningStatus.UNAVAILABLE, "UNAVAILABLE", issues = listOf(PlanningIssue("AST_UNAVAILABLE", "No AST provider configured")),
    )
    override fun impact(root: File, symbol: String) = ImpactEvidence(
        PlanningStatus.UNAVAILABLE, issues = listOf(PlanningIssue("CODANNA_UNAVAILABLE", "No impact provider configured")),
    )
}

/** Recover ordinary boundary failures without swallowing cancellation or JVM errors. */
internal inline fun <T> boundary(onFailure: (Exception) -> T, action: () -> T): T = try {
    action()
} catch (error: java.util.concurrent.CancellationException) {
    throw error
} catch (error: InterruptedException) {
    Thread.currentThread().interrupt()
    throw error
} catch (error: Exception) {
    onFailure(error)
}
