package io.agentdevkit.scheduler

sealed class SkipReason {
    object UnknownWrites : SkipReason()
    data class DependencyNotSucceeded(val unsatisfied: List<Pair<String, TaskState>>) : SkipReason()
    data class RunningConflict(val taskId: String, val scope: WriteScope) : SkipReason()
    data class SelectedConflict(val taskId: String, val scope: WriteScope) : SkipReason()
    object CapacityExceeded : SkipReason()
    data class UnknownRunningWrites(val taskId: String) : SkipReason()
    data class AffinityPreempted(val groupId: String, val competingTaskId: String) : SkipReason()
}
