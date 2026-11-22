#!/usr/bin/env node
// Leaves out E2E spec files that already passed against the same code (docs/operations/ci-selection.md).
//
//   plan   --scope S --extra X [--shard K/N] [--full] --records F... --out skip.json
//          skip.json lists the spec files this job leaves out: those of other shards, and (without --full) those whose
//          last run passed and whose inputs are still the same. Shards split the spec files by name alone, so a shard
//          run again after a failure gets the same files and runs the one that failed.
//   record --scope S --extra X --records F... --chunks F... --assets F... --tests F... --out records.json
//          Folds this run into the records: which spec files passed, and which source files each one loaded.
//
// A spec file's inputs are: the spec and the files it imports, the source files of every built file the browser
// fetched while its tests ran, the files that shape every test (package-lock.json, the Playwright and Vite configs,
// index.html), and X (the workflow, and for the real-API run the backend). Change any of them and the spec runs again.
// A spec that failed, passed only on a retry, or could not be traced runs again. Only plain node is needed.
import { createHash } from 'node:crypto'
import { appendFileSync, existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const FORMAT = 'already-passed/1'
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')
const GLOBAL = ['package-lock.json', 'playwright.config.ts', 'vite.config.js', 'index.html',
  'e2e/support/already-passed/reporter.ts', 'e2e/support/already-passed/vitePlugin.js']
// A request this close to a test's start or end still counts as the test's.
const SLACK_MS = 1000
const KEEP_DAYS = 30
const hashes = new Map()

const [command, ...rest] = process.argv.slice(2)
const args = { records: [], chunks: [], assets: [], tests: [] }
for (let index = 0; index < rest.length; index += 1) {
  const name = rest[index].replace(/^--/, '')
  // A flag with no value (--full) is true.
  const value = rest[index + 1] === undefined || rest[index + 1].startsWith('--') ? true : rest[++index]
  if (Array.isArray(args[name])) args[name].push(value)
  else args[name] = value
}
const scope = args.scope ?? 'mock'
const extra = args.extra ?? ''

if (command === 'plan') plan()
else if (command === 'record') record()
else {
  console.error('usage: select.mjs plan|record --scope S --extra X --records F... --out F')
  process.exit(2)
}

function plan() {
  const [shard, shards] = (args.shard ?? '1/1').split('/').map(Number)
  const all = specFiles()
  const mine = all.filter((file) => nameHash(file) % shards === shard - 1)
  const records = args.full ? {} : loadRecords()
  const passed = mine.filter((file) => {
    const entry = records[file]
    return entry?.outcome === 'passed' && fingerprint(file, entry.modules, entry.public) === entry.fingerprint
  })
  const skip = [...all.filter((file) => !mine.includes(file)), ...passed].sort()
  writeJson(args.out, { scope, skip })
  report(shards > 1
    ? `Shard ${shard}/${shards} has ${mine.length} of ${all.length} spec files; ${passed.length} already passed against this code and are left out (${scope}).`
    : `${passed.length} of ${all.length} spec files already passed against this code and are left out (${scope}).`, passed)
}

/** FNV-1a of the file name: which shard a spec file belongs to, whatever has passed. */
function nameHash(file) {
  let hash = 0x811c9dc5
  for (const char of file) hash = Math.imul(hash ^ char.charCodeAt(0), 0x01000193) >>> 0
  return hash
}

function record() {
  const records = loadRecords()
  const files = {}
  for (const path of args.chunks) {
    if (existsSync(path)) Object.assign(files, JSON.parse(readFileSync(path, 'utf8')).files)
  }
  const requests = args.assets.filter(existsSync).flatMap((path) => readFileSync(path, 'utf8').split('\n'))
    .map((line) => line.match(/^(\d+) (\S+)/)).filter(Boolean)
    .map(([, time, url]) => ({ time: Number(time), path: url.replace(/[?#].*$/, '') }))
  const runs = new Map()
  for (const path of args.tests.filter(existsSync)) {
    for (const line of readFileSync(path, 'utf8').split('\n')) {
      if (!line.trim()) continue
      const event = JSON.parse(line)
      const run = runs.get(event.run) ?? { planned: [], results: [], ended: false }
      if (event.type === 'begin') run.planned.push(...event.tests)
      else if (event.type === 'result') run.results.push(event)
      else if (event.type === 'end') run.ended = true
      runs.set(event.run, run)
    }
  }

  const outcome = new Map()
  const windows = new Map()
  for (const run of runs.values()) {
    const byFile = new Map()
    for (const test of run.planned) byFile.set(test.file, [...(byFile.get(test.file) ?? []), test.id])
    const ordered = [...run.results].sort((left, right) => left.start - right.start)
    for (const [file, ids] of byFile) {
      const passed = run.ended && ids.every((id) => {
        const results = run.results.filter((result) => result.id === id)
        const last = results.at(-1)
        // A test that needed a retry is flaky: it runs again next time.
        return last && results.every((result) => result.retry === 0)
          && (last.status === last.expected || last.status === 'skipped')
      })
      outcome.set(file, (outcome.get(file) ?? true) && passed)
    }
    for (const request of requests) {
      const owners = ordered.filter((result) => request.time >= result.start - SLACK_MS && request.time <= result.end + SLACK_MS)
      if (owners.length === 0) {
        // Between tests (a hook, a server start): count it for the tests on both sides.
        const before = ordered.filter((result) => result.end < request.time).at(-1)
        const after = ordered.find((result) => result.start > request.time)
        if (before) owners.push(before)
        if (after) owners.push(after)
      }
      for (const owner of owners) windows.set(owner.file, [...(windows.get(owner.file) ?? []), request.path])
    }
  }

  const now = Date.now()
  let passedCount = 0
  const rerun = []
  for (const [file, passed] of outcome) {
    const modules = new Set()
    const publicFiles = new Set()
    let traced = true
    for (const path of new Set(windows.get(file) ?? [])) {
      if (path.startsWith('/api') || path.startsWith('/ws')) continue
      if (path.startsWith('/assets/')) {
        const sources = files[path.slice(1)]
        if (!sources) traced = false
        else for (const source of sources) modules.add(source)
      } else if (path !== '/' && existsSync(join(root, 'public', path)) && statSync(join(root, 'public', path)).isFile()) {
        publicFiles.add(`public${path}`)
      }
    }
    if (passed && traced) {
      const sortedModules = [...modules].sort()
      const sortedPublic = [...publicFiles].sort()
      records[file] = { outcome: 'passed', at: now, fingerprint: fingerprint(file, sortedModules, sortedPublic),
        modules: sortedModules, public: sortedPublic }
      passedCount += 1
    } else {
      records[file] = { outcome: passed ? 'untraced' : 'failed', at: now }
      rerun.push(file)
    }
  }
  for (const [file, entry] of Object.entries(records)) {
    if (now - entry.at > KEEP_DAYS * 86_400_000) delete records[file]
  }
  writeJson(args.out, { format: FORMAT, scope, files: records })
  report(`Recorded ${passedCount} spec files that passed (${scope}); ${rerun.length} will run again next time.`, rerun)
}

/** The newest entry for each spec file across the given record files (one per shard). */
function loadRecords() {
  const merged = {}
  for (const path of args.records) {
    if (!path || !existsSync(path)) continue
    const data = JSON.parse(readFileSync(path, 'utf8'))
    if (data.format !== FORMAT || data.scope !== scope) continue
    for (const [file, entry] of Object.entries(data.files)) {
      if (!merged[file] || merged[file].at < entry.at) merged[file] = entry
    }
  }
  return merged
}

function fingerprint(spec, modules = [], publicFiles = []) {
  const hash = createHash('sha256')
  const add = (key, value) => hash.update(`${key}\0${value}\n`)
  add('format', FORMAT)
  add('scope', scope)
  add('extra', extra)
  for (const file of GLOBAL) add(file, hashFile(file))
  for (const file of importGraph(spec)) add(file, hashFile(file))
  for (const module of modules) add(module, module === 'node_modules' || module.startsWith('virtual:') ? '' : hashFile(module))
  for (const file of publicFiles) add(file, hashFile(file))
  return hash.digest('hex')
}

function hashFile(file) {
  if (!hashes.has(file)) {
    const path = join(root, file)
    hashes.set(file, existsSync(path) && statSync(path).isFile()
      ? createHash('sha256').update(readFileSync(path)).digest('hex') : 'missing')
  }
  return hashes.get(file)
}

/** The spec and every file it imports by a relative or `@/` path, followed through those files. */
function importGraph(spec) {
  const seen = new Set()
  const queue = [spec]
  while (queue.length > 0) {
    const file = queue.shift()
    if (seen.has(file)) continue
    seen.add(file)
    const path = join(root, file)
    if (!existsSync(path) || !/\.(m?[jt]sx?)$/.test(file)) continue
    const source = readFileSync(path, 'utf8')
    const pattern = /(?:from\s*|import\s*\(\s*|import\s+)['"]([^'"]+)['"]/g
    for (const [, specifier] of source.matchAll(pattern)) {
      const base = specifier.startsWith('@/') ? join(root, 'src', specifier.slice(2))
        : specifier.startsWith('.') ? resolve(dirname(path), specifier) : null
      if (!base) continue
      const found = ['', '.ts', '.tsx', '.js', '.mjs', '/index.ts', '/index.tsx', '/index.js']
        .map((suffix) => `${base}${suffix}`).find((candidate) => existsSync(candidate) && statSync(candidate).isFile())
      queue.push(found ? relative(root, found).split('\\').join('/') : `unresolved:${specifier}`)
    }
  }
  return [...seen].sort()
}

function specFiles() {
  return readdirSync(join(root, 'e2e')).filter((name) => name.endsWith('.spec.ts')).map((name) => `e2e/${name}`).sort()
}

function writeJson(path, value) {
  if (!path) return
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, `${JSON.stringify(value)}\n`)
}

function report(headline, files) {
  console.log(headline)
  for (const file of files) console.log(`  ${file}`)
  if (process.env.GITHUB_STEP_SUMMARY) {
    const list = files.length > 0 ? `\n<details><summary>${files.length} files</summary>\n\n${files.map((file) => `- ${file}`).join('\n')}\n</details>\n` : ''
    appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${headline}\n${list}\n`)
  }
}
