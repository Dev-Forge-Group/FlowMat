import { describe, expect, it } from 'vitest'
import type { CanvasEdgeViewModel, CanvasNodeViewModel } from '../../../entities/workflow/model/types'
import { findProcesses, neighbourhood, reach, readingGraph, route, type ReadingGraph } from './graphReading'

const graph = (edges: [string, string, string][], nodeIds?: string[]): ReadingGraph => ({
  nodeIds: nodeIds ?? [...new Set(edges.flatMap(([, from, to]) => [from, to]))],
  edges: edges.map(([id, from, to]) => ({ id, from, to })),
})

// mix -> bake -> cool -> pack, bake -> inspect -> pack (a split that joins again), and a separate wash -> dry.
const bakery = graph([
  ['c1', 'mix', 'bake'], ['c2', 'bake', 'cool'], ['c3', 'cool', 'pack'], ['c4', 'bake', 'inspect'], ['c5', 'inspect', 'pack'],
  ['c6', 'wash', 'dry'],
])

describe('readingGraph', () => {
  it('takes processes and their saved connections, leaving out connections to processes that are gone', () => {
    const node = (processId: string) => ({ processId }) as CanvasNodeViewModel
    const edge = (connectionId: string, fromProcessId: string, toProcessId: string) =>
      ({ connectionId, fromProcessId, toProcessId }) as CanvasEdgeViewModel
    expect(readingGraph({ nodes: [node('a'), node('b')], edges: [edge('ab', 'a', 'b'), edge('ax', 'a', 'gone')] })).toEqual({
      nodeIds: ['a', 'b'], edges: [{ id: 'ab', from: 'a', to: 'b' }],
    })
  })
})

describe('neighbourhood', () => {
  it('is the process, its direct neighbours either way and those connections', () => {
    expect(neighbourhood(bakery, 'bake')).toEqual({ nodeIds: ['bake', 'mix', 'cool', 'inspect'], edgeIds: ['c1', 'c2', 'c4'] })
    expect(neighbourhood(bakery, 'nowhere')).toEqual({ nodeIds: [], edgeIds: [] })
  })
})

describe('reach', () => {
  it('follows every connection downstream, with the steps from the origin', () => {
    const after = reach(bakery, 'bake', 'downstream')!
    expect(after.nodeIds).toEqual(['bake', 'cool', 'inspect', 'pack'])
    expect(after.edgeIds).toEqual(['c2', 'c3', 'c4', 'c5'])
    expect(after.depth).toEqual({ bake: 0, cool: 1, inspect: 1, pack: 2 })
    expect(after.maxHops).toBe(2)
  })

  it('follows every connection upstream, through both branches of a join', () => {
    const before = reach(bakery, 'pack', 'upstream')!
    expect(before.nodeIds).toEqual(['pack', 'cool', 'inspect', 'bake', 'mix'])
    expect(before.edgeIds).toEqual(['c1', 'c2', 'c3', 'c4', 'c5'])
    expect(before.maxHops).toBe(3)
  })

  it('stops at a loop, never leaves its part of the graph, and knows only processes in the graph', () => {
    const loop = graph([['ab', 'a', 'b'], ['ba', 'b', 'a'], ['aa', 'a', 'a']])
    expect(reach(loop, 'a', 'downstream')).toMatchObject({ nodeIds: ['a', 'b'], edgeIds: ['ab'], maxHops: 1 })
    expect(reach(bakery, 'wash', 'downstream')!.nodeIds).toEqual(['wash', 'dry'])
    expect(reach(bakery, 'pack', 'downstream')).toMatchObject({ nodeIds: ['pack'], edgeIds: [], maxHops: 0 })
    expect(reach(bakery, 'nowhere', 'upstream')).toBeNull()
  })
})

describe('route', () => {
  it('is the first way found along the saved directions', () => {
    expect(route(bakery, 'mix', 'pack')).toEqual({ nodeIds: ['mix', 'bake', 'cool', 'pack'], edgeIds: ['c1', 'c2', 'c3'], hops: 3 })
  })

  it('never goes against a connection, nor between separate parts of the graph', () => {
    expect(route(bakery, 'pack', 'mix')).toBeNull()
    expect(route(bakery, 'mix', 'dry')).toBeNull()
  })

  it('needs two different known processes and ignores a connection to itself', () => {
    expect(route(bakery, 'mix', 'mix')).toBeNull()
    expect(route(bakery, 'mix', 'nowhere')).toBeNull()
    expect(route(graph([['aa', 'a', 'a'], ['ab', 'a', 'b']]), 'a', 'b')).toEqual({ nodeIds: ['a', 'b'], edgeIds: ['ab'], hops: 1 })
  })
})

describe('findProcesses', () => {
  const node = (processId: string, name: string, processType = 'operation') =>
    ({ processId, name, processType, nodeType: 'process' }) as CanvasNodeViewModel
  const nodes = [node('p1', 'Final bake'), node('p2', 'Bake'), node('p3', '반죽 혼합'), node('p4', 'Check', 'bake-inspection')]

  it('puts names starting with the query first, then names holding it, then types or ids', () => {
    expect(findProcesses(nodes, '  BAKE ').map((one) => one.processId)).toEqual(['p2', 'p1', 'p4'])
    expect(findProcesses(nodes, 'p3').map((one) => one.processId)).toEqual(['p3'])
  })

  it('finds Korean names as written, and nothing for an empty query or beyond the limit', () => {
    expect(findProcesses(nodes, '혼합').map((one) => one.processId)).toEqual(['p3'])
    expect(findProcesses(nodes, '   ')).toEqual([])
    expect(findProcesses(nodes, 'p', 2)).toHaveLength(2)
  })
})
