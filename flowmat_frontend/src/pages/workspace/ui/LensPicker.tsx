import { useEffect, useRef, useState } from 'react'

/**
 * Reading the canvas by kind (archify's semantic lens): one kind with what it touches, or two kinds and the connections
 * between them; a third is refused. Each kind on the canvas is a button with its count. Esc or Close puts focus back.
 */
export function LensPicker({ kinds, chosen, onToggle, onClose }: {
  kinds: { kind: string; label: string; count: number }[]
  chosen: readonly string[]
  /** Turns a kind on or off; why it was refused, or null. */
  onToggle(kind: string): string | null
  onClose(): void
}) {
  const [error, setError] = useState<string | null>(null)
  const firstRef = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    firstRef.current?.focus()
    return () => previous?.focus?.()
  }, [])

  return (
    <div
      role="dialog"
      aria-label="Read by kind"
      className="canvas-reading-popup canvas-reading-popup--lens"
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.preventDefault()
          event.stopPropagation()
          onClose()
        }
      }}
    >
      <h3>Read by kind</h3>
      <p className="canvas-reading-chip__hint">One kind shows what it touches; two show the connections between them.</p>
      <div role="group" aria-label="Kinds" className="canvas-reading-kinds">
        {kinds.map((entry, index) => (
          <button key={entry.kind} ref={index === 0 ? firstRef : undefined} type="button"
            aria-pressed={chosen.includes(entry.kind)} onClick={() => setError(onToggle(entry.kind))}>
            {entry.label} {entry.count}
          </button>
        ))}
      </div>
      {kinds.length === 0 && <p className="canvas-reading-chip__hint">There are no processes yet.</p>}
      {error && <p role="alert" className="canvas-reading-chip__error">{error}</p>}
      <button type="button" onClick={onClose}>Close</button>
    </div>
  )
}
