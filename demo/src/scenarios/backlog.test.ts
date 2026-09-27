import { describe, expect, it } from 'vitest'
import { backlogTasks } from './backlog.generated'
import { selectBatch } from '../scheduler/selectBatch'

describe('the real sample backlog', () => {
  it('imports all eleven issues with distinct IDs and declared target files', () => {
    expect(backlogTasks).toHaveLength(11)
    expect(new Set(backlogTasks.map(task => task.id)).size).toBe(11)
    expect(backlogTasks.every(task => task.target_files.length > 0)).toBe(true)
  })

  it('selects the four real tasks and explains blocked work', () => {
    const result = selectBatch({ tasks: backlogTasks, capacity: 4 })
    expect(result).toMatchObject({
      type: 'batch',
      selected: [
        'issue-20260927-082500-001-normalize-unicode-whitespace',
        'issue-20260927-082500-002-deduplicate-batch-names',
        'issue-20260927-082500-004-include-total-in-summary',
        'issue-20260927-082500-005-accept-cli-name-input',
      ],
    })
    if (result.type !== 'batch') throw new Error('Expected valid real backlog')
    expect(result.skipped.map(skip => skip.reason.type)).toContain('DependencyNotSucceeded')
    expect(result.skipped.map(skip => skip.reason.type)).toContain('SelectedConflict')
    expect(result.skipped.map(skip => skip.reason.type)).toContain('CapacityExceeded')
  })
})
