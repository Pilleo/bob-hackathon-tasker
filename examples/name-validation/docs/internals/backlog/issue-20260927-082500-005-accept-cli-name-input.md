---
schema_version: 2
document_type: issue
id: issue-20260927-082500-005-accept-cli-name-input
title: "Accept names from command-line arguments"
severity: LOW
status: open
priority: 80
dependencies: []
target_files:
  - "src/main/kotlin/sample/Main.kt"
---

## Context
The sample `main` always processes the same hard-coded names. When arguments are supplied, pass them to `NameBatchProcessor` in order; keep the current built-in example when no arguments are supplied, so the sample remains easy to run.

## Targets
src/main/kotlin/sample/Main.kt
