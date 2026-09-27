---
schema_version: 2
document_type: issue
id: issue-20260927-082500-006-record-rejection-reasons
title: "Record reasons for rejected batch names"
severity: MEDIUM
status: open
priority: 75
dependencies:
  - issue-20260927-081953-321-alternative-blank-name-fix
target_files:
  - "src/main/kotlin/sample/NameBatch.kt"
  - "src/main/kotlin/sample/NameBatchProcessor.kt"
  - "src/main/kotlin/sample/NameSummaryFormatter.kt"
  - "src/test/kotlin/sample/NameBatchProcessorTest.kt"
---

## Context
`NameBatch` currently stores rejected input strings without explaining why they were rejected. Define explicit rejection reasons and retain the original input alongside each reason. Update processing and summary output consistently, with tests for the current blank-name case once validation is corrected.

## Targets
src/main/kotlin/sample/NameBatch.kt
src/main/kotlin/sample/NameBatchProcessor.kt
src/main/kotlin/sample/NameSummaryFormatter.kt
src/test/kotlin/sample/NameBatchProcessorTest.kt
