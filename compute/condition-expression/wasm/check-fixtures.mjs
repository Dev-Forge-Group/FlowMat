// Runs the shared fixtures through the WebAssembly build, across the boundary an editor would use: UTF-8 in, JSON out.
// Every answer must be the one Java recorded. Usage: node wasm/check-fixtures.mjs [path/to/flowmat_condition.wasm]
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const module = process.argv[2] ?? join(root, 'target/wasm32-unknown-unknown/release/flowmat_condition.wasm')
const { instance } = await WebAssembly.instantiate(readFileSync(module))
const { memory, alloc, dealloc, check, evaluate } = instance.exports
const encoder = new TextEncoder()
const decoder = new TextDecoder()

/** Copies text into the module's memory; the caller frees it. */
function put(text) {
  const bytes = encoder.encode(text)
  const pointer = alloc(bytes.length)
  new Uint8Array(memory.buffer, pointer, bytes.length).set(bytes)
  return [pointer, bytes.length]
}

function free(...pieces) {
  for (const [pointer, length] of pieces) dealloc(pointer, length)
}

/** Reads and frees a result: its pointer in the high 32 bits, its length in the low 32. */
function take(packed) {
  const pointer = Number(packed >> 32n)
  const length = Number(packed & 0xffffffffn)
  const text = decoder.decode(new Uint8Array(memory.buffer, pointer, length))
  dealloc(pointer, length)
  return JSON.parse(text)
}

/** JSON with each number kept as its text, so 12345678901234567890.123 reaches the module as written. */
const read = (name) => JSON.parse(readFileSync(join(root, 'fixtures', name), 'utf8'),
  (key, value, context) => (typeof value === 'number' ? JSON.rawJSON(context.source) : value))

/** What the module answers, in the form of Java's recorded outcomes. */
function outcome(source, declared, snapshots) {
  const sourceText = put(source)
  const declaredText = declared === undefined ? [0, 0] : put(JSON.stringify(declared))
  const report = take(check(...sourceText, ...declaredText))
  free(declaredText)
  if (!report.ok) {
    free(sourceText)
    if (report.message !== `Invalid condition at position ${report.position}: ${report.reason}`) {
      throw new Error(`unexpected message ${report.message}`)
    }
    return { source, error: { position: report.position, reason: report.reason } }
  }
  let results = ''
  for (const snapshot of snapshots) {
    const answer = evaluate(...sourceText, ...snapshot)
    if (answer < 0) throw new Error(`evaluate answered ${answer} for ${source}`)
    results += String(answer)
  }
  free(sourceText)
  const answered = { source, attributes: report.attributes, results }
  if (declared !== undefined) answered.declaredError = report.declaredError
  return answered
}

function compare(name, recorded, answered) {
  const differences = []
  recorded.forEach((entry, index) => {
    const expected = JSON.stringify(entry)
    const actual = JSON.stringify(answered[index])
    if (expected !== actual) differences.push(`#${index}\n  Java ${expected}\n  WASM ${actual}`)
  })
  if (recorded.length !== answered.length || differences.length > 0) {
    console.error(`${name}: ${differences.length} of ${recorded.length} cases differ from Java\n${differences.join('\n')}`)
    process.exitCode = 1
  } else {
    console.log(`${name}: ${recorded.length} cases as Java answers them`)
  }
}

const cases = read('cases.json')
const expected = read('cases.expected.json')
const handPicked = cases.snapshots.map((entry) => put(JSON.stringify(entry.snapshot)))
compare('cases.expected.json', expected.outcomes,
  cases.cases.map((entry) => outcome(entry.source, entry.declared, handPicked)))
free(...handPicked)

const generated = read('generated.json')
const corpus = generated.snapshots.map((snapshot) => put(JSON.stringify(snapshot)))
compare('generated.json', generated.cases, generated.cases.map((entry) => outcome(entry.source, undefined, corpus)))
free(...corpus)
console.log(`module ${readFileSync(module).length} bytes`)
