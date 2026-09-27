import { execFileSync } from 'node:child_process'
import { mkdtempSync, rmSync, writeFileSync, existsSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'
import { selectBatch } from '../src/scheduler/selectBatch.ts'
import { scenarios } from '../src/scenarios/index.ts'

const demo = resolve(fileURLToPath(import.meta.url), '../..')
const repository = resolve(demo, '..')
const sample = join(repository, 'examples/name-validation')
const cli = join(repository, 'planner-app/build/install/planner-app/bin/planner-app')
if (!existsSync(cli)) throw new Error('Build the Kotlin CLI first: ./gradlew :planner-app:installDist')

const directory = mkdtempSync(join(tmpdir(), 'bob-scheduler-parity-'))
let cases = 0
function runCli(args) {
  try {
    return JSON.parse(execFileSync(cli, ['schedule', ...args], { cwd: sample, encoding: 'utf8' }))
  } catch (error) {
    if (error.status === 1 && error.stderr) return JSON.parse(error.stderr.toString()) // CLI prints invalid snapshots to stderr
    throw error
  }
}
function compare(label, input, actual) {
  const expected = selectBatch(input)
  const { fixture: _fixture, ...fromCli } = actual
  if (JSON.stringify(expected) !== JSON.stringify(fromCli)) {
    throw new Error(`${label} diverges from Kotlin\nBrowser: ${JSON.stringify(expected)}\nKotlin:  ${JSON.stringify(fromCli)}`)
  }
  cases++
  console.log(`PASS ${label}`)
}
function fixture(label, input) {
  const file = join(directory, `case-${cases}.json`)
  writeFileSync(file, JSON.stringify({
    capacity: input.capacity,
    affinityGroups: input.affinityGroups ?? [],
    tasks: input.tasks.map(({ id, priority, state, dependencyIds, target_files }) =>
      ({ id, priority, state, dependencyIds, target_files })),
  }))
  compare(label, input, runCli(['--fixture', file]))
}

try {
  const backlog = scenarios[0]
  // The CLI accepts each issue path after its own --issues flag.
  const paths = backlog.tasks.flatMap(task => ['--issues', `docs/internals/backlog/${task.id}.md`])
  for (const capacity of [1, 2, 4, 6, 11]) {
    compare(`real Markdown backlog / capacity ${capacity}`, { ...backlog, capacity }, runCli(['--capacity', String(capacity), ...paths]))
  }
  for (const scenario of scenarios.slice(1)) fixture(scenario.name, scenario)
  const dependency = scenarios[2]
  fixture('dependency succeeds and unlocks follow-up', {
    ...dependency, tasks: dependency.tasks.map(task => task.id === 'validator' ? { ...task, state: 'SUCCEEDED' } : task),
  })
  const affinity = scenarios[3]
  fixture('affinity disabled', { ...affinity, affinityGroups: [] })
  fixture('affinity with spare capacity', { ...affinity, capacity: 3 })
  fixture('invalid cyclic snapshot', { tasks: [
    { id: 'a', title: 'a', priority: 1, state: 'PENDING', dependencyIds: ['b'], target_files: ['a.kt'] },
    { id: 'b', title: 'b', priority: 1, state: 'PENDING', dependencyIds: ['a'], target_files: ['b.kt'] },
  ], capacity: 2 })
  console.log(`${cases} browser/Kotlin parity cases matched.`)
} finally {
  rmSync(directory, { recursive: true, force: true })
}
