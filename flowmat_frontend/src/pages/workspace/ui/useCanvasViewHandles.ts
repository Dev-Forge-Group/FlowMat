import { useEffect } from 'react'
import type { ReactFlowInstance } from '@xyflow/react'
import { nextZoomStep } from '../model/cameraSteps'
import type { LayoutLine, LayoutSnapshot } from '../model/layoutChecks'
import { motionStill } from '../model/motion'

/** The camera stepper the view controls use: a zoom step in or out, and back to the whole canvas. */
export interface CanvasCamera {
  zoomStep(direction: 1 | -1): void
  reset(): void
}

interface Handles {
  /** Brings processes into view: one closer up, several together. */
  onRevealReady?(reveal: (processIds: string[]) => void): void
  onCameraReady?(camera: CanvasCamera): void
  /** Measures what was drawn, for the layout warnings. */
  onLayoutReady?(measure: () => LayoutSnapshot): void
}

/** How often, in canvas pixels, a drawn line is sampled for the layout warnings. */
const LAYOUT_SAMPLE_SPACING = 6

/** Hands the page what it does with the canvas's view once React Flow is there; each moves at once while motion is still. */
export function useCanvasViewHandles(instance: ReactFlowInstance | null,
  { onRevealReady, onCameraReady, onLayoutReady }: Handles) {
  useEffect(() => {
    if (!instance) return
    onRevealReady?.((processIds) => {
      if (processIds.length === 0) return
      const lone = processIds.length === 1
      void instance.fitView({ nodes: processIds.map((id) => ({ id })), padding: lone ? 0.8 : 0.2,
        maxZoom: lone ? 1.25 : 1, duration: motionStill() ? 0 : 300 })
    })
  }, [onRevealReady, instance])

  // archify's camera stepper.
  useEffect(() => {
    if (!instance) return
    onCameraReady?.({
      zoomStep(direction) {
        void instance.zoomTo(nextZoomStep(instance.getZoom(), direction), { duration: motionStill() ? 0 : 200 })
      },
      reset() {
        void instance.fitView({ padding: 0.15, maxZoom: 1, duration: motionStill() ? 0 : 300 })
      },
    })
  }, [onCameraReady, instance])

  // The layout warnings measure what was drawn: each line's path, sampled every few canvas pixels, and each process's box.
  useEffect(() => {
    if (!instance) return
    onLayoutReady?.(() => {
      const boxes = instance.getNodes().filter((node) => node.type === 'flowmatNode').map((node) => ({
        id: node.id, x: node.position.x, y: node.position.y,
        width: node.measured?.width ?? node.width ?? 0, height: node.measured?.height ?? node.height ?? 0 }))
      const lines: LayoutLine[] = []
      for (const edge of instance.getEdges()) {
        const path = document.querySelector<SVGPathElement>(
          `.react-flow__edge[data-id="${CSS.escape(edge.id)}"] path.react-flow__edge-path`)
        if (!path || typeof path.getTotalLength !== 'function') continue
        const length = path.getTotalLength()
        const steps = Math.max(1, Math.ceil(length / LAYOUT_SAMPLE_SPACING))
        const points = Array.from({ length: steps + 1 }, (_, index) => {
          const point = path.getPointAtLength((length * index) / steps)
          return { x: point.x, y: point.y }
        })
        lines.push({ id: edge.id, from: edge.source, to: edge.target, points })
      }
      return { boxes, lines }
    })
  }, [onLayoutReady, instance])
}
