import { describe, expect, it } from 'vitest'
import {
  connectionPreview, kindCounts, reachCounts, readingTargets, readingView, routeChoices, routePickError, routePickText,
  routeTitle, toggleLensKind,
} from './canvasReadingModel'
import type { ReadingGraph } from './graphReading'

// mix -> bake -> cool, bake -> pack, and wash alone; bake is equipment, cool storage, pack an output, the rest processes.
const graph: ReadingGraph = {
  nodeIds: ['mix', 'bake', 'cool', 'pack', 'wash'],
  edges: [{ id: 'c1', from: 'mix', to: 'bake' }, { id: 'c2', from: 'bake', to: 'cool' }, { id: 'c3', from: 'bake', to: 'pack' }],
  kinds: new Map([['mix', 'process'], ['bake', 'equipment'], ['cool', 'storage'], ['pack', 'output'], ['wash', 'process']]),
}
const nameOf = (id: string) => id[0].toUpperCase() + id.slice(1)

describe('readingView', () => {
  it('shows a process with its neighbours', () => {
    const view = readingView(graph, { kind: 'focus', processId: 'bake', reach: null }, nameOf)!
    expect(view.text).toBe('Bake: 3 connected processes, 3 connections')
    expect(view.highlight).toMatchObject({ tone: 'focus', origin: 'bake' })
    expect([...view.highlight!.nodeIds]).toEqual(['bake', 'mix', 'cool', 'pack'])
    expect(view.steps).toEqual([])
  })

  it('shows everything after or before a process, and says when there is nothing', () => {
    const after = readingView(graph, { kind: 'focus', processId: 'bake', reach: 'downstream' }, nameOf)!
    expect(after.text).toBe('Downstream · 2 processes · 2 connections · max 1 hop')
    expect([...after.highlight!.edgeIds]).toEqual(['c2', 'c3'])
    expect(readingView(graph, { kind: 'focus', processId: 'cool', reach: 'upstream' }, nameOf)!.text)
      .toBe('Upstream · 2 processes · 2 connections · max 2 hops')
    expect(readingView(graph, { kind: 'focus', processId: 'wash', reach: 'downstream' }, nameOf)!.text).toBe('No downstream processes from Wash')
  })

  it('shows the way between two processes step by step, or says there is none', () => {
    const way = readingView(graph, { kind: 'route', from: 'mix', to: 'cool' }, nameOf)!
    expect(way.text).toBe('3 processes · 2 steps · shortest path')
    expect(way.steps).toEqual(['mix', 'bake', 'cool'])
    expect(way.highlight).toMatchObject({ tone: 'route', origin: 'mix' })
    expect(readingView(graph, { kind: 'route', from: 'cool', to: 'mix' }, nameOf))
      .toEqual({ highlight: null, text: 'Mix cannot be reached from Cool along the arrows.', steps: [] })
  })

  it('has nothing for a process that is gone', () => {
    expect(readingView(graph, { kind: 'focus', processId: 'gone', reach: null }, nameOf)).toBeNull()
  })
})

describe('a pinned connection', () => {
  it('shows the connection with its two processes and names them', () => {
    const view = readingView(graph, { kind: 'relation', connectionId: 'c2' }, nameOf)

    expect(view?.text).toBe('Pinned connection from Bake to Cool')
    expect([...view!.highlight!.nodeIds]).toEqual(['bake', 'cool'])
    expect([...view!.highlight!.edgeIds]).toEqual(['c2'])
    expect(view?.highlight?.origin).toBe('bake')
    expect(readingTargets(graph, { kind: 'relation', connectionId: 'c2' })).toEqual(['bake', 'cool'])
  })

  it('names a loop once, and has nothing for a connection that is gone', () => {
    const looping: ReadingGraph = { ...graph, edges: [...graph.edges, { id: 'c9', from: 'wash', to: 'wash' }] }

    expect(readingView(looping, { kind: 'relation', connectionId: 'c9' }, nameOf)?.text)
      .toBe('Pinned connection: Wash loops back to itself')
    expect(readingTargets(looping, { kind: 'relation', connectionId: 'c9' })).toEqual(['wash'])
    expect(readingView(graph, { kind: 'relation', connectionId: 'gone' }, nameOf)).toBeNull()
    expect(readingTargets(graph, { kind: 'relation', connectionId: 'gone' })).toEqual([])
  })
})

describe('reading by kind', () => {
  it('shows one kind with every connection touching it and the other ends nearby', () => {
    const storage = readingView(graph, { kind: 'lens', kinds: ['storage'] }, nameOf)!
    expect(storage.text).toBe('1 Storage process · 1 touching connection · connected peers remain visible')
    expect(storage.highlight).toMatchObject({ tone: 'lens', origin: null })
    expect([...storage.highlight!.edgeIds]).toEqual(['c2'])
    expect([...storage.highlight!.nearIds!]).toEqual(['bake'])
    expect(readingView(graph, { kind: 'lens', kinds: ['equipment'] }, nameOf)!.text)
      .toBe('1 Equipment process · 3 touching connections · connected peers remain visible')
  })

  it('shows two kinds with only the connections between them, counted each way', () => {
    const view = readingView(graph, { kind: 'lens', kinds: ['process', 'equipment'] }, nameOf)!
    expect(view.text).toBe('Process → Equipment: 1 · Equipment → Process: 0 · 1 direct connection')
    expect(readingView(graph, { kind: 'lens', kinds: ['storage', 'equipment'] }, nameOf)!.text)
      .toBe('Storage → Equipment: 0 · Equipment → Storage: 1 · 1 direct connection')
    expect([...view.highlight!.nodeIds]).toEqual(['mix', 'bake', 'wash'])
    expect([...view.highlight!.edgeIds]).toEqual(['c1'])
    expect(view.highlight!.nearIds).toBeUndefined()
  })

  it('says when the canvas has none of a kind', () => {
    expect(readingView(graph, { kind: 'lens', kinds: ['input'] }, nameOf))
      .toEqual({ highlight: null, text: 'No Input processes on this canvas.', steps: [] })
  })

  it('counts the kinds on the canvas in palette order', () => {
    expect(kindCounts(graph)).toEqual([
      { kind: 'process', label: 'Process', count: 2 }, { kind: 'equipment', label: 'Equipment', count: 1 },
      { kind: 'storage', label: 'Storage', count: 1 }, { kind: 'output', label: 'Output', count: 1 },
    ])
  })

  it('turns a kind on and off, and refuses a third', () => {
    expect(toggleLensKind([], 'storage')).toEqual({ kinds: ['storage'], error: null })
    expect(toggleLensKind(['storage', 'output'], 'storage')).toEqual({ kinds: ['output'], error: null })
    expect(toggleLensKind(['storage', 'output'], 'input'))
      .toEqual({ kinds: ['storage', 'output'], error: 'A lens shows one or two kinds; turn one off first.' })
  })
})

describe('readingTargets', () => {
  it('brings into view what is read, or both ends of a route that does not exist', () => {
    expect(readingTargets(graph, { kind: 'lens', kinds: ['storage'] })).toEqual(['cool', 'bake'])
    expect(readingTargets(graph, { kind: 'lens', kinds: ['process', 'equipment'] })).toEqual(['mix', 'bake', 'wash'])
    expect(readingTargets(graph, { kind: 'lens', kinds: ['input'] })).toEqual([])
    expect(readingTargets(graph, { kind: 'focus', processId: 'cool', reach: null })).toEqual(['cool', 'bake'])
    expect(readingTargets(graph, { kind: 'focus', processId: 'bake', reach: 'downstream' })).toEqual(['bake', 'cool', 'pack'])
    expect(readingTargets(graph, { kind: 'route', from: 'mix', to: 'pack' })).toEqual(['mix', 'bake', 'pack'])
    expect(readingTargets(graph, { kind: 'route', from: 'cool', to: 'mix' })).toEqual(['cool', 'mix'])
  })
})

describe('connectionPreview', () => {
  it('shows one connection with its two processes, or nothing for a connection that is gone', () => {
    const shown = connectionPreview(graph, 'c3')!
    expect([...shown.nodeIds]).toEqual(['bake', 'pack'])
    expect([...shown.edgeIds]).toEqual(['c3'])
    expect(connectionPreview(graph, 'gone')).toBeNull()
  })
})

describe('reachCounts', () => {
  it('counts the processes before and after', () => {
    expect(reachCounts(graph, 'bake')).toEqual({ upstream: 1, downstream: 2 })
    expect(reachCounts(graph, 'wash')).toEqual({ upstream: 0, downstream: 0 })
    expect(reachCounts(graph, 'gone')).toEqual({ upstream: 0, downstream: 0 })
  })
})

describe('routeChoices', () => {
  it('starts a route at any process with a connection leading on, in canvas order', () => {
    const starts = routeChoices({ ...graph, edges: [...graph.edges, { id: 'self', from: 'wash', to: 'wash' }] }, null)
    expect([...starts.notes]).toEqual([['mix', 'start'], ['bake', 'start']])
    expect(starts.highlight).toMatchObject({ tone: 'candidate', origin: null })
    expect([...starts.highlight.edgeIds]).toEqual([])
  })

  it('ends a route at any process downstream of its start, nearest first, with the steps to it', () => {
    const ends = routeChoices(graph, 'mix')
    expect([...ends.notes]).toEqual([['bake', '1 hop'], ['cool', '2 hops'], ['pack', '2 hops']])
    expect(ends.highlight).toMatchObject({ tone: 'candidate', origin: 'mix' })
    expect([...ends.highlight.nodeIds]).toEqual(['mix', 'bake', 'cool', 'pack'])
    expect([...ends.highlight.edgeIds]).toEqual(['c1', 'c2', 'c3'])
    expect(routeChoices(graph, 'cool').notes.size).toBe(0)
    expect(routeChoices(graph, 'gone').notes.size).toBe(0)
  })
})

describe('routePickText and routePickError', () => {
  it('titles the route panel as archify does', () => {
    expect(routeTitle(null, null, nameOf)).toBe('Click where the path starts')
    expect(routeTitle('mix', null, nameOf)).toBe('Where does the path from Mix end?')
    expect(routeTitle('mix', 'cool', nameOf)).toBe('Mix to Cool')
  })

  it('says what to pick next', () => {
    expect(routePickText('mix', 1, nameOf)).toBe('1 reachable process is highlighted. Click it.')
    expect(routePickText(null, 2, nameOf)).toBe('Click a start, then an end. Paths follow the arrows.')
    expect(routePickText(null, 0, nameOf)).toBe('No process has a connection leading on, so there is no route to pick.')
    expect(routePickText('mix', 3, nameOf))
      .toBe('3 reachable processes are highlighted. Click one.')
    expect(routePickText('cool', 0, nameOf)).toBe('No arrows lead out of Cool. Clear it and pick another start.')
  })

  it('refuses a start with nothing after it, the start itself as the end, and an end it cannot reach', () => {
    expect(routePickError(null, 'bake', routeChoices(graph, null), nameOf)).toBeNull()
    expect(routePickError(null, 'cool', routeChoices(graph, null), nameOf))
      .toBe('No arrows lead out of Cool. Pick a highlighted process.')
    const ends = routeChoices(graph, 'mix')
    expect(routePickError('mix', 'pack', ends, nameOf)).toBeNull()
    expect(routePickError('mix', 'mix', ends, nameOf)).toBe('Pick two different processes.')
    expect(routePickError('mix', 'wash', ends, nameOf))
      .toBe('Wash cannot be reached from Mix along the arrows. Pick a highlighted process.')
  })
})
