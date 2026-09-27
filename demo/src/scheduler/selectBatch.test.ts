import { describe, expect, it } from 'vitest'
import { selectBatch } from './selectBatch'
import type { Task } from './types'

const task = (id: string, priority: number, files: string[], extras: Partial<Task> = {}): Task => ({
  id, title: id, priority, state: 'PENDING', dependencyIds: [], target_files: files, ...extras,
})

describe('browser scheduler', () => {
  it('selects independent tasks in priority then ID order without changing the input', () => {
    const tasks = [task('z', 2, ['z.kt']), task('b', 3, ['b.kt']), task('a', 3, ['a.kt'])]
    const before = structuredClone(tasks)
    expect(selectBatch({ tasks, capacity: 3 })).toEqual({
      type: 'batch', selected: ['a', 'b', 'z'], skipped: [],
    })
    expect(tasks).toEqual(before)
  })

  it('identifies the selected task and file causing a collision', () => {
    expect(selectBatch({ tasks: [task('first', 10, ['src/A.kt']), task('second', 9, ['src/A.kt'])], capacity: 2 }))
      .toMatchObject({ selected: ['first'], skipped: [{ id: 'second', reason: { type: 'SelectedConflict', taskId: 'first', scope: 'file:src/A.kt' } }] })
  })

  it('reserves a running task file before considering pending tasks', () => {
    expect(selectBatch({ tasks: [task('running', 0, ['src/A.kt'], { state: 'RUNNING' }), task('waiting', 30, ['src/A.kt'])], capacity: 2 }))
      .toMatchObject({ selected: [], skipped: [{ id: 'waiting', reason: { type: 'RunningConflict', taskId: 'running', scope: 'file:src/A.kt' } }] })
  })

  it('waits for a prerequisite to succeed, not merely be selected in this batch', () => {
    const first = task('first', 30, ['first.kt'])
    const second = task('second', 10, ['second.kt'], { dependencyIds: ['first'] })
    const before = selectBatch({ tasks: [first, second], capacity: 2 })
    expect(before).toMatchObject({ selected: ['first'], skipped: [{ id: 'second', reason: { type: 'DependencyNotSucceeded', unsatisfied: [{ id: 'first', state: 'PENDING' }] } }] })
    expect(selectBatch({ tasks: [{ ...first, state: 'SUCCEEDED' }, second], capacity: 2 }))
      .toMatchObject({ selected: ['second'], skipped: [] })
  })

  it('reports capacity before another collision once the batch is full', () => {
    expect(selectBatch({ tasks: [task('first', 10, ['a.kt']), task('second', 9, ['a.kt'])], capacity: 1 }))
      .toMatchObject({ selected: ['first'], skipped: [{ id: 'second', reason: { type: 'CapacityExceeded' } }] })
  })

  it('preempts only the last slot for a compatible member of an active affinity group', () => {
    const tasks = [task('fix', 90, ['Validator.kt']), task('other', 85, ['Feature.kt']), task('test', 70, ['ValidatorTest.kt'])]
    const plain = selectBatch({ tasks, capacity: 2 })
    expect(plain).toMatchObject({ selected: ['fix', 'other'] })
    const withAffinity = selectBatch({ tasks, capacity: 2, affinityGroups: [{ id: 'cache', scopes: ['file:Validator.kt', 'file:ValidatorTest.kt'] }] })
    expect(withAffinity).toMatchObject({ selected: ['fix', 'test'], skipped: [{ id: 'other', reason: { type: 'AffinityPreempted', groupId: 'cache', competingTaskId: 'test' } }] })
    expect(selectBatch({ tasks, capacity: 3, affinityGroups: [{ id: 'cache', scopes: ['file:Validator.kt', 'file:ValidatorTest.kt'] }] }))
      .toMatchObject({ selected: ['fix', 'other', 'test'], skipped: [] })
  })

  it('rejects invalid capacity, unknown dependency, cycles, and conflicting running work', () => {
    expect(selectBatch({ tasks: [], capacity: 0 })).toMatchObject({ type: 'invalidSnapshot', diagnostics: ['capacity must be > 0 but was 0'] })
    expect(selectBatch({ tasks: [task('a', 1, ['a.kt'], { dependencyIds: ['missing'] })], capacity: 1 })).toMatchObject({ type: 'invalidSnapshot', diagnostics: ['unknown dependency IDs: missing'] })
    expect(selectBatch({ tasks: [task('a', 1, ['a.kt'], { dependencyIds: ['b'] }), task('b', 2, ['b.kt'], { dependencyIds: ['a'] })], capacity: 2 })).toMatchObject({ type: 'invalidSnapshot', diagnostics: ['dependency graph contains a cycle'] })
    expect(selectBatch({ tasks: [task('a', 1, ['a.kt'], { state: 'RUNNING' }), task('b', 2, ['a.kt'], { state: 'RUNNING' })], capacity: 2 })).toMatchObject({ type: 'invalidSnapshot', diagnostics: [expect.stringContaining('conflicting write scope')] })
  })

  it('rejects empty or unsafe file claims instead of silently treating tasks as write-free', () => {
    expect(selectBatch({ tasks: [task('a', 1, [])], capacity: 2 })).toMatchObject({ type: 'invalidSnapshot', diagnostics: [expect.stringContaining('target_files')] })
    expect(selectBatch({ tasks: [task('a', 1, ['../outside.kt'])], capacity: 2 })).toMatchObject({ type: 'invalidSnapshot', diagnostics: [expect.stringContaining('target_files')] })
  })
})
