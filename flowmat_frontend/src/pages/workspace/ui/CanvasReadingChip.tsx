import { useState } from 'react'
import type { CanvasReading } from '../model/useCanvasReading'

/**
 * The one place the canvas says what is being read (archify's focus, reach and route, read only). With a process
 * selected it offers its neighbours, everything before or after it, and a route from it; while reading it offers a link
 * to the same view and a way out. A route's processes are listed in order, each bringing its process into view; Kinds
 * reads the canvas by kind of process; Runs shows a production run over it; Changes marks what the draft changed since a
 * published revision.
 */
export function CanvasReadingChip({
  reading, selectedProcessId, onFind, onLens, onGuide, runSummary, onRuns, changesSummary, onChanges,
}: {
  reading: CanvasReading
  selectedProcessId: string | null
  onFind(): void
  onLens(): void
  onGuide(): void
  /** What the production run shown over the canvas says, if one is shown. */
  runSummary: string | null
  onRuns(): void
  /** What the draft changed since the compared revision, if one is compared. */
  changesSummary: string | null
  onChanges(): void
}) {
  const [copied, setCopied] = useState(false)
  const state = reading.state
  // The selected process, or else the one being read; its buttons read from it.
  const subject = selectedProcessId ?? (state?.kind === 'focus' ? state.processId : null)
  const known = subject != null && reading.knows(subject)
  const counts = known ? reading.counts(subject) : null
  const readingOf = state?.kind === 'focus' && state.processId === subject ? state.reach : undefined
  const steps = reading.view?.steps ?? []

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
          <span role="status">{reading.picking.text}</span>
          {reading.picking.error && <span role="alert" className="canvas-reading-chip__error">{reading.picking.error}</span>}
          <button type="button" onClick={reading.cancelRoute} title="Esc">Cancel</button>
        </>
      ) : (
        <>
          {reading.view && <span role="status">{reading.view.text}</span>}
          {steps.length > 0 && (
            <ol aria-label="Route steps" className="canvas-reading-steps">
              {steps.map((processId) => (
                <li key={processId}>
                  <button type="button" onClick={() => reading.reveal([processId])}>{reading.nameOf(processId)}</button>
                </li>
              ))}
            </ol>
          )}
          {!reading.view && known && <span>{reading.nameOf(subject)}</span>}
          {known && counts && (
            <>
              <button type="button" aria-pressed={readingOf === null}
                onClick={() => reading.focus(subject)}>Neighbours</button>
              <button type="button" aria-pressed={readingOf === 'upstream'}
                disabled={counts.upstream === 0} onClick={() => reading.focus(subject, 'upstream')}>Upstream {counts.upstream}</button>
              <button type="button" aria-pressed={readingOf === 'downstream'}
                disabled={counts.downstream === 0} onClick={() => reading.focus(subject, 'downstream')}>Downstream {counts.downstream}</button>
              <button type="button" disabled={counts.downstream === 0}
                onClick={() => reading.startRoute(subject)}>Route from here</button>
            </>
          )}
          {state && (
            <>
              <button type="button" onClick={() => void copyLink()}>{copied ? 'Link copied' : 'Copy link'}</button>
              <button type="button" onClick={reading.clear} title="Esc">Clear</button>
            </>
          )}
          {!state && !known && (
            <>
              <span className="canvas-reading-chip__hint">Select a process to follow its connections.</span>
              <button type="button" onClick={() => reading.startRoute()}>Pick a route</button>
            </>
          )}
        </>
      )}
      {runSummary && (
        <>
          <span className="canvas-run-summary">{runSummary}</span>
          <button type="button" onClick={() => reading.run.show(null)}>Hide run</button>
        </>
      )}
      {changesSummary && (
        <>
          <span className="canvas-run-summary">{changesSummary}</span>
          <button type="button" onClick={() => reading.changes.show(null)}>Hide changes</button>
        </>
      )}
      <button type="button" onClick={onFind} title="/">Find</button>
      <button type="button" onClick={onLens} aria-pressed={state?.kind === 'lens'}>Kinds</button>
      <button type="button" onClick={onRuns} aria-pressed={reading.run.id != null}>Runs</button>
      <button type="button" onClick={onChanges} aria-pressed={reading.changes.id != null}>Changes</button>
      <button type="button" onClick={onGuide} title="?" aria-label="Reading guide">?</button>
    </div>
  )
}
