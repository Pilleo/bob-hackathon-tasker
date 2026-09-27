// Generated from the sample Markdown issues by npm run import:backlog. Do not hand-edit.
import type { Task } from '../scheduler/types'

export const backlogTasks: Task[] = [
  {
    "id": "issue-20260927-081953-321-alternative-blank-name-fix",
    "title": "Alternative blank-name fix",
    "priority": 0,
    "state": "PENDING",
    "dependencyIds": [],
    "target_files": [
      "src/main/kotlin/sample/Main.kt"
    ]
  },
  {
    "id": "issue-20260927-081953-481-improve-name-normalization",
    "title": "Improve name normalization",
    "priority": 0,
    "state": "PENDING",
    "dependencyIds": [],
    "target_files": [
      "src/main/kotlin/sample/NameNormalizer.kt"
    ]
  },
  {
    "id": "issue-20260927-082500-001-normalize-unicode-whitespace",
    "title": "Normalize Unicode whitespace in names",
    "priority": 100,
    "state": "PENDING",
    "dependencyIds": [],
    "target_files": [
      "src/main/kotlin/sample/NameNormalizer.kt",
      "src/test/kotlin/sample/NameNormalizerTest.kt"
    ]
  },
  {
    "id": "issue-20260927-082500-002-deduplicate-batch-names",
    "title": "Deduplicate normalized names in a batch",
    "priority": 95,
    "state": "PENDING",
    "dependencyIds": [],
    "target_files": [
      "src/main/kotlin/sample/NameBatchProcessor.kt",
      "src/test/kotlin/sample/NameBatchProcessorTest.kt"
    ]
  },
  {
    "id": "issue-20260927-082500-003-limit-batch-name-length",
    "title": "Bound accepted batch name length",
    "priority": 90,
    "state": "PENDING",
    "dependencyIds": [],
    "target_files": [
      "src/main/kotlin/sample/NameBatchProcessor.kt",
      "src/test/kotlin/sample/NameBatchProcessorTest.kt"
    ]
  },
  {
    "id": "issue-20260927-082500-004-include-total-in-summary",
    "title": "Show total processed names in the summary",
    "priority": 85,
    "state": "PENDING",
    "dependencyIds": [],
    "target_files": [
      "src/main/kotlin/sample/NameSummaryFormatter.kt",
      "src/test/kotlin/sample/NameSummaryFormatterTest.kt"
    ]
  },
  {
    "id": "issue-20260927-082500-005-accept-cli-name-input",
    "title": "Accept names from command-line arguments",
    "priority": 80,
    "state": "PENDING",
    "dependencyIds": [],
    "target_files": [
      "src/main/kotlin/sample/Main.kt"
    ]
  },
  {
    "id": "issue-20260927-082500-006-record-rejection-reasons",
    "title": "Record reasons for rejected batch names",
    "priority": 75,
    "state": "PENDING",
    "dependencyIds": [
      "issue-20260927-081953-321-alternative-blank-name-fix"
    ],
    "target_files": [
      "src/main/kotlin/sample/NameBatch.kt",
      "src/main/kotlin/sample/NameBatchProcessor.kt",
      "src/main/kotlin/sample/NameSummaryFormatter.kt",
      "src/test/kotlin/sample/NameBatchProcessorTest.kt"
    ]
  },
  {
    "id": "issue-20260927-082500-007-cover-batch-ordering",
    "title": "Cover batch result ordering with a regression test",
    "priority": 70,
    "state": "PENDING",
    "dependencyIds": [],
    "target_files": [
      "src/test/kotlin/sample/NameBatchProcessorTest.kt"
    ]
  },
  {
    "id": "issue-20260927-082500-008-document-name-input",
    "title": "Document sample name input and output",
    "priority": 65,
    "state": "PENDING",
    "dependencyIds": [
      "issue-20260927-082500-005-accept-cli-name-input"
    ],
    "target_files": [
      "README.md"
    ]
  },
  {
    "id": "issue-20260927-082500-009-demonstrate-deduplication",
    "title": "Demonstrate duplicate handling in the sample",
    "priority": 60,
    "state": "PENDING",
    "dependencyIds": [
      "issue-20260927-082500-002-deduplicate-batch-names"
    ],
    "target_files": [
      "src/main/kotlin/sample/Main.kt",
      "README.md"
    ]
  }
]
