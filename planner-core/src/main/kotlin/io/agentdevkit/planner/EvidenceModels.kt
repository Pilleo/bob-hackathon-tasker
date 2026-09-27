package io.agentdevkit.planner

import kotlinx.serialization.Serializable

@Serializable
enum class PlanningStatus { OK, PARTIAL, UNAVAILABLE, INVALID, FAILED }

@Serializable
data class PlanningIssue(val code: String, val message: String)

@Serializable
data class SourceEvidence(val status: PlanningStatus, val trust: String, val excerpt: String = "",
    val issues: List<PlanningIssue> = emptyList(), val provider: String = "unknown")

@Serializable
data class ImpactEvidence(val status: PlanningStatus, val symbols: List<String> = emptyList(),
    val issues: List<PlanningIssue> = emptyList(), val provider: String = "unknown")

@Serializable
data class FileFingerprint(val path: String, val sha256: String)

@Serializable
data class TargetEvidence(val file: String, val symbol: String?, val trust: String, val excerpt: String = "")

@Serializable
data class PlanningEvidence(val status: PlanningStatus, val targets: List<TargetEvidence>,
    val files: List<FileFingerprint>, val issues: List<PlanningIssue>, val repositoryRevision: String?,
    val impacts: Map<String, ImpactEvidence> = emptyMap(), val steps: List<StepEvidence> = emptyList())
