# Scheduler Plan

## Overview

Implement a `:scheduler` Kotlin module that, given a snapshot of tasks, proposes the next
compatible batch for parallel execution. The core rule: no two tasks in a proposed batch may
claim writes to the same file, directory, or resource. The module is pure and has no I/O,
no project dependencies, and no agent involvement.

Wire the module into `planner-app` via a thin bridge that translates `VerifiedPlan` values
into scheduler task snapshots and add a `schedule` CLI subcommand for demo and interactive use.

Scope is defined by [`docs/SCHEDULER-BRIEF.md`](docs/SCHEDULER-BRIEF.md).

Sub-Tasks 1–5 are complete. Sub-Tasks 6–7 extend the scheduler with cache-aware scheduling.

---

## Sub-Task 1 — Create the `:scheduler` module scaffold

**Status:** [x] done

### Intent
Set up the empty Gradle module so it can be compiled, tested, and boundary-checked as part of
the root `check` task, before writing any scheduler logic.

### Expected Outcomes
- `./gradlew :scheduler:check` passes (no sources = no failures).
- `./gradlew checkModuleBoundaries` passes with `scheduler` having no project dependencies
  and `planner-app` allowed to depend on `scheduler`.
- `./gradlew check` still passes.

### Todo List
1. Create directory tree `scheduler/src/main/kotlin/io/agentdevkit/scheduler/` and
   `scheduler/src/test/kotlin/io/agentdevkit/scheduler/`.
2. Write `scheduler/build.gradle.kts` — mirror `planner-core/build.gradle.kts` exactly
   (Kotlin + serialization plugins, JDK 25 toolchain, JVM 21 target, JUnit 5).
   Do **not** add any project dependencies.
3. Add `":scheduler"` to `settings.gradle.kts` include list.
4. In root `build.gradle.kts`:
   - Add `"scheduler" to emptySet<String>()` in the `allowed` map inside `checkModuleBoundaries`.
   - Add `":planner-app" to setOf(":planner", ":planner-core", ":agent-acp", ":scheduler")`.
   - Add `":scheduler:check"` to the `check` task's `dependsOn`.

### Relevant Context
- [`settings.gradle.kts`](settings.gradle.kts) — add `:scheduler` to the `include` call.
- [`build.gradle.kts`](build.gradle.kts) — `allowed` map (line 12) and `check` dependsOn (line 35).
- [`planner-core/build.gradle.kts`](planner-core/build.gradle.kts) — copy as template.

---

## Sub-Task 2 — Define the scheduler domain model

**Status:** [x] done

### Intent
Define immutable Kotlin data types for all scheduler inputs and outputs. This is the public
API of the `:scheduler` module. Nothing else in the module depends on planner types.

### Expected Outcomes
- `TaskSnapshot` and related types compile and are fully immutable.
- `WriteScope` sealed hierarchy covers `FileScope`, `DirScope`, `ResourceScope`.
- `Writes` sealed type distinguishes `Known(scopes: List<WriteScope>)` (possibly empty) from
  `Unknown` — the empty known case represents a task with no writes.
- `TaskState` enum covers `PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED`, `BLOCKED`.
- `SchedulerInput` holds a list of `TaskSnapshot` and a positive `capacity: Int`.
- `ScheduleResult` sealed type: `InvalidSnapshot(diagnostics)` or
  `Batch(selected: List<String>, skipped: List<SkippedTask>)`.
- `SkippedTask` carries a task ID and a `SkipReason` (sealed: `UnknownWrites`,
  `DependencyNotSucceeded(unsatisfied: List<Pair<String, TaskState>>)` — all unsatisfied deps,
  `RunningConflict(taskId, scope)`, `SelectedConflict(taskId, scope)`, `CapacityExceeded`).
- All types are in package `io.agentdevkit.scheduler`.

### Todo List
1. Create `TaskSnapshot.kt` with `TaskSnapshot(id, priority, state, dependencyIds, writes)`.
2. Create `WriteScope.kt` sealed hierarchy: `FileScope(path)`, `DirScope(path)`, `ResourceScope(id)`.
3. Create `Writes.kt` sealed type: `Known(scopes)` and `Unknown`.
4. Create `SchedulerInput.kt` with `TaskSnapshot` list and `capacity`.
5. Create `ScheduleResult.kt` sealed type with `InvalidSnapshot` and `Batch`.
6. Create `SkipReason.kt` sealed hierarchy for every reason variant.

### Relevant Context
- `docs/SCHEDULER-BRIEF.md` §Input contract, §Output contract — authoritative type spec.
- `planner-core/src/main/kotlin/io/agentdevkit/planner/PlanningDocument.kt` — `PlanStep.writes`
  is `List<String>?`; null means unknown. The bridge (Sub-Task 4) converts these strings.

---

## Sub-Task 3 — Implement `BatchSelector` with conflict detection

**Status:** [x] done

### Intent
Implement the pure selection algorithm and all supporting logic: write-scope conflict
checking (file, directory, resource), dependency resolution, greedy priority ordering,
and snapshot validation. This is the core of the scheduler.

### Expected Outcomes
- `BatchSelector.select(input: SchedulerInput): ScheduleResult` is implemented.
- Snapshot validation rejects: non-positive capacity, duplicate IDs, unknown dependency IDs,
  cycles in the dependency graph, conflicting running-task write reservations.
- Selection: sorts eligible pending tasks (all deps `SUCCEEDED`) by descending priority then
  ascending ID; greedily adds tasks whose writes don't conflict with running or already-selected.
- `Unknown` writes on a pending task → `SkipReason.UnknownWrites`.
- `Unknown` writes on a running task → all new selections blocked (explicit reason).
- Directory conflict rule: `dir:src/api` conflicts with `file:src/api/A.kt` (segment boundary),
  but not with `file:src/apiculture/B.kt`.
- Every unselected pending task gets a `SkippedTask` entry with the correct reason.
- All tests from the compact TDD case list in `SCHEDULER-BRIEF.md` pass.

### Todo List
1. Implement `WriteScope.conflictsWith(other: WriteScope): Boolean` — handle all six cross-type
   cases plus `dir`/`file` path-segment boundary rule.
2. Implement `BatchSelector` with these phases:
   a. Validate capacity > 0.
   b. Check no duplicate task IDs.
   c. Check all dependency IDs reference known tasks.
   d. Detect cycles (DFS or Kahn's algorithm).
   e. Check running tasks have no conflicting write scopes with each other.
   f. Build running write reservation set; if any running task has `Unknown` writes, track that.
   g. Sort pending-eligible tasks (all deps `SUCCEEDED`) by (-priority, id).
   h. Greedily select: skip if unknown writes, skip if write conflict with reserved or already
      selected; otherwise add to batch and accumulate its writes into the reserved set.
   i. For all unselected pending tasks, assign the first applicable `SkipReason`.
3. Write `BatchSelectorTest` covering every case in the compact TDD list, including the
   literal four-task acceptance example from the brief.
4. Add a dedicated `WriteScopeConflictTest` for file-identity, dir/descendant,
   sibling-prefix, and resource cases.

### Relevant Context
- `docs/SCHEDULER-BRIEF.md` §Selection rules — all seven rules, in order.
- `docs/SCHEDULER-BRIEF.md` §Literal acceptance example — must pass as a test.
- `docs/SCHEDULER-BRIEF.md` §Compact TDD case list — enumerate these as test cases.
- Sub-Task 2 types are the only dependency.

---

## Sub-Task 4 — Bridge: `VerifiedPlan` → scheduler task snapshot (in `planner-app`)

**Status:** [x] done

### Intent
Write the translation layer in `planner-app` that converts a `VerifiedPlan` into a
`TaskSnapshot` the scheduler understands. This is the only place that knows about both
worlds; the scheduler never imports planner types.

### Expected Outcomes
- `PlanToSchedulerBridge.kt` lives in `planner-app` and takes a `VerifiedPlan` plus a
  caller-supplied `Map<String, TaskState>` (current execution states of this and all
  referenced plans).
- _Note: bridge integration with the CLI is deferred; the `schedule` command operates on
  JSON fixtures only for now._
- It maps: `document.id` → `id`, `document.priority` → `priority`,
  `document.dependencies` → `dependencyIds`.
- It unions all `step.writes` across steps to build the task-level `Writes`:
  - If any step has `null` writes → `Writes.Unknown`.
  - Otherwise → `Writes.Known(allScopes)` where each string is parsed into a `WriteScope`
    (prefix `file:`, `dir:`, `resource:`).
- Write-scope strings are normalised (resolve `.` and `..`, no trailing slash) at this boundary.
- The bridge validates `plan.isCurrent(root)` before translating; rejects stale plans.

### Todo List
1. Add `":scheduler"` to `planner-app/build.gradle.kts` dependencies.
2. Create `PlanToSchedulerBridge.kt` in `planner-app`.
3. Implement `WriteScope` string parsing (`file:`, `dir:`, `resource:` prefixes).
4. Implement the `writes` union logic (null-any → Unknown, otherwise Known).
5. Write a unit test with a minimal `PlanningDocument` fixture to verify translation.

### Relevant Context
- [`planner/src/main/kotlin/io/agentdevkit/planner/PlanVerification.kt`](planner/src/main/kotlin/io/agentdevkit/planner/PlanVerification.kt) —
  `VerifiedPlan.document` is the frozen `PlanningDocument`.
- [`planner-core/src/main/kotlin/io/agentdevkit/planner/PlanningDocument.kt`](planner-core/src/main/kotlin/io/agentdevkit/planner/PlanningDocument.kt) —
  `PlanStep.writes: List<String>?`, `PlanningDocument.priority: Int`, `.dependencies: List<String>`.
- `ARCHITECTURE.md` — normalise filesystem aliases at the application boundary.

---

## Sub-Task 5 — Add `schedule` CLI subcommand

**Status:** [x] done

### Intent
Expose the scheduler through a thin CLI in `planner-app` so the feature can be demonstrated
interactively. The command accepts either a JSON task-snapshot fixture (for isolated demos,
labelled as simulated) or a set of verified plan files from the repository.

### Expected Outcomes
- `./gradlew :planner-app:run --args='schedule --capacity 2 --fixture examples/four-tasks.json'`
  prints the result as **JSON** to stdout.
- The four-task acceptance example from `SCHEDULER-BRIEF.md` is included as
  `examples/four-tasks.json`.
- Output JSON shape: `{ "selected": [...], "skipped": [{ "id": ..., "reason": { "type": ..., ... } }], "fixture": true }`.
  The `"fixture": true` field labels simulated input explicitly.
- The subcommand is listed in the help/error message alongside existing commands.

### Todo List
1. Create `ScheduleCommand.kt` in `planner-app` that:
   a. Parses `--capacity N` (default 2) and `--fixture <path>` flags.
   b. Reads the JSON fixture as a list of plain task snapshots (id, priority, state,
      dependencyIds, writes string list or null).
   c. Calls `BatchSelector.select()`.
   d. Serialises the result to JSON and prints it to stdout (use kotlinx.serialization).
2. Register `schedule` in `PlannerMain.kt` dispatch table.
3. Create `examples/four-tasks.json` with the literal four-task example.
4. Add a comment/label in the output when the input is a fixture (not a verified plan).

### Relevant Context
- [`planner-app/src/main/kotlin/io/agentdevkit/planner/app/PlannerMain.kt`](planner-app/src/main/kotlin/io/agentdevkit/planner/app/PlannerMain.kt) —
  existing dispatch pattern in the `when` block (lines 19–56).
- `docs/SCHEDULER-BRIEF.md` §Verification and demo — demo requirements.
- Sub-Task 3 `BatchSelector` and Sub-Task 2 types are the inputs.

---

## Sub-Task 6 — Cache-affinity scheduling in `BatchSelector`

**Status:** [x] done

### Intent
Allow the caller to declare *affinity groups* — named sets of write scopes where tasks that
share a group benefit from warm caches and should be co-scheduled ahead of unrelated tasks.
The scheduler uses these hints to boost the effective priority of tasks that share a group
with the currently-selected set, so cache-hot work is batched together rather than
interleaved with unrelated high-priority tasks.

An affinity group wins the last slot over a higher-priority task that shares no group with
already-selected tasks. A task displaced this way receives `SkipReason.AffinityPreempted`
instead of `CapacityExceeded`.

### Design

**New type: `AffinityGroup`**
```
data class AffinityGroup(val id: String, val scopes: Set<WriteScope>)
```
- `id` is an opaque string used in skip reasons and JSON output.
- `scopes` is the set of write scopes that define membership; a task is a member of a group
  if any of its write scopes intersect (via `conflictsWith`) any scope in the group.

**`SchedulerInput` gains an optional field:**
```
val affinityGroups: List<AffinityGroup> = emptyList()
```

**New `SkipReason` variant:**
```
data class AffinityPreempted(val groupId: String, val competingTaskId: String) : SkipReason()
```
`groupId` — the affinity group that caused the preemption.
`competingTaskId` — the lower-priority task that was preferred over this one.

**Modified selection algorithm (Phase 9 extension):**
After the normal conflict checks pass for a candidate task, and before accepting it into the
batch, check whether the remaining capacity slot should be reserved for an affinity-group
member instead:

1. After at least one task is already selected, compute the *active groups*: all affinity
   groups that contain at least one already-selected task (membership = any write scope
   intersects the group's scopes).
2. If active groups are non-empty AND the current candidate does not belong to any active
   group, scan the remaining eligible tasks (lower priority / higher id) for any task that
   *does* belong to an active group and would not conflict with the current reservation set.
3. If such a group-member task exists → skip the current candidate with
   `SkipReason.AffinityPreempted(groupId, competingTaskId)` (use the first/lowest-priority
   group-member found, since it will be selected in a later greedy step anyway).
4. Otherwise (no group-member is waiting or all group-members are blocked) → accept the
   candidate normally.

This means affinity only overrides priority when there is actually a waiting group member
that can fill the slot; it does not waste capacity.

### Expected Outcomes
- `AffinityGroup` type exists in the `:scheduler` module.
- `SchedulerInput.affinityGroups` defaults to empty (fully backward-compatible).
- A task displaced by affinity gets `SkipReason.AffinityPreempted(groupId, competingTaskId)`.
- When no affinity groups are provided, behaviour is identical to today (no regression).
- A task that belongs to an active group is never preempted by affinity (it is the group member).
- Affinity only fires when capacity is the limiting factor (not when the candidate would be
  selected anyway).
- All existing tests continue to pass unchanged.

### Todo List
1. Add `AffinityGroup(id: String, scopes: Set<WriteScope>)` data class to the `:scheduler`
   module.
2. Add `affinityGroups: List<AffinityGroup> = emptyList()` to `SchedulerInput`.
3. Add `AffinityPreempted(groupId: String, competingTaskId: String)` to `SkipReason`.
4. Extend `BatchSelector.select()` Phase 9 with the affinity preemption check described above.
   Keep the existing greedy logic intact; the affinity check is a new guard before accepting.
5. Write `AffinitySchedulingTest` in the `:scheduler` test sources covering:
   - No affinity groups → identical output to current behaviour (regression guard).
   - Single active group: group member preempts a higher-priority non-member for the last slot;
     preempted task gets `AffinityPreempted(groupId, competingTaskId)`.
   - Multiple tasks in the same group: all co-scheduled when capacity allows.
   - Group member that would conflict is still skipped with the conflict reason (affinity does
     not override conflict rules).
   - No waiting group member available → non-member accepted normally (no wasted capacity).
   - Affinity does not fire on the first selected task (no active group yet).
   - Two active groups: both respected independently.
6. Run `./gradlew :scheduler:check` — all tests green.

### Relevant Context
- [`scheduler/src/main/kotlin/io/agentdevkit/scheduler/BatchSelector.kt`](scheduler/src/main/kotlin/io/agentdevkit/scheduler/BatchSelector.kt)
  — Phase 9 greedy loop is where the new guard inserts.
- [`scheduler/src/main/kotlin/io/agentdevkit/scheduler/SchedulerInput.kt`](scheduler/src/main/kotlin/io/agentdevkit/scheduler/SchedulerInput.kt)
  — add `affinityGroups` field with default.
- [`scheduler/src/main/kotlin/io/agentdevkit/scheduler/SkipReason.kt`](scheduler/src/main/kotlin/io/agentdevkit/scheduler/SkipReason.kt)
  — add `AffinityPreempted`.

---

## Sub-Task 7 — Expose affinity groups in CLI and fixture format

**Status:** [x] done

### Intent
Let the `schedule` CLI accept affinity group declarations in the fixture JSON so the
cache-aware scheduling can be demonstrated end-to-end without code changes.

### Design

Extend the fixture format with an optional top-level `affinityGroups` key:
```json
{
  "capacity": 2,
  "affinityGroups": [
    { "id": "validator-cache", "scopes": ["file:src/Validator.kt", "file:src/ValidatorTest.kt"] }
  ],
  "tasks": [ ... ]
}
```
The existing flat-array format (used by `examples/four-tasks.json`) remains valid — it is
treated as `{ "tasks": [...], "affinityGroups": [] }`.

Add `examples/cache-affinity.json` to demonstrate the feature: a scenario where a
lower-priority task that shares a cache group with an already-selected task is preferred over
a higher-priority task that touches unrelated files.

Extend `ScheduleCommand` serialisation to emit `AffinityPreempted` reasons in JSON output.

### Expected Outcomes
- Old flat-array fixture still works unchanged.
- New object-fixture format with `affinityGroups` is accepted.
- `examples/cache-affinity.json` runs and shows affinity preemption in the output.
- `AffinityPreempted` is serialised as
  `{ "type": "AffinityPreempted", "groupId": "...", "competingTaskId": "..." }`.
- `./gradlew check` passes.

### Todo List
1. Update `ScheduleCommand` fixture parsing to accept both the flat array format and the new
   object format `{ tasks, capacity?, affinityGroups? }`.
2. Add `AffinityPreempted` case to `serialiseReason()` in `ScheduleCommand.kt`.
3. Create `examples/cache-affinity.json` with a scenario that demonstrates preemption.
4. Run `./gradlew :planner-app:run --args='schedule --fixture examples/cache-affinity.json'`
   and verify `AffinityPreempted` appears in the output.
5. Run `./gradlew check`.

### Relevant Context
- [`planner-app/src/main/kotlin/io/agentdevkit/planner/app/ScheduleCommand.kt`](planner-app/src/main/kotlin/io/agentdevkit/planner/app/ScheduleCommand.kt)
  — `parseFixtures()` and `serialiseReason()` need updating.
- `examples/four-tasks.json` — must still work after the parser change.

---

## Notes for Implementation

- The `:scheduler` module must have **zero project dependencies** — all scheduler types are
  self-contained. Only `planner-app` imports scheduler.
- `Unknown` writes on a **running** task is a special case: it blocks all new selections
  (not just tasks that would otherwise conflict) and must surface a distinct reason.
- The directory conflict rule operates on **path segments**, not string prefixes:
  `dir:src/api` vs `file:src/apiculture/B.kt` must **not** conflict.
- All tests should be pure JUnit 5 with no I/O, no agents, no filesystem access.
- `BatchSelector` must be deterministic: identical inputs always produce identical outputs.
- `SkipReason.DependencyNotSucceeded` carries **all** unsatisfied dependency IDs and their
  states, not just the first one.
- CLI output is JSON only; no human-readable prose mode required for this scope.
- The bridge (Sub-Task 4) is implemented but not yet wired to the CLI; the `schedule` command
  accepts JSON fixtures only.
- **Known debt (Bug 4):** `PlanToSchedulerBridge.translate` exists but `ScheduleCommand` never
  calls it. To wire real plan files: add a `--plans <file>...` flag to `ScheduleCommand` that
  calls `PlanVerification.verify` for each path, rejects stale plans (`isCurrent == false`),
  and uses `PlanToSchedulerBridge.translate` to build `TaskSnapshot` values. The caller must
  supply task execution states separately (e.g. a `--states <json>` flag). The fixture path
  and exit-code contract (0/1/4) already support this extension without breaking changes.
- **Exit code contract:** `InvalidSnapshot` → exit 1; usable `Batch` → exit 0; parse/I/O
  errors → exit 4. Automation must check the exit code before consuming the JSON output.
- **Affinity preemption only fires on the last available slot** (`selected.size == capacity - 1`).
  This guarantees no capacity is wasted: a non-member is only displaced when accepting it
  would consume the final slot that a waiting group member could fill.
