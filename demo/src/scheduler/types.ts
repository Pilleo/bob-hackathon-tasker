export type TaskState = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'BLOCKED'

export type Task = {
  id: string
  title: string
  priority: number
  state: TaskState
  dependencyIds: string[]
  target_files: string[]
}

export type AffinityGroup = { id: string; scopes: string[] }
export type SchedulerInput = { tasks: Task[]; capacity: number; affinityGroups?: AffinityGroup[] }

export type SkipReason =
  | { type: 'DependencyNotSucceeded'; unsatisfied: { id: string; state: TaskState }[] }
  | { type: 'RunningConflict' | 'SelectedConflict'; taskId: string; scope: string }
  | { type: 'CapacityExceeded' }
  | { type: 'AffinityPreempted'; groupId: string; competingTaskId: string }

export type ScheduleResult =
  | { type: 'batch'; selected: string[]; skipped: { id: string; reason: SkipReason }[] }
  | { type: 'invalidSnapshot'; diagnostics: string[] }
