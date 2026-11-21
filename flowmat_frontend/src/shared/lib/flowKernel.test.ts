import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { beforeAll, describe, expect, it } from 'vitest'
import { flowKernel, type FlowKernel } from './flowKernel'

/**
 * The editor's flow kernel against the fixtures Java writes (compute/flow-kernel/fixtures): every validation answer and
 * every routing decision must come out the same through this wrapper and the committed WebAssembly module.
 */
const fixtures = fileURLToPath(new URL('../../../../compute/flow-kernel/fixtures/', import.meta.url))
const wasm = fileURLToPath(new URL('../wasm/flowmat_flow_kernel.wasm', import.meta.url))
const read = (name: string): any => JSON.parse(readFileSync(fixtures + name, 'utf8'))

let kernel: FlowKernel

beforeAll(async () => {
  const { instance } = await WebAssembly.instantiate(readFileSync(wasm))
  kernel = flowKernel(instance.exports)
})

describe('the flow kernel in the editor', () => {
  it('checks every recorded graph as Java does', () => {
    const file = read('validation.json')
    expect(file.cases.length).toBeGreaterThan(100)
    for (const entry of file.cases) {
      expect(kernel.validate(entry.graph, file.context.knownUnits), entry.id).toEqual(entry.expected)
    }
  })

  it('tries every recorded flow as the server ran it', () => {
    const file = read('walk.json')
    expect(file.cases.length).toBeGreaterThan(50)
    for (const entry of file.cases) {
      expect(kernel.walk(entry.graph, entry.input, file.context.schemaVersion), entry.id).toEqual(entry.expected)
    }
  })

  it('routes every recorded graph as Java does', () => {
    const file = read('routing.json')
    expect(file.cases.length).toBeGreaterThan(100)
    for (const entry of file.cases) {
      expect(kernel.route(entry.graph, entry.probes, file.context.schemaVersion), entry.id).toEqual(entry.expected)
    }
  })
})
