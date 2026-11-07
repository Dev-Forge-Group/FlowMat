import { getWorkflowNodeDefinition, getWorkflowPaletteDefinitions } from '../../../entities/workflow/model/nodeCatalog'
import { neighbourhood, reach, route, type ReadingGraph } from './graphReading'
import type { ReadingState } from './readingHash'

export type ReadingTone = 'focus' | 'upstream' | 'downstream' | 'route' | 'candidate' | 'lens'

/** What the canvas shows strongly while it is read; every other process and connection recedes. */
export interface CanvasReadingHighlight {
  nodeIds: ReadonlySet<string>
  edgeIds: ReadonlySet<string>
  tone: ReadingTone
  /** The process the reading starts from, if it has one. */
  origin: string | null
  /** Processes shown halfway: the other ends of what a lens kind touches. */
  nearIds?: ReadonlySet<string>
}

export interface ReadingView {
  /** Null when there is nothing to highlight, as for a route that does not exist. */
  highlight: CanvasReadingHighlight | null
  text: string
  /** A route's processes from its start to its end; empty for every other reading. */
  steps: string[]
}

const count = (value: number, one: string, many = `${one}s`) => `${value} ${value === 1 ? one : many}`

/**
 * What a reading shows and says, from the saved graph only: a process with its neighbours, every process before or after
 * it, or the way between two processes. Null for a process the graph does not have.
 */
export function readingView(graph: ReadingGraph, state: ReadingState, nameOf: (processId: string) => string): ReadingView | null {
  if (state.kind === 'lens') return lensView(graph, state.kinds)
  if (state.kind === 'route') {
    const found = route(graph, state.from, state.to)
    if (!found) {
      return { highlight: null, text: `${nameOf(state.to)} cannot be reached from ${nameOf(state.from)} along the arrows.`, steps: [] }
    }
    // archify's route receipt: "3 nodes · 2 steps · shortest path".
    return {
      highlight: { nodeIds: new Set(found.nodeIds), edgeIds: new Set(found.edgeIds), tone: 'route', origin: state.from },
      text: `${count(found.nodeIds.length, 'process', 'processes')} · ${count(found.hops, 'step')} · shortest path`,
      steps: found.nodeIds,
    }
  }
  const name = nameOf(state.processId)
  if (!state.reach) {
    const around = neighbourhood(graph, state.processId)
    if (around.nodeIds.length === 0) return null
    return {
      highlight: { nodeIds: new Set(around.nodeIds), edgeIds: new Set(around.edgeIds), tone: 'focus', origin: state.processId },
      text: `${name}: ${count(around.nodeIds.length - 1, 'connected process', 'connected processes')}, ${count(around.edgeIds.length, 'connection')}`,
      steps: [],
    }
  }
  const found = reach(graph, state.processId, state.reach)
  if (!found) return null
  const others = found.nodeIds.length - 1
  // archify's reach receipt: "Downstream · 8 nodes · 8 links · max 5 hops".
  return {
    highlight: { nodeIds: new Set(found.nodeIds), edgeIds: new Set(found.edgeIds), tone: state.reach, origin: state.processId },
    text: others === 0
      ? `No ${state.reach} processes from ${name}`
      : `${state.reach === 'upstream' ? 'Upstream' : 'Downstream'} · ${count(others, 'process', 'processes')} · `
        + `${count(found.edgeIds.length, 'connection')} · max ${count(found.maxHops, 'hop')}`,
    steps: [],
  }
}

/**
 * The processes a reading is about, to bring into view: a route (or its two ends when there is none), a reach, a
 * neighbourhood, or a lens with what is nearby.
 */
export function readingTargets(graph: ReadingGraph, state: ReadingState): string[] {
  if (state.kind === 'lens') {
    const shown = lensView(graph, state.kinds).highlight
    return shown ? [...shown.nodeIds, ...(shown.nearIds ?? [])] : []
  }
  if (state.kind === 'route') return route(graph, state.from, state.to)?.nodeIds ?? [state.from, state.to]
  if (!state.reach) return neighbourhood(graph, state.processId).nodeIds
  return reach(graph, state.processId, state.reach)?.nodeIds ?? []
}

/**
 * Reading by kind (archify's semantic lens): one kind shows its processes and every connection touching them, the other
 * ends halfway; two kinds show their processes and only the connections between the two, either way.
 */
function lensView(graph: ReadingGraph, kinds: readonly string[]): ReadingView {
  const kindOf = (id: string) => graph.kinds?.get(id)
  const label = kinds.map((kind) => getWorkflowNodeDefinition(kind).label).join(' and ')
  const members = graph.nodeIds.filter((id) => kinds.includes(kindOf(id) ?? ''))
  if (members.length === 0) return { highlight: null, text: `No ${label} processes on this canvas.`, steps: [] }
  if (kinds.length === 1) {
    const touching = graph.edges.filter((edge) => kindOf(edge.from) === kinds[0] || kindOf(edge.to) === kinds[0])
    const near = new Set(touching.flatMap((edge) => [edge.from, edge.to]).filter((id) => kindOf(id) !== kinds[0]))
    // archify's single-kind receipt: "4 Service nodes · 6 touching relationships · connected peers remain visible".
    return {
      highlight: { nodeIds: new Set(members), edgeIds: new Set(touching.map((edge) => edge.id)), tone: 'lens', origin: null, nearIds: near },
      text: `${count(members.length, `${label} process`, `${label} processes`)} · ${count(touching.length, 'touching connection')}`
        + ' · connected peers remain visible',
      steps: [],
    }
  }
  const [first, second] = kinds
  const isForward = (edge: ReadingGraph['edges'][number]) => kindOf(edge.from) === first && kindOf(edge.to) === second
  const between = graph.edges.filter((edge) => isForward(edge) || (kindOf(edge.from) === second && kindOf(edge.to) === first))
  const forward = between.filter(isForward).length
  const [firstLabel, secondLabel] = kinds.map((kind) => getWorkflowNodeDefinition(kind).label)
  // archify's comparison receipt: "Service → Database: 3 · Database → Service: 0 · 3 direct relationships".
  return {
    highlight: { nodeIds: new Set(members), edgeIds: new Set(between.map((edge) => edge.id)), tone: 'lens', origin: null },
    text: `${firstLabel} → ${secondLabel}: ${forward} · ${secondLabel} → ${firstLabel}: ${between.length - forward} · `
      + count(between.length, 'direct connection'),
    steps: [],
  }
}

/** The kinds on the canvas, in palette order, each with its label and how many processes have it. */
export function kindCounts(graph: ReadingGraph): { kind: string; label: string; count: number }[] {
  const counts = new Map<string, number>()
  for (const id of graph.nodeIds) {
    const kind = graph.kinds?.get(id)
    if (kind) counts.set(kind, (counts.get(kind) ?? 0) + 1)
  }
  return getWorkflowPaletteDefinitions()
    .filter((definition) => counts.has(definition.nodeType))
    .map((definition) => ({ kind: definition.nodeType, label: definition.label, count: counts.get(definition.nodeType) ?? 0 }))
}

/** The lens after a kind is pressed: off again if it was on, added if there is room, else refused (one or two kinds). */
export function toggleLensKind(current: readonly string[], kind: string): { kinds: string[]; error: string | null } {
  if (current.includes(kind)) return { kinds: current.filter((other) => other !== kind), error: null }
  if (current.length >= 2) return { kinds: [...current], error: 'A lens shows one or two kinds; turn one off first.' }
  return { kinds: [...current, kind], error: null }
}

/** One connection and its two processes, shown while its row in the inspector is pointed at (archify's relationship preview). */
export function connectionPreview(graph: ReadingGraph, connectionId: string): CanvasReadingHighlight | null {
  const edge = graph.edges.find((candidate) => candidate.id === connectionId)
  return edge ? { nodeIds: new Set([edge.from, edge.to]), edgeIds: new Set([edge.id]), tone: 'focus', origin: null } : null
}

/** How many processes lie before and after a process along the saved connections. */
export function reachCounts(graph: ReadingGraph, processId: string): { upstream: number; downstream: number } {
  const size = (direction: 'upstream' | 'downstream') => Math.max(0, (reach(graph, processId, direction)?.nodeIds.length ?? 1) - 1)
  return { upstream: size('upstream'), downstream: size('downstream') }
}

/** The processes a route can be picked at next, each with a short note, and what the canvas marks meanwhile. */
export interface RouteChoices {
  notes: ReadonlyMap<string, string>
  highlight: CanvasReadingHighlight
}

/**
 * Where a route can start (any process with a connection leading on, in canvas order) or, once it has a start, end: every
 * process downstream of the start, nearest first, noting the steps it takes to get there (archify's route candidates).
 */
export function routeChoices(graph: ReadingGraph, from: string | null): RouteChoices {
  if (from == null) {
    const out = new Map<string, number>()
    for (const edge of graph.edges) if (edge.from !== edge.to) out.set(edge.from, (out.get(edge.from) ?? 0) + 1)
    const starts = graph.nodeIds.filter((id) => out.has(id))
    // archify's finder badges: "start" for a route source, "N hops" for a reachable destination.
    return {
      notes: new Map(starts.map((id) => [id, 'start'])),
      highlight: { nodeIds: new Set(starts), edgeIds: new Set(), tone: 'candidate', origin: null },
    }
  }
  const found = reach(graph, from, 'downstream')
  if (!found) return { notes: new Map(), highlight: { nodeIds: new Set(), edgeIds: new Set(), tone: 'candidate', origin: from } }
  return {
    notes: new Map(found.nodeIds.slice(1).map((id) => [id, count(found.depth[id], 'hop')])),
    highlight: { nodeIds: new Set(found.nodeIds), edgeIds: new Set(found.edgeIds), tone: 'candidate', origin: from },
  }
}

/** The route panel's title (archify route probe): where to start, where the path from the start ends, or the route itself. */
export function routeTitle(from: string | null, to: string | null, nameOf: (processId: string) => string): string {
  if (from == null) return 'Click where the path starts'
  if (to == null) return `Where does the path from ${nameOf(from)} end?`
  return `${nameOf(from)} to ${nameOf(to)}`
}

/** What the route panel says while a route is being picked (archify's wording). */
export function routePickText(from: string | null, choices: number, nameOf: (processId: string) => string): string {
  if (from == null) {
    return choices === 0
      ? 'No process has a connection leading on, so there is no route to pick.'
      : 'Click a start, then an end. Paths follow the arrows.'
  }
  if (choices === 0) return `No arrows lead out of ${nameOf(from)}. Clear it and pick another start.`
  return choices === 1
    ? '1 reachable process is highlighted. Click it.'
    : `${choices} reachable processes are highlighted. Click one.`
}

/** Why a picked process cannot be the route's start or end, or null when it can; picking goes on either way. */
export function routePickError(from: string | null, picked: string, choices: RouteChoices,
  nameOf: (processId: string) => string): string | null {
  if (choices.notes.has(picked)) return null
  if (from == null) return `No arrows lead out of ${nameOf(picked)}. Pick a highlighted process.`
  if (picked === from) return 'Pick two different processes.'
  return `${nameOf(picked)} cannot be reached from ${nameOf(from)} along the arrows. Pick a highlighted process.`
}
