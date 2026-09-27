package io.agentdevkit.planner

import kotlinx.serialization.Serializable

@Serializable
enum class ReadOnlyLaunch { UNQUALIFIED, BUBBLEWRAP }

@Serializable
data class ReviewerConfiguration(
    val command: List<String>,
    val readOnly: ReadOnlyLaunch = ReadOnlyLaunch.UNQUALIFIED,
    val timeoutMs: Long = 120_000,
    val maxOutputBytes: Int = 256_000,
    val model: String? = null,
)

@Serializable
data class PlannerReviewConfiguration(
    val defaultReviewer: String = "antigravity",
    val reviewers: Map<String, ReviewerConfiguration> = emptyMap(),
)
