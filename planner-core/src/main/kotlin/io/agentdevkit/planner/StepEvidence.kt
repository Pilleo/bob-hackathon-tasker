package io.agentdevkit.planner

import kotlinx.serialization.Serializable

@Serializable
data class StepEvidence(val stepId: String, val targets: List<TargetEvidence>,
    val verification: List<TargetEvidence>, val impacts: List<ImpactEvidence>,
    val issues: List<PlanningIssue>)
