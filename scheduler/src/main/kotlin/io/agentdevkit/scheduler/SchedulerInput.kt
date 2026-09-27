package io.agentdevkit.scheduler

data class SchedulerInput(
    val tasks: List<TaskSnapshot>,
    val capacity: Int,
    val affinityGroups: List<AffinityGroup> = emptyList(),
)
