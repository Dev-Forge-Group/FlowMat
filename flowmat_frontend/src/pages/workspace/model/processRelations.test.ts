import { describe, expect, it } from 'vitest'
import type { CanvasEdgeViewModel } from '../../../entities/workflow/model/types'
import { processRelations } from './processRelations'

const edge = (connectionId: string, fromProcessId: string, toProcessId: string, label: string | null = null,
  connectionType = 'material_flow') => ({ connectionId, fromProcessId, toProcessId, label, connectionType }) as CanvasEdgeViewModel
const known = new Set(['mix', 'bake', 'cool', 'pack'])

describe('processRelations', () => {
  it('lists what leaves, enters and loops on a process in saved order, by label or else type', () => {
    const edges = [edge('c1', 'mix', 'bake', ' dough '), edge('c2', 'bake', 'cool'), edge('c3', 'bake', 'pack', null, 'control_flow'),
      edge('c4', 'bake', 'bake', 'rework'), edge('c5', 'cool', 'mix'), edge('c6', 'bake', 'gone')]
    expect(processRelations(edges, 'bake', known)).toEqual({
      out: [{ connectionId: 'c2', otherId: 'cool', note: 'material flow' }, { connectionId: 'c3', otherId: 'pack', note: 'control flow' }],
      in: [{ connectionId: 'c1', otherId: 'mix', note: 'dough' }],
      loop: [{ connectionId: 'c4', otherId: 'bake', note: 'rework' }],
    })
  })

  it('has nothing for a process without connections', () => {
    expect(processRelations([edge('c1', 'mix', 'bake')], 'pack', known)).toEqual({ out: [], in: [], loop: [] })
  })
})
