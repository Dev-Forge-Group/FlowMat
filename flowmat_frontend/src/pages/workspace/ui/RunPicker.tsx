import { useEffect, useRef } from 'react'
import type { ProductionRunDto } from '../../../shared/types/api'

/**
 * Chooses the production run shown over the canvas: each process then carries the mark of its latest step. View only;
 * Esc or Close puts focus back.
 */
export function RunPicker({ runs, loading, shownId, onShow, onClose }: {
  runs: ProductionRunDto[]
  loading: boolean
  shownId: string | null
  onShow(productionRunId: string | null): void
  onClose(): void
}) {
  const firstRef = useRef<HTMLButtonElement>(null)
  const closeRef = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    ;(firstRef.current ?? closeRef.current)?.focus()
    return () => previous?.focus?.()
  }, [])

  return (
    <div
      role="dialog"
      aria-label="Show a run"
      className="canvas-reading-popup"
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.preventDefault()
          event.stopPropagation()
          onClose()
        }
      }}
    >
      <h3>Show a run</h3>
      <p className="canvas-reading-chip__hint">Each process shows how its latest step in the run stands.</p>
      <div role="group" aria-label="Runs" className="canvas-reading-kinds">
        {runs.map((run, index) => (
          <button key={run.productionRunId} ref={index === 0 ? firstRef : undefined} type="button"
            aria-pressed={run.productionRunId === shownId} onClick={() => onShow(run.productionRunId)}>
            {run.runNumber} <span className="canvas-reading-chip__hint">{run.runStatus}</span>
          </button>
        ))}
      </div>
      {loading && <p className="canvas-reading-chip__hint">Reading the runs…</p>}
      {!loading && runs.length === 0 && <p className="canvas-reading-chip__hint">This workflow has no production runs yet.</p>}
      {shownId && <button type="button" onClick={() => onShow(null)}>Hide run</button>}
      <button ref={closeRef} type="button" onClick={onClose}>Close</button>
    </div>
  )
}
