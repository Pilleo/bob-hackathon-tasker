# Sample project for planning

This independent Kotlin application intentionally accepts blank customer names. It is sample input for the planner, not scheduler implementation.

From the repository root, run `./gradlew -p examples/name-validation run` to see the current behavior. Build the planner distribution with `./gradlew :planner-app:installDist`, then use it from this sample directory:

```bash
cd examples/name-validation
../../planner-app/build/install/planner-app/bin/planner-app issue new --title "Reject blank customer names" --target src/main/kotlin/sample/Main.kt --target src/main/kotlin/sample/NameBatchProcessor.kt
```

The two required `target_files` are saved in frontmatter. Fill Context with: “Reject whitespace-only customer names while keeping nonblank names valid. Add a regression test.” The issue path printed by the command is relative to this sample directory. To propose it for scheduling immediately, run `../../planner-app/build/install/planner-app/bin/planner-app schedule --issues <printed-issue-path> --capacity 2`; review and evidence remain separate from scheduling.

For live elaboration/review, configure an authenticated ACP executable in this directory using `planner setup --name <id> --agent <absolute-file> [--arg <argument>]...`. ast-grep and Codanna are optional installed tools; Codanna must index this workspace. Git revision and missing-index limitations remain explicit. A successful model review is not the same as evidence-backed readiness; use fixture-based planner tests when external tools are absent.
