// Runs the flow kernel's fixtures through the WebAssembly build, across the boundary the editor uses: UTF-8 JSON in,
// JSON out. Every answer must be the one Java recorded. Usage: node wasm/check-fixtures.mjs [path/to/module.wasm]
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const module = process.argv[2] ?? join(root, 'target/wasm32-unknown-unknown/release/flowmat_flow_kernel.wasm')
const { instance } = await WebAssembly.instantiate(readFileSync(module))
const { memory, alloc, dealloc, validate, route, walk } = instance.exports
const encoder = new TextEncoder()
const decoder = new TextDecoder()

/** Sends a request as JSON text and reads, then frees, the JSON answer. */
function call(exported, request) {
  const bytes = encoder.encode(JSON.stringify(request))
  const pointer = alloc(bytes.length)
  new Uint8Array(memory.buffer, pointer, bytes.length).set(bytes)
  const packed = exported(pointer, bytes.length)
  dealloc(pointer, bytes.length)
  const at = Number(packed >> 32n)
  const length = Number(packed & 0xffffffffn)
  const text = decoder.decode(new Uint8Array(memory.buffer, at, length))
  dealloc(at, length)
  return JSON.parse(text, keepNumbers)
}

/** Each number as its text, so 2.50 stays 2.50 on the way in and is compared as written on the way out. */
function keepNumbers(key, value, context) {
  return typeof value === 'number' ? JSON.rawJSON(context.source) : value
}

const read = (name) => JSON.parse(readFileSync(join(root, 'fixtures', name), 'utf8'), keepNumbers)

/** JSON text with object keys in order, so two answers compare by content. */
function canonical(value) {
  if (JSON.isRawJSON(value)) return JSON.stringify(value)
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`
  if (value !== null && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`
  }
  return JSON.stringify(value)
}

function compare(name, cases, answer) {
  const differences = []
  for (const entry of cases) {
    const expected = canonical(entry.expected)
    const actual = canonical(answer(entry))
    if (expected !== actual) differences.push(`${entry.id}\n  Java ${expected}\n  WASM ${actual}`)
  }
  if (differences.length > 0) {
    console.error(`${name}: ${differences.length} of ${cases.length} graphs differ from Java\n${differences.join('\n')}`)
    process.exitCode = 1
  } else {
    console.log(`${name}: ${cases.length} graphs as Java answers them`)
  }
}

const validation = read('validation.json')
compare('validation.json', validation.cases, (entry) =>
  call(validate, { graph: entry.graph, knownUnits: validation.context.knownUnits }))

const routing = read('routing.json')
compare('routing.json', routing.cases, (entry) =>
  call(route, { graph: entry.graph, probes: entry.probes, schemaVersion: routing.context.schemaVersion }))

const walks = read('walk.json')
compare('walk.json', walks.cases, (entry) =>
  call(walk, { graph: entry.graph, input: entry.input, schemaVersion: walks.context.schemaVersion }))
