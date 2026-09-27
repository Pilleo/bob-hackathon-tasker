---
schema_version: 2
document_type: issue
id: issue-20260927-082500-001-normalize-unicode-whitespace
title: "Normalize Unicode whitespace in names"
severity: MEDIUM
status: open
priority: 100
dependencies: []
target_files:
  - "src/main/kotlin/sample/NameNormalizer.kt"
  - "src/test/kotlin/sample/NameNormalizerTest.kt"
---

## Context
`NameNormalizer` collapses ASCII spacing, but names pasted with nonbreaking spaces can keep unexpected separators. Define which Unicode whitespace counts as a word separator, normalize it to one ordinary space, and cover leading, trailing, and internal cases in a test.

## Targets
src/main/kotlin/sample/NameNormalizer.kt
src/test/kotlin/sample/NameNormalizerTest.kt
