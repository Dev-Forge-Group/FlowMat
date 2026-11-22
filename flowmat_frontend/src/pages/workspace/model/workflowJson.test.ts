import { describe, expect, it } from 'vitest'
import { workflowJson } from './workflowJson'

describe('the workflow as JSON', () => {
  it('writes processes with their ports, connections and annotations, positions rounded', () => {
    const port = { name: 'flour', ioType: 'material', direction: 'input', quantity: '10', unit: 'kg', colorScheme: 'sky' }
    const json = workflowJson({
      workflow: { workflowId: 'wf', workflowName: 'Bread' } as never,
      nodes: [{ id: 'p1', name: 'Mix', nodeType: 'process', processType: 'process', colorScheme: 'slate',
        position: { x: 10.6, y: 20.2 }, size: { width: 150, height: 72 }, description: null, inputs: [port], outputs: [] }] as never,
      edges: [{ id: 'c1', source: 'p1', target: 'p2', connectionType: 'flow', label: null, flowRate: null, unit: 'kg',
        delayTimeSec: 60, lossRate: 0.1, priority: 1 }] as never,
      annotations: [],
    })
    expect(json).toMatchObject({
      version: 1,
      workflow: { id: 'wf', name: 'Bread' },
      nodes: [{ id: 'p1', posX: 11, posY: 20, inputs: [{ name: 'flour', quantity: '10', unit: 'kg' }], outputs: [] }],
      edges: [{ id: 'c1', lossRate: 0.1, delayTimeSec: 60 }],
      annotations: [],
    })
    expect(Number.isNaN(Date.parse(json.exportedAt))).toBe(false)
  })
})
