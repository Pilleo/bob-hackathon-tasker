import { describe, expect, it } from 'vitest'
import { scenarios } from './index'
import { selectBatch } from '../scheduler/selectBatch'

describe('interactive scenarios', () => {
  it('starts with the real Markdown backlog and offers three focused comparisons', () => {
    expect(scenarios.map(s => s.id)).toEqual(['backlog', 'conflicts', 'dependencies', 'affinity'])
    expect(scenarios[0].tasks).toHaveLength(11)
  })

  it('explains both running reservations and selected-file collisions', () => {
    const scenario = scenarios.find(s => s.id === 'conflicts')!
    const result = selectBatch(scenario)
    expect(result.type).toBe('batch')
    if (result.type !== 'batch') return
    expect(result.skipped.map(s => s.reason.type)).toContain('RunningConflict')
    expect(result.skipped.map(s => s.reason.type)).toContain('SelectedConflict')
  })

  it('illustrates how affinity changes the final slot', () => {
    const scenario = scenarios.find(s => s.id === 'affinity')!
    expect(selectBatch({ ...scenario, affinityGroups: [] })).toMatchObject({ selected: ['validator-fix', 'feature-x'] })
    expect(selectBatch(scenario)).toMatchObject({ selected: ['validator-fix', 'validator-test'] })
  })
})
