package io.agentdevkit.scheduler

data class SkippedTask(
    val id: String,
    val reason: SkipReason,
)
