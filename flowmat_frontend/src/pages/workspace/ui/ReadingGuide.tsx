import { useEffect, useRef } from 'react'

/** What the canvas can be read with ("?"); Esc or Close puts focus back where it was. */
export function ReadingGuide({ onClose }: { onClose(): void }) {
  const closeRef = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    closeRef.current?.focus()
    return () => previous?.focus?.()
  }, [])

  return (
    <div
      role="dialog"
      aria-label="Reading guide"
      className="canvas-reading-popup canvas-reading-popup--guide"
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.preventDefault()
          event.stopPropagation()
          onClose()
        }
      }}
    >
      <h3>Reading the canvas</h3>
      <dl>
        <dt>Neighbours</dt><dd>A process and the processes it connects to directly.</dd>
        <dt>Upstream / Downstream</dt><dd>Every process before or after it along the saved connections.</dd>
        <dt>Route from here</dt><dd>The processes it can reach are marked; click one to see the way there, step by step.</dd>
        <dt>Kinds</dt><dd>One kind of process with what it touches, or two kinds and the connections between them.</dd>
        <dt>Runs</dt><dd>A production run over the canvas: each process marks its latest step (○ ▶ ✓ ✕ ↷ ⊘).</dd>
        <dt>Changes</dt><dd>What the draft changed since a published revision: + added, Δ changed; what is gone is listed by name.</dd>
        <dt>Copy link</dt><dd>A link that opens this same view.</dd>
        <dt><kbd>/</kbd></dt><dd>Find a process by name, type or id; while picking a route, only where it can go.</dd>
        <dt><kbd>?</kbd></dt><dd>This guide.</dd>
        <dt><kbd>Esc</kbd></dt><dd>Stop picking a route, then stop reading (the first Esc leaves the current tool, such as Process).</dd>
      </dl>
      <p className="canvas-reading-chip__hint">Reading only highlights what the workflow defines; nothing is changed or saved.</p>
      <button ref={closeRef} type="button" onClick={onClose}>Close</button>
    </div>
  )
}
