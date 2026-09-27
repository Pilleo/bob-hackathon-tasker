import type { Task, TaskState } from '../scheduler/types'

const states: TaskState[] = ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'BLOCKED']

type Props = {
  task: Task
  selected: boolean
  highlightedFile: string | null
  onStateChange: (id: string, next: TaskState) => void
}

export function TaskCard({ task, selected, highlightedFile, onStateChange }: Props) {
  const highlighted = highlightedFile !== null && task.target_files.includes(highlightedFile)
  return <article className={`task-card ${selected ? 'is-selected' : ''} ${highlighted ? 'is-highlighted' : ''}`}
    data-conflict-highlight={highlighted} aria-label={`Task ${task.title}`}>
    <div className="task-card-top">
      <div className="task-number">{task.id.startsWith('issue-') ? task.id.split('-').at(-1)?.slice(0, 3)?.toUpperCase() : task.id.slice(0, 2).toUpperCase()}</div>
      <span className="priority">P{task.priority}</span>
      {selected && <span className="selected-tag">IN BATCH</span>}
    </div>
    <h3>{task.title}</h3>
    <div className="file-list" aria-label={`Files targeted by ${task.title}`}>
      {task.target_files.map(file => <span key={file} className={`file-chip ${file === highlightedFile ? 'file-chip-hot' : ''}`} title={file}>{file.split('/').at(-1)}</span>)}
    </div>
    <div className="task-card-bottom">
      <label className="state-label">STATUS
        <select value={task.state} aria-label={`Status for ${task.title}`}
          onChange={event => onStateChange(task.id, event.target.value as TaskState)}>
          {states.map(state => <option key={state} value={state}>{state.toLowerCase().replace('_', ' ')}</option>)}
        </select>
      </label>
      {task.dependencyIds.length > 0 && <span className="dep-count" title={task.dependencyIds.join(', ')}>{task.dependencyIds.length} prerequisite{task.dependencyIds.length === 1 ? '' : 's'}</span>}
    </div>
  </article>
}
