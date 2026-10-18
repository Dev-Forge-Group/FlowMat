import { describe, expect, it } from 'vitest'
import type { CanvasEdgeViewModel } from '../../../entities/workflow/model/types'
import { connectionUndoInput } from './connectionUndo'

const edge: CanvasEdgeViewModel = {
  id: 'connection-1',
  connectionId: 'connection-1',
  source: 'source',
  target: 'target',
  sourceHandle: 'out-default',
  targetHandle: 'in-default',
  fromProcessId: 'source',
  toProcessId: 'target',
  fromIoId: null,
  toIoId: null,
  connectionType: 'material',
  label: null,
  flowRate: null,
  unit: null,
  delayTimeSec: null,
  lossRate: null,
  priority: null,
  conditionExpr: null,
  capacity: null,
  failurePolicy: 'stop',
  version: 1,
  versionNonce: 1,
}

describe('connectionUndoInput', () => {
  it('puts back a Split gateway\'s share or clears it (ADR-008)', () => {
    expect(connectionUndoInput({ ...edge, splitKind: 'share', splitValue: 70 },
      { connectionId: 'connection-1', splitKind: 'rest' }))
      .toEqual({ connectionId: 'connection-1', splitKind: 'share', splitValue: 70 })
    expect(connectionUndoInput({ ...edge, splitKind: 'rest', splitValue: null },
      { connectionId: 'connection-1', clearSplit: true }))
      .toEqual({ connectionId: 'connection-1', splitKind: 'rest' })
    expect(connectionUndoInput(edge, { connectionId: 'connection-1', splitKind: 'fixed', splitValue: 2 }))
      .toEqual({ connectionId: 'connection-1', clearSplit: true })
  })

  it('puts back whether the connection was its gateway\'s default (ADR-006)', () => {
    expect(connectionUndoInput({ ...edge, defaultRoute: true }, { connectionId: 'connection-1', defaultRoute: false }))
      .toEqual({ connectionId: 'connection-1', defaultRoute: true })
    expect(connectionUndoInput(edge, { connectionId: 'connection-1', defaultRoute: true }))
      .toEqual({ connectionId: 'connection-1', defaultRoute: false })
  })

  it('restores cleared fields and the default values represented by null', () => {
    expect(connectionUndoInput(edge, {
      connectionId: edge.id,
      connectionLabel: 'new label',
      connectionType: 'data_flow',
      flowRate: 5,
      unit: 'kg',
      delayTimeSec: 2,
      lossRate: 0.1,
      priority: 3,
      conditionExpr: 'quantity > 0',
      capacity: 10,
      clearCapacity: false,
      failurePolicy: 'retry',
    })).toEqual({
      connectionId: edge.id,
      connectionLabel: null,
      connectionType: 'material',
      flowRate: null,
      unit: null,
      delayTimeSec: null,
      lossRate: null,
      priority: null,
      conditionExpr: '',
      clearCapacity: true,
      failurePolicy: 'stop',
    })
  })

  it('restores an existing capacity without changing fields omitted from the edit', () => {
    expect(connectionUndoInput({ ...edge, label: 'Original', capacity: 7 }, {
      connectionId: edge.id,
      connectionLabel: null,
      clearCapacity: true,
    })).toEqual({
      connectionId: edge.id,
      connectionLabel: 'Original',
      capacity: 7,
      clearCapacity: false,
    })
  })
})
