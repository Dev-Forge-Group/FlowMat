import { useCallback } from 'react'
import dagre from '@dagrejs/dagre'
import type { CanvasNodeViewModel, CanvasEdgeViewModel } from '../../../entities/workflow/model/types'
import { nodeDisplayHeight } from './nodeHeight'

export type LayoutDirection = 'TB' | 'LR'

interface UseAutoLayoutOptions {
  nodes: CanvasNodeViewModel[]
  edges: CanvasEdgeViewModel[]
  onBatchUpdate(positions: Array<{ processId: string; posX: number; posY: number }>): Promise<void>
  onFitView?(): void
}

export function useAutoLayout({ nodes, edges, onBatchUpdate, onFitView }: UseAutoLayoutOptions) {
  const applyLayout = useCallback(
    async (direction: LayoutDirection) => {
      if (nodes.length === 0) return

      const graph = new dagre.graphlib.Graph()
      graph.setDefaultEdgeLabel(() => ({}))
      graph.setGraph({ rankdir: direction, nodesep: 60, ranksep: 80 })

      for (const node of nodes) {
        graph.setNode(node.id, { width: node.size.width, height: nodeDisplayHeight(node) })
      }
      for (const edge of edges) {
        graph.setEdge(edge.source, edge.target)
      }

      dagre.layout(graph)

      const positions = nodes.map((node) => {
        const { x, y } = graph.node(node.id)
        return {
          processId: node.id,
          posX: Math.round(x - node.size.width / 2),
          posY: Math.round(y - nodeDisplayHeight(node) / 2),
        }
      })

      await onBatchUpdate(positions)
      setTimeout(() => onFitView?.(), 100)
    },
    [nodes, edges, onBatchUpdate, onFitView]
  )

  return { applyLayout }
}
