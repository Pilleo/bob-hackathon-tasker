import { useState } from 'react'
import { BatchResults } from './components/BatchResults'
import { TaskCard } from './components/TaskCard'
import { scenarios } from './scenarios/index'
import { selectBatch } from './scheduler/selectBatch'
import type { Task, TaskState } from './scheduler/types'

const sourceUrl = 'https://github.com/Pilleo/bob-hackathon-tasker/tree/main/demo'

function copyTasks(tasks: Task[]): Task[] {
  return tasks.map(task => ({ ...task, dependencyIds: [...task.dependencyIds], target_files: [...task.target_files] }))
}

export default function App() {
  const presentationUrl = `${import.meta.env.BASE_URL}scheduler-presentation.pdf`
  const [scenarioId, setScenarioId] = useState(scenarios[0].id)
  const scenario = scenarios.find(item => item.id === scenarioId)!
  const [tasks, setTasks] = useState(() => copyTasks(scenarios[0].tasks))
  const [capacity, setCapacity] = useState(scenarios[0].capacity)
  const [affinityOn, setAffinityOn] = useState(true)
  const [result, setResult] = useState(() => selectBatch(scenarios[0]))
  const [stale, setStale] = useState(false)
  const [highlightedFile, setHighlightedFile] = useState<string | null>(null)
  const affinityGroups = affinityOn ? scenario.affinityGroups : []

  function loadScenario(id: string) {
    const next = scenarios.find(item => item.id === id)!
    setScenarioId(id)
    setTasks(copyTasks(next.tasks))
    setCapacity(next.capacity)
    setAffinityOn(true)
    setResult(selectBatch(next))
    setStale(false)
    setHighlightedFile(null)
  }

  function changeState(id: string, state: TaskState) {
    setTasks(current => current.map(task => task.id === id ? { ...task, state } : task))
    setStale(true)
  }

  function propose() {
    setResult(selectBatch({ tasks, capacity, affinityGroups }))
    setStale(false)
    setHighlightedFile(null)
  }

  const selected = result.type === 'batch' ? new Set(result.selected) : new Set<string>()

  return <div className="app-shell">
    <header className="site-header">
      <a className="brand" href="#top" aria-label="Bob Hackathon Tasker home"><span className="brand-mark">B<span>↗</span></span><span>BOB <em>/</em> TASKER<small>PARALLEL AGENT SCHEDULING</small></span></a>
      <nav aria-label="Main links"><a href="#workspace">Explore demo</a><a href="#how-it-works">How it works</a><a className="nav-source" href={sourceUrl} target="_blank" rel="noopener noreferrer">Source code ↗</a></nav>
    </header>

    <main id="top">
      <section className="hero page-wrap">
        <div className="hero-copy">
          <span className="topline"><span className="pulse-dot" /> LIVE INTERACTIVE DEMO <span className="topline-rule" /> IBM BOB HACKATHON TASKER</span>
          <h1>Let agents work<br />in parallel. <span>Not collide.</span></h1>
          <p>Plan the work. Find the overlap. Propose a compatible batch — with a clear reason for every task that waits.</p>
          <div className="hero-links"><a className="button button-primary" href="#workspace">Explore the scheduler <span>↗</span></a><a className="text-link" href={presentationUrl} target="_blank" rel="noopener noreferrer">View presentation ↗</a></div>
          <div className="hero-facts"><span><b>01</b> File-aware</span><span><b>02</b> Dependency-safe</span><span><b>03</b> Explainable</span></div>
        </div>
        <div className="hero-visual" aria-hidden="true">
          <div className="orbit orbit-outer" /><div className="orbit orbit-inner" />
          <div className="visual-task visual-task-one"><span className="visual-dot" /> TASK 01 <small>name-normalizer.kt</small></div>
          <div className="visual-task visual-task-two"><span className="visual-dot" /> TASK 02 <small>batch-processor.kt</small></div>
          <div className="visual-task visual-task-three"><span className="visual-dot" /> TASK 03 <small>name-normalizer.kt</small></div>
          <div className="visual-core"><span>PARALLEL<br />BATCH</span><b>02</b><small>COMPATIBLE TASKS</small></div>
          <span className="visual-badge visual-badge-success">✓ SELECTED</span><span className="visual-badge visual-badge-conflict">⊘ CONFLICT</span>
        </div>
      </section>

      <section className="workspace-section" id="workspace">
        <div className="page-wrap">
          <div className="section-header"><div><div className="eyebrow">THE WORKSPACE <span className="section-dash" /></div><h2>Make the next move <i>count.</i></h2><p>Change the inputs. See exactly why each task does or doesn’t fit.</p></div><span className="sample-pill">✦ WORKS WITHOUT AN ACCOUNT</span></div>
          <div className="scenario-tabs" role="group" aria-label="Choose a scheduling scenario">
            {scenarios.map((item, index) => <button key={item.id} type="button" className={`scenario-tab ${scenario.id === item.id ? 'active' : ''}`}
              aria-pressed={scenario.id === item.id} onClick={() => loadScenario(item.id)}><span className="scenario-index">0{index + 1}</span>{item.name}</button>)}
          </div>
          <div className="control-bar">
            <p>{scenario.description}</p>
            <div className="control-actions">
              <label className="capacity-control">PARALLEL SLOTS <input type="number" min="1" max="20" aria-label="Parallel slots" value={capacity}
                onChange={event => { setCapacity(Number(event.target.value)); setStale(true) }} /></label>
              {scenario.affinityGroups && <label className="affinity-control"><span>Prefer shared context</span><input type="checkbox" role="switch" aria-label="Prefer shared context" checked={affinityOn}
                onChange={event => { setAffinityOn(event.target.checked); setStale(true) }} /><span className="toggle-track" aria-hidden="true" /></label>}
              <button type="button" className="button button-muted" onClick={() => loadScenario(scenario.id)}>Reset scenario</button>
              <button type="button" className="button button-primary propose-button" onClick={propose}>Propose batch <span>↗</span></button>
            </div>
          </div>
          <div className="workspace-grid">
            <section className="tasks-panel" aria-label="Tasks in this scenario">
              <div className="panel-heading"><div><div className="eyebrow">INPUT SNAPSHOT</div><h2>Work queue</h2></div><span className="task-total">{tasks.length} tasks</span></div>
              {highlightedFile && <div className="file-alert">Shared file: <strong>{highlightedFile}</strong><button type="button" onClick={() => setHighlightedFile(null)} aria-label="Clear highlighted file">×</button></div>}
              <div className="task-grid">{tasks.map(task => <TaskCard key={task.id} task={task} selected={selected.has(task.id)} highlightedFile={highlightedFile} onStateChange={changeState} />)}</div>
            </section>
            <BatchResults result={result} tasks={tasks} capacity={capacity} stale={stale}
              onComplete={id => changeState(id, 'SUCCEEDED')} onInspect={setHighlightedFile} />
          </div>
          <div className="workspace-footnote"><span className="footnote-icon">i</span><span>Scheduling produces a proposal from a snapshot. It does not execute tasks, launch agents, or reserve files. Completing a task here updates only this browser session.</span></div>
        </div>
      </section>

      <section className="explainer page-wrap" id="how-it-works">
        <div className="eyebrow">UNDER THE HOOD <span className="section-dash" /></div><h2>A smarter path from issue to action.</h2>
        <div className="explainer-steps"><article><span>01 / DECLARE</span><h3>Write an issue</h3><p>Each real Markdown issue declares its target files, priority, status and prerequisites.</p></article><article><span>02 / PLAN</span><h3>Ground the work</h3><p>The existing planner can gather code evidence and request an ACP agent’s elaboration and review.</p></article><article><span>03 / SCHEDULE</span><h3>Find a safe batch</h3><p>The Bob-built Kotlin scheduler selects compatible work and explains each deferral.</p></article></div>
        <div className="explain-bottom"><div><span className="eyebrow">A NOTE ON CACHE AFFINITY</span><p>Related eligible work can be preferred for the last open slot. This creates an opportunity to reuse context — actual time or cost savings depend on the agents and workload.</p></div><a href={presentationUrl} target="_blank" rel="noopener noreferrer">Get the full presentation ↗</a></div>
      </section>
    </main>
    <footer className="site-footer"><div className="page-wrap"><span><b>BOB / TASKER</b> · Parallel work, without the collisions.</span><span>Built with IBM Bob <span aria-hidden="true">✦</span> <a href={sourceUrl} target="_blank" rel="noopener noreferrer">View the source ↗</a></span></div></footer>
  </div>
}
