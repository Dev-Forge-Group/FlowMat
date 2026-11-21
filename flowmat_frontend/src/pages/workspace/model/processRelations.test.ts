import { describe, expect, it } from 'vitest'
import type { CanvasEdgeViewModel } from '../../../entities/workflow/model/types'
import { intentAnnouncement, processRelations, relationCount } from './processRelations'

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

describe('the Intent Trace announcement', () => {
  const edges = [edge('c1', 'mix', 'bake'), edge('c2', 'bake', 'cool'), edge('c3', 'bake', 'pack'), edge('c4', 'bake', 'bake')]

  it('counts what leaves, enters and loops, a loop once', () => {
    const relations = processRelations(edges, 'bake', known)
    expect(relationCount(relations)).toBe(4)
    expect(intentAnnouncement('Bake', relations))
      .toBe('Bake. 2 outgoing, 1 incoming, 1 self loop. 4 connections. Press Enter for details.')
  })

  it('leaves the loop out when there is none', () => {
    expect(intentAnnouncement('Mix', processRelations(edges, 'mix', known)))
      .toBe('Mix. 1 outgoing, 0 incoming. 1 connections. Press Enter for details.')
  })
})
