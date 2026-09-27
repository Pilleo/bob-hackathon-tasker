---
schema_version: 2
document_type: issue
id: issue-20260927-082500-002-deduplicate-batch-names
title: "Deduplicate normalized names in a batch"
severity: MEDIUM
status: open
priority: 95
dependencies: []
target_files:
  - "src/main/kotlin/sample/NameBatchProcessor.kt"
  - "src/test/kotlin/sample/NameBatchProcessorTest.kt"
---

## Context
`NameBatchProcessor` currently accepts repeated names separately even when normalization makes them identical. Keep the first accepted occurrence, decide how later duplicates are reported in `rejected`, and cover the ordering and duplicate behavior in a test.

## Targets
src/main/kotlin/sample/NameBatchProcessor.kt
src/test/kotlin/sample/NameBatchProcessorTest.kt
