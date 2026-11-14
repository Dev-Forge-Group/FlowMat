import { useEffect, useRef } from 'react'
import type { ProcessRelations } from '../model/processRelations'

/** archify's relationship explorer groups and labels (viewer.passport.relationship.*). */
const GROUPS = [
  { key: 'out', title: 'Outgoing', direction: 'OUT →', relation: 'connects to' },
  { key: 'in', title: 'Incoming', direction: '← IN', relation: 'connects from' },
  { key: 'loop', title: 'Self loops', direction: 'LOOP', relation: 'loops back' },
] as const

/** A pointer resting on a row shows its connection after this long; a keyboard focus shows it at once (archify). */
const PREVIEW_DELAY_MS = 90

/**
 * A process's connections in the Semantic Passport (archify's relationship explorer, adapted from archify focus.js,
 * Apache-2.0): what leaves it, what enters it and what loops on it, in saved order. Pointing at or focusing a row shows
 * just that connection on the canvas; choosing it goes to the process at the other end, and its pin keeps that connection
 * shown with a link to it (archify's pinned relationship, `#relation=`). The up and down arrows move between rows.
 */
export function ProcessConnections({ relations, nameOf, pinnedId, onPin, onPreview, onFollow }: {
  relations: ProcessRelations
  nameOf(processId: string): string
  /** The connection pinned, if it is one of these. */
  pinnedId: string | null
  onPin(connectionId: string): void
  /** Shows one connection on the canvas; null when the row is left. */
  onPreview(connectionId: string | null): void
  onFollow(processId: string): void
}) {
  const listRef = useRef<HTMLDivElement>(null)
  const timer = useRef<number | undefined>(undefined)
  const previewRef = useRef(onPreview)
  previewRef.current = onPreview
  const total = relations.out.length + relations.in.length + relations.loop.length

  // Nothing stays previewed once the rows are gone.
  useEffect(() => () => {
    window.clearTimeout(timer.current)
    previewRef.current(null)
  }, [])

  function show(connectionId: string | null, delay = 0) {
    window.clearTimeout(timer.current)
    if (delay === 0 || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) onPreview(connectionId)
    else timer.current = window.setTimeout(() => onPreview(connectionId), delay)
  }

  function moveFocus(event: React.KeyboardEvent<HTMLElement>) {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
    const rows = [...(listRef.current?.querySelectorAll<HTMLButtonElement>('button.relationship-lens-row') ?? [])]
    const at = rows.indexOf(document.activeElement as HTMLButtonElement)
    if (at < 0) return
    event.preventDefault()
    rows[Math.min(rows.length - 1, Math.max(0, at + (event.key === 'ArrowDown' ? 1 : -1)))]?.focus()
  }

  return (
    <div ref={listRef} className="relationship-lens-list" role="group" aria-label="Connected relationships" onKeyDown={moveFocus}>
      {total === 0 && <p className="relationship-lens-empty">No connected relationships</p>}
      {GROUPS.filter((group) => relations[group.key].length > 0).map((group) => (
        <div key={group.key} className="relationship-lens-group">
          <span className="relationship-lens-group-title">{group.title}</span>
          {relations[group.key].map((relation) => {
            const pinned = relation.connectionId === pinnedId
            return (
              <div key={relation.connectionId} className="relationship-lens-entry" data-pinned={pinned ? 'true' : undefined}>
                <button
                  type="button"
                  className="relationship-lens-row"
                  data-direction={group.key}
                  aria-label={`${group.title}: ${relation.note}, ${nameOf(relation.otherId)}`}
                  onPointerEnter={(event) => { if (event.pointerType !== 'touch') show(relation.connectionId, PREVIEW_DELAY_MS) }}
                  onPointerLeave={() => show(null)}
                  onFocus={() => show(relation.connectionId)}
                  onBlur={() => show(null)}
                  onClick={() => onFollow(relation.otherId)}
                >
                  <span className="relationship-lens-direction" aria-hidden="true">{group.direction}</span>
                  <strong>{nameOf(relation.otherId)}</strong>
                  <small>{group.relation} · {relation.note}</small>
                </button>
                <button
                  type="button"
                  className="relationship-lens-pin"
                  aria-pressed={pinned}
                  aria-label={`${pinned ? 'Unpin' : 'Pin'} the connection ${group.key === 'in' ? 'from' : 'to'} ${nameOf(relation.otherId)}`}
                  title={pinned ? 'Unpin connection' : 'Pin connection and keep a link to it'}
                  onClick={() => onPin(relation.connectionId)}
                >
                  <span aria-hidden="true">{pinned ? '●' : '○'}</span>
                </button>
              </div>
            )
          })}
        </div>
      ))}
    </div>
  )
}
