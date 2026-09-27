package io.agentdevkit.scheduler

sealed class ScheduleResult {
    data class InvalidSnapshot(val diagnostics: List<String>) : ScheduleResult()
    data class Batch(val selected: List<String>, val skipped: List<SkippedTask>) : ScheduleResult()
}
