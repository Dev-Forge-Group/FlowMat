import type { CanvasEdgeViewModel, CanvasNodeViewModel, ConnectCompletePayload } from '../../../entities/workflow/model/types'
import { nodeDisplayHeight } from './nodeHeight'

/** The canvas's processes and connections as React Flow holds them; the view model rides along as data. */

export type RfNode = {
  id: string
  type: 'flowmatNode'
  position: { x: number; y: number }
  width: number
  height: number
  selected: boolean
  draggable?: boolean
  dragging?: boolean
  data: CanvasNodeViewModel
}

export type RfEdge = {
  id: string
  type: 'flowmatEdge'
  source: string
  target: string
  sourceHandle: string | null
  targetHandle: string | null
  selected: boolean
  data: CanvasEdgeViewModel
}

export function toRfProcessNodes(nodes: CanvasNodeViewModel[], selectedId: string | null): RfNode[] {
  return nodes.map((n) => ({
    id: n.id,
    type: 'flowmatNode' as const,
    position: n.position,
    width: n.size.width,
    height: nodeDisplayHeight(n),
    selected: n.id === selectedId,
    data: n,
  }))
}

export function toRfEdges(edges: CanvasEdgeViewModel[], selectedId: string | null): RfEdge[] {
  return edges.map((e) => ({
    id: e.id,
    type: 'flowmatEdge' as const,
    source: e.source,
    target: e.target,
    sourceHandle: e.sourceHandle,
    targetHandle: e.targetHandle,
    selected: e.id === selectedId,
    data: e,
  }))
}

/** A connection as React Flow reports one drawn or moved: its ends, and the handles where it was let go, if any. */
export interface DrawnConnection {
  source: string | null
  target: string | null
  sourceHandle?: string | null
  targetHandle?: string | null
}

/**
 * What a drawn connection asks to save: its processes, the ports at its handles, and the default handles where it
 * was not drawn from or to a port. None while an end is missing.
 */
export function connectionPayload(connection: DrawnConnection): ConnectCompletePayload | null {
  if (!connection.source || !connection.target) return null
  return {
    fromProcessId: connection.source,
    toProcessId: connection.target,
    fromIoId: connection.sourceHandle ?? null,
    toIoId: connection.targetHandle ?? null,
    sourceHandle: connection.sourceHandle ?? 'out-default',
    targetHandle: connection.targetHandle ?? 'in-default',
  }
}

/** Whether a connection may be drawn: not from a process to itself, and not the same ends and handles as one there. */
export function isNewConnection(connection: DrawnConnection, edges: readonly Pick<RfEdge, 'source' | 'target' | 'sourceHandle' | 'targetHandle'>[]): boolean {
  if (connection.source === connection.target) return false
  const sourceHandle = connection.sourceHandle ?? null
  const targetHandle = connection.targetHandle ?? null
  return !edges.some((edge) => edge.source === connection.source && edge.target === connection.target
    && edge.sourceHandle === sourceHandle && edge.targetHandle === targetHandle)
}
