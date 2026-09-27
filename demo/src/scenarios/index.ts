import type { SchedulerInput, Task } from '../scheduler/types.ts'
import { backlogTasks } from './backlog.generated.ts'

export type Scenario = SchedulerInput & {
  id: string
  name: string
  description: string
}

const task = (id: string, title: string, priority: number, target_files: string[],
  overrides: Partial<Task> = {}): Task => ({
  id, title, priority, state: 'PENDING', dependencyIds: [], target_files, ...overrides,
})

export const scenarios: Scenario[] = [
  {
    id: 'backlog', name: 'Real project backlog', capacity: 4,
    description: 'Eleven Markdown issues from the name-validation project. These are the actual file claims and dependencies.',
    tasks: backlogTasks,
  },
  {
    id: 'conflicts', name: 'File collisions', capacity: 2,
    description: 'A running task reserves one file. Two pending tasks compete for another. Independent work can still proceed.',
    tasks: [
      task('running-batch', 'Process the current batch', 0, ['src/NameBatch.kt'], { state: 'RUNNING' }),
      task('batch-change', 'Update the batch model', 100, ['src/NameBatch.kt']),
      task('normalize', 'Normalize names', 90, ['src/NameNormalizer.kt']),
      task('normalize-alt', 'Alternative normalization', 85, ['src/NameNormalizer.kt']),
      task('guide', 'Write the usage guide', 70, ['README.md']),
    ],
  },
  {
    id: 'dependencies', name: 'Dependency chain', capacity: 2,
    description: 'A dependent task waits until its prerequisite is already complete — selection in this batch is not enough.',
    tasks: [
      task('validator', 'Fix name validation', 95, ['src/Validator.kt']),
      task('integration', 'Integrate validation in batch', 85, ['src/Batch.kt'], { dependencyIds: ['validator'] }),
      task('docs', 'Document error messages', 65, ['README.md']),
    ],
  },
  {
    id: 'affinity', name: 'Cache affinity', capacity: 2,
    description: 'Compare simple priority against the optional preference for a related, eligible task in the last slot.',
    tasks: [
      task('validator-fix', 'Fix the validator', 90, ['src/Validator.kt']),
      task('feature-x', 'Build an unrelated feature', 85, ['src/Feature.kt']),
      task('validator-test', 'Add validator tests', 70, ['src/ValidatorTest.kt']),
    ],
    affinityGroups: [{ id: 'validator-context', scopes: ['file:src/Validator.kt', 'file:src/ValidatorTest.kt'] }],
  },
]
