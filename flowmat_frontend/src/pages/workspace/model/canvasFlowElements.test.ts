import { describe, expect, it } from 'vitest'
import type { CanvasEdgeViewModel, CanvasNodeViewModel } from '../../../entities/workflow/model/types'
import { connectionPayload, isNewConnection, toRfEdges, toRfProcessNodes } from './canvasFlowElements'

describe('the canvas as React Flow holds it', () => {
  it('keeps each process\'s place, size and selection, with the view model as data', () => {
    const mix = { id: 'mix', position: { x: 10, y: 20 }, size: { width: 160, height: 90 } } as unknown as CanvasNodeViewModel
    expect(toRfProcessNodes([mix], 'mix')).toEqual([
      { id: 'mix', type: 'flowmatNode', position: { x: 10, y: 20 }, width: 160, height: 90, selected: true, data: mix },
    ])
  })

  it('keeps each connection\'s ends and handles as saved', () => {
    const line = { id: 'c1', source: 'mix', target: 'bake', sourceHandle: 'io-out', targetHandle: null } as unknown as CanvasEdgeViewModel
    expect(toRfEdges([line], null)).toEqual([
      { id: 'c1', type: 'flowmatEdge', source: 'mix', target: 'bake', sourceHandle: 'io-out', targetHandle: null,
        selected: false, data: line },
    ])
  })
})

describe('connectionPayload', () => {
  it('names the ports at the handles, and the default handles where there was none', () => {
    expect(connectionPayload({ source: 'mix', target: 'bake', sourceHandle: 'io-out', targetHandle: null })).toEqual({
      fromProcessId: 'mix', toProcessId: 'bake', fromIoId: 'io-out', toIoId: null,
      sourceHandle: 'io-out', targetHandle: 'in-default',
    })
    expect(connectionPayload({ source: 'mix', target: 'bake' })).toMatchObject({ sourceHandle: 'out-default', fromIoId: null })
  })

  it('has nothing while an end is missing', () => {
    expect(connectionPayload({ source: 'mix', target: null })).toBeNull()
  })
})

describe('isNewConnection', () => {
  const edges = [{ source: 'mix', target: 'bake', sourceHandle: 'io-out', targetHandle: null }]

  it('refuses a process to itself and the same ends and handles twice', () => {
    expect(isNewConnection({ source: 'mix', target: 'mix' }, [])).toBe(false)
    expect(isNewConnection({ source: 'mix', target: 'bake', sourceHandle: 'io-out' }, edges)).toBe(false)
  })

  it('allows the same processes through other handles', () => {
    expect(isNewConnection({ source: 'mix', target: 'bake', sourceHandle: 'io-out-2' }, edges)).toBe(true)
    expect(isNewConnection({ source: 'bake', target: 'mix', sourceHandle: 'io-out' }, edges)).toBe(true)
  })
})
