import type { AffinityGroup, ScheduleResult, SchedulerInput, SkipReason, Task } from './types'

const invalid = (message: string): ScheduleResult => ({ type: 'invalidSnapshot', diagnostics: [message] })

function canonicalFile(path: string): string | null {
  if (!path || path.startsWith('/') || path.includes('\\') || /^[a-z]:/i.test(path) ||
      /[\x00-\x1f\x7f]/.test(path) || path.split('/').includes('..')) return null
  const segments = path.split('/').filter(part => part !== '' && part !== '.')
  return segments.length ? segments.join('/') : null
}

function cycleExists(tasks: Task[]): boolean {
  const degree = new Map(tasks.map(task => [task.id, task.dependencyIds.length]))
  const dependents = new Map<string, string[]>()
  for (const task of tasks) for (const dep of task.dependencyIds) {
    dependents.set(dep, [...(dependents.get(dep) ?? []), task.id])
  }
  const queue = tasks.filter(task => task.dependencyIds.length === 0).map(task => task.id)
  let processed = 0
  for (let head = 0; head < queue.length; head++) {
    processed++
    for (const id of dependents.get(queue[head]) ?? []) {
      const next = (degree.get(id) ?? 0) - 1
      degree.set(id, next)
      if (next === 0) queue.push(id)
    }
  }
  return processed !== tasks.length
}

function collision(files: string[], reserved: Map<string, string>): { taskId: string; scope: string } | null {
  for (const file of files) {
    const owner = reserved.get(file)
    if (owner !== undefined) return { taskId: owner, scope: `file:${file}` }
  }
  return null
}

function inGroup(files: string[], group: AffinityGroup): boolean {
  return files.some(file => group.scopes.includes(`file:${file}`))
}

/** A file-claim-only port of the Kotlin BatchSelector for the public static demo. */
export function selectBatch({ tasks, capacity, affinityGroups = [] }: SchedulerInput): ScheduleResult {
  if (!Number.isInteger(capacity) || capacity <= 0) return invalid(`capacity must be > 0 but was ${capacity}`)
  const ids = tasks.map(task => task.id)
  const duplicates = [...new Set(ids.filter((id, index) => ids.indexOf(id) !== index))].sort()
  if (duplicates.length) return invalid(`duplicate task IDs: ${duplicates.join(', ')}`)
  const byId = new Map(tasks.map(task => [task.id, task]))
  const unknown = [...new Set(tasks.flatMap(task => task.dependencyIds.filter(id => !byId.has(id))))].sort()
  if (unknown.length) return invalid(`unknown dependency IDs: ${unknown.join(', ')}`)
  if (cycleExists(tasks)) return invalid('dependency graph contains a cycle')

  const filesById = new Map<string, string[]>()
  for (const task of tasks) {
    if (!task.target_files.length) return invalid(`task ${task.id} must declare nonempty target_files`)
    const files = task.target_files.map(canonicalFile)
    if (files.some(file => file === null)) return invalid(`task ${task.id} has invalid target_files path`)
    filesById.set(task.id, files as string[])
  }
  for (const group of affinityGroups) {
    if (!group.scopes.every(scope => scope.startsWith('file:') && canonicalFile(scope.slice(5)) === scope.slice(5))) {
      return invalid(`affinity group ${group.id} must contain valid file scopes`)
    }
  }

  const running = tasks.filter(task => task.state === 'RUNNING')
  const runningReservations = new Map<string, string>()
  for (const task of running) for (const file of filesById.get(task.id)!) {
    const other = runningReservations.get(file)
    if (other && other !== task.id) {
      return invalid(`running tasks '${other}' and '${task.id}' have conflicting write scope: FileScope(path=${file})`)
    }
    runningReservations.set(file, task.id)
  }

  const pending = tasks.filter(task => task.state === 'PENDING')
  const eligible = pending.filter(task => task.dependencyIds.every(id => byId.get(id)!.state === 'SUCCEEDED'))
    .sort((a, b) => b.priority - a.priority || a.id.localeCompare(b.id, 'en'))
  const ineligible = pending.filter(task => task.dependencyIds.some(id => byId.get(id)!.state !== 'SUCCEEDED'))
  const selected: string[] = []
  const reasons = new Map<string, SkipReason>()
  const selectedReservations = new Map<string, string>()

  for (const [index, task] of eligible.entries()) {
    if (selected.length >= capacity) {
      reasons.set(task.id, { type: 'CapacityExceeded' })
      continue
    }
    const files = filesById.get(task.id)!
    const runningConflict = collision(files, runningReservations)
    if (runningConflict) {
      reasons.set(task.id, { type: 'RunningConflict', ...runningConflict })
      continue
    }
    const selectedConflict = collision(files, selectedReservations)
    if (selectedConflict) {
      reasons.set(task.id, { type: 'SelectedConflict', ...selectedConflict })
      continue
    }
    if (affinityGroups.length && selected.length > 0 && selected.length === capacity - 1) {
      const active = affinityGroups.filter(group => selected.some(id => inGroup(filesById.get(id)!, group)))
      if (active.length && !active.some(group => inGroup(files, group))) {
        const waiting = eligible.slice(index + 1).find(next =>
          !selected.includes(next.id) && !reasons.has(next.id) &&
          active.some(group => inGroup(filesById.get(next.id)!, group)) &&
          !collision(filesById.get(next.id)!, runningReservations) &&
          !collision(filesById.get(next.id)!, selectedReservations),
        )
        if (waiting) {
          const group = active.find(candidate => inGroup(filesById.get(waiting.id)!, candidate))!
          reasons.set(task.id, { type: 'AffinityPreempted', groupId: group.id, competingTaskId: waiting.id })
          continue
        }
      }
    }
    selected.push(task.id)
    for (const file of files) selectedReservations.set(file, task.id)
  }

  const skipped = [
    ...ineligible.map(task => ({ id: task.id, reason: {
      type: 'DependencyNotSucceeded' as const,
      unsatisfied: task.dependencyIds.filter(id => byId.get(id)!.state !== 'SUCCEEDED')
        .map(id => ({ id, state: byId.get(id)!.state })),
    } })),
    ...eligible.filter(task => !selected.includes(task.id)).map(task => ({
      id: task.id, reason: reasons.get(task.id) ?? { type: 'CapacityExceeded' as const },
    })),
  ]
  return { type: 'batch', selected, skipped }
}
