package io.agentdevkit.scheduler

data class TaskSnapshot(
    val id: String,
    val priority: Int,
    val state: TaskState,
    val dependencyIds: List<String>,
    val writes: Writes,
)
