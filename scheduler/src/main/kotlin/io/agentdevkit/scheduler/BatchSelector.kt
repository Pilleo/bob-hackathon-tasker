package io.agentdevkit.scheduler

object BatchSelector {

    fun select(input: SchedulerInput): ScheduleResult {
        val tasks = input.tasks
        val capacity = input.capacity

        // Phase 1: Validate capacity > 0
        if (capacity <= 0) {
            return ScheduleResult.InvalidSnapshot(listOf("capacity must be > 0 but was $capacity"))
        }

        // Phase 2: Check no duplicate task IDs
        val allIds = tasks.map { it.id }
        val duplicates = allIds.groupBy { it }.filter { it.value.size > 1 }.keys.sorted()
        if (duplicates.isNotEmpty()) {
            return ScheduleResult.InvalidSnapshot(
                listOf("duplicate task IDs: ${duplicates.joinToString(", ")}")
            )
        }

        val taskById = tasks.associateBy { it.id }

        // Phase 3: Check all dependency IDs reference known task IDs
        val unknownDeps = tasks.flatMap { task ->
            task.dependencyIds.filter { depId -> depId !in taskById }
        }.distinct().sorted()
        if (unknownDeps.isNotEmpty()) {
            return ScheduleResult.InvalidSnapshot(
                listOf("unknown dependency IDs: ${unknownDeps.joinToString(", ")}")
            )
        }

        // Phase 4: Detect cycles (Kahn's algorithm)
        val cycleError = detectCycle(tasks, taskById)
        if (cycleError != null) {
            return ScheduleResult.InvalidSnapshot(listOf(cycleError))
        }

        // Phase 5: Check running tasks have no conflicting write scopes with each other
        val runningTasks = tasks.filter { it.state == TaskState.RUNNING }
        val runningKnownTasks = runningTasks.filter { it.writes is Writes.Known }
        for (i in runningKnownTasks.indices) {
            for (j in i + 1 until runningKnownTasks.size) {
                val taskA = runningKnownTasks[i]
                val taskB = runningKnownTasks[j]
                val scopesA = (taskA.writes as Writes.Known).scopes
                val scopesB = (taskB.writes as Writes.Known).scopes
                for (scopeA in scopesA) {
                    for (scopeB in scopesB) {
                        if (scopeA.conflictsWith(scopeB)) {
                            return ScheduleResult.InvalidSnapshot(
                                listOf(
                                    "running tasks '${taskA.id}' and '${taskB.id}' have conflicting write scope: $scopeA"
                                )
                            )
                        }
                    }
                }
            }
        }

        // Phase 6: Build running reservation set; track running tasks with Unknown writes
        // Map from WriteScope -> taskId for conflict reporting
        val runningReservations = mutableMapOf<WriteScope, String>()
        var unknownRunningTaskId: String? = null
        for (task in runningTasks) {
            when (val w = task.writes) {
                is Writes.Known -> w.scopes.forEach { scope -> runningReservations[scope] = task.id }
                is Writes.Unknown -> if (unknownRunningTaskId == null) unknownRunningTaskId = task.id
            }
        }

        // Phase 7: Find eligible pending tasks (all deps SUCCEEDED)
        val pendingTasks = tasks.filter { it.state == TaskState.PENDING }

        // Phase 8: Sort eligible tasks by descending priority, ascending id
        val eligibleTasks = pendingTasks.filter { task ->
            task.dependencyIds.all { depId -> taskById[depId]?.state == TaskState.SUCCEEDED }
        }.sortedWith(compareByDescending<TaskSnapshot> { it.priority }.thenBy { it.id })

        val ineligibleTasks = pendingTasks.filter { task ->
            task.dependencyIds.any { depId -> taskById[depId]?.state != TaskState.SUCCEEDED }
        }

        // Phase 9: Greedy selection loop
        val selected = mutableListOf<String>()
        val selectedSkips = mutableMapOf<String, SkipReason>()
        // Track per-selected-task scopes for conflict reporting: scope -> taskId
        val selectedReservations = mutableMapOf<WriteScope, String>()

        for (task in eligibleTasks) {
            // If any RUNNING task has Unknown writes → skip
            if (unknownRunningTaskId != null) {
                selectedSkips[task.id] = SkipReason.UnknownRunningWrites(unknownRunningTaskId)
                continue
            }

            // If task's writes == Unknown → skip
            if (task.writes is Writes.Unknown) {
                selectedSkips[task.id] = SkipReason.UnknownWrites
                continue
            }

            // If selected count >= capacity → skip
            if (selected.size >= capacity) {
                selectedSkips[task.id] = SkipReason.CapacityExceeded
                continue
            }

            val scopes = (task.writes as Writes.Known).scopes

            // Check against running reservations
            val runningConflict = scopes.firstNotNullOfOrNull { scope ->
                runningReservations.entries.firstOrNull { (reserved, _) -> scope.conflictsWith(reserved) }
                    ?.let { (conflictingScope, conflictingTaskId) -> Pair(conflictingTaskId, conflictingScope) }
            }
            if (runningConflict != null) {
                selectedSkips[task.id] = SkipReason.RunningConflict(runningConflict.first, runningConflict.second)
                continue
            }

            // Check against already-selected tasks' writes
            val selectedConflict = scopes.firstNotNullOfOrNull { scope ->
                selectedReservations.entries.firstOrNull { (reserved, _) -> scope.conflictsWith(reserved) }
                    ?.let { (conflictingScope, conflictingTaskId) -> Pair(conflictingTaskId, conflictingScope) }
            }
            if (selectedConflict != null) {
                selectedSkips[task.id] = SkipReason.SelectedConflict(selectedConflict.first, selectedConflict.second)
                continue
            }

            // Affinity preemption guard: only fire when this is the LAST available slot
            // (selected.size == capacity - 1). This ensures affinity never displaces a task
            // that would have fit alongside the group member — no wasted capacity.
            if (input.affinityGroups.isNotEmpty() && selected.isNotEmpty() && selected.size == capacity - 1) {
                // Compute active groups: groups that contain at least one already-selected task
                val activeGroups = input.affinityGroups.filter { group ->
                    selected.any { selectedId ->
                        val selectedTask = taskById[selectedId]!!
                        taskBelongsToGroup(selectedTask, group)
                    }
                }

                if (activeGroups.isNotEmpty()) {
                    // Check if current candidate belongs to any active group
                    val candidateInGroup = activeGroups.any { group -> taskBelongsToGroup(task, group) }

                    if (!candidateInGroup) {
                        // Look for a waiting group member among remaining eligible tasks
                        val remainingEligible = eligibleTasks.drop(eligibleTasks.indexOf(task) + 1)
                            .filter { it.id !in selected && it.id !in selectedSkips }

                        val waitingGroupMember = remainingEligible.firstOrNull { remaining ->
                            activeGroups.any { group -> taskBelongsToGroup(remaining, group) } &&
                            remaining.writes is Writes.Known &&
                            (remaining.writes as Writes.Known).scopes.none { scope ->
                                (runningReservations.keys + selectedReservations.keys).any { reserved -> scope.conflictsWith(reserved) }
                            }
                        }

                        if (waitingGroupMember != null) {
                            val activeGroup = activeGroups.first { group -> taskBelongsToGroup(waitingGroupMember, group) }
                            selectedSkips[task.id] = SkipReason.AffinityPreempted(activeGroup.id, waitingGroupMember.id)
                            continue
                        }
                    }
                }
            }

            // Add to selected
            selected.add(task.id)
            scopes.forEach { scope -> selectedReservations[scope] = task.id }
        }

        // Phase 10: Collect skipped reasons for ALL pending tasks not selected
        val skipped = mutableListOf<SkippedTask>()

        // Ineligible tasks (deps not all SUCCEEDED)
        for (task in ineligibleTasks) {
            val unsatisfied = task.dependencyIds
                .mapNotNull { depId ->
                    val depState = taskById[depId]?.state
                    if (depState != TaskState.SUCCEEDED) Pair(depId, depState!!) else null
                }
            skipped.add(SkippedTask(task.id, SkipReason.DependencyNotSucceeded(unsatisfied)))
        }

        // Eligible but skipped tasks
        for (task in eligibleTasks) {
            if (task.id !in selected) {
                val reason = selectedSkips[task.id]
                    ?: SkipReason.CapacityExceeded // fallback (shouldn't happen)
                skipped.add(SkippedTask(task.id, reason))
            }
        }

        // Phase 11: Return Batch
        return ScheduleResult.Batch(selected = selected, skipped = skipped)
    }

    private fun taskBelongsToGroup(task: TaskSnapshot, group: AffinityGroup): Boolean {
        val scopes = (task.writes as? Writes.Known)?.scopes ?: return false
        return scopes.any { taskScope -> group.scopes.any { groupScope -> taskScope.conflictsWith(groupScope) } }
    }

    private fun detectCycle(tasks: List<TaskSnapshot>, @Suppress("UNUSED_PARAMETER") taskById: Map<String, TaskSnapshot>): String? {
        // Kahn's algorithm on the dependency DAG
        // Build adjacency: dep -> list of tasks that depend on it
        val dependents = mutableMapOf<String, MutableList<String>>()
        for (task in tasks) {
            for (depId in task.dependencyIds) {
                dependents.getOrPut(depId) { mutableListOf() }.add(task.id)
            }
        }

        // inDegree[t] = number of unprocessed dependencies of t
        val inDegree = tasks.associate { it.id to it.dependencyIds.size }.toMutableMap()
        val queue = ArrayDeque<String>()
        for (task in tasks) {
            if (task.dependencyIds.isEmpty()) queue.add(task.id)
        }

        var processed = 0
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            processed++
            for (dependent in dependents[current] ?: emptyList()) {
                val newDegree = (inDegree[dependent] ?: 0) - 1
                inDegree[dependent] = newDegree
                if (newDegree == 0) queue.add(dependent)
            }
        }

        return if (processed < tasks.size) "dependency graph contains a cycle" else null
    }
}
