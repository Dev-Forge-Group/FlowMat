import { useState } from 'react'
import type { CanvasReading } from '../model/useCanvasReading'

/**
 * The one place the canvas says what is being read (archify's focus, reach and route, read only). With a process
 * selected it offers its neighbours, everything before or after it, and a route from it; while reading it offers a link
 * to the same view and a way out.
 */
export function CanvasReadingChip({ reading, selectedProcessId, onFind, onGuide }: {
  reading: CanvasReading
  selectedProcessId: string | null
  onFind(): void
  onGuide(): void
}) {
  const [copied, setCopied] = useState(false)
  const state = reading.state
  // The selected process, or else the one being read; its buttons read from it.
  const subject = selectedProcessId ?? (state?.kind === 'focus' ? state.processId : null)
  const known = subject != null && reading.knows(subject)
  const counts = known ? reading.counts(subject) : null
  const readingOf = state?.kind === 'focus' && state.processId === subject ? state.reach : undefined

  async function copyLink() {
    try {
      await navigator.clipboard.writeText(window.location.href)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      setCopied(false)
    }
  }

  return (
    <div role="region" aria-label="Canvas reading" className="canvas-reading-chip">
      {reading.picking ? (
        <>
          <span role="status">
            {reading.picking.from == null
              ? 'Route: click the process to start from.'
              : `Route from ${reading.nameOf(reading.picking.from)}: click the process to end at.`}
          </span>
          <button type="button" onClick={reading.clear}>Cancel</button>
        </>
      ) : (
        <>
          {reading.view && <span role="status">{reading.view.text}</span>}
          {!reading.view && known && <span>{reading.nameOf(subject)}</span>}
          {known && counts && (
            <>
              <button type="button" aria-pressed={readingOf === null}
                onClick={() => reading.focus(subject)}>Neighbours</button>
              <button type="button" aria-pressed={readingOf === 'upstream'}
                disabled={counts.upstream === 0} onClick={() => reading.focus(subject, 'upstream')}>Upstream {counts.upstream}</button>
              <button type="button" aria-pressed={readingOf === 'downstream'}
                disabled={counts.downstream === 0} onClick={() => reading.focus(subject, 'downstream')}>Downstream {counts.downstream}</button>
              <button type="button" onClick={() => reading.startRoute(subject)}>Route from here</button>
            </>
          )}
          {state && (
            <>
              <button type="button" onClick={() => void copyLink()}>{copied ? 'Link copied' : 'Copy link'}</button>
              <button type="button" onClick={reading.clear} title="Esc">Clear</button>
            </>
          )}
          {!state && !known && <span className="canvas-reading-chip__hint">Select a process to follow its connections.</span>}
        </>
      )}
      <button type="button" onClick={onFind} title="/">Find</button>
      <button type="button" onClick={onGuide} title="?" aria-label="Reading guide">?</button>
    </div>
  )
}
