---
schema_version: 2
document_type: issue
id: issue-20260927-082500-003-limit-batch-name-length
title: "Bound accepted batch name length"
severity: MEDIUM
status: open
priority: 90
dependencies: []
target_files:
  - "src/main/kotlin/sample/NameBatchProcessor.kt"
  - "src/test/kotlin/sample/NameBatchProcessorTest.kt"
---

## Context
Batch processing currently accepts arbitrarily long normalized names. Choose and document a maximum length, reject inputs exceeding it after normalization, and test the boundary and preservation of the original rejected input. This shares the batch processor and its tests with the deduplication issue.

## Targets
src/main/kotlin/sample/NameBatchProcessor.kt
src/test/kotlin/sample/NameBatchProcessorTest.kt
