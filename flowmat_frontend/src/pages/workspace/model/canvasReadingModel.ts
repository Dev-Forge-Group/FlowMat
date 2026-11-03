import { neighbourhood, reach, route, type ReadingGraph } from './graphReading'
import type { ReadingState } from './readingHash'

export type ReadingTone = 'focus' | 'upstream' | 'downstream' | 'route'

/** What the canvas shows strongly while it is read; every other process and connection recedes. */
export interface CanvasReadingHighlight {
  nodeIds: ReadonlySet<string>
  edgeIds: ReadonlySet<string>
  tone: ReadingTone
  /** The process the reading starts from. */
  origin: string
}

export interface ReadingView {
  /** Null when there is nothing to highlight, as for a route that does not exist. */
  highlight: CanvasReadingHighlight | null
  text: string
}

const count = (value: number, one: string, many = `${one}s`) => `${value} ${value === 1 ? one : many}`

/**
 * What a reading shows and says, from the saved graph only: a process with its neighbours, every process before or after
 * it, or the way between two processes. Null for a process the graph does not have.
 */
export function readingView(graph: ReadingGraph, state: ReadingState, nameOf: (processId: string) => string): ReadingView | null {
  if (state.kind === 'route') {
    const found = route(graph, state.from, state.to)
    if (!found) {
      return { highlight: null, text: `No route from ${nameOf(state.from)} to ${nameOf(state.to)} along the connections.` }
    }
    return {
      highlight: { nodeIds: new Set(found.nodeIds), edgeIds: new Set(found.edgeIds), tone: 'route', origin: state.from },
      text: `Route ${found.nodeIds.map(nameOf).join(' → ')}: ${count(found.hops, 'connection')}`,
    }
  }
  const name = nameOf(state.processId)
  if (!state.reach) {
    const around = neighbourhood(graph, state.processId)
    if (around.nodeIds.length === 0) return null
    return {
      highlight: { nodeIds: new Set(around.nodeIds), edgeIds: new Set(around.edgeIds), tone: 'focus', origin: state.processId },
      text: `${name}: ${count(around.nodeIds.length - 1, 'connected process', 'connected processes')}, ${count(around.edgeIds.length, 'connection')}`,
    }
  }
  const found = reach(graph, state.processId, state.reach)
  if (!found) return null
  const others = found.nodeIds.length - 1
  return {
    highlight: { nodeIds: new Set(found.nodeIds), edgeIds: new Set(found.edgeIds), tone: state.reach, origin: state.processId },
    text: others === 0
      ? `Nothing ${state.reach} of ${name}.`
      : `${state.reach === 'upstream' ? 'Upstream' : 'Downstream'} of ${name}: ${count(others, 'process', 'processes')}, `
        + `${count(found.edgeIds.length, 'connection')}, ${count(found.maxHops, 'step')}`,
  }
}

/** How many processes lie before and after a process along the saved connections. */
export function reachCounts(graph: ReadingGraph, processId: string): { upstream: number; downstream: number } {
  const size = (direction: 'upstream' | 'downstream') => Math.max(0, (reach(graph, processId, direction)?.nodeIds.length ?? 1) - 1)
  return { upstream: size('upstream'), downstream: size('downstream') }
}
