import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react'
import { getViewportForBounds, useReactFlow, useStore, type ReactFlowState } from '@xyflow/react'
import type { CanvasNodeViewModel } from '../../../entities/workflow/model/types'

/**
 * archify's Semantic Radar (adapted from archify template.source.html and viewer.css, Apache-2.0): a small overview of the
 * canvas that opens from MAP. Each process is a box in its kind's colour and the live camera is the outlined window over
 * them. Click a process to read it, drag or click elsewhere to move the camera there, use the arrow keys to pan, and drag
 * the panel's head to put it somewhere else. Rendered inside React Flow so it follows the camera as it moves.
 */

/** archify's radar kinds for FlowMat's node types; their colours are archify's. */
const RADAR_KINDS: Record<string, string> = {
  input: 'start',
  output: 'success',
  process: 'backend',
  equipment: 'cloud',
  storage: 'database',
  exclusive: 'decision',
  join: 'messagebus',
  split: 'messagebus',
}

type Box = { id: string; x: number; y: number; width: number; height: number; kind: string; label: string }
type Rect = { x: number; y: number; width: number; height: number }
type Position = { left: number; top: number }

const EDGE_GAP = 8
/** archify's radar centres the camera at 150% of the whole-map zoom or closer, so a click lands somewhere readable. */
const NAVIGATION_SCALE = 1.5

const selectNodes = (state: ReactFlowState) => state.nodes
const selectTransform = (state: ReactFlowState) => state.transform
const selectWidth = (state: ReactFlowState) => state.width
const selectHeight = (state: ReactFlowState) => state.height

function union(a: Rect, b: Rect): Rect {
  const x = Math.min(a.x, b.x)
  const y = Math.min(a.y, b.y)
  return { x, y, width: Math.max(a.x + a.width, b.x + b.width) - x, height: Math.max(a.y + a.height, b.y + b.height) - y }
}

function contains(outer: Rect, inner: Rect) {
  return outer.x <= inner.x && outer.y <= inner.y
    && outer.x + outer.width >= inner.x + inner.width && outer.y + outer.height >= inner.y + inner.height
}

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value))
}

export function SemanticRadar({ open, activeIds, onFocus, onClose }: {
  open: boolean
  /** The processes in hand (selected or read), marked on the radar. */
  activeIds: ReadonlySet<string>
  /** Reads a process picked on the radar. */
  onFocus(processId: string): void
  onClose(): void
}) {
  const nodes = useStore(selectNodes)
  const [tx, ty, zoom] = useStore(selectTransform)
  const width = useStore(selectWidth)
  const height = useStore(selectHeight)
  const { setCenter } = useReactFlow()
  const panelRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<SVGSVGElement>(null)
  const viewportDrag = useRef<number | null>(null)
  const panelDrag = useRef<{ pointerId: number; originX: number; originY: number; start: Position; previous: Position | null } | null>(null)
  // Where the reader dragged the panel, inside the canvas; null keeps archify's place above the view controls.
  const [position, setPosition] = useState<Position | null>(null)
  const [dragging, setDragging] = useState<'viewport' | 'panel' | null>(null)

  const boxes = useMemo<Box[]>(() => nodes.filter((node) => node.type === 'flowmatNode').map((node) => {
    const process = node.data as unknown as CanvasNodeViewModel
    return {
      id: node.id,
      x: node.position.x,
      y: node.position.y,
      width: Math.max(3, node.measured?.width ?? node.width ?? 0),
      height: Math.max(3, node.measured?.height ?? node.height ?? 0),
      kind: RADAR_KINDS[process.nodeType] ?? 'neutral',
      label: process.name,
    }
  }), [nodes])

  const nodesBounds = useMemo<Rect | null>(() => boxes.length === 0 ? null
    : boxes.slice(1).reduce<Rect>((bounds, box) => union(bounds, box), boxes[0]), [boxes])

  // Esc while the panel is dragged puts it back where the drag began, before any other Esc handling.
  const panelDragging = dragging === 'panel'
  useEffect(() => {
    if (!panelDragging) return
    function cancel(event: globalThis.KeyboardEvent) {
      const drag = panelDrag.current
      if (event.key !== 'Escape' || !drag) return
      event.preventDefault()
      event.stopPropagation()
      panelDrag.current = null
      setDragging(null)
      setPosition(drag.previous)
    }
    window.addEventListener('keydown', cancel, true)
    return () => window.removeEventListener('keydown', cancel, true)
  }, [panelDragging])

  if (!open) return null

  const measured = width > 0 && height > 0 && zoom > 0
  const visible: Rect | null = measured ? { x: -tx / zoom, y: -ty / zoom, width: width / zoom, height: height / zoom } : null
  const mapBounds = visible && nodesBounds ? union(nodesBounds, visible) : visible ?? nodesBounds
  const margin = mapBounds ? Math.max(mapBounds.width, mapBounds.height) * 0.04 : 0
  const viewBox = mapBounds
    ? [mapBounds.x - margin, mapBounds.y - margin, mapBounds.width + margin * 2, mapBounds.height + margin * 2].join(' ')
    : '0 0 100 100'
  const full = !nodesBounds || (visible !== null && contains(visible, nodesBounds))
  const status = !visible ? 'Building overview'
    : `${boxes.length} ${boxes.length === 1 ? 'process' : 'processes'} · ${full ? 'full map' : `${Math.round(zoom * 100)}% viewport`}`

  // The camera moves without animating while the radar steers it, as in archify, so the window follows the pointer.
  function centerAt(x: number, y: number) {
    let target = zoom
    if (nodesBounds && measured) {
      const whole = getViewportForBounds(nodesBounds, width, height, 0.1, 3, 0.15).zoom
      target = Math.max(zoom, Math.min(3, whole * NAVIGATION_SCALE))
    }
    void setCenter(x, y, { zoom: target, duration: 0 })
  }

  function navigate(event: PointerEvent) {
    const matrix = mapRef.current?.getScreenCTM()
    if (!matrix) return
    const point = new DOMPoint(event.clientX, event.clientY).matrixTransform(matrix.inverse())
    centerAt(point.x, point.y)
  }

  function close() {
    onClose()
    document.querySelector<HTMLButtonElement>('.diagram-nav [data-mode="map"]')?.focus()
  }

  function onSurfacePointerDown(event: PointerEvent<HTMLDivElement>) {
    if (event.button !== 0 || (event.target as Element).closest('[data-radar-node-id]')) return
    event.preventDefault()
    viewportDrag.current = event.pointerId
    setDragging('viewport')
    event.currentTarget.setPointerCapture?.(event.pointerId)
    navigate(event)
  }

  function endViewportDrag(event: PointerEvent<HTMLDivElement>) {
    if (viewportDrag.current !== event.pointerId) return
    viewportDrag.current = null
    setDragging(null)
    if (event.currentTarget.hasPointerCapture?.(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId)
  }

  function onSurfaceKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      close()
      return
    }
    const node = (event.target as Element).closest('[data-radar-node-id]')
    if (node && (event.key === 'Enter' || event.key === ' ')) {
      event.preventDefault()
      onFocus(node.getAttribute('data-radar-node-id')!)
      return
    }
    const step: Record<string, [number, number]> = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }
    const direction = step[event.key]
    if (!direction || !visible) return
    event.preventDefault()
    event.stopPropagation()
    centerAt(visible.x + visible.width / 2 + direction[0] * Math.max(12, visible.width * 0.24),
      visible.y + visible.height / 2 + direction[1] * Math.max(12, visible.height * 0.24))
  }

  function placeWithin(next: Position): Position {
    const panel = panelRef.current
    const panelWidth = panel?.offsetWidth ?? 0
    const panelHeight = panel?.offsetHeight ?? 0
    return {
      left: clamp(next.left, EDGE_GAP, Math.max(EDGE_GAP, width - panelWidth - EDGE_GAP)),
      top: clamp(next.top, EDGE_GAP, Math.max(EDGE_GAP, height - panelHeight - EDGE_GAP)),
    }
  }

  function onHeadPointerDown(event: PointerEvent<HTMLDivElement>) {
    const panel = panelRef.current
    if (event.button !== 0 || !panel || (event.target as Element).closest('button')) return
    event.preventDefault()
    event.stopPropagation()
    panelDrag.current = { pointerId: event.pointerId, originX: event.clientX, originY: event.clientY,
      start: { left: panel.offsetLeft, top: panel.offsetTop }, previous: position }
    setDragging('panel')
    event.currentTarget.setPointerCapture?.(event.pointerId)
  }

  function onHeadPointerMove(event: PointerEvent<HTMLDivElement>) {
    const drag = panelDrag.current
    if (!drag || drag.pointerId !== event.pointerId) return
    event.preventDefault()
    setPosition(placeWithin({ left: drag.start.left + event.clientX - drag.originX, top: drag.start.top + event.clientY - drag.originY }))
  }

  function finishPanelDrag(event: PointerEvent<HTMLDivElement>, cancel: boolean) {
    const drag = panelDrag.current
    if (!drag || drag.pointerId !== event.pointerId) return
    panelDrag.current = null
    setDragging(null)
    if (event.currentTarget.hasPointerCapture?.(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId)
    if (cancel) setPosition(drag.previous)
  }

  // A drag put the panel somewhere; a smaller canvas (leaving the presentation stage) keeps it inside.
  const placed = position ? placeWithin(position) : null

  return (
    <div ref={panelRef} className="overview-map nodrag nopan nowheel" role="region" aria-labelledby="overview-map-title"
      data-dragging={dragging === 'viewport' ? 'true' : undefined}
      data-panel-dragging={dragging === 'panel' ? 'true' : undefined}
      data-manual-placement={placed ? 'true' : undefined}
      style={placed ? { left: placed.left, top: placed.top, right: 'auto', bottom: 'auto' } : undefined}>
      <div className="overview-map-head" onPointerDown={onHeadPointerDown} onPointerMove={onHeadPointerMove}
        onPointerUp={(event) => finishPanelDrag(event, false)} onPointerCancel={(event) => finishPanelDrag(event, true)}>
        <span className="overview-map-live" aria-hidden="true" />
        <span className="overview-map-copy">
          <strong id="overview-map-title">Semantic radar</strong>
          <small aria-live="polite">{status}</small>
        </span>
        <button type="button" className="overview-map-close" aria-label="Close semantic radar" onClick={close}>×</button>
      </div>
      <div className="overview-map-surface" tabIndex={0} role="group"
        aria-label="Diagram overview. Click a node to focus it, or use arrow keys to pan."
        onPointerDown={onSurfacePointerDown}
        onPointerMove={(event) => {
          if (viewportDrag.current === event.pointerId) navigate(event)
        }}
        onPointerUp={endViewportDrag}
        onPointerCancel={endViewportDrag}
        onKeyDown={onSurfaceKeyDown}>
        <svg ref={mapRef} viewBox={viewBox} preserveAspectRatio="xMidYMid meet" role="group"
          aria-label="Semantic diagram radar nodes">
          <g className="overview-map-nodes">
            {boxes.map((box) => (
              <rect key={box.id} className="overview-map-node" x={box.x} y={box.y} width={box.width} height={box.height}
                rx={Math.max(2, Math.min(8, Math.min(box.width, box.height) * 0.1))}
                data-radar-node-id={box.id} data-kind={box.kind}
                data-radar-active={activeIds.has(box.id) ? 'true' : undefined}
                tabIndex={0} role="button" aria-label={`Focus ${box.label} from Semantic Radar`}
                onClick={() => onFocus(box.id)} />
            ))}
          </g>
          {visible && (
            <rect className="overview-map-viewport" x={visible.x} y={visible.y} width={visible.width} height={visible.height}
              rx={3} ry={3} />
          )}
        </svg>
      </div>
      <div className="overview-map-hint" aria-hidden="true"><span>Click node</span><span>Drag to pan</span></div>
    </div>
  )
}
