import { useEffect, useRef } from 'react'
import type { WorkflowRevisionDto } from '../../../shared/types/api'
import type { RevisionChanges } from '../model/revisionChanges'

/**
 * Chooses the published revision the draft is compared with (archify's delta): added processes and connections carry a +,
 * changed ones a Δ, and what is gone is named here, since the canvas no longer has it. Each added or changed process is a
 * button that brings it into view. View only; Esc or Close puts focus back.
 */
export function ChangesPicker({ revisions, loading, shownId, changes, nameOf, onShow, onReveal, onClose }: {
  revisions: WorkflowRevisionDto[]
  loading: boolean
  shownId: string | null
  changes: RevisionChanges | null
  nameOf(processId: string): string
  onShow(revisionId: string | null): void
  onReveal(processId: string): void
  onClose(): void
}) {
  const firstRef = useRef<HTMLButtonElement>(null)
  const closeRef = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    ;(firstRef.current ?? closeRef.current)?.focus()
    return () => previous?.focus?.()
  }, [])

  const processes = changes ? [...changes.processes] : []
  return (
    <div
      role="dialog"
      aria-label="Compare with a revision"
      className="canvas-reading-popup canvas-reading-popup--side"
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.preventDefault()
          event.stopPropagation()
          onClose()
        }
      }}
    >
      <h3>Compare with a revision</h3>
      <p className="canvas-reading-chip__hint">The draft against a published revision: + added, Δ changed. Moving a process is not a change.</p>
      <div role="group" aria-label="Revisions" className="canvas-reading-kinds">
        {revisions.map((revision, index) => (
          <button key={revision.workflowRevisionId} ref={index === 0 ? firstRef : undefined} type="button"
            aria-pressed={revision.workflowRevisionId === shownId} onClick={() => onShow(revision.workflowRevisionId)}>
            v{revision.revisionNo} <span className="canvas-reading-chip__hint">{revision.status}</span>
          </button>
        ))}
      </div>
      {loading && <p className="canvas-reading-chip__hint">Reading the revisions…</p>}
      {!loading && revisions.length === 0 && <p className="canvas-reading-chip__hint">This workflow has no published revision yet.</p>}
      {changes && (
        <section aria-label="What changed" className="canvas-changes">
          {processes.length > 0 && (
            <ul aria-label="Changed processes">
              {processes.map(([processId, mark]) => (
                <li key={processId}>
                  <button type="button" onClick={() => onReveal(processId)}>
                    {mark === 'added' ? '+' : 'Δ'} {nameOf(processId)}
                    <span className="canvas-reading-chip__hint">
                      {mark === 'added' ? ' added' : ` ${(changes.fields.get(processId) ?? []).join(', ')}`}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
          {(changes.removedProcesses.length > 0 || changes.removedConnections.length > 0) && (
            <ul aria-label="Removed">
              {changes.removedProcesses.map((removed) => <li key={removed.processId}>− {removed.name}</li>)}
              {changes.removedConnections.map((removed) => <li key={removed.connectionId}>− {removed.text}</li>)}
            </ul>
          )}
        </section>
      )}
      {shownId && <button type="button" onClick={() => onShow(null)}>Hide changes</button>}
      <button ref={closeRef} type="button" onClick={onClose}>Close</button>
    </div>
  )
}
