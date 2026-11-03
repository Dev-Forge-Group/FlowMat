import type { CanvasEdgeViewModel, CanvasNodeViewModel } from '../../../entities/workflow/model/types'

/**
 * Reading the saved process graph: who feeds a process, what it feeds, and the way from one process to another. Everything
 * follows the saved connections (from process -> to process), never on-screen positions or line shapes, so what is shown
 * is what the workflow defines. Read only; nothing here changes, saves or shares the graph.
 */
export interface ReadingGraph {
  nodeIds: string[]
  edges: { id: string; from: string; to: string }[]
}

export type ReachDirection = 'upstream' | 'downstream'

/** Every process before (upstream) or after (downstream) the origin, with the connections between them. */
export interface Reach {
  origin: string
  direction: ReachDirection
  /** The origin first, then the others in the order they were reached. */
  nodeIds: string[]
  edgeIds: string[]
  /** Steps from the origin for every process reached; the origin is 0. */
  depth: Record<string, number>
  maxHops: number
}

export interface Route {
  /** From the first process to the last, in order. */
  nodeIds: string[]
  edgeIds: string[]
  hops: number
}

export function readingGraph(canvas: { nodes: CanvasNodeViewModel[]; edges: CanvasEdgeViewModel[] }): ReadingGraph {
  const nodeIds = canvas.nodes.map((node) => node.processId)
  const known = new Set(nodeIds)
  const edges = canvas.edges
    .filter((edge) => known.has(edge.fromProcessId) && known.has(edge.toProcessId))
    .map((edge) => ({ id: edge.connectionId, from: edge.fromProcessId, to: edge.toProcessId }))
  return { nodeIds, edges }
}

/** The process, the processes it is connected to either way, and those connections. */
export function neighbourhood(graph: ReadingGraph, id: string): { nodeIds: string[]; edgeIds: string[] } {
  if (!graph.nodeIds.includes(id)) return { nodeIds: [], edgeIds: [] }
  const nodeIds = [id]
  const edgeIds: string[] = []
  for (const edge of graph.edges) {
    if (edge.from !== id && edge.to !== id) continue
    edgeIds.push(edge.id)
    const other = edge.from === id ? edge.to : edge.from
    if (!nodeIds.includes(other)) nodeIds.push(other)
  }
  return { nodeIds, edgeIds }
}

/**
 * Breadth first along the saved connections: downstream follows connections leaving a process, upstream those entering it.
 * A loop ends where it meets a process already reached. Null for a process that is not in the graph.
 */
export function reach(graph: ReadingGraph, origin: string, direction: ReachDirection): Reach | null {
  if (!graph.nodeIds.includes(origin)) return null
  const depth: Record<string, number> = { [origin]: 0 }
  const nodeIds = [origin]
  for (let cursor = 0; cursor < nodeIds.length; cursor += 1) {
    const current = nodeIds[cursor]
    for (const edge of graph.edges) {
      const next = direction === 'downstream' ? (edge.from === current ? edge.to : null) : (edge.to === current ? edge.from : null)
      if (next == null || next in depth) continue
      depth[next] = depth[current] + 1
      nodeIds.push(next)
    }
  }
  const reached = new Set(nodeIds)
  const edgeIds = graph.edges
    .filter((edge) => edge.from !== edge.to && reached.has(edge.from) && reached.has(edge.to)
      && (direction === 'downstream' ? depth[edge.to] > 0 : depth[edge.from] > 0))
    .map((edge) => edge.id)
  return { origin, direction, nodeIds, edgeIds, depth, maxHops: Math.max(...nodeIds.map((id) => depth[id])) }
}

/**
 * The first way found breadth first along the saved connections, in their listed order; a connection from a process to
 * itself is never part of it. Null when the two are the same process, either is unknown or there is no way.
 */
export function route(graph: ReadingGraph, from: string, to: string): Route | null {
  if (from === to || !graph.nodeIds.includes(from) || !graph.nodeIds.includes(to)) return null
  const via = new Map<string, { node: string; edge: string }>()
  const queue = [from]
  const seen = new Set([from])
  for (let cursor = 0; cursor < queue.length && !seen.has(to); cursor += 1) {
    const current = queue[cursor]
    for (const edge of graph.edges) {
      if (edge.from !== current || edge.to === edge.from || seen.has(edge.to)) continue
      seen.add(edge.to)
      via.set(edge.to, { node: current, edge: edge.id })
      queue.push(edge.to)
    }
  }
  if (!seen.has(to)) return null
  const nodeIds = [to]
  const edgeIds: string[] = []
  for (let step = via.get(to); step; step = via.get(step.node)) {
    edgeIds.unshift(step.edge)
    nodeIds.unshift(step.node)
  }
  return { nodeIds, edgeIds, hops: edgeIds.length }
}

/**
 * Processes whose name, type or id holds the query, ignoring case and surrounding spaces: names starting with it first,
 * then other names holding it, then type or id matches, each in canvas order. An empty query finds nothing.
 */
export function findProcesses(nodes: CanvasNodeViewModel[], query: string, limit = 20): CanvasNodeViewModel[] {
  const needle = query.trim().toLocaleLowerCase()
  if (!needle) return []
  const rank = (node: CanvasNodeViewModel): number => {
    const name = node.name.toLocaleLowerCase()
    if (name.startsWith(needle)) return 0
    if (name.includes(needle)) return 1
    const other = [node.processType, node.nodeType, node.processId].map((value) => (value ?? '').toLocaleLowerCase())
    return other.some((value) => value.includes(needle)) ? 2 : -1
  }
  return nodes
    .map((node, index) => ({ node, index, rank: rank(node) }))
    .filter((entry) => entry.rank >= 0)
    .sort((left, right) => left.rank - right.rank || left.index - right.index)
    .slice(0, limit)
    .map((entry) => entry.node)
}
