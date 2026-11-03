import { describe, expect, it } from 'vitest'
import { reachCounts, readingView } from './canvasReadingModel'
import type { ReadingGraph } from './graphReading'

// mix -> bake -> cool, bake -> pack, and wash alone.
const graph: ReadingGraph = {
  nodeIds: ['mix', 'bake', 'cool', 'pack', 'wash'],
  edges: [{ id: 'c1', from: 'mix', to: 'bake' }, { id: 'c2', from: 'bake', to: 'cool' }, { id: 'c3', from: 'bake', to: 'pack' }],
}
const nameOf = (id: string) => id[0].toUpperCase() + id.slice(1)

describe('readingView', () => {
  it('shows a process with its neighbours', () => {
    const view = readingView(graph, { kind: 'focus', processId: 'bake', reach: null }, nameOf)!
    expect(view.text).toBe('Bake: 3 connected processes, 3 connections')
    expect(view.highlight).toMatchObject({ tone: 'focus', origin: 'bake' })
    expect([...view.highlight!.nodeIds]).toEqual(['bake', 'mix', 'cool', 'pack'])
  })

  it('shows everything after or before a process, and says when there is nothing', () => {
    const after = readingView(graph, { kind: 'focus', processId: 'bake', reach: 'downstream' }, nameOf)!
    expect(after.text).toBe('Downstream of Bake: 2 processes, 2 connections, 1 step')
    expect([...after.highlight!.edgeIds]).toEqual(['c2', 'c3'])
    expect(readingView(graph, { kind: 'focus', processId: 'cool', reach: 'upstream' }, nameOf)!.text)
      .toBe('Upstream of Cool: 2 processes, 2 connections, 2 steps')
    expect(readingView(graph, { kind: 'focus', processId: 'wash', reach: 'downstream' }, nameOf)!.text).toBe('Nothing downstream of Wash.')
  })

  it('shows the way between two processes, or says there is none', () => {
    const way = readingView(graph, { kind: 'route', from: 'mix', to: 'cool' }, nameOf)!
    expect(way.text).toBe('Route Mix → Bake → Cool: 2 connections')
    expect(way.highlight).toMatchObject({ tone: 'route', origin: 'mix' })
    expect(readingView(graph, { kind: 'route', from: 'cool', to: 'mix' }, nameOf))
      .toEqual({ highlight: null, text: 'No route from Cool to Mix along the connections.' })
  })

  it('has nothing for a process that is gone', () => {
    expect(readingView(graph, { kind: 'focus', processId: 'gone', reach: null }, nameOf)).toBeNull()
  })
})

describe('reachCounts', () => {
  it('counts the processes before and after', () => {
    expect(reachCounts(graph, 'bake')).toEqual({ upstream: 1, downstream: 2 })
    expect(reachCounts(graph, 'wash')).toEqual({ upstream: 0, downstream: 0 })
    expect(reachCounts(graph, 'gone')).toEqual({ upstream: 0, downstream: 0 })
  })
})
