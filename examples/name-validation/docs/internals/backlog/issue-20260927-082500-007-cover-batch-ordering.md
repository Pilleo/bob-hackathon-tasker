---
schema_version: 2
document_type: issue
id: issue-20260927-082500-007-cover-batch-ordering
title: "Cover batch result ordering with a regression test"
severity: LOW
status: open
priority: 70
dependencies: []
target_files:
  - "src/test/kotlin/sample/NameBatchProcessorTest.kt"
---

## Context
`NameBatchProcessor` appends names as it encounters them. Add a regression test that checks accepted names retain input order after normalization and rejected names retain their original spelling and order. This test file overlaps the proposed batch changes.

## Targets
src/test/kotlin/sample/NameBatchProcessorTest.kt
