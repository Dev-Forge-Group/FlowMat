import { useEffect, useRef } from 'react'
import { useOnViewportChange, useReactFlow, useStoreApi, useUpdateNodeInternals, type Viewport } from '@xyflow/react'
import type { RfNode } from '../model/canvasFlowElements'

/** Small pieces inside ReactFlow that watch it: the zoom, the saved viewport, and nodes' handle measurements. */

/** Reports the zoom when the canvas first shows and after each move or zoom ends, for the view controls' percentage. */
export function ZoomReporter({ onZoomChange }: { onZoomChange?: (zoom: number) => void }) {
  const { getZoom } = useReactFlow()
  const report = useRef(onZoomChange)
  report.current = onZoomChange
  useEffect(() => {
    report.current?.(getZoom())
    // The first fit may land after mount.
    const timer = window.setTimeout(() => report.current?.(getZoom()), 400)
    return () => window.clearTimeout(timer)
  }, [getZoom])
  useOnViewportChange({ onEnd: (viewport: Viewport) => report.current?.(viewport.zoom) })
  return null
}

export function ViewportPersister({ storageKey }: { storageKey: string }) {
  useOnViewportChange({
    onEnd: (viewport: Viewport) => {
      localStorage.setItem(storageKey, JSON.stringify(viewport))
    },
  })
  return null
}

/** Calls updateNodeInternals for nodes whose handle count changed. Must be inside ReactFlow. */
export function InternalsUpdater({ nodeIds }: { nodeIds: string[] }) {
  const updateNodeInternals = useUpdateNodeInternals()
  useEffect(() => {
    if (nodeIds.length > 0) updateNodeInternals(nodeIds)
  }, [nodeIds, updateNodeInternals])
  return null
}

/**
 * Measures the nodes that still have no handle positions a frame after the nodes changed. React Flow draws no
 * connection to such a node.
 *
 * React Flow forgets a node's handle positions when it is given a new node object without `measured`, and it measures
 * the node again only after it has rendered that node as measured. When the new object arrives between the first
 * measurement and that render, the node never gets its handles back, so its connections stay missing until something
 * resizes it. On an ordinary load React Flow has measured every node by the time this looks, and it does nothing.
 * Must be inside ReactFlow.
 */
export function HandleBoundsRecovery({ nodes }: { nodes: RfNode[] }) {
  const store = useStoreApi()
  const updateNodeInternals = useUpdateNodeInternals()
  useEffect(() => {
    const frame = requestAnimationFrame(() => {
      const stuck: string[] = []
      for (const node of store.getState().nodeLookup.values()) {
        if (!node.internals.handleBounds && !node.hidden) stuck.push(node.id)
      }
      if (stuck.length > 0) updateNodeInternals(stuck)
    })
    return () => cancelAnimationFrame(frame)
  }, [nodes, store, updateNodeInternals])
  return null
}

/** The viewport this browser left the workflow at, if it saved one. */
export function loadSavedViewport(storageKey: string): Viewport | undefined {
  try {
    const raw = localStorage.getItem(storageKey)
    if (!raw) return undefined
    return JSON.parse(raw) as Viewport
  } catch {
    return undefined
  }
}
