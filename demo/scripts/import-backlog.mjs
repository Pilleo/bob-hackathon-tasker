import { readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { resolve, basename, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import YAML from 'yaml'

const demo = resolve(fileURLToPath(import.meta.url), '../..')
const backlog = resolve(demo, '../examples/name-validation/docs/internals/backlog')
const output = resolve(demo, 'src/scenarios/backlog.generated.ts')
const states = { open: 'PENDING', pending: 'PENDING', draft: 'PENDING', in_progress: 'RUNNING', running: 'RUNNING', resolved: 'SUCCEEDED', succeeded: 'SUCCEEDED', failed: 'FAILED', blocked: 'BLOCKED' }

const files = readdirSync(backlog).filter(file => /^issue-.*\.md$/.test(file)).sort()
if (!files.length) throw new Error(`No issue documents found in ${backlog}`)
const tasks = files.map(file => {
  const raw = readFileSync(join(backlog, file), 'utf8')
  const match = /^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/.exec(raw)
  if (!match) throw new Error(`${file}: missing YAML frontmatter`)
  const doc = YAML.parseDocument(match[1], { uniqueKeys: true })
  if (doc.errors.length) throw new Error(`${file}: ${doc.errors.map(error => error.message).join('; ')}`)
  const data = doc.toJS()
  if (data.schema_version !== 2 || data.document_type !== 'issue' || data.id !== basename(file, '.md')) {
    throw new Error(`${file}: expected schema-v2 issue with a matching filename and ID`)
  }
  if (!Number.isInteger(data.priority) || typeof data.title !== 'string' || !data.title.trim()) {
    throw new Error(`${file}: priority and title must be valid`)
  }
  const state = states[String(data.status).toLowerCase()]
  if (!state) throw new Error(`${file}: unknown status ${data.status}`)
  if (!Array.isArray(data.target_files) || !data.target_files.length ||
      !data.target_files.every(path => typeof path === 'string' && path.trim() &&
        !path.startsWith('/') && !path.includes('\\') && !path.split('/').includes('..'))) {
    throw new Error(`${file}: nonempty repository-relative target_files required`)
  }
  if (!Array.isArray(data.dependencies) || !data.dependencies.every(dep => typeof dep === 'string')) {
    throw new Error(`${file}: dependencies must be a list of issue IDs`)
  }
  return { id: data.id, title: data.title, priority: data.priority, state,
    dependencyIds: data.dependencies, target_files: data.target_files }
})

writeFileSync(output,
  `// Generated from the sample Markdown issues by npm run import:backlog. Do not hand-edit.\n` +
  `import type { Task } from '../scheduler/types'\n\n` +
  `export const backlogTasks: Task[] = ${JSON.stringify(tasks, null, 2)}\n`)
console.log(`Imported ${tasks.length} real backlog issues into ${output}`)
