import type { ScheduleResult, SkipReason, Task } from '../scheduler/types'

type Props = {
  result: ScheduleResult
  tasks: Task[]
  capacity: number
  stale: boolean
  onComplete: (id: string) => void
  onInspect: (file: string) => void
}

function explain(reason: SkipReason, byId: Map<string, Task>, capacity: number) {
  switch (reason.type) {
    case 'RunningConflict': return {
      label: 'Running conflict',
      message: `Already running: ${byId.get(reason.taskId)?.title ?? reason.taskId} also edits ${reason.scope.slice(5)}.`,
      file: reason.scope.slice(5),
    }
    case 'SelectedConflict': return {
      label: 'Selected conflict',
      message: `Already selected: ${byId.get(reason.taskId)?.title ?? reason.taskId} also edits ${reason.scope.slice(5)}.`,
      file: reason.scope.slice(5),
    }
    case 'DependencyNotSucceeded': return {
      label: 'Waiting for prerequisite',
      message: reason.unsatisfied.map(dep => `Waiting for ${byId.get(dep.id)?.title ?? dep.id} (${dep.state.toLowerCase()}).`).join(' '),
      file: null,
    }
    case 'CapacityExceeded': return { label: 'At capacity', message: `All ${capacity} parallel slots are filled.`, file: null }
    case 'AffinityPreempted': return {
      label: 'Shared context preferred',
      message: `Last slot favors ${byId.get(reason.competingTaskId)?.title ?? reason.competingTaskId} in the ${reason.groupId} group.`,
      file: null,
    }
  }
}

export function BatchResults({ result, tasks, capacity, stale, onComplete, onInspect }: Props) {
  const byId = new Map(tasks.map(task => [task.id, task]))
  return <section className="results-panel" aria-label="Batch proposal">
    <div className="panel-heading">
      <div><div className="eyebrow">SCHEDULER OUTPUT</div><h2>Proposed batch</h2></div>
      {result.type === 'batch' && <span className="batch-count">{result.selected.length} / {capacity} slots</span>}
    </div>
    {stale && <div className="stale-notice" role="status">Changes pending — recompute to see the new proposal.</div>}
    {result.type === 'invalidSnapshot' ? <div role="alert" className="invalid-card">
      <strong>Snapshot needs attention</strong>{result.diagnostics.map(diagnostic => <p key={diagnostic}>{diagnostic}</p>)}
    </div> : <>
      <div className="result-section-label"><span className="dot mint" /> READY FOR HANDOFF <span className="section-count">{result.selected.length}</span></div>
      <div className="selected-list">
        {result.selected.length === 0 && <p className="empty-list">No task is eligible in this snapshot.</p>}
        {result.selected.map((id, index) => <div className="selected-row" key={id} data-testid="selected-task">
          <span className="slot-number">{String(index + 1).padStart(2, '0')}</span>
          <div className="selected-copy"><strong>{byId.get(id)?.title ?? id}</strong><span>{byId.get(id)?.target_files.map(file => file.split('/').at(-1)).join(' · ')}</span></div>
          <button type="button" className="icon-action" onClick={() => onComplete(id)} aria-label={`Complete ${byId.get(id)?.title ?? id}`} title="Mark succeeded and recompute">✓</button>
        </div>)}
      </div>
      <div className="result-section-label deferred"><span className="dot amber" /> DEFERRED <span className="section-count">{result.skipped.length}</span></div>
      <div className="skipped-list">
        {result.skipped.length === 0 && <p className="empty-list">Nothing was deferred.</p>}
        {result.skipped.map(({ id, reason }) => {
          const detail = explain(reason, byId, capacity)
          return <article key={id} className="skipped-row">
            <div className="skipped-meta"><span>{detail.label}</span><span className="reason-code">{reason.type}</span></div>
            <strong>{byId.get(id)?.title ?? id}</strong>
            <p>{detail.message}</p>
            {detail.file && <button type="button" className="inspect-link" onClick={() => onInspect(detail.file!)}>Inspect {byId.get(id)?.title ?? id} ↗</button>}
          </article>
        })}
      </div>
    </>}
  </section>
}
