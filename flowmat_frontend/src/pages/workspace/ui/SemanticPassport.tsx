import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react'
import { getWorkflowNodeDefinition } from '../../../entities/workflow/model/nodeCatalog'
import type { CanvasNodeViewModel } from '../../../entities/workflow/model/types'
import type { CanvasReading } from '../model/useCanvasReading'
import { formatReadingHash, withViewParams } from '../model/readingHash'
import { ProcessConnections } from './ProcessConnections'

/** Where the reader moved the passport, in pixels from the canvas's top left corner. */
export type PassportPosition = { left: number; top: number }

const EDGE_GAP = 8

/** The position kept inside the canvas the passport sits on. */
function within(chip: HTMLElement, next: PassportPosition): PassportPosition {
  const canvas = chip.offsetParent as HTMLElement | null
  if (!canvas) return next
  return {
    left: Math.max(EDGE_GAP, Math.min(next.left, canvas.clientWidth - chip.offsetWidth - EDGE_GAP)),
    top: Math.max(EDGE_GAP, Math.min(next.top, canvas.clientHeight - chip.offsetHeight - EDGE_GAP)),
  }
}

/**
 * archify's Semantic Passport (adapted from archify focus.js and template.source.html, Apache-2.0): one compact surface
 * for the process in hand, with its name, kind and stable id, its authored reach upstream and downstream with counts,
 * the reach receipt, and its connected relationships. The single place for a process's reading details. Its handle moves
 * it: drag it, or use the arrow keys (Shift for small steps), and Home puts it back at the top left.
 */
export function SemanticPassport({ reading, node, position, onMove, onClose, onFollow }: {
  reading: CanvasReading
  node: CanvasNodeViewModel
  /** Where the reader moved it; null keeps archify's place at the top left. Kept from one process to the next. */
  position: PassportPosition | null
  onMove(position: PassportPosition | null): void
  onClose(): void
  /** Goes to the process at the other end of a relationship. */
  onFollow(processId: string): void
}) {
  const chipRef = useRef<HTMLDivElement>(null)
  const drag = useRef<{ pointerId: number; originX: number; originY: number; start: PassportPosition;
    previous: PassportPosition | null; moved: boolean } | null>(null)
  const [dragging, setDragging] = useState(false)
  const [copied, setCopied] = useState<'copied' | 'failed' | null>(null)

  // A smaller canvas (leaving the presentation stage, a wider side panel) keeps a moved passport inside it.
  useEffect(() => {
    const chip = chipRef.current
    const canvas = chip?.offsetParent
    if (!chip || !canvas || !position || typeof ResizeObserver !== 'function') return
    const observer = new ResizeObserver(() => {
      const next = within(chip, position)
      if (next.left !== position.left || next.top !== position.top) onMove(next)
    })
    observer.observe(canvas)
    return () => observer.disconnect()
  }, [onMove, position])

  // Esc while the passport is dragged puts it back where the drag began, before any other Esc handling.
  useEffect(() => {
    if (!dragging) return
    function cancel(event: globalThis.KeyboardEvent) {
      const active = drag.current
      if (event.key !== 'Escape' || !active) return
      event.preventDefault()
      event.stopPropagation()
      drag.current = null
      setDragging(false)
      onMove(active.previous)
    }
    window.addEventListener('keydown', cancel, true)
    return () => window.removeEventListener('keydown', cancel, true)
  }, [dragging, onMove])

  function beginDrag(event: PointerEvent<HTMLButtonElement>) {
    const chip = chipRef.current
    if (event.button !== 0 || !chip || drag.current) return
    event.preventDefault()
    event.stopPropagation()
    drag.current = { pointerId: event.pointerId, originX: event.clientX, originY: event.clientY,
      start: position ?? { left: chip.offsetLeft, top: chip.offsetTop }, previous: position, moved: false }
    setDragging(true)
    event.currentTarget.setPointerCapture?.(event.pointerId)
  }

  function moveDrag(event: PointerEvent<HTMLButtonElement>) {
    const active = drag.current
    const chip = chipRef.current
    if (!active || active.pointerId !== event.pointerId || !chip) return
    const dx = event.clientX - active.originX
    const dy = event.clientY - active.originY
    // archify's threshold: a press that hardly moves is not a drag.
    if (!active.moved && Math.hypot(dx, dy) <= 3) return
    active.moved = true
    event.preventDefault()
    onMove(within(chip, { left: active.start.left + dx, top: active.start.top + dy }))
  }

  function finishDrag(event: PointerEvent<HTMLButtonElement>, cancel: boolean) {
    const active = drag.current
    if (!active || active.pointerId !== event.pointerId) return
    drag.current = null
    setDragging(false)
    if (event.currentTarget.hasPointerCapture?.(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId)
    if (cancel) onMove(active.previous)
  }

  function moveWithKeys(event: KeyboardEvent<HTMLButtonElement>) {
    const chip = chipRef.current
    if (!chip) return
    if (event.key === 'Home') {
      event.preventDefault()
      onMove(null)
      return
    }
    if (event.metaKey || event.ctrlKey || event.altKey) return
    const step: Record<string, [number, number]> = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }
    const direction = step[event.key]
    if (!direction) return
    event.preventDefault()
    const from = position ?? { left: chip.offsetLeft, top: chip.offsetTop }
    const distance = event.shiftKey ? 4 : 16
    onMove(within(chip, { left: from.left + direction[0] * distance, top: from.top + direction[1] * distance }))
  }

  const processId = node.processId
  const counts = reading.counts(processId)
  const relations = reading.relationsOf(processId)
  const state = reading.state
  const reach = state?.kind === 'focus' && state.processId === processId ? state.reach : undefined
  // A connection pinned here (`#relation=`): one of this process's, from either end.
  const pinnedId = reading.pinned && (reading.pinned.from === processId || reading.pinned.to === processId)
    ? reading.pinned.connectionId : null
  const receipt = reach || pinnedId ? reading.view?.text ?? null : null
  const kind = getWorkflowNodeDefinition(node.nodeType).label

  function toggleReach(direction: 'upstream' | 'downstream') {
    if (reach === direction) reading.focus(processId)
    else reading.focus(processId, direction)
  }

  async function copyLink() {
    const hash = pinnedId || (state?.kind === 'focus' && state.processId === processId)
      ? window.location.hash
      : withViewParams(formatReadingHash({ kind: 'focus', processId, reach: null }), reading.run.id, reading.changes.id)
    const url = `${window.location.origin}${window.location.pathname}${window.location.search}${hash}`
    try {
      await navigator.clipboard.writeText(url)
      setCopied('copied')
    } catch {
      setCopied('failed')
    }
    window.setTimeout(() => setCopied(null), 1500)
  }

  return (
    <div ref={chipRef} className="focus-chip relationship-lens" role="region" aria-labelledby="semantic-passport-title"
      data-manual-placement={position ? 'true' : undefined} data-panel-dragging={dragging ? 'true' : undefined}
      style={position ? { left: position.left, top: position.top } : undefined}>
      <button type="button" className="relationship-lens-drag-handle"
        aria-label="Move semantic passport. Drag, use arrow keys, or press Home to reset."
        title="Move semantic passport. Drag, use arrow keys, or press Home to reset."
        onPointerDown={beginDrag} onPointerMove={moveDrag} onPointerUp={(event) => finishDrag(event, false)}
        onPointerCancel={(event) => finishDrag(event, true)} onKeyDown={moveWithKeys} />
      <div className="relationship-lens-head">
        <div className="relationship-lens-copy">
          <span className="relationship-lens-eyebrow">Semantic passport</span>
          <strong className="relationship-lens-title" id="semantic-passport-title">{node.name}</strong>
          {node.description && <span className="semantic-passport-detail">{node.description}</span>}
          <div className="semantic-passport-meta" aria-label="Process metadata">
            <span data-passport="kind">{kind}</span>
            <code data-passport="id">{processId}</code>
          </div>
          <span className="relationship-lens-summary">
            {relations.out.length} outgoing · {relations.in.length} incoming
            {relations.loop.length > 0 ? ` · ${relations.loop.length} loop` : ''}
          </span>
          <div className="semantic-passport-reach">
            <span className="semantic-passport-reach-label">Authored reach</span>
            <div className="semantic-passport-reach-actions" role="group" aria-label="Trace authored reachability">
              <button type="button" data-reach="upstream" aria-pressed={reach === 'upstream'} disabled={counts.upstream === 0}
                aria-label={counts.upstream === 0 ? 'No upstream processes'
                  : `Trace ${counts.upstream} upstream ${counts.upstream === 1 ? 'process' : 'processes'}`}
                onClick={() => toggleReach('upstream')}>
                <span>Upstream</span><strong>{counts.upstream}</strong>
              </button>
              <button type="button" data-reach="downstream" aria-pressed={reach === 'downstream'} disabled={counts.downstream === 0}
                aria-label={counts.downstream === 0 ? 'No downstream processes'
                  : `Trace ${counts.downstream} downstream ${counts.downstream === 1 ? 'process' : 'processes'}`}
                onClick={() => toggleReach('downstream')}>
                <span>Downstream</span><strong>{counts.downstream}</strong>
              </button>
            </div>
            {receipt && <small className="semantic-passport-reach-status" role="status">{receipt}</small>}
          </div>
        </div>
        <div className="relationship-lens-actions">
          <button type="button" className="relationship-lens-close" aria-label="Close semantic passport" title="Close"
            onClick={onClose}>&#215;</button>
          <button type="button" className="relationship-lens-copy-link"
            aria-label={pinnedId ? 'Copy link to pinned connection' : 'Copy link to focused process'}
            onClick={() => void copyLink()}>
            {copied === 'copied' ? 'Copied' : copied === 'failed' ? 'Copy failed' : 'Copy link'}
          </button>
        </div>
      </div>
      <ProcessConnections key={`passport-${processId}`} relations={relations} nameOf={reading.nameOf}
        pinnedId={pinnedId} onPin={reading.pin} onPreview={reading.previewConnection} onFollow={onFollow} />
    </div>
  )
}
