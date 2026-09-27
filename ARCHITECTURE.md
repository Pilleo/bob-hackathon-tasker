# Planner boundaries

- `planner-core`: immutable document, evidence and review values, pure readiness evaluation.
- `planner`: Markdown/YAML parsing, evidence collection, issue elaboration/review workflows, receipt storage and projections. `PlanningAgent` and `PlanningTools` are host-provided ports. `PlannerSetupOptions` is shared with the ADK host for consistent named agent configuration. The workflow asks for parser-verified candidate hints, checks the agent's chosen targets, and makes one bounded refinement turn when source evidence or actionable target-validation errors are available. `freshCollection()` starts a new bounded evidence pass on either side of an agent turn. An in-file, hashed `adk:generated:v1` marker identifies agent-owned sections: intact generated sections can be corrected; manually edited generated sections and original human-authored sections cannot be replaced automatically. Human answers to generated questions are preserved separately without rewriting line endings; unanswered decisions stay unanswered until a human responds. Managed review text is excluded from generated-section hashes. Plan verification binds the parsed document, evidence, review and scheduling snapshot to the exact original issue bytes.
- `agent-acp`: optional live ACP transport implementing `PlanningAgent`, with bounded subprocesses and read-only sandbox launch.
- `planner-app`: standalone CLI composition and `RepositoryPlanningTools` adapter. AST matching, Codanna semantic search/impact, and Git revision run as bounded read-only argument-array subprocesses with live output caps, process-tree cleanup and an overall evidence budget. Its `.planner.json` holds named ACP profiles; configuration is not part of an exported workspace. `./gradlew check` in the export runs all modules and enforces their dependency graph.

## Bob-built scheduler

The independent `scheduler` module, tests, fixture CLI, and planner bridge were built here with Bob; the ADK scheduler implementation was not copied. See [docs/SCHEDULER-BRIEF.md](docs/SCHEDULER-BRIEF.md). `planner-app` maps mandatory issue-level `target_files` (plus proposed step/test files) to internal file-conflict reservations. `schedule --issues` makes a planning proposal immediately and does not imply review acceptance or permission to execute. `--fixture` remains a simulated demonstration mode.

`checkModuleBoundaries` allows `planner-app` to depend on `scheduler`, but `scheduler` has no project dependencies. Keep this check active.

For planner workflow tests, inject `PlanningAgent` and `PlanningTools` fixtures. Core scheduling tests should use plain immutable task data and no agent, network, clock or filesystem. A future UI should call application services instead of duplicating readiness or conflict rules.
