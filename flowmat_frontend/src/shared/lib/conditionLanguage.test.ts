import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { beforeAll, describe, expect, it } from 'vitest'
import { conditionChecker, declaredAttributes, type ConditionChecker } from './conditionLanguage'

/**
 * The editor's checker against the condition language's shared fixtures (compute/condition-expression/fixtures), which
 * record what the backend's Java ConditionExpression answers: every case must come out the same through this wrapper
 * and the committed WebAssembly module the editor ships.
 */
const fixtures = fileURLToPath(new URL('../../../../compute/condition-expression/fixtures/', import.meta.url))
// The editor's module: the flow kernel, which links the condition language in and exports it.
const wasm = fileURLToPath(new URL('../wasm/flowmat_flow_kernel.wasm', import.meta.url))

/** JSON with each number kept as written, so 12345678901234567890.123 reaches the module exactly. */
function read(name: string): any {
  const raw = (JSON as unknown as { rawJSON(text: string): unknown }).rawJSON
  const reviver = (_key: string, value: unknown, context?: { source?: string }) =>
    (typeof value === 'number' && context?.source ? raw(context.source) : value)
  return JSON.parse(readFileSync(fixtures + name, 'utf8'), reviver as never)
}

let checker: ConditionChecker

beforeAll(async () => {
  const { instance } = await WebAssembly.instantiate(readFileSync(wasm))
  checker = conditionChecker(instance.exports)
})

function outcome(source: string, declared: string[] | undefined, snapshots: string[]) {
  const report = checker.check(source, declared)
  if (!report.ok) {
    expect(report.message).toBe(`Invalid condition at position ${report.position}: ${report.reason}`)
    return { source, error: { position: report.position, reason: report.reason } }
  }
  const results = snapshots.map((snapshot) => (checker.evaluate(source, snapshot) ? '1' : '0')).join('')
  return { source, attributes: report.attributes, results, ...(declared === undefined ? {} : { declaredError: report.declaredError }) }
}

describe('the condition checker in the editor', () => {
  it('answers the hand-picked cases as Java does', () => {
    const input = read('cases.json')
    const expected = read('cases.expected.json')
    const snapshots = input.snapshots.map((entry: { snapshot: unknown }) => JSON.stringify(entry.snapshot))
    const answered = input.cases.map((entry: { source: string; declared?: string[] }) => outcome(entry.source, entry.declared, snapshots))
    expect(JSON.stringify(answered)).toBe(JSON.stringify(expected.outcomes))
  })

  it('answers the generated cases as Java does', () => {
    const corpus = read('generated.json')
    const snapshots = corpus.snapshots.map((snapshot: unknown) => JSON.stringify(snapshot))
    const answered = corpus.cases.map((entry: { source: string }) => outcome(entry.source, undefined, snapshots))
    expect(answered.length).toBe(1500)
    expect(JSON.stringify(answered)).toBe(JSON.stringify(corpus.cases))
  })

  it('reads the names a draft schema declares, and none from no schema', () => {
    expect(declaredAttributes('{"type":"object","properties":{"grade":{"type":"string"},"weight":{"type":"number"}}}'))
      .toEqual(['grade', 'weight'])
    expect(declaredAttributes('{"type":"object"}')).toEqual([])
    expect(declaredAttributes('')).toBeNull()
    expect(declaredAttributes('{"type":')).toBeNull()
    expect(declaredAttributes(null)).toBeNull()
    expect(declaredAttributes({ type: 'object', properties: { a: { type: 'number' } } })).toEqual(['a'])
  })
})
