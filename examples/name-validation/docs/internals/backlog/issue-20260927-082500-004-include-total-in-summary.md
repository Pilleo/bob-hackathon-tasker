---
schema_version: 2
document_type: issue
id: issue-20260927-082500-004-include-total-in-summary
title: "Show total processed names in the summary"
severity: LOW
status: open
priority: 85
dependencies: []
target_files:
  - "src/main/kotlin/sample/NameSummaryFormatter.kt"
  - "src/test/kotlin/sample/NameSummaryFormatterTest.kt"
---

## Context
`NameSummaryFormatter` reports accepted and rejected counts separately but omits the total. Include a total equal to their sum while keeping both existing counts visible, and test empty and mixed batches.

## Targets
src/main/kotlin/sample/NameSummaryFormatter.kt
src/test/kotlin/sample/NameSummaryFormatterTest.kt
