/** Alignment guides while a process is dragged: the nearest edge or centre of another process within a few pixels. */

/** How near, in flow-space pixels, an edge or centre must come to snap. */
export const SNAP_THRESHOLD = 8

export interface SnapGuide {
  type: 'vertical' | 'horizontal'
  position: number
  start: number
  end: number
}

/**
 * The guides for a dragged box: at most one vertical (left, centre or right lined up) and one horizontal (top, middle or
 * bottom), each the nearest within {@link SNAP_THRESHOLD}, spanning both boxes.
 */
export function calculateSnapGuides(
  dragging: { x: number; y: number; w: number; h: number },
  others: Array<{ id: string; x: number; y: number; w: number; h: number }>
): SnapGuide[] {
  const dL = dragging.x
  const dC = dragging.x + dragging.w / 2
  const dR = dragging.x + dragging.w
  const dT = dragging.y
  const dM = dragging.y + dragging.h / 2
  const dB = dragging.y + dragging.h

  let bestX: { dist: number; guide: SnapGuide } | null = null
  let bestY: { dist: number; guide: SnapGuide } | null = null

  for (const o of others) {
    const oL = o.x
    const oC = o.x + o.w / 2
    const oR = o.x + o.w
    const oT = o.y
    const oM = o.y + o.h / 2
    const oB = o.y + o.h

    for (const dPt of [dL, dC, dR]) {
      for (const oPt of [oL, oC, oR]) {
        const dist = Math.abs(dPt - oPt)
        if (dist < SNAP_THRESHOLD && (!bestX || dist < bestX.dist)) {
          const allY = [dT, dB, oT, oB]
          bestX = {
            dist,
            guide: { type: 'vertical', position: oPt, start: Math.min(...allY), end: Math.max(...allY) },
          }
        }
      }
    }

    for (const dPt of [dT, dM, dB]) {
      for (const oPt of [oT, oM, oB]) {
        const dist = Math.abs(dPt - oPt)
        if (dist < SNAP_THRESHOLD && (!bestY || dist < bestY.dist)) {
          const allX = [dL, dR, oL, oR]
          bestY = {
            dist,
            guide: { type: 'horizontal', position: oPt, start: Math.min(...allX), end: Math.max(...allX) },
          }
        }
      }
    }
  }

  return [bestX?.guide, bestY?.guide].filter(Boolean) as SnapGuide[]
}

/** The guides for a process being dragged to a position, against every other process on the canvas. */
export function snapGuidesFor(processId: string, position: { x: number; y: number },
  nodes: readonly { id: string; position: { x: number; y: number }; width: number; height: number }[]): SnapGuide[] {
  const dragging = nodes.find((node) => node.id === processId)
  if (!dragging) return []
  const others = nodes.filter((node) => node.id !== processId)
    .map((node) => ({ id: node.id, x: node.position.x, y: node.position.y, w: node.width, h: node.height }))
  return calculateSnapGuides({ x: position.x, y: position.y, w: dragging.width, h: dragging.height }, others)
}
