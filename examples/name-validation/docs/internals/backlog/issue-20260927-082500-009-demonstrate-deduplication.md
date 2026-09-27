---
schema_version: 2
document_type: issue
id: issue-20260927-082500-009-demonstrate-deduplication
title: "Demonstrate duplicate handling in the sample"
severity: LOW
status: open
priority: 60
dependencies:
  - issue-20260927-082500-002-deduplicate-batch-names
target_files:
  - "src/main/kotlin/sample/Main.kt"
  - "README.md"
---

## Context
Once duplicate handling is implemented, include differently spaced versions of the same name in the sample input and show which occurrence is retained. Document the resulting batch output so the behavior is visible without reading the processor source.

## Targets
src/main/kotlin/sample/Main.kt
README.md
