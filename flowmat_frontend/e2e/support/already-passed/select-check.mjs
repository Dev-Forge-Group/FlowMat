// node --test e2e/support/already-passed/select-check.mjs
// What select.mjs must never do is leave out a spec that did not pass against the code in front of it.
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { after, beforeEach, test } from 'node:test'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '../../..')
const select = join(here, 'select.mjs')
const spec = 'e2e/auth.spec.ts'
const other = 'e2e/count-plans.spec.ts'
// A source file the spec "loaded", inside the frontend folder so the fingerprint reads it.
const moduleDir = join(here, '.check-module')
const module = 'e2e/support/already-passed/.check-module/screen.ts'

let work
beforeEach(() => {
  work = mkdtempSync(join(tmpdir(), 'already-passed-'))
  mkdirSync(moduleDir, { recursive: true })
  writeFileSync(join(root, module), 'export const screen = 1\n')
  writeFileSync(join(work, 'chunks.json'), JSON.stringify({ files: { 'assets/screen-1.js': [module], 'assets/index-1.js': [] } }))
})
after(() => rmSync(moduleDir, { recursive: true, force: true }))

function run(command, ...args) {
  return execFileSync(process.execPath, [select, command, ...args], { cwd: root, encoding: 'utf8' })
}

/** One Playwright run: the tests it planned, how each ended, and what the preview server served meanwhile. */
function playwrightRun(name, { results, requests = [], ended = true }) {
  const lines = [{ type: 'begin', run: name, tests: results.map(({ file, id }) => ({ file, id })) },
    ...results.map((result) => ({ type: 'result', run: name, expected: 'passed', retry: 0, status: 'passed', ...result })),
    ...(ended ? [{ type: 'end', run: name, status: 'passed' }] : [])]
  writeFileSync(join(work, `${name}.jsonl`), lines.map((line) => JSON.stringify(line)).join('\n'))
  writeFileSync(join(work, `${name}.log`), requests.map(([time, path]) => `${time} ${path}`).join('\n'))
}

function record(name, { records = [], extra = 'x', out = 'records.json' } = {}) {
  run('record', '--scope', 'mock', '--extra', extra, ...records.flatMap((file) => ['--records', join(work, file)]),
    '--chunks', join(work, 'chunks.json'), '--assets', join(work, `${name}.log`), '--tests', join(work, `${name}.jsonl`),
    '--out', join(work, out))
}

function skipped({ records = ['records.json'], extra = 'x', shard } = {}) {
  run('plan', '--scope', 'mock', '--extra', extra, ...(shard ? ['--shard', shard] : []),
    ...records.flatMap((file) => ['--records', join(work, file)]), '--out', join(work, 'skip.json'))
  return JSON.parse(readFileSync(join(work, 'skip.json'), 'utf8')).skip
}

const passing = { results: [{ file: spec, id: 'a', start: 1000, end: 2000 }], requests: [[1500, '/assets/screen-1.js']] }

test('a spec that passed is left out while its code is the same', () => {
  playwrightRun('one', passing)
  record('one')
  assert.deepEqual(skipped(), [spec])
})

test('a change to a file the spec loaded, or to the workflow, runs it again', () => {
  playwrightRun('one', passing)
  record('one')
  assert.deepEqual(skipped({ extra: 'changed workflow' }), [])
  writeFileSync(join(root, module), 'export const screen = 2\n')
  assert.deepEqual(skipped(), [])
})

test('a spec that failed, or passed only on a retry, runs again', () => {
  playwrightRun('failed', { ...passing, results: [{ ...passing.results[0], status: 'failed' }] })
  record('failed')
  assert.deepEqual(skipped(), [])
  playwrightRun('flaky', { ...passing, results: [{ ...passing.results[0], status: 'failed' },
    { ...passing.results[0], retry: 1, start: 2100, end: 3000 }] })
  record('flaky')
  assert.deepEqual(skipped(), [])
})

test('a spec is left out only when all of its tests passed in a run that finished', () => {
  playwrightRun('partial', { results: [...passing.results, { file: spec, id: 'b', start: 2100, end: 2500, status: 'failed' }],
    requests: passing.requests })
  record('partial')
  assert.deepEqual(skipped(), [])
  playwrightRun('crashed', { ...passing, ended: false })
  record('crashed')
  assert.deepEqual(skipped(), [])
})

test('a file the browser fetched that the build did not list runs the spec again', () => {
  playwrightRun('untraced', { ...passing, requests: [[1500, '/assets/unknown-9.js']] })
  record('untraced')
  assert.deepEqual(skipped(), [])
})

test('a file fetched between two tests counts for both', () => {
  playwrightRun('between', { results: [{ file: other, id: 'a', start: 1000, end: 2000 }, { file: spec, id: 'b', start: 9000, end: 9500 }],
    requests: [[5000, '/assets/screen-1.js']] })
  record('between')
  assert.deepEqual(skipped(), [spec, other].sort())
  writeFileSync(join(root, module), 'export const screen = 3\n')
  assert.deepEqual(skipped(), [])
})

test('the newest record of a spec wins across shards, so a later failure is not hidden by an older pass', async () => {
  playwrightRun('old', passing)
  record('old', { out: 'shard-1.json' })
  await new Promise((done) => setTimeout(done, 5))
  playwrightRun('new', { ...passing, results: [{ ...passing.results[0], status: 'failed' }] })
  record('new', { out: 'shard-2.json' })
  assert.deepEqual(skipped({ records: ['shard-1.json', 'shard-2.json'] }), [])
  assert.deepEqual(skipped({ records: ['shard-2.json', 'shard-1.json'] }), [])
})

test('shards split the spec files by name: each file in exactly one, whatever has passed', () => {
  playwrightRun('one', passing)
  record('one')
  const all = JSON.parse(execFileSync('node', ['-e', "console.log(JSON.stringify(require('fs').readdirSync('e2e').filter((n) => n.endsWith('.spec.ts')).map((n) => 'e2e/' + n)))"], { cwd: root, encoding: 'utf8' }))
  for (const records of [[], ['records.json']]) {
    const runs = ['1/3', '2/3', '3/3'].map((shard) => {
      const skip = new Set(skipped({ records, shard }))
      return all.filter((file) => !skip.has(file))
    })
    const ran = runs.flat()
    assert.equal(new Set(ran).size, ran.length, 'no file in two shards')
    assert.deepEqual([...ran, ...(records.length ? [spec] : [])].sort(), [...all].sort())
  }
})
