# Bob-built scheduler contract

## Outcome

Given a snapshot of tasks, propose the next compatible batch and explain why each other task was not selected. Bob implemented the scheduler in this repository; no ADK scheduler implementation was imported. The current issue-facing input is a nonempty `target_files` list, declared during issue creation. File conflicts are derived from that list, not from per-step write declarations. The scheduler's `Writes` and `WriteScope` types remain internal conflict-engine types.

## Module boundary

- `:scheduler` is a Kotlin/JDK-only library with JUnit tests, no project dependencies and no I/O.
- The root `check` and `checkModuleBoundaries` cover it; only `planner-app` depends on `scheduler`.
- Issue/fixture parsing and conversion of `target_files` into internal file reservations live in `planner-app`.
- No automatic agent invocation, code execution, persistent queue, or reservations in the selector.

## Input contract

Create issues with `issue new --title <text> --target <relative-file> [--target <relative-file>]...`. The schema-v2 frontmatter stores these mandatory, distinct paths in `target_files`. Existing issue files can still be read from their old `## Targets` section without being rewritten. `schedule --issues <issue-file> [--issues <issue-file>]... --capacity N` proposes tasks by issue ID and priority. Issues with no declared target files are rejected. New issues begin open/pending; `resolved`, `in_progress`, `failed`, and `blocked` statuses map to scheduler states. List referenced dependencies in the same snapshot.

Each `target_files` path is a repository-relative file conflict claim. The bridge also reserves files named in agent-generated steps and verification so newly proposed files cannot be missed. Normalize paths and reject escapes at the application boundary. The legacy scheduler engine additionally supports directory and opaque-resource scopes internally; those are not issue-file fields.

## Output contract

Return either an invalid-snapshot result with diagnostics, or a proposed batch with ordered issue/task IDs plus a structured reason for every unselected pending item. Reasons distinguish unresolved/failed dependency, running-task conflict, selected-task conflict, and batch capacity. Conflict explanations identify the other task and file. Missing `target_files` is a validation error at the issue boundary.

## Selection rules

1. Validate positive batch capacity, unique task IDs, known dependencies and an acyclic dependency graph.
2. A pending task is eligible only when every dependency is already succeeded in the input snapshot. Selecting a prerequisite now does not satisfy its dependent in the same batch.
3. Sort eligible tasks by descending priority, then ascending task ID; choose greedily.
4. Running tasks reserve their target files regardless of priority. Conflicting running reservations are an invalid snapshot. The pure scheduler retains an explicit unknown-claim state for non-issue inputs.
5. New issues must declare target files; an empty list cannot be treated as write-free.
6. File claims of newly selected tasks must not overlap running or already selected tasks.
7. The batch does not change the input, start processes, or acquire reservations. The caller must reserve atomically against the same snapshot before executing anything.

## Literal acceptance example

With capacity 2:

| Task | Priority | State | Dependencies | target_files |
|---|---:|---|---|---|
| validation | 100 | pending | none | src/Validator.kt |
| message | 90 | pending | none | src/Validator.kt |
| docs | 80 | pending | none | docs/validation.md |
| tests | 70 | pending | validation | src/ValidatorTest.kt |

Select `validation`, then `docs`. Explain `message` conflicts with selected `validation`; explain `tests` waits for `validation` to succeed. After the caller records that success, recompute from the new snapshot.

## Compact TDD case list

- Priority and deterministic ID tie-break; input collections remain unchanged.
- File identity and path aliases in issue `target_files`; directory/descendant and opaque-resource cases for the internal selector.
- Running reservations; empty/missing target-file declarations rejected at the issue boundary.
- Succeeded dependency permits selection; pending/failed dependency does not.
- Dependency selected in this batch does not release its dependent yet.
- Invalid capacity, duplicate IDs, unknown dependency, cycles, conflicting running reservations.
- Capacity explanation and exact conflicting task/scope.
- Every selected pair is conflict-free; parameterize repeated scope/state cases.

## Planner integration

`schedule --issues` is a proposal for which issues can be planned in parallel using their declared target files. It does not require ACP acceptance and is not permission to execute code. For a separate implementation-ready handoff, the existing `PlanVerification.verify(issue, root, tools)` and `VerifiedPlan.isCurrent(root)` still enforce evidence and current review; its bridge maps the same target files to scheduler-owned types. The caller supplies execution states and all referenced dependencies.

The selector and explanations are implemented. The thin `schedule --issues` CLI reads real issue files; `schedule --fixture` remains labelled simulated input. The scheduler library itself never parses Markdown.

## Verification and demo

Run `./gradlew check` for the complete project. For the demo, create two real issues with overlapping `--target` paths, schedule both with `--issues`, and show one deferral. Then show independent issues selected together. The four-task fixture remains a simulation for dependency transitions. Preserve Bob IDE task-session screenshots under `bob_sessions/` and distinguish imported foundation from new scheduler work.
